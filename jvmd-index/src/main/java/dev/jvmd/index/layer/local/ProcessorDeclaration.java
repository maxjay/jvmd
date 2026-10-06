package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;
import dev.jvmd.index.layer.machine.Ann;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;

/** Detached, queryable declaration data from {@link ProcessorElementProjection}; never retains a javac object. */
public record ProcessorDeclaration(ElementKind kind, String name, String docComment, List<String> modifiers,
                                   List<Annotation> annotations, Detail detail) {
    public ProcessorDeclaration { modifiers = List.copyOf(modifiers); annotations = List.copyOf(annotations); }
    public record Source(String path, ProcessorDeclaration declaration) { }
    /** Both maps are observable, including explicit presence and defaults of nested annotations. */
    public record Annotation(Ann explicit, Ann effective) { }

    public sealed interface Detail { }
    public record TypeDeclaration(String binaryName, String nesting, List<ProcessorDeclaration> parameters, Type superclass,
                                  List<Type> interfaces, List<Type> permitted, List<ProcessorDeclaration> components,
                                  List<ProcessorDeclaration> enclosed) implements Detail {
        public TypeDeclaration {
            parameters = List.copyOf(parameters); interfaces = List.copyOf(interfaces); permitted = List.copyOf(permitted);
            components = List.copyOf(components); enclosed = List.copyOf(enclosed);
        }
    }
    public record Executable(List<ProcessorDeclaration> typeParameters, Type returns, Type receiver,
                             List<ProcessorDeclaration> parameters, List<Type> thrown, boolean varargs, boolean defaultMethod,
                             Ann.Val explicitDefault, Ann.Val effectiveDefault) implements Detail {
        public Executable { typeParameters = List.copyOf(typeParameters); parameters = List.copyOf(parameters); thrown = List.copyOf(thrown); }
    }
    public record Variable(Type type, Ann.Val constant) implements Detail { }
    public record Parameter(List<Type> bounds) implements Detail { public Parameter { bounds = List.copyOf(bounds); } }
    public record Package(String qualifiedName, List<ProcessorDeclaration> enclosed) implements Detail {
        public Package { enclosed = List.copyOf(enclosed); }
    }
    public record Other(Type type) implements Detail { }

    public record Type(TypeKind kind, List<Annotation> annotations, Shape shape) {
        public Type { annotations = List.copyOf(annotations); }
    }
    public sealed interface Shape { }
    public record Array(Type component) implements Shape { }
    public record Declared(String binaryName, Type enclosing, List<Type> arguments) implements Shape {
        public Declared { arguments = List.copyOf(arguments); }
    }
    public record VariableReference(byte[] elementKey) implements Shape {
        public VariableReference { elementKey = elementKey.clone(); }
        @Override public byte[] elementKey() { return elementKey.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof VariableReference ref && java.util.Arrays.equals(elementKey, ref.elementKey);
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(elementKey); }
    }
    public record Wildcard(Type extendsBound, Type superBound) implements Shape { }
    public record Intersection(List<Type> bounds) implements Shape { public Intersection { bounds = List.copyOf(bounds); } }
    public record Union(List<Type> alternatives) implements Shape { public Union { alternatives = List.copyOf(alternatives); } }

    /** Decodes the declaration projection, including all descendants, after the header compiler has closed. */
    public static Source decode(byte[] bytes) {
        var in = new Reader(bytes);
        var result = new Source(in.zstr(), declaration(in));
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing processor declaration bytes");
        return result;
    }

    private static ProcessorDeclaration declaration(Reader in) {
        var kind = ElementKind.valueOf(in.str());
        var name = in.str();
        var comment = flag(in) ? in.text() : null;
        var modifiers = list(in, in::str);
        var annotations = annotations(in);
        Detail detail = switch (kind) {
            case CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD -> new TypeDeclaration(in.str(), in.str(), declarations(in),
                    type(in), types(in), types(in), declarations(in), declarations(in));
            case METHOD, CONSTRUCTOR, STATIC_INIT, INSTANCE_INIT -> {
                var parameters = declarations(in); var returns = type(in); var receiver = type(in);
                var arguments = declarations(in); var thrown = types(in); boolean varargs = flag(in), defaults = flag(in);
                boolean hasDefault = flag(in);
                yield new Executable(parameters, returns, receiver, arguments, thrown, varargs, defaults,
                        hasDefault ? value(in) : null, hasDefault ? value(in) : null);
            }
            case FIELD, ENUM_CONSTANT, PARAMETER, LOCAL_VARIABLE, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE ->
                    new Variable(type(in), flag(in) ? value(in) : null);
            case TYPE_PARAMETER -> new Parameter(types(in));
            case PACKAGE -> new Package(in.str(), declarations(in));
            default -> new Other(type(in));
        };
        return new ProcessorDeclaration(kind, name, comment, modifiers, annotations, detail);
    }

    private static Type type(Reader in) {
        var kind = TypeKind.valueOf(in.str());
        var annotations = annotations(in);
        Shape shape = switch (kind) {
            case ARRAY -> new Array(type(in));
            case DECLARED, ERROR -> new Declared(in.str(), type(in), types(in));
            case TYPEVAR -> new VariableReference(in.lenBytes());
            case WILDCARD -> new Wildcard(optionalType(in), optionalType(in));
            case INTERSECTION -> new Intersection(types(in));
            case UNION -> new Union(types(in));
            default -> null;
        };
        return new Type(kind, annotations, shape);
    }

    private static Type optionalType(Reader in) { return flag(in) ? type(in) : null; }
    private static List<Type> types(Reader in) { return list(in, () -> type(in)); }
    private static List<ProcessorDeclaration> declarations(Reader in) { return list(in, () -> declaration(in)); }
    private static List<Annotation> annotations(Reader in) {
        return list(in, () -> new Annotation(annotation(in), annotation(in)));
    }
    private static <T> List<T> list(Reader in, Supplier<T> read) {
        int count = in.count();
        var result = new ArrayList<T>();
        for (int i = 0; i < count; i++) result.add(read.get());
        return List.copyOf(result);
    }
    private static Ann annotation(Reader in) {
        String descriptor = in.str(); int count = in.u16();
        var elements = new ArrayList<Ann.Element>();
        for (int i = 0; i < count; i++) elements.add(new Ann.Element(in.str(), value(in)));
        return new Ann(descriptor, List.copyOf(elements));
    }
    private static Ann.Val value(Reader in) {
        int tag = in.u8();
        return switch (tag) {
            case 's' -> new Ann.Val.Str(in.text());
            case 'Z', 'B', 'C', 'S', 'I', 'F' -> new Ann.Val.Prim(tag, in.u32());
            case 'J', 'D' -> new Ann.Val.Prim(tag, in.u64());
            case 'e' -> new Ann.Val.Enum(in.str(), in.str());
            case 'c' -> new Ann.Val.Cls(in.str());
            case '@' -> new Ann.Val.Nested(annotation(in));
            case '[' -> {
                int count = in.u16(); var values = new ArrayList<Ann.Val>();
                for (int i = 0; i < count; i++) values.add(value(in));
                yield new Ann.Val.Array(List.copyOf(values));
            }
            default -> throw new IllegalArgumentException("Invalid processor annotation tag: " + tag);
        };
    }

    /** Check lengths before allocating: a malformed declaration must not turn a corrupt length into a large allocation. */
    private static final class Reader {
        private final Codec.Reader in;
        Reader(byte[] bytes) { in = new Codec.Reader(bytes); }
        int remaining() { return in.remaining(); }
        void require(int count) {
            if (count < 0 || count > remaining()) throw new IllegalArgumentException("Truncated processor declaration");
        }
        int u8() { require(1); return in.u8(); }
        int u16() { require(2); return in.u16(); }
        long u32() { require(4); return in.u32(); }
        long u64() { require(8); return in.u64(); }
        int count() {
            long count = u32();
            if (count > remaining()) throw new IllegalArgumentException("Invalid processor declaration length");
            return (int) count;
        }
        byte[] lenBytes() { return in.raw(count()); }
        String str() { return new String(lenBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        String text() {
            int length = count();
            if (length > remaining() / 2) throw new IllegalArgumentException("Truncated processor declaration text");
            var text = new char[length];
            for (int i = 0; i < length; i++) text[i] = (char) u16();
            return new String(text);
        }
        String zstr() {
            var bytes = new java.io.ByteArrayOutputStream(); int value;
            while ((value = u8()) != 0) bytes.write(value);
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static boolean flag(Reader in) {
        int flag = in.u8();
        if (flag > 1) throw new IllegalArgumentException("Invalid processor declaration flag");
        return flag == 1;
    }
}
