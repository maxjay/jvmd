package dev.jvmd.boot.cold.stage2;

import com.sun.tools.javac.code.AnnoConstruct;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Pair;
import dev.jvmd.index.layer.local.ProcessorDeclaration;
import dev.jvmd.index.layer.machine.Ann;
import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;

/**
 * Source-side declaration queries for a body task. Native symbols remain the handles passed to javac utilities,
 * Filer and Messager. Detached annotation values use javac's value/visitor/formatting implementation, without installing
 * metadata in the shared symbols. ProcessorSourceTypes supplies detached native type-use views; package/module views remain separate.
 */
final class ProcessorSourceQueries implements ProcessorReads.Model {
    private final Elements elements;
    private final javax.lang.model.util.Types types;
    private final com.sun.tools.javac.code.Types compilerTypes;
    private final com.sun.tools.javac.code.Symtab symbols;
    private final com.sun.tools.javac.util.Names names;
    private final ProcessorSourceElements declarations;
    private final ProcessorSourceTypes sourceTypes;
    private final Map<Element, Annotations> annotations = new IdentityHashMap<>();
    private final Map<ExecutableElement, AnnotationValue> defaultValues = new IdentityHashMap<>();
    private final Map<AnnotationMirror, Map<? extends ExecutableElement, ? extends AnnotationValue>> defaults = new IdentityHashMap<>();

    ProcessorSourceQueries(ProcessingEnvironment environment, Function<String, ProcessorDeclaration.Source> sources, Predicate<Element> explicit) {
        elements = environment.getElementUtils(); types = environment.getTypeUtils();
        var context = ((JavacProcessingEnvironment) environment).getContext();
        compilerTypes = com.sun.tools.javac.code.Types.instance(context);
        symbols = com.sun.tools.javac.code.Symtab.instance(context); names = com.sun.tools.javac.util.Names.instance(context);
        declarations = new ProcessorSourceElements(elements, types, sources, explicit, this::privateMember);
        sourceTypes = new ProcessorSourceTypes(types, compilerTypes, symbols, declarations, this::descriptor, this::typeAnnotations);
    }

    @Override public Object invoke(Object receiver, Method method, Object[] arguments) throws Throwable {
        String name = method.getName();
        if (receiver == types) {
            if (name.equals("asMemberOf")) return sourceTypes.member((Type) arguments[0], (Element) arguments[1]);
            if (name.equals("directSupertypes")) return sourceTypes.directSupertypes((Type) arguments[0]);
        }
        if (receiver == elements) {
            if (name.equals("getOrigin") && arguments.length == 1 && arguments[0] instanceof Element element) {
                var source = declarations.declaration(element);
                if (source != null) return source.origin();
            }
            if ((name.equals("isBridge") || name.equals("isCompactConstructor") || name.equals("isCanonicalConstructor"))
                    && arguments[0] instanceof ExecutableElement element) {
                var source = declarations.declaration(element);
                if (source != null && source.detail() instanceof ProcessorDeclaration.Executable executable) return switch (name) {
                    case "isBridge" -> executable.bridge();
                    case "isCompactConstructor" -> executable.compactConstructor();
                    default -> executable.canonicalConstructor();
                };
            }
            if (name.equals("getAllMembers") && arguments[0] instanceof TypeElement type) return allMembers(type);
            if (name.equals("getElementValuesWithDefaults") && defaults.containsKey(arguments[0])) return defaults.get(arguments[0]);
            if (name.equals("getDocComment") && arguments[0] instanceof Element element) {
                var source = declarations.declaration(element);
                if (source != null) return source.docComment();
            }
            if (name.equals("isDeprecated") && arguments[0] instanceof Element element) {
                var source = declarations.declaration(element);
                if (source != null) return source.deprecated();
            }
            if (name.equals("getAllAnnotationMirrors") && arguments[0] instanceof Element element)
                return element instanceof TypeElement type ? allAnnotations(type) : annotations(element).getAnnotationMirrors();
        }
        if (receiver instanceof Element element) {
            if (name.equals("getAnnotationMirrors") || name.equals("getAnnotation") || name.equals("getAnnotationsByType")) {
                var view = annotations(element);
                // Invoke the public AnnotatedConstruct contract: ProcessorReads handles its native mirrored-type exceptions.
                return javax.lang.model.AnnotatedConstruct.class.getMethod(name, method.getParameterTypes()).invoke(view, arguments);
            }
            var source = declarations.declaration(element);
            if (source != null) {
                if (name.equals("asType")) return sourceTypes.of(element);
                if (source.detail() instanceof ProcessorDeclaration.TypeDeclaration declaration) {
                    if (name.equals("getSuperclass")) return sourceTypes.type(declaration.superclass());
                    if (name.equals("getInterfaces")) return sourceTypes.list(declaration.interfaces());
                    if (name.equals("getPermittedSubclasses")) return sourceTypes.list(declaration.permitted());
                }
                if (source.detail() instanceof ProcessorDeclaration.Parameter && name.equals("getBounds"))
                    return compilerTypes.getBounds((Type.TypeVar) sourceTypes.of(element));
                if (source.detail() instanceof ProcessorDeclaration.Executable) {
                    if (name.equals("getReturnType")) return sourceTypes.of(element).getReturnType();
                    if (name.equals("getReceiverType")) return sourceTypes.of(element).getReceiverType();
                    if (name.equals("getThrownTypes")) return sourceTypes.of(element).getThrownTypes();
                }
                if (name.equals("getSimpleName")) return elements.getName(source.name());
                if (name.equals("getKind")) return source.kind();
                if (name.equals("getModifiers")) {
                    var modifiers = java.util.EnumSet.noneOf(javax.lang.model.element.Modifier.class);
                    source.modifiers().forEach(m -> modifiers.add(javax.lang.model.element.Modifier.valueOf(m)));
                    return java.util.Collections.unmodifiableSet(modifiers);
                }
                if (name.equals("getEnclosedElements") && element instanceof TypeElement type) return sourceList(declarations.enclosed(type));
                if (name.equals("getDefaultValue") && element instanceof ExecutableElement executable) return defaultValue(executable);
                if (name.equals("getParameters") && element instanceof ExecutableElement executable) return sourceList(executable.getParameters());
                if (name.equals("getTypeParameters")) return sourceList(element instanceof ExecutableElement executable
                        ? executable.getTypeParameters() : ((TypeElement) element).getTypeParameters());
                if (name.equals("getRecordComponents") && element instanceof TypeElement type) return sourceList(type.getRecordComponents());
                if (name.equals("toString")) return elementText(element);
            }
        }
        return method.invoke(receiver, arguments);
    }

    private java.util.List<? extends Element> sourceList(java.util.List<? extends Element> values) {
        return new java.util.AbstractList<Element>() {
            @Override public Element get(int index) { return values.get(index); }
            @Override public int size() { return values.size(); }
            @Override public String toString() { return values.stream().map(ProcessorSourceQueries.this::elementText).collect(java.util.stream.Collectors.joining(",")); }
        };
    }

    private String elementText(Element element) {
        var source = declarations.declaration(element);
        if (source != null && element instanceof Symbol.MethodSymbol method)
            return new Symbol.MethodSymbol(method.flags(), method.name, sourceTypes.of(element), method.owner).toString();
        return source != null && (element instanceof javax.lang.model.element.VariableElement
                || element instanceof javax.lang.model.element.TypeParameterElement) ? source.name() : element.toString();
    }

    /** Build only a temporary member scope. Native override/inheritance rules operate on the original owners and types. */
    private java.util.List<Symbol> allMembers(TypeElement type) {
        var owner = (Symbol) type;
        var declared = com.sun.tools.javac.code.Scope.WriteableScope.create(owner);
        for (var member : memberScope(type).reversed()) declared.enter(member);
        var scope = declared.dupUnshared();
        for (var inherited : compilerTypes.closure((Type) type.asType())) {
            var declaring = (TypeElement) inherited.asElement();
            for (var member : memberScope(declaring)) {
                boolean overridden = false;
                for (var candidate : scope.getSymbolsByName(member.getSimpleName())) {
                    if (candidate.kind == member.kind && (candidate.flags() & com.sun.tools.javac.code.Flags.SYNTHETIC) == 0
                            && candidate.getKind() == javax.lang.model.element.ElementKind.METHOD
                            && elements.overrides((ExecutableElement) candidate, (ExecutableElement) member, declaring)) {
                        overridden = true; break;
                    }
                }
                if (overridden) continue;
                var kind = member.getKind();
                boolean initializer = kind == javax.lang.model.element.ElementKind.CONSTRUCTOR
                        || kind == javax.lang.model.element.ElementKind.STATIC_INIT || kind == javax.lang.model.element.ElementKind.INSTANCE_INIT;
                if (member.owner == owner || !initializer && member.isInheritedIn(owner, compilerTypes)) scope.enter(member);
            }
        }
        var result = new java.util.ArrayList<Symbol>();
        for (var member : scope.getSymbols(com.sun.tools.javac.code.Scope.LookupKind.NON_RECURSIVE))
            if ((member.flags() & com.sun.tools.javac.code.Flags.SYNTHETIC) == 0) result.add(member);
        // getAllMembers returns a standard list (with brackets), unlike javac's comma-only declaration lists.
        var values = java.util.List.copyOf(result);
        return new java.util.AbstractList<Symbol>() {
            @Override public Symbol get(int index) { return values.get(index); }
            @Override public int size() { return values.size(); }
            @Override public String toString() { return values.stream().map(ProcessorSourceQueries.this::elementText)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]")); }
        };
    }

    private java.util.List<Symbol> memberScope(TypeElement type) {
        var source = declarations.enclosed(type);
        // javac prepends record components to getEnclosedElements, but keeps only their backing fields in the member scope.
        if (source != null) return source.reversed().stream()
                .filter(e -> e.getKind() != javax.lang.model.element.ElementKind.RECORD_COMPONENT).map(e -> (Symbol) e).toList();
        var result = new java.util.ArrayList<Symbol>();
        for (var member : ((Symbol) type).members().getSymbols(com.sun.tools.javac.code.Scope.LookupKind.NON_RECURSIVE)) result.add(member);
        return result;
    }

    private Annotations annotations(Element element) {
        var found = annotations.get(element);
        if (found != null) return found;
        var source = declarations.declaration(element);
        List<Attribute.Compound> mirrors;
        if (source == null) mirrors = List.from(element.getAnnotationMirrors().stream().map(a -> (Attribute.Compound) a).toList());
        else mirrors = List.from(source.annotations().stream().map(this::compound).toList());
        var result = new Annotations(element, mirrors); annotations.put(element, result); return result;
    }

    private TypeElement parent(TypeElement type) {
        var superclass = type.getSuperclass();
        return superclass.getKind() == TypeKind.DECLARED ? (TypeElement) types.asElement(superclass) : null;
    }

    /** Match javac's inherited-annotation order, including precedence over farther ancestors and @Inherited on the annotation type. */
    private List<Attribute.Compound> allAnnotations(TypeElement type) {
        var result = annotations(type).getAnnotationMirrors();
        for (var current = type; current.getKind() == javax.lang.model.element.ElementKind.CLASS;) {
            current = parent(current);
            if (current == null || current.getQualifiedName().contentEquals("java.lang.Object")) break;
            var previous = result;
            for (var annotation : annotations(current).getAnnotationMirrors()) {
                var annotationType = (TypeElement) annotation.getAnnotationType().asElement();
                if (annotations(annotationType).getAnnotation(Inherited.class) != null
                        && previous.stream().noneMatch(a -> a.type.tsym == annotation.type.tsym)) result = result.prepend(annotation);
            }
        }
        return result;
    }

    private final class Annotations extends AnnoConstruct {
        private final Element owner;
        private final List<Attribute.Compound> mirrors;
        Annotations(Element owner, List<Attribute.Compound> mirrors) { this.owner = owner; this.mirrors = mirrors; }
        @Override public List<Attribute.Compound> getAnnotationMirrors() { return mirrors; }
        @Override protected <A extends Annotation> Attribute.Compound getAttribute(Class<A> annotation) {
            var direct = super.getAttribute(annotation);
            var parent = direct == null && annotation.isAnnotationPresent(Inherited.class) && owner instanceof TypeElement type ? parent(type) : null;
            return parent == null ? direct : annotations(parent).getAttribute(annotation);
        }
        @Override protected <A extends Annotation> A[] getInheritedAnnotations(Class<A> annotation) {
            var parent = owner instanceof TypeElement type ? parent(type) : null;
            return parent == null ? super.getInheritedAnnotations(annotation) : annotations(parent).getAnnotationsByType(annotation);
        }
    }

    private Attribute.Compound compound(ProcessorDeclaration.Annotation annotation) {
        var result = compound(annotation.explicit(), annotation.effective());
        // JavacElements.getOrigin reads this detached compound's synthesized bit, also for inherited containers.
        result.setSynthesized(annotation.origin() == Elements.Origin.MANDATED);
        return result;
    }

    private Attribute.Compound compound(Ann explicit, Ann effective) {
        var type = descriptor(explicit.descriptor());
        var methods = new LinkedHashMap<String, Symbol.MethodSymbol>();
        for (var member : type.tsym.getEnclosedElements()) if (member instanceof Symbol.MethodSymbol method)
            methods.put(method.getSimpleName().toString(), method);
        var pairs = new java.util.ArrayList<Pair<Symbol.MethodSymbol, Attribute>>();
        var values = new LinkedHashMap<ExecutableElement, AnnotationValue>();
        for (var entry : explicit.elements()) {
            var method = required(methods, entry.name());
            var current = effective.elements().stream().filter(e -> e.name().equals(entry.name())).findFirst().orElseThrow();
            var value = value(entry.value(), current.value(), method.getReturnType());
            pairs.add(Pair.of(method, value)); values.put(method, value);
        }
        var result = new Attribute.Compound(type, List.from(pairs));
        // Defaults return the same handles for explicit entries. Nested values carry their own effective map.
        var complete = new LinkedHashMap<ExecutableElement, AnnotationValue>();
        for (var entry : effective.elements()) {
            var method = required(methods, entry.name());
            complete.put(method, values.containsKey(method) ? values.get(method) : java.util.Objects.requireNonNull(defaultValue(method)));
        }
        defaults.put(result, java.util.Collections.unmodifiableMap(complete));
        return result;
    }

    private AnnotationValue defaultValue(ExecutableElement method) {
        if (defaultValues.containsKey(method)) return defaultValues.get(method);
        var declaration = declarations.declaration(method);
        if (declaration == null) return method.getDefaultValue();
        var executable = (ProcessorDeclaration.Executable) declaration.detail();
        var result = executable.explicitDefault() == null ? null : value(executable.explicitDefault(), executable.effectiveDefault(), (Type) method.getReturnType());
        defaultValues.put(method, result); return result;
    }

    private static <T> T required(Map<String, T> values, String name) {
        var value = values.get(name);
        if (value == null) throw new IllegalStateException("Processor declaration has no native annotation member: " + name);
        return value;
    }

    /** T deliberately omits private members. Give processors detached native handles without inserting them into javac's scopes. */
    private Element privateMember(TypeElement owner, ProcessorDeclaration declaration) {
        if (!declaration.modifiers().contains("PRIVATE"))
            throw new IllegalStateException("Source declaration has no native element: " + declaration.kind() + " " + declaration.name());
        long flags = 0;
        for (var modifier : declaration.modifiers()) flags |= switch (modifier) {
            case "PRIVATE" -> com.sun.tools.javac.code.Flags.PRIVATE;
            case "STATIC" -> com.sun.tools.javac.code.Flags.STATIC;
            case "FINAL" -> com.sun.tools.javac.code.Flags.FINAL;
            case "TRANSIENT" -> com.sun.tools.javac.code.Flags.TRANSIENT;
            case "VOLATILE" -> com.sun.tools.javac.code.Flags.VOLATILE;
            case "SYNCHRONIZED" -> com.sun.tools.javac.code.Flags.SYNCHRONIZED;
            case "NATIVE" -> com.sun.tools.javac.code.Flags.NATIVE;
            case "STRICTFP" -> com.sun.tools.javac.code.Flags.STRICTFP;
            default -> throw new IllegalStateException("Unexpected private member modifier: " + modifier);
        };
        if (declaration.detail() instanceof ProcessorDeclaration.Variable variable) {
            var type = type(variable.type(), Map.of());
            var symbol = new Symbol.VarSymbol(flags, names.fromString(declaration.name()), type, (Symbol) owner);
            if (variable.constant() != null) symbol.setData(((Attribute.Constant) value(variable.constant(), variable.constant(), type)).value);
            return symbol;
        }
        var executable = (ProcessorDeclaration.Executable) declaration.detail();
        if (executable.varargs()) flags |= com.sun.tools.javac.code.Flags.VARARGS;
        var symbol = new Symbol.MethodSymbol(flags, names.fromString(declaration.name()), null, (Symbol) owner);
        var variables = new LinkedHashMap<ProcessorDeclaration.Key, Type>();
        for (var parameter : executable.typeParameters()) variables.put(parameter.key(), new Type.TypeVar(names.fromString(parameter.name()), symbol, symbols.botType));
        for (var parameter : executable.typeParameters()) {
            var bounds = ((ProcessorDeclaration.Parameter) parameter.detail()).bounds().stream().map(t -> type(t, variables)).toList();
            compilerTypes.setBounds((Type.TypeVar) variables.get(parameter.key()), List.from(bounds));
        }
        var arguments = executable.parameters().stream().map(p -> type(((ProcessorDeclaration.Variable) p.detail()).type(), variables)).toList();
        var thrown = executable.thrown().stream().map(t -> type(t, variables)).toList();
        var method = new Type.MethodType(List.from(arguments), type(executable.returns(), variables), List.from(thrown), symbols.methodClass);
        method.recvtype = type(executable.receiver(), variables);
        symbol.type = variables.isEmpty() ? method : new Type.ForAll(List.from(variables.values()), method);
        return symbol;
    }

    private Type type(ProcessorDeclaration.Type source, Map<ProcessorDeclaration.Key, Type> variables) {
        return sourceTypes.type(source, variables);
    }

    private List<Attribute.TypeCompound> typeAnnotations(java.util.List<ProcessorDeclaration.Annotation> source) {
        return List.from(source.stream().map(annotation -> {
            var value = compound(annotation);
            var result = new Attribute.TypeCompound(value, com.sun.tools.javac.code.TypeAnnotationPosition.unknown);
            // TypeCompound does not copy Compound's synthesized bit.
            result.setSynthesized(value.isSynthesized());
            defaults.put(result, defaults.get(value)); return result;
        }).toList());
    }

    private Attribute value(Ann.Val explicit, Ann.Val effective, Type expected) {
        return switch (explicit) {
            case Ann.Val.Str text -> new Attribute.Constant(expected, text.value());
            case Ann.Val.Prim primitive -> new Attribute.Constant(expected, switch (primitive.tag()) {
                case 'J' -> Long.valueOf(primitive.bits());
                case 'D' -> Double.valueOf(Double.longBitsToDouble(primitive.bits()));
                case 'F' -> Float.valueOf(Float.intBitsToFloat((int) primitive.bits()));
                default -> Integer.valueOf((int) primitive.bits());
            });
            case Ann.Val.Cls type -> new Attribute.Class(compilerTypes, descriptor(type.descriptor()));
            case Ann.Val.Enum enumeration -> {
                var type = descriptor(enumeration.descriptor());
                var constant = type.tsym.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals(enumeration.constant())
                        && e.getKind() == javax.lang.model.element.ElementKind.ENUM_CONSTANT).findFirst().orElseThrow();
                yield new Attribute.Enum(type, (Symbol.VarSymbol) constant);
            }
            case Ann.Val.Nested nested -> compound(nested.annotation(), ((Ann.Val.Nested) effective).annotation());
            case Ann.Val.Array array -> {
                var component = ((Type.ArrayType) expected).getComponentType();
                var current = ((Ann.Val.Array) effective).values();
                var values = new Attribute[array.values().size()];
                for (int i = 0; i < values.length; i++) values[i] = value(array.values().get(i), current.get(i), component);
                yield new Attribute.Array(expected, values);
            }
        };
    }

    private Type descriptor(String descriptor) {
        return (Type) switch (descriptor.charAt(0)) {
            case 'Z' -> types.getPrimitiveType(TypeKind.BOOLEAN); case 'B' -> types.getPrimitiveType(TypeKind.BYTE);
            case 'C' -> types.getPrimitiveType(TypeKind.CHAR); case 'S' -> types.getPrimitiveType(TypeKind.SHORT);
            case 'I' -> types.getPrimitiveType(TypeKind.INT); case 'J' -> types.getPrimitiveType(TypeKind.LONG);
            case 'F' -> types.getPrimitiveType(TypeKind.FLOAT); case 'D' -> types.getPrimitiveType(TypeKind.DOUBLE);
            case 'V' -> types.getNoType(TypeKind.VOID);
            case '[' -> types.getArrayType(descriptor(descriptor.substring(1)));
            case 'L' -> {
                String name = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                var type = elements.getTypeElement(name);
                // Elements accepts canonical names; a descriptor carries an exact binary name. Use javac's ordinary
                // binary-name entry in the resolved package's module, retaining literal '$' in both outer and inner names.
                if (type == null) {
                    int dot = name.lastIndexOf('.');
                    var pkg = (Symbol.PackageSymbol) elements.getPackageElement(dot < 0 ? "" : name.substring(0, dot));
                    if (pkg != null) { var symbol = symbols.enterClass(pkg.modle, names.fromString(name)); symbol.complete(); type = symbol; }
                }
                if (type == null) throw new IllegalStateException("Processor annotation type cannot be resolved: " + name);
                yield types.erasure(type.asType());
            }
            default -> throw new IllegalStateException("Invalid processor annotation descriptor: " + descriptor);
        };
    }
}
