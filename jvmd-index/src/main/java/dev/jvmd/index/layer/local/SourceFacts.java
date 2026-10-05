package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.machine.Fact;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
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

/**
 * {@code Φ_src} (stage 2, 3.1 and appendix C): the facts and edges of the declarations of one source file, as javac's {@code Enter}
 * and {@code MemberEnter} resolved them, encoded exactly as {@code ClassFacts} would encode the class file javac would emit for
 * them. A source module and its jar therefore have equal {@code r}, equal {@code O} sums, equal {@code N} and {@code E} roots
 * whenever their APIs are equal; {@code k} may differ, because the tail (parameter names, type annotations) depends on how the jar
 * was compiled.
 *
 * <p>Nothing here looks at a method body. A declaration whose header mentions a type that did not resolve is a declaration fault
 * (C.6): it yields no fact and is listed by key; the rest of the file produces facts.
 *
 * <p>Known gaps against a class file, none of which touches {@code r} for ordinary code: type annotations are not written to the
 * tail (they are not resolution facts), and an enum with constant bodies has no {@code PermittedSubclasses} here because javac
 * creates those anonymous classes only when it attributes the bodies.
 */
public final class SourceFacts {
    /** What one file declares. {@code typeKeys} are internal names; each becomes an {@code O} key. */
    public record Result(List<Fact> facts, List<Entry> edges, List<String> typeKeys, List<FileRow.Fault> faults) {
        /** The {@code N} entries of this file: its facts keyed by simple name. */
        public List<Entry> byName() {
            var out = new ArrayList<Entry>(facts.size());
            for (var f : facts) out.add(f.byName());
            return out;
        }
    }

    private static final int KIND_TYPE = 0, KIND_FIELD = 1, KIND_METHOD = 2;
    private static final int EXTENDS = 1, IMPLEMENTS = 2, PERMITS = 3, ENCLOSES = 4, FIELD_TYPE = 5, PARAM_TYPE = 6, RETURN_TYPE = 7,
            THROWS = 8, ANNOTATION = 9, RECORD_COMPONENT_TYPE = 10;
    private static final int TYPE_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
            | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM | ClassFile.ACC_MODULE;
    private static final int INNER_ACCESS = ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED;
    private static final int FIELD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_VOLATILE | ClassFile.ACC_TRANSIENT | ClassFile.ACC_ENUM;
    private static final int METHOD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_ABSTRACT | ClassFile.ACC_VARARGS;
    private static final List<String> META_ANNOTATIONS = List.of("Retention", "Target", "Repeatable", "Inherited", "Documented");
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
    public Result of(List<? extends TypeElement> declared) {
        var out = new Out();
        for (var type : declared) type(type, out);
        return new Result(List.copyOf(out.facts), List.copyOf(out.edges.values()), List.copyOf(out.typeKeys), List.copyOf(out.faults));
    }

    private static final class Out {
        final List<Fact> facts = new ArrayList<>();
        final TreeMap<byte[], Entry> edges = new TreeMap<>(Arrays::compareUnsigned);
        final List<String> typeKeys = new ArrayList<>();
        final List<FileRow.Fault> faults = new ArrayList<>();
    }

    // ---- keys (the same bytes as ClassFacts) ----------------------------------------------------------------------------

    private static byte[] typeKey(String internalName) { return new Codec.Writer(internalName.length() + 2).zstr(internalName).u8(KIND_TYPE).toBytes(); }

    private static byte[] memberKey(String internalName, int kind, String name, String descriptor) {
        return new Codec.Writer(internalName.length() + name.length() + descriptor.length() + 6).zstr(internalName).u8(kind).zstr(name).zstr(descriptor).toBytes();
    }

    private static byte[] edgeKey(String target, int kind, byte[] source) {
        return new Codec.Writer(target.length() + source.length + 2).zstr(target).u8(kind).raw(source).toBytes();
    }

    private static String simpleName(String internalName) {
        int cut = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
        return internalName.substring(cut + 1);
    }

    // ---- types -----------------------------------------------------------------------------------------------------------

    private String binaryName(TypeElement type) { return elements.getBinaryName(type).toString().replace('.', '/'); }

    private void type(TypeElement type, Out out) {
        String owner = binaryName(type);
        byte[] key = typeKey(owner);
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

        var res = new Codec.Writer();
        res.u8(kindCode).u16(access).optStr(signature).optStr(superName);
        res.u32(interfaces.size());
        for (var i : interfaces) res.str(i);
        res.u32(permits.size());
        for (var p : permits) res.str(p);
        res.optStr(host).optStr(outer);
        var components = kindCode == 3 ? type.getRecordComponents() : List.<javax.lang.model.element.RecordComponentElement>of();
        res.u32(components.size());
        var componentTypes = new ArrayList<String[]>();
        for (var rc : components) {
            var t = rc.asType();
            String desc = descriptor(t, errors), csig = signature(t, errors);
            res.str(rc.getSimpleName().toString()).str(desc).optStr(csig);
            componentTypes.add(new String[] {desc, csig});
        }
        var annotations = retained(type);
        if (kindCode == 4) {
            for (var meta : META_ANNOTATIONS) {
                AnnotationMirror found = null;
                for (var a : type.getAnnotationMirrors())
                    if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals("java.lang.annotation." + meta)) found = a;
                if (found == null) res.u8(0); else { res.u8(1); annotation(res, found); }
            }
        }
        if (!errors.isEmpty()) return;

        add(out, key, simpleName(owner), res, tail(type, annotations, false, null));
        if (superName != null) edge(out, superName, EXTENDS, key);
        for (var i : interfaces) edge(out, i, IMPLEMENTS, key);
        for (var p : permits) edge(out, p, PERMITS, key);
        if (outer != null) edge(out, outer, ENCLOSES, key);
        for (var c : componentTypes) typeNames(c[0], c[1], n -> edge(out, n, RECORD_COMPONENT_TYPE, key));
        annotationEdges(annotations, key, out);
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
        byte[] key = memberKey(ownerName, KIND_FIELD, name, desc);
        if (!errors.isEmpty()) { out.faults.add(fault(key, errors)); return; }

        var res = new Codec.Writer();
        res.u16(flags & FIELD_ACCESS).optStr(signature);
        Object constant = field.getConstantValue();
        if (constant == null) res.u8(0);
        else {
            res.u8(1);
            switch (constant) {
                case Boolean z -> res.u8(3).u32(z ? 1 : 0);
                case Character c -> res.u8(3).u32(c);
                case Byte b -> res.u8(3).u32(b.intValue() & 0xFFFFFFFFL);
                case Short s -> res.u8(3).u32(s.intValue() & 0xFFFFFFFFL);
                case Integer i -> res.u8(3).u32(i & 0xFFFFFFFFL);
                case Float f -> res.u8(4).u32(Float.floatToRawIntBits(f) & 0xFFFFFFFFL);
                case Long l -> res.u8(5).u64(l);
                case Double d -> res.u8(6).u64(Double.doubleToRawLongBits(d));
                case String s -> res.u8(8).str(s);
                default -> throw new IllegalArgumentException("Unexpected constant " + constant.getClass());
            }
        }
        var annotations = retained(field);
        add(out, key, name, res, tail(field, annotations, false, null));
        typeNames(desc, signature, n -> edge(out, n, FIELD_TYPE, key));
        annotationEdges(annotations, key, out);
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
        byte[] key = memberKey(ownerName, KIND_METHOD, name, desc.toString());
        // A default value of an annotation element is part of its header: its type must have resolved too.
        var defaultValue = method.getDefaultValue();
        if (!errors.isEmpty()) { out.faults.add(fault(key, errors)); return; }

        var res = new Codec.Writer();
        res.u16(flags & METHOD_ACCESS).optStr(signature);
        res.u32(thrown.size());
        for (var t : thrown) res.str(t);
        if (defaultValue != null) { res.u8(1); value(res, defaultValue, returnType); } else res.u8(0);
        var annotations = retained(method);
        add(out, key, name, res, tail(method, annotations, true, inner ? owner : null));
        methodEdges(out, key, desc.toString(), signature, thrown);
        annotationEdges(annotations, key, out);
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

    private void methodEdges(Out out, byte[] key, String desc, String signature, List<String> thrown) {
        int close = desc.indexOf(')');
        descriptorNames(desc.substring(1, close), n -> edge(out, n, PARAM_TYPE, key));
        descriptorNames(desc.substring(close + 1), n -> edge(out, n, RETURN_TYPE, key));
        for (var t : thrown) edge(out, t, THROWS, key);
        if (signature == null) return;
        try {
            var sig = MethodSignature.parseFrom(signature);
            for (var tp : sig.typeParameters()) typeParamNames(tp, n -> edge(out, n, PARAM_TYPE, key));
            for (var a : sig.arguments()) signatureNames(a, n -> edge(out, n, PARAM_TYPE, key));
            signatureNames(sig.result(), n -> edge(out, n, RETURN_TYPE, key));
            for (var t : sig.throwableSignatures()) signatureNames(t, n -> edge(out, n, THROWS, key));
        } catch (RuntimeException malformed) {
            // As in ClassFacts: a signature that cannot be read contributes no edges; the string itself is still in res.
        }
    }

    private void typeNames(String descriptor, String signature, Consumer<String> out) {
        descriptorNames(descriptor, out);
        if (signature == null) return;
        try { signatureNames(Signature.parseFrom(signature), out); } catch (RuntimeException malformed) { /* see methodEdges */ }
    }

    private static void descriptorNames(String descriptor, Consumer<String> out) {
        for (int i = 0; i < descriptor.length(); i++) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) return;
                out.accept(descriptor.substring(i + 1, end));
                i = end;
            }
        }
    }

    private static String internalName(ClassDesc desc) {
        String d = desc.descriptorString();
        return d.substring(1, d.length() - 1);
    }

    private static void signatureNames(Signature s, Consumer<String> out) {
        switch (s) {
            case Signature.ClassTypeSig c -> {
                c.outerType().ifPresent(o -> signatureNames(o, out));
                out.accept(internalName(c.classDesc()));
                for (var arg : c.typeArgs()) if (arg instanceof Signature.TypeArg.Bounded b) signatureNames(b.boundType(), out);
            }
            case Signature.ArrayTypeSig a -> signatureNames(a.componentSignature(), out);
            default -> { }
        }
    }

    private static void typeParamNames(Signature.TypeParam tp, Consumer<String> out) {
        tp.classBound().ifPresent(b -> signatureNames(b, out));
        for (var b : tp.interfaceBounds()) signatureNames(b, out);
    }

    private void annotationEdges(List<AnnotationMirror> retained, byte[] key, Out out) {
        for (var a : retained) descriptorNames(descriptor(a.getAnnotationType(), new ArrayList<>()), n -> edge(out, n, ANNOTATION, key));
    }

    private void edge(Out out, String target, int kind, byte[] source) {
        var key = edgeKey(target, kind, source);
        out.edges.computeIfAbsent(key, k -> new Entry(k, Entry.NONE, digest.hash(k)));
    }

    /** One fact: {@code e = u32 resLen || res || tail}, {@code h = Digest(res)}. */
    private void add(Out out, byte[] key, String simpleName, Codec.Writer res, byte[] tail) {
        var resBytes = res.toBytes();
        var e = new Codec.Writer(resBytes.length + tail.length + 4).u32(resBytes.length).raw(resBytes).raw(tail).toBytes();
        out.facts.add(new Fact(key, e, digest.hash(resBytes), simpleName));
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

    /** Everything else ClassFacts keeps (A.4): annotations, type annotations, deprecation, and for methods parameter names. */
    private byte[] tail(Element element, List<AnnotationMirror> retained, boolean method, TypeElement innerOwner) {
        var out = new Codec.Writer();
        for (var wanted : new Retention[] {Retention.RUNTIME, Retention.CLASS}) {
            var chosen = new ArrayList<AnnotationMirror>();
            for (var a : retained) if (retention(a) == wanted) chosen.add(a);
            out.u32(chosen.size());
            for (var a : chosen) annotation(out, a);
        }
        out.u32(0); // type annotations: not resolution facts, and javac's target info for them is attribution's business
        out.u8(elements.isDeprecated(element) ? 1 : 0);
        if (method) {
            var names = new ArrayList<String[]>();
            if (parameters) {
                if (innerOwner != null) names.add(new String[] {"this$0", String.valueOf(ClassFile.ACC_FINAL | MANDATED)});
                for (var p : ((ExecutableElement) element).getParameters()) {
                    int flags = (p.getModifiers().contains(Modifier.FINAL) ? ClassFile.ACC_FINAL : 0)
                            | (elements.getOrigin(p) == Elements.Origin.MANDATED ? MANDATED : 0);
                    names.add(new String[] {p.getSimpleName().toString(), String.valueOf(flags)});
                }
            }
            out.u32(names.size());
            for (var n : names) out.optStr(n[0]).u16(Integer.parseInt(n[1]));
        }
        return out.toBytes();
    }

    /** {@code annotation = str typeDescriptor || u16 elementCount || (str name || value)[elementCount]} (A.4a). */
    private void annotation(Codec.Writer out, AnnotationMirror a) {
        var values = a.getElementValues();
        out.str(descriptor(a.getAnnotationType(), new ArrayList<>())).u16(values.size());
        for (var e : values.entrySet()) {
            out.str(e.getKey().getSimpleName().toString());
            value(out, e.getValue(), e.getKey().getReturnType());
        }
    }

    /**
     * {@code value = u8 tag || payload} (A.4a). javac hands a byte, short or int constant over as the same Java type, so the tag
     * comes from the element's declared type, not from the value.
     */
    private void value(Codec.Writer out, AnnotationValue v, TypeMirror expected) {
        Object o = v.getValue();
        switch (expected.getKind()) {
            case ARRAY -> {
                var component = ((ArrayType) expected).getComponentType();
                out.u8('[');
                if (o instanceof List<?> list) {
                    out.u16(list.size());
                    for (var item : list) value(out, (AnnotationValue) item, component);
                } else { out.u16(1); value(out, v, component); }
            }
            case BOOLEAN -> out.u8('Z').u32((o instanceof Boolean z ? z : ((Number) o).intValue() != 0) ? 1 : 0);
            case BYTE -> out.u8('B').u32(((Number) o).byteValue() & 0xFFFFFFFFL);
            case CHAR -> out.u8('C').u32(o instanceof Character c ? c : ((Number) o).intValue() & 0xFFFF);
            case SHORT -> out.u8('S').u32(((Number) o).shortValue() & 0xFFFFFFFFL);
            case INT -> out.u8('I').u32(((Number) o).intValue() & 0xFFFFFFFFL);
            case LONG -> out.u8('J').u64(((Number) o).longValue());
            case FLOAT -> out.u8('F').u32(Float.floatToRawIntBits(((Number) o).floatValue()) & 0xFFFFFFFFL);
            case DOUBLE -> out.u8('D').u64(Double.doubleToRawLongBits(((Number) o).doubleValue()));
            default -> {
                if (o instanceof String s) out.u8('s').str(s);
                else if (o instanceof VariableElement constant) out.u8('e').str(descriptor(constant.asType(), new ArrayList<>())).str(constant.getSimpleName().toString());
                else if (o instanceof TypeMirror t) out.u8('c').str(descriptor(t, new ArrayList<>()));
                else if (o instanceof AnnotationMirror nested) { out.u8('@'); annotation(out, nested); }
                else throw new IllegalArgumentException("Unexpected annotation value " + o);
            }
        }
    }
}
