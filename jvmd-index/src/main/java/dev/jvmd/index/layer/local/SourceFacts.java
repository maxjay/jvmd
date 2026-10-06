package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.machine.Ann;
import dev.jvmd.index.layer.machine.Edges;
import dev.jvmd.index.layer.machine.Fact;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.Res;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;

/**
 * {@code Φ_src} (stage 2, 3.1 and appendix C): the facts and edges of the declarations of one source file, as javac's {@code Enter}
 * and {@code MemberEnter} resolved them, encoded exactly as {@code ClassFacts} would encode the class file javac would emit for
 * them. A source module and its jar therefore have equal {@code k} whenever their APIs are equal. Their independent {@code A}
 * and {@code EA} trees also match when the compiler options match; {@code -parameters} can change {@code a}, never {@code k}.
 *
 * <p>Nothing here looks at a method body. A declaration whose header mentions a type that did not resolve is a declaration fault
 * (C.6): it yields no fact and is listed by key; the rest of the file produces facts.
 *
 * <p>Signature type annotations come from javac's completed symbols and their assigned positions. The retained annotations use
 * the same codec and fact ownership as {@code ClassFacts}; {@code EA} is derived from those tail bytes. An enum with constant
 * bodies has no {@code PermittedSubclasses} here because javac creates those anonymous classes only when it attributes the bodies.
 */
public final class SourceFacts {
    /**
     * What one file declares. {@code typeKeys} are internal names; each becomes an {@code O} key. {@code headerTargets} are the targets of
     * {@code edges}, sorted and distinct: every type its declaration headers mention. Header attribution reads are collected separately
     * from the completed javac symbols by {@link ProofCollector}.
     */
    public record Result(List<Fact> facts, List<Entry> edges, List<String> headerTargets, List<String> typeKeys, List<FileRow.Fault> faults) {
        public static final Result NONE = new Result(List.of(), List.of(), List.of(), List.of(), List.of());

        /** The {@code N} entries of this file: its facts keyed by simple name. */
        public List<Entry> byName() {
            var out = new ArrayList<Entry>(facts.size());
            for (var f : facts) out.add(f.byName());
            return out;
        }
    }

    private static final int TYPE_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
            | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM | ClassFile.ACC_MODULE;
    private static final int INNER_ACCESS = ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED;
    private static final int FIELD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_VOLATILE | ClassFile.ACC_TRANSIENT | ClassFile.ACC_ENUM;
    private static final int METHOD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_ABSTRACT | ClassFile.ACC_VARARGS;
    private static final int MANDATED = 0x8000;

    private enum Retention { SOURCE, CLASS, RUNTIME }

    private final Digest digest;
    private final Elements elements;
    private final Types types;
    private final boolean parameters;

    /**
     * @param parameters whether the module compiles with {@code -parameters}: parameter names are then in the tail, as javac writes them
     */
    public SourceFacts(Digest digest, Elements elements, Types types, boolean parameters) {
        this.digest = digest;
        this.elements = elements;
        this.types = types;
        this.parameters = parameters;
    }

    /** The facts of the top-level types a file declares, and of their member types, in declaration order. */
    public Result of(List<? extends TypeElement> declared) { return of(declared, null, false); }

    /** Package-info emission is determined by the native header task's policy and completed package annotations. */
    public Result of(List<? extends TypeElement> declared, javax.lang.model.element.PackageElement pkg, boolean packageClass) {
        var out = new Out();
        for (var type : declared) type(type, out);
        if (packageClass) packageFact(java.util.Objects.requireNonNull(pkg), out);
        return new Result(List.copyOf(out.facts), List.copyOf(out.edges.values()), List.copyOf(out.targets), List.copyOf(out.typeKeys), List.copyOf(out.faults));
    }

    /**
     * The module descriptor fact of a {@code module-info.java} (stage 1, A.4 item 10; stage 2, section 8 and E.3): names and flags read
     * from the parsed directives and completed service type references. It is encoded as {@code ClassFacts} encodes
     * {@code module-info.class}: type key {@code module-info}, kind 5, access {@code ACC_MODULE}, then the {@code Module} attribute.
     * javac writes the implicit {@code requires java.base} first, as mandated, and a {@code requires_version} for every required module
     * that has a version, which is the one thing here that is not in the file; {@code versionOf} supplies it.
     *
     * @param moduleVersion the {@code --module-version} the build passed, which javac records as this module's own version; or null
     * @param versionOf     the version of a required module as javac would read it, or null
     */
    public Result ofModule(com.sun.source.tree.ModuleTree module, String moduleVersion, Function<String, String> versionOf,
                           Function<com.sun.source.tree.ExpressionTree, TypeElement> resolvedType) {
        try { return module(module, moduleVersion, versionOf, resolvedType); }
        catch (RuntimeException | StackOverflowError failure) {
            return new Result(List.of(), List.of(), List.of(), List.of(),
                    List.of(new FileRow.Fault(Keys.typeKey("module-info"), "module declaration could not be read: " + failure)));
        }
    }

    private Result module(com.sun.source.tree.ModuleTree module, String moduleVersion, Function<String, String> versionOf,
                          Function<com.sun.source.tree.ExpressionTree, TypeElement> resolvedType) {
        var out = new Out();
        var requires = new ArrayList<Res.Requires>();
        var exports = new ArrayList<Res.Directive>();
        var opens = new ArrayList<Res.Directive>();
        var uses = new ArrayList<String>();
        var provides = new ArrayList<Res.Provides>();
        var resolved = new java.util.HashMap<String, TypeElement>();
        Function<com.sun.source.tree.ExpressionTree, TypeElement> recordType = reference -> {
            var type = resolvedType.apply(reference);
            if (type != null) resolved.put(binaryName(type), type);
            return type;
        };
        boolean explicitBase = false;
        for (var directive : module.getDirectives()) {
            switch (directive) {
                case com.sun.source.tree.RequiresTree r -> {
                    String required = r.getModuleName().toString();
                    explicitBase |= required.equals("java.base");
                    requires.add(new Res.Requires(required, (r.isTransitive() ? 0x0020 : 0) | (r.isStatic() ? 0x0040 : 0), versionOf.apply(required)));
                }
                case com.sun.source.tree.ExportsTree e -> exports.add(packageDirective(e.getPackageName().toString(), e.getModuleNames()));
                case com.sun.source.tree.OpensTree o -> opens.add(packageDirective(o.getPackageName().toString(), o.getModuleNames()));
                case com.sun.source.tree.UsesTree u -> uses.add(moduleType(u.getServiceName(), recordType));
                case com.sun.source.tree.ProvidesTree p -> {
                    var with = new ArrayList<String>();
                    for (var implementation : p.getImplementationNames()) with.add(moduleType(implementation, recordType));
                    provides.add(new Res.Provides(moduleType(p.getServiceName(), recordType), with));
                }
                default -> { }
            }
        }
        if (!explicitBase) requires.add(0, new Res.Requires("java.base", MANDATED, versionOf.apply("java.base")));
        var inners = new java.util.LinkedHashMap<String, Res.Inner>();
        for (var name : uses) moduleInner(resolved.get(name), inners);
        for (var provide : provides) {
            moduleInner(resolved.get(provide.service()), inners);
            for (var name : provide.with()) moduleInner(resolved.get(name), inners);
        }
        var descriptor = new Res.Module(module.getName().toString(), module.getModuleType() == com.sun.source.tree.ModuleTree.ModuleKind.OPEN ? 0x0020 : 0,
                moduleVersion, requires, exports, opens, uses, provides, List.copyOf(inners.values()));
        var res = new Res.Type(Res.Type.MODULE, ClassFile.ACC_MODULE, null, null, List.of(), List.of(), null, null, null, List.of(), List.of(), descriptor, Res.Warnings.NONE);
        var tail = Entry.NONE;
        add(out, Keys.typeKey("module-info"), "module-info", res.encode(), tail);
        out.typeKeys.add("module-info");
        return new Result(List.copyOf(out.facts), List.of(), inners.keySet().stream().sorted().toList(), List.copyOf(out.typeKeys), List.of());
    }

    private String moduleType(com.sun.source.tree.ExpressionTree reference, Function<com.sun.source.tree.ExpressionTree, TypeElement> resolvedType) {
        var type = resolvedType.apply(reference);
        if (type == null || type.asType().getKind() == TypeKind.ERROR)
            throw new IllegalArgumentException("Unresolved module service type: " + reference);
        return binaryName(type);
    }

    private void moduleInner(TypeElement type, Map<String, Res.Inner> into) {
        if (!(type.getEnclosingElement() instanceof TypeElement outer)) return;
        String name = binaryName(type);
        if (into.containsKey(name)) return;
        moduleInner(outer, into);
        int flags = (int) ((com.sun.tools.javac.code.Symbol.ClassSymbol) type).flags() & 0x761f;
        into.put(name, new Res.Inner(name, binaryName(outer), type.getSimpleName().toString(), flags));
    }

    private void packageFact(javax.lang.model.element.PackageElement pkg, Out out) {
        String owner = pkg.getQualifiedName().toString().replace('.', '/') + "/package-info";
        var key = Keys.typeKey(owner);
        try {
            // Lower emits a synthetic abstract interface with Object as superclass and the package's annotations.
            // Synthetic is excluded from the resolution access mask, just as on the class-file side.
            var warning = warnings(pkg);
            // Lower copies annotations to package_info, but does not copy the package symbol's DEPRECATED flag.
            warning = new Res.Warnings(false, warning.deprecation(), warning.safeVarargs());
            var res = new Res.Type(1, ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT, null, "java/lang/Object",
                    List.of(), List.of(), null, null, null, List.of(), List.of(), null, warning);
            var tail = tail(pkg, retained(pkg), false, null);
            add(out, key, "package-info", res.encode(), tail);
            edge(out, "java/lang/Object", Edges.EXTENDS, key);
            out.typeKeys.add(owner);
        } catch (RuntimeException | StackOverflowError broken) {
            out.faults.add(new FileRow.Fault(key, "package declaration could not be read: " + broken));
        }
    }

    private static Res.Directive packageDirective(String packageName, List<? extends com.sun.source.tree.ExpressionTree> to) {
        var modules = new ArrayList<String>();
        if (to != null) for (var module : to) modules.add(module.toString());
        return new Res.Directive(packageName.replace('.', '/'), 0, modules);
    }

    private static final class Out {
        final List<Fact> facts = new ArrayList<>();
        final TreeMap<byte[], Entry> edges = new TreeMap<>(Arrays::compareUnsigned);
        final java.util.Set<String> targets = new java.util.TreeSet<>();
        final List<String> typeKeys = new ArrayList<>();
        final List<FileRow.Fault> faults = new ArrayList<>();
    }

    // ---- types -----------------------------------------------------------------------------------------------------------

    private String binaryName(TypeElement type) { return elements.getBinaryName(type).toString().replace('.', '/'); }

    private void type(TypeElement type, Out out) {
        String owner = binaryName(type);
        byte[] key = Keys.typeKey(owner);
        var errors = new ArrayList<String>();
        try {
            typeFact(type, owner, key, errors, out);
        } catch (RuntimeException | StackOverflowError broken) {
            errors.add("declaration could not be read: " + broken);
        }
        boolean faulted = !errors.isEmpty();
        if (!faulted) out.typeKeys.add(owner);
        else {
            out.faults.add(new FileRow.Fault(key, "cannot resolve " + String.join(", ", errors)));
            // A declaration that faulted yields no fact, and a type without a header has no usable members; nested types stand on their own.
        }
        for (var member : type.getEnclosedElements()) {
            switch (member.getKind()) {
                case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> type((TypeElement) member, out);
                case FIELD, ENUM_CONSTANT -> { if (!faulted) field(type, owner, (VariableElement) member, out); }
                case METHOD, CONSTRUCTOR -> { if (!faulted) method(type, owner, (ExecutableElement) member, out); }
                default -> { }
            }
        }
    }

    private void typeFact(TypeElement type, String owner, byte[] key, List<String> errors, Out out) {
        var kind = type.getKind();
        int kindCode = kind == ElementKind.ANNOTATION_TYPE ? 4 : kind == ElementKind.INTERFACE ? 1 : kind == ElementKind.ENUM ? 2 : kind == ElementKind.RECORD ? 3 : 0;
        var mods = type.getModifiers();
        // The class-level flags javac's ClassWriter writes: protected becomes public, static and private are not class-level flags.
        int flags = 0;
        if (mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED)) flags |= ClassFile.ACC_PUBLIC;
        if (mods.contains(Modifier.FINAL)) flags |= ClassFile.ACC_FINAL;
        if (kindCode == 1 || kindCode == 4) flags |= ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT;
        else if (mods.contains(Modifier.ABSTRACT)) flags |= ClassFile.ACC_ABSTRACT;
        if (kindCode == 4) flags |= ClassFile.ACC_ANNOTATION;
        if (kindCode == 2) flags |= ClassFile.ACC_ENUM;
        int access = flags & TYPE_ACCESS;
        boolean nested = type.getNestingKind() == NestingKind.MEMBER;
        if (nested) {
            int inner = 0;
            if (mods.contains(Modifier.STATIC)) inner |= ClassFile.ACC_STATIC;
            if (mods.contains(Modifier.PRIVATE)) inner |= ClassFile.ACC_PRIVATE;
            if (mods.contains(Modifier.PROTECTED)) inner |= ClassFile.ACC_PROTECTED;
            access |= inner & INNER_ACCESS;
        }

        var superType = type.getSuperclass();
        String superName = null;
        if (superType.getKind() == TypeKind.NONE) { if (kindCode == 1 || kindCode == 4) superName = "java/lang/Object"; }
        else superName = internalName(superType, errors);
        var interfaces = new ArrayList<String>();
        for (var i : type.getInterfaces()) interfaces.add(internalName(i, errors));
        var permits = new ArrayList<String>();
        for (var p : type.getPermittedSubclasses()) permits.add(internalName(p, errors));
        String host = null, outer = null;
        if (nested) {
            Element top = type;
            while (top.getEnclosingElement() instanceof TypeElement enclosing) top = enclosing;
            host = binaryName((TypeElement) top);
            outer = binaryName((TypeElement) type.getEnclosingElement());
        }
        String signature = classSignature(type, errors);

        var recordComponents = kindCode == 3 ? type.getRecordComponents() : List.<javax.lang.model.element.RecordComponentElement>of();
        var components = new ArrayList<Res.Component>();
        for (var rc : recordComponents) {
            var t = rc.asType();
            components.add(new Res.Component(rc.getSimpleName().toString(), descriptor(t, errors), signature(t, errors)));
        }
        var annotations = retained(type);
        var metas = new ArrayList<Ann>();
        if (kindCode == Res.Type.ANNOTATION) {
            for (var meta : Res.META_ANNOTATIONS) {
                AnnotationMirror found = null;
                for (var a : type.getAnnotationMirrors())
                    if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals("java.lang.annotation." + meta)) found = a;
                metas.add(found == null ? null : annotation(found));
            }
        }
        if (!errors.isEmpty()) return;

        var res = new Res.Type(kindCode, access, signature, superName, interfaces, permits, host, outer, outer == null ? null : type.getSimpleName().toString(), components, metas, null, warnings(type));
        add(out, key, type.getSimpleName().toString(), res.encode(), tail(type, annotations, false, null));
        if (superName != null) edge(out, superName, Edges.EXTENDS, key);
        for (var i : interfaces) edge(out, i, Edges.IMPLEMENTS, key);
        for (var p : permits) edge(out, p, Edges.PERMITS, key);
        if (outer != null) edge(out, outer, Edges.ENCLOSES, key);
        for (var c : components) Edges.typeNames(c.descriptor(), c.signature(), n -> edge(out, n, Edges.RECORD_COMPONENT_TYPE, key));
    }

    // ---- members ---------------------------------------------------------------------------------------------------------

    private void field(TypeElement owner, String ownerName, VariableElement field, Out out) {
        var mods = field.getModifiers();
        if (mods.contains(Modifier.PRIVATE)) return;
        int flags = 0;
        if (mods.contains(Modifier.PUBLIC)) flags |= ClassFile.ACC_PUBLIC;
        if (mods.contains(Modifier.PROTECTED)) flags |= ClassFile.ACC_PROTECTED;
        if (mods.contains(Modifier.STATIC)) flags |= ClassFile.ACC_STATIC;
        if (mods.contains(Modifier.FINAL)) flags |= ClassFile.ACC_FINAL;
        if (mods.contains(Modifier.VOLATILE)) flags |= ClassFile.ACC_VOLATILE;
        if (mods.contains(Modifier.TRANSIENT)) flags |= ClassFile.ACC_TRANSIENT;
        if (field.getKind() == ElementKind.ENUM_CONSTANT) flags |= ClassFile.ACC_ENUM;
        String name = field.getSimpleName().toString();
        var errors = new ArrayList<String>();
        String desc = descriptor(field.asType(), errors);
        String signature = signature(field.asType(), errors);
        byte[] key = Keys.memberKey(ownerName, Keys.FIELD, name, desc);
        if (!errors.isEmpty()) { out.faults.add(fault(key, errors)); return; }

        Object constant = field.getConstantValue();
        Res.Constant value = switch (constant) {
            case null -> null;
            case Boolean z -> new Res.Constant(3, z ? 1 : 0, null);
            case Character c -> new Res.Constant(3, c, null);
            case Byte b -> new Res.Constant(3, b, null);
            case Short s -> new Res.Constant(3, s, null);
            case Integer i -> new Res.Constant(3, i, null);
            case Float f -> new Res.Constant(4, Float.floatToRawIntBits(f), null);
            case Long l -> new Res.Constant(5, l, null);
            case Double d -> new Res.Constant(6, Double.doubleToRawLongBits(d), null);
            case String s -> new Res.Constant(8, 0, s);
            default -> throw new IllegalArgumentException("Unexpected constant " + constant.getClass());
        };
        var annotations = retained(field);
        add(out, key, name, new Res.Field(flags & FIELD_ACCESS, signature, value, warnings(field)).encode(), tail(field, annotations, false, null));
        Edges.typeNames(desc, signature, n -> edge(out, n, Edges.FIELD_TYPE, key));
    }

    private void method(TypeElement owner, String ownerName, ExecutableElement method, Out out) {
        var mods = method.getModifiers();
        if (mods.contains(Modifier.PRIVATE)) return;
        boolean constructor = method.getKind() == ElementKind.CONSTRUCTOR;
        int flags = 0;
        if (mods.contains(Modifier.PUBLIC)) flags |= ClassFile.ACC_PUBLIC;
        if (mods.contains(Modifier.PROTECTED)) flags |= ClassFile.ACC_PROTECTED;
        if (mods.contains(Modifier.STATIC)) flags |= ClassFile.ACC_STATIC;
        if (mods.contains(Modifier.FINAL)) flags |= ClassFile.ACC_FINAL;
        if (mods.contains(Modifier.ABSTRACT)) flags |= ClassFile.ACC_ABSTRACT;
        if (method.isVarArgs()) flags |= ClassFile.ACC_VARARGS;
        String name = method.getSimpleName().toString();
        var errors = new ArrayList<String>();

        // The descriptor: erased declared parameters, preceded by the outer instance for a constructor of an inner class (C.2).
        var desc = new StringBuilder("(");
        boolean inner = constructor && owner.getNestingKind() == NestingKind.MEMBER && owner.getKind() == ElementKind.CLASS && !owner.getModifiers().contains(Modifier.STATIC);
        if (inner) desc.append(descriptor(((TypeElement) owner.getEnclosingElement()).asType(), errors));
        else if (constructor && owner.getKind() == ElementKind.ENUM) desc.append("Ljava/lang/String;I");
        for (var p : method.getParameters()) desc.append(descriptor(p.asType(), errors));
        var returnType = method.getReturnType();
        desc.append(')').append(constructor ? "V" : descriptor(returnType, errors));
        var thrown = new ArrayList<String>();
        for (var t : method.getThrownTypes()) thrown.add(internalName(t, errors));
        String signature = methodSignature(method, constructor, errors);
        byte[] key = Keys.memberKey(ownerName, Keys.METHOD, name, desc.toString());
        // A default value of an annotation element is part of its header: its type must have resolved too.
        var defaultValue = method.getDefaultValue();
        if (!errors.isEmpty()) { out.faults.add(fault(key, errors)); return; }

        var res = new Res.Method(flags & METHOD_ACCESS, signature, thrown, defaultValue == null ? null : value(defaultValue, returnType), warnings(method));
        var annotations = retained(method);
        add(out, key, name, res.encode(), tail(method, annotations, true, inner ? owner : null));
        Edges.method(desc.toString(), signature, thrown, (target, kind) -> edge(out, target, kind, key));
    }

    // ---- descriptors and signatures --------------------------------------------------------------------------------------

    /** The erased JVM descriptor of a type. An unresolved type is rendered by its name and noted in {@code errors}. */
    private String descriptor(TypeMirror type, List<String> errors) {
        var erased = type.getKind() == TypeKind.ERROR ? type : types.erasure(type);
        return switch (erased.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case CHAR -> "C";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) erased).getComponentType(), errors);
            case DECLARED -> "L" + binaryName((TypeElement) ((DeclaredType) erased).asElement()) + ";";
            case ERROR -> { errors.add(erased.toString()); yield "L" + erased.toString().replace('.', '/') + ";"; }
            default -> "Ljava/lang/Object;";
        };
    }

    private String internalName(TypeMirror type, List<String> errors) {
        String d = descriptor(type, errors);
        return d.substring(1, d.length() - 1);
    }

    /** True if javac would write a {@code Signature} for this type: it is not its own erasure. */
    private boolean needsSignature(TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> needsSignature(((ArrayType) type).getComponentType());
            case TYPEVAR, INTERSECTION, WILDCARD -> true;
            case DECLARED -> hasParameters((DeclaredType) type);
            default -> false;
        };
    }

    private static boolean hasParameters(DeclaredType type) {
        if (!type.getTypeArguments().isEmpty()) return true;
        return type.getEnclosingType() instanceof DeclaredType outer && hasParameters(outer);
    }

    private String signature(TypeMirror type, List<String> errors) {
        if (!needsSignature(type)) return null; // an unresolved type in it is noted by its descriptor
        var sb = new StringBuilder();
        signature(sb, type, errors);
        return sb.toString();
    }

    private void signature(StringBuilder sb, TypeMirror type, List<String> errors) {
        switch (type.getKind()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE, VOID -> sb.append(descriptor(type, errors));
            case ARRAY -> { sb.append('['); signature(sb, ((ArrayType) type).getComponentType(), errors); }
            case TYPEVAR -> sb.append('T').append(((javax.lang.model.type.TypeVariable) type).asElement().getSimpleName()).append(';');
            case DECLARED -> { sb.append('L'); classSignature(sb, (DeclaredType) type, errors); sb.append(';'); }
            case ERROR -> { errors.add(type.toString()); sb.append('L').append(type.toString().replace('.', '/')).append(';'); }
            case INTERSECTION -> signature(sb, types.erasure(type), errors);
            default -> sb.append("Ljava/lang/Object;");
        }
    }

    private void classSignature(StringBuilder sb, DeclaredType type, List<String> errors) {
        var element = (TypeElement) type.asElement();
        if (type.getEnclosingType() instanceof DeclaredType outer && hasParameters(outer)) {
            classSignature(sb, outer, errors);
            sb.append('.').append(element.getSimpleName());
        } else sb.append(binaryName(element));
        if (!type.getTypeArguments().isEmpty()) {
            sb.append('<');
            for (var argument : type.getTypeArguments()) {
                if (argument instanceof WildcardType w) {
                    if (w.getExtendsBound() != null) { sb.append('+'); signature(sb, w.getExtendsBound(), errors); }
                    else if (w.getSuperBound() != null) { sb.append('-'); signature(sb, w.getSuperBound(), errors); }
                    else sb.append('*');
                } else signature(sb, argument, errors);
            }
            sb.append('>');
        }
    }

    private void typeParameters(StringBuilder sb, List<? extends TypeParameterElement> parameters, List<String> errors) {
        if (parameters.isEmpty()) return;
        sb.append('<');
        for (var p : parameters) {
            sb.append(p.getSimpleName());
            var bounds = p.getBounds();
            // An interface as the first bound leaves the class bound empty: "T::Ljava/lang/Comparable<TT;>;".
            if (!bounds.isEmpty() && types.asElement(bounds.get(0)) != null && types.asElement(bounds.get(0)).getKind().isInterface()) sb.append(':');
            for (var b : bounds) { sb.append(':'); signature(sb, b, errors); }
        }
        sb.append('>');
    }

    private String classSignature(TypeElement type, List<String> errors) {
        boolean needed = !type.getTypeParameters().isEmpty();
        var superType = type.getSuperclass();
        needed |= needsSignature(superType);
        for (var i : type.getInterfaces()) needed |= needsSignature(i);
        if (!needed) return null;
        var sb = new StringBuilder();
        typeParameters(sb, type.getTypeParameters(), errors);
        if (superType.getKind() == TypeKind.NONE) sb.append("Ljava/lang/Object;"); else signature(sb, superType, errors);
        for (var i : type.getInterfaces()) signature(sb, i, errors);
        return sb.toString();
    }

    private String methodSignature(ExecutableElement method, boolean constructor, List<String> errors) {
        boolean needed = !method.getTypeParameters().isEmpty() || (!constructor && needsSignature(method.getReturnType()));
        for (var p : method.getParameters()) needed |= needsSignature(p.asType());
        boolean thrownVariable = false;
        for (var t : method.getThrownTypes()) { needed |= needsSignature(t); thrownVariable |= t.getKind() == TypeKind.TYPEVAR; }
        if (!needed) return null;
        var sb = new StringBuilder();
        typeParameters(sb, method.getTypeParameters(), errors);
        sb.append('(');
        for (var p : method.getParameters()) signature(sb, p.asType(), errors);
        sb.append(')');
        if (constructor) sb.append('V'); else signature(sb, method.getReturnType(), errors);
        if (thrownVariable) for (var t : method.getThrownTypes()) { sb.append('^'); signature(sb, t, errors); }
        return sb.toString();
    }

    // ---- edges (the same names ClassFacts reads out of descriptors and signatures) -----------------------------------------

    private void edge(Out out, String target, int kind, byte[] source) {
        out.targets.add(target);
        var key = Keys.edgeKey(target, kind, source);
        out.edges.computeIfAbsent(key, k -> new Entry(k, Entry.NONE, digest.hash(k)));
    }

    /** LAYOUT 4: resolution and annotation projections have separate trees. */
    private void add(Out out, byte[] key, String simpleName, byte[] resBytes, byte[] tail) {
        out.facts.add(Fact.of(digest, key, resBytes, tail, simpleName));
        for (var descriptor : Ann.tailAnnotationTypes(tail)) Edges.descriptorNames(descriptor, n -> edge(out, n, Edges.ANNOTATION, key));
    }

    private static FileRow.Fault fault(byte[] key, List<String> errors) { return new FileRow.Fault(key, "cannot resolve " + String.join(", ", errors)); }

    // ---- annotations and the tail ------------------------------------------------------------------------------------------

    private Retention retention(AnnotationMirror annotation) {
        var type = (TypeElement) annotation.getAnnotationType().asElement();
        for (var meta : type.getAnnotationMirrors()) {
            if (!((TypeElement) meta.getAnnotationType().asElement()).getQualifiedName().contentEquals("java.lang.annotation.Retention")) continue;
            for (var v : meta.getElementValues().values())
                if (v.getValue() instanceof VariableElement policy) return Retention.valueOf(policy.getSimpleName().toString());
        }
        return Retention.CLASS;
    }

    /** The annotations a class file would carry (source retention is dropped), visible first as in {@link #tail}. */
    private List<AnnotationMirror> retained(Element element) {
        var out = new ArrayList<AnnotationMirror>();
        for (var a : element.getAnnotationMirrors()) {
            if (a.getAnnotationType().getKind() == TypeKind.ERROR) continue;
            if (retention(a) != Retention.SOURCE) out.add(a);
        }
        return out;
    }

    private Res.Warnings warnings(Element element) {
        Ann deprecated = null, safeVarargs = null;
        for (var a : element.getAnnotationMirrors()) {
            String name = ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
            if (name.equals("java.lang.Deprecated")) deprecated = annotation(a);
            if (name.equals("java.lang.SafeVarargs")) safeVarargs = annotation(a);
        }
        return Res.Warnings.of(elements.isDeprecated(element), deprecated, safeVarargs != null);
    }

    /** Full retained annotation metadata; warning annotations overlap the minimal resolution projection. */
    private byte[] tail(Element element, List<AnnotationMirror> retained, boolean method, TypeElement innerOwner) {
        var out = new Codec.Writer();
        for (var wanted : new Retention[] {Retention.RUNTIME, Retention.CLASS}) {
            var chosen = new ArrayList<AnnotationMirror>();
            for (var a : retained) if (retention(a) == wanted) chosen.add(a);
            var list = new ArrayList<Ann>(chosen.size());
            for (var a : chosen) list.add(annotation(a));
            Ann.encodeList(out, list);
        }
        var typeAnnotations = typeAnnotations(element);
        out.u32(typeAnnotations.size());
        for (var a : typeAnnotations) {
            var p = a.position;
            int index = switch (p.type) {
                case CLASS_EXTENDS, THROWS -> p.type_index;
                default -> p.parameter_index;
            };
            var path = new Codec.Writer();
            for (var step : p.location) path.u8(step.tag.tag).u8(step.arg);
            annotation(a).encodeTypeAnnotation(out, p.type.targetTypeValue(), index, p.bound_index, path.toBytes());
        }
        if (method) {
            var names = new ArrayList<String[]>();
            var executable = (ExecutableElement) element;
            boolean writeNames = parameters || executable.getKind() == ElementKind.CONSTRUCTOR && elements.isCanonicalConstructor(executable);
            boolean requiredFlags = innerOwner != null || executable.getParameters().stream()
                    .anyMatch(p -> (((Symbol) p).flags() & (ClassFile.ACC_SYNTHETIC | MANDATED)) != 0);
            if (writeNames || requiredFlags) {
                if (innerOwner != null) {
                    // Lower's outer-instance parameter: private member classes use SYNTHETIC, others MANDATED.
                    var enclosing = types.erasure(((Symbol) element).innermostAccessibleEnclosingClass().asType());
                    int depth = 0;
                    while (enclosing instanceof DeclaredType d && d.getEnclosingType() instanceof DeclaredType outer) {
                        depth++;
                        enclosing = outer;
                    }
                    int flags = ClassFile.ACC_FINAL | (innerOwner.getModifiers().contains(Modifier.PRIVATE) ? ClassFile.ACC_SYNTHETIC : MANDATED);
                    names.add(new String[] {writeNames ? "this$" + depth : null, String.valueOf(flags)});
                }
                for (var p : executable.getParameters()) {
                    int flags = (int) ((Symbol) p).flags() & (ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC | MANDATED);
                    names.add(new String[] {writeNames ? p.getSimpleName().toString() : null, String.valueOf(flags)});
                }
            }
            out.u32(names.size());
            for (var n : names) out.optStr(n[0]).u16(Integer.parseInt(n[1]));
        }
        var bytes = out.toBytes();
        return Arrays.equals(bytes, new byte[bytes.length]) ? Entry.NONE : bytes;
    }

    private Ann annotation(AnnotationMirror a) {
        var elements = new ArrayList<Ann.Element>();
        for (var e : a.getElementValues().entrySet())
            elements.add(new Ann.Element(e.getKey().getSimpleName().toString(), value(e.getValue(), e.getKey().getReturnType())));
        return new Ann(descriptor(a.getAnnotationType(), new ArrayList<>()), elements);
    }

    /** Only declaration targets: code-local annotations are outside this projection. */
    private List<Attribute.TypeCompound> typeAnnotations(Element element) {
        var out = new ArrayList<Attribute.TypeCompound>();
        for (var wanted : new Retention[] {Retention.RUNTIME, Retention.CLASS}) {
            for (var a : ((Symbol) element).getRawTypeAttributes())
                if (!a.position.type.isLocal() && retention(a) == wanted) out.add(a);
        }
        return out;
    }

    /** javac hands a byte, short or int constant over as the same Java type, so the tag comes from the element's declared type, not from the value. */
    private Ann.Val value(AnnotationValue v, TypeMirror expected) {
        Object o = v.getValue();
        return switch (expected.getKind()) {
            case ARRAY -> {
                var component = ((ArrayType) expected).getComponentType();
                var values = new ArrayList<Ann.Val>();
                if (o instanceof List<?> list) for (var item : list) values.add(value((AnnotationValue) item, component));
                else values.add(value(v, component));
                yield new Ann.Val.Array(values);
            }
            case BOOLEAN -> new Ann.Val.Prim('Z', (o instanceof Boolean z ? z : ((Number) o).intValue() != 0) ? 1 : 0);
            case BYTE -> new Ann.Val.Prim('B', ((Number) o).byteValue());
            case CHAR -> new Ann.Val.Prim('C', o instanceof Character c ? c : ((Number) o).intValue() & 0xFFFF);
            case SHORT -> new Ann.Val.Prim('S', ((Number) o).shortValue());
            case INT -> new Ann.Val.Prim('I', ((Number) o).intValue());
            case LONG -> new Ann.Val.Prim('J', ((Number) o).longValue());
            case FLOAT -> new Ann.Val.Prim('F', Float.floatToRawIntBits(((Number) o).floatValue()));
            case DOUBLE -> new Ann.Val.Prim('D', Double.doubleToRawLongBits(((Number) o).doubleValue()));
            default -> {
                if (o instanceof String s) yield new Ann.Val.Str(s);
                if (o instanceof VariableElement constant) yield new Ann.Val.Enum(descriptor(constant.asType(), new ArrayList<>()), constant.getSimpleName().toString());
                if (o instanceof TypeMirror t) yield new Ann.Val.Cls(descriptor(t, new ArrayList<>()));
                if (o instanceof AnnotationMirror nested) yield new Ann.Val.Nested(annotation(nested));
                throw new IllegalArgumentException("Unexpected annotation value " + o);
            }
        };
    }
}
