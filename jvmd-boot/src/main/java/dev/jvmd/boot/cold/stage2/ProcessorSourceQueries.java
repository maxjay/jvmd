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
 * Source-side type declaration queries for a body task. Native symbols remain the handles passed to javac utilities,
 * Filer and Messager. Detached annotation values use javac's value/visitor/formatting implementation, without installing
 * metadata in the shared symbols. Member/type-use and package/module source views are separate outstanding adapters.
 */
final class ProcessorSourceQueries implements ProcessorReads.Model {
    private final Elements elements;
    private final javax.lang.model.util.Types types;
    private final com.sun.tools.javac.code.Types compilerTypes;
    private final com.sun.tools.javac.code.Symtab symbols;
    private final com.sun.tools.javac.util.Names names;
    private final Function<String, ProcessorDeclaration.Source> sources;
    private final Predicate<Element> explicit;
    private final Map<TypeElement, ProcessorDeclaration.Source> declarations = new IdentityHashMap<>();
    private final Map<TypeElement, Annotations> annotations = new IdentityHashMap<>();
    private final Map<AnnotationMirror, Map<? extends ExecutableElement, ? extends AnnotationValue>> defaults = new IdentityHashMap<>();

    ProcessorSourceQueries(ProcessingEnvironment environment, Function<String, ProcessorDeclaration.Source> sources, Predicate<Element> explicit) {
        elements = environment.getElementUtils(); types = environment.getTypeUtils();
        var context = ((JavacProcessingEnvironment) environment).getContext();
        compilerTypes = com.sun.tools.javac.code.Types.instance(context);
        symbols = com.sun.tools.javac.code.Symtab.instance(context); names = com.sun.tools.javac.util.Names.instance(context);
        this.sources = sources; this.explicit = explicit;
    }

    @Override public Object invoke(Object receiver, Method method, Object[] arguments) throws Throwable {
        String name = method.getName();
        if (receiver == elements) {
            if (name.equals("getElementValuesWithDefaults") && defaults.containsKey(arguments[0])) return defaults.get(arguments[0]);
            if (name.equals("getDocComment") && arguments[0] instanceof TypeElement type) {
                var source = source(type);
                if (source != null) return source.declaration().docComment();
            }
            if (name.equals("getAllAnnotationMirrors") && arguments[0] instanceof TypeElement type) return allAnnotations(type);
        }
        if (receiver instanceof TypeElement type && (name.equals("getAnnotationMirrors") || name.equals("getAnnotation") || name.equals("getAnnotationsByType"))) {
            var view = annotations(type);
            // Invoke the public AnnotatedConstruct contract: ProcessorReads handles its native mirrored-type exceptions.
            return javax.lang.model.AnnotatedConstruct.class.getMethod(name, method.getParameterTypes()).invoke(view, arguments);
        }
        return method.invoke(receiver, arguments);
    }

    private ProcessorDeclaration.Source source(TypeElement type) {
        if (!declarations.containsKey(type)) declarations.put(type, explicit.test(type) ? null
                : sources.apply(elements.getBinaryName(type).toString().replace('.', '/')));
        return declarations.get(type);
    }

    private Annotations annotations(TypeElement type) {
        var found = annotations.get(type);
        if (found != null) return found;
        var source = source(type);
        List<Attribute.Compound> mirrors;
        if (source == null) mirrors = List.from(type.getAnnotationMirrors().stream().map(a -> (Attribute.Compound) a).toList());
        else mirrors = List.from(source.declaration().annotations().stream().map(a -> compound(a.explicit(), a.effective())).toList());
        var result = new Annotations(type, mirrors); annotations.put(type, result); return result;
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
        private final TypeElement owner;
        private final List<Attribute.Compound> mirrors;
        Annotations(TypeElement owner, List<Attribute.Compound> mirrors) { this.owner = owner; this.mirrors = mirrors; }
        @Override public List<Attribute.Compound> getAnnotationMirrors() { return mirrors; }
        @Override protected <A extends Annotation> Attribute.Compound getAttribute(Class<A> annotation) {
            var direct = super.getAttribute(annotation);
            var parent = direct == null && annotation.isAnnotationPresent(Inherited.class) ? parent(owner) : null;
            return parent == null ? direct : annotations(parent).getAttribute(annotation);
        }
        @Override protected <A extends Annotation> A[] getInheritedAnnotations(Class<A> annotation) {
            var parent = parent(owner);
            return parent == null ? super.getInheritedAnnotations(annotation) : annotations(parent).getAnnotationsByType(annotation);
        }
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
            complete.put(method, values.containsKey(method) ? values.get(method) : defaultValue(method, entry.value()));
        }
        defaults.put(result, java.util.Collections.unmodifiableMap(complete));
        return result;
    }

    private AnnotationValue defaultValue(Symbol.MethodSymbol method, Ann.Val effective) {
        var source = source((TypeElement) method.getEnclosingElement());
        if (source == null) return java.util.Objects.requireNonNull(method.getDefaultValue());
        // Annotation members have distinct names and no parameters. The effective map alone loses explicit presence
        // inside a default such as @Nested; its declaration retains the exact explicit default, including nested arrays.
        var declaration = (ProcessorDeclaration.TypeDeclaration) source.declaration().detail();
        var member = declaration.enclosed().stream().filter(e -> e.kind() == javax.lang.model.element.ElementKind.METHOD
                && e.name().contentEquals(method.getSimpleName())).findFirst().orElseThrow();
        var literal = ((ProcessorDeclaration.Executable) member.detail()).explicitDefault();
        if (literal == null) throw new IllegalStateException("Source annotation member has no declared default: " + method);
        return value(literal, effective, method.getReturnType());
    }

    private static <T> T required(Map<String, T> values, String name) {
        var value = values.get(name);
        if (value == null) throw new IllegalStateException("Processor declaration has no native annotation member: " + name);
        return value;
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
