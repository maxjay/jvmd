package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code res} of a fact (stage 1, A.4): the part of it that is a resolution fact, one record per fact kind with its encoder and
 * decoder side by side. {@code ClassFacts} and {@code SourceFacts} build these and encode them; stubs and the module descriptor
 * lookup decode them. Nothing else in the code base knows the byte layout.
 */
public final class Res {
    private Res() { }

    /** An annotation-type's meta-annotations, in this fixed order: {@code Retention, Target, Repeatable, Inherited, Documented}. */
    public static final List<String> META_ANNOTATIONS = List.of("Retention", "Target", "Repeatable", "Inherited", "Documented");

    /** javac reads these attributes even when annotation processing is disabled. */
    public record Warnings(boolean deprecatedAttribute, int deprecation, boolean safeVarargs) {
        public static final Warnings NONE = new Warnings(false, 0, false);
        public Warnings {
            if (deprecation < 0 || deprecation > 2) throw new IllegalArgumentException("Unknown deprecation state " + deprecation);
        }
        /** Only presence and forRemoval affect javac; since and explicit defaults do not. */
        public static Warnings of(boolean attribute, Ann annotation, boolean safeVarargs) {
            int state = annotation == null ? 0 : 1;
            if (annotation != null) for (var e : annotation.elements())
                if (e.name().equals("forRemoval") && e.value() instanceof Ann.Val.Prim p && p.bits() != 0) state = 2;
            return new Warnings(attribute, state, safeVarargs);
        }
        public static boolean reads(String descriptor) {
            return descriptor.equals("Ljava/lang/Deprecated;") || descriptor.equals("Ljava/lang/SafeVarargs;");
        }
        void encode(Codec.Writer out) {
            out.u8(deprecatedAttribute ? 1 : 0).u8(deprecation).u8(safeVarargs ? 1 : 0);
        }
        static Warnings decode(Codec.Reader in) {
            return new Warnings(in.u8() == 1, in.u8(), in.u8() == 1);
        }
        public List<Ann> annotations() {
            var out = new ArrayList<Ann>();
            if (deprecation != 0) out.add(new Ann("Ljava/lang/Deprecated;", deprecation == 2
                    ? List.of(new Ann.Element("forRemoval", new Ann.Val.Prim('Z', 1))) : List.of()));
            if (safeVarargs) out.add(new Ann("Ljava/lang/SafeVarargs;", List.of()));
            return List.copyOf(out);
        }
    }

    // ---- a type -------------------------------------------------------------------------------------------------------------

    public record Component(String name, String descriptor, String signature) { }

    /**
     * {@code u8 kind || u16 access || opt<str> signature || opt<str> super || list<str> interfaces || list<str> permits ||
     * opt<str> nestHost || opt<str> outer || opt<str> innerName || list<(str name || str descriptor || opt<str> signature)> components ||
     * [kind 4: opt<annotation>[5] metas] || [kind 5: module]}.
     *
     * @param metas  for an annotation type, one slot per {@link #META_ANNOTATIONS} entry, null where the type does not carry it
     * @param module for a module descriptor (kind 5), the {@code Module} attribute; null if there is none
     */
    public record Type(int kind, int access, String signature, String superName, List<String> interfaces, List<String> permits,
                       String nestHost, String outer, String innerName, List<Component> components, List<Ann> metas, Module module, Warnings warnings) {
        public static final int ANNOTATION = 4, MODULE = 5;

        public byte[] encode() {
            var out = new Codec.Writer(128).u8(kind).u16(access).optStr(signature).optStr(superName);
            out.u32(interfaces.size());
            for (var i : interfaces) out.str(i);
            out.u32(permits.size());
            for (var p : permits) out.str(p);
            out.optStr(nestHost).optStr(outer).optStr(innerName);
            out.u32(components.size());
            for (var c : components) out.str(c.name()).str(c.descriptor()).optStr(c.signature());
            if (kind == ANNOTATION) for (var meta : metas) { if (meta == null) out.u8(0); else { out.u8(1); meta.encode(out); } }
            if (kind == MODULE) {
                out.u8(module == null ? 0 : 1);
                if (module != null) module.encode(out);
            }
            warnings.encode(out);
            return out.toBytes();
        }

        public static Type decode(byte[] res) {
            var in = new Codec.Reader(res);
            int kind = in.u8(), access = in.u16();
            String signature = in.optStr(), superName = in.optStr();
            var interfaces = strings(in);
            var permits = strings(in);
            String nestHost = in.optStr(), outer = in.optStr(), innerName = in.optStr();
            int n = in.count();
            var components = new ArrayList<Component>(n);
            for (int i = 0; i < n; i++) components.add(new Component(in.str(), in.str(), in.optStr()));
            var metas = new ArrayList<Ann>();
            if (kind == ANNOTATION) for (int i = 0; i < META_ANNOTATIONS.size(); i++) metas.add(in.u8() == 1 ? Ann.decode(in) : null);
            Module module = kind == MODULE && in.u8() == 1 ? Module.decode(in) : null;
            return new Type(kind, access, signature, superName, interfaces, permits, nestHost, outer, innerName, List.copyOf(components), metas, module, Warnings.decode(in));
        }
    }

    // ---- a module descriptor ------------------------------------------------------------------------------------------------

    public record Requires(String module, int flags, String version) { }

    /** An {@code exports} or {@code opens} directive: the package (internal form) and the modules it is qualified to. */
    public record Directive(String packageName, int flags, List<String> to) { }

    public record Provides(String service, List<String> with) { }

    /** Referenced nested declarations in native descriptor constant-pool traversal order. */
    public record Inner(String name, String outer, String simpleName, int flags) { }

    /**
     * {@code str name || u16 flags || opt<str> version || list<requires> || list<exports> || list<opens> || list<str> uses ||
     * list<provides> || list<inner>}: the {@code Module} attribute and referenced {@code InnerClasses} metadata, with every name resolved.
     */
    public record Module(String name, int flags, String version, List<Requires> requires, List<Directive> exports, List<Directive> opens,
                         List<String> uses, List<Provides> provides, List<Inner> innerClasses) {
        void encode(Codec.Writer out) {
            out.str(name).u16(flags).optStr(version);
            out.u32(requires.size());
            for (var r : requires) out.str(r.module()).u16(r.flags()).optStr(r.version());
            directives(out, exports);
            directives(out, opens);
            out.u32(uses.size());
            for (var u : uses) out.str(u);
            out.u32(provides.size());
            for (var p : provides) {
                out.str(p.service()).u32(p.with().size());
                for (var w : p.with()) out.str(w);
            }
            out.u32(innerClasses.size());
            for (var inner : innerClasses) out.str(inner.name()).optStr(inner.outer()).optStr(inner.simpleName()).u16(inner.flags());
        }

        static Module decode(Codec.Reader in) {
            String name = in.str();
            int flags = in.u16();
            String version = in.optStr();
            int n = in.count();
            var requires = new ArrayList<Requires>(n);
            for (int i = 0; i < n; i++) requires.add(new Requires(in.str(), in.u16(), in.optStr()));
            var exports = directives(in);
            var opens = directives(in);
            var uses = strings(in);
            n = in.count();
            var provides = new ArrayList<Provides>(n);
            for (int i = 0; i < n; i++) provides.add(new Provides(in.str(), strings(in)));
            n = in.count();
            var inners = new ArrayList<Inner>(n);
            for (int i = 0; i < n; i++) inners.add(new Inner(in.str(), in.optStr(), in.optStr(), in.u16()));
            return new Module(name, flags, version, List.copyOf(requires), exports, opens, uses, List.copyOf(provides), List.copyOf(inners));
        }

        private static void directives(Codec.Writer out, List<Directive> directives) {
            out.u32(directives.size());
            for (var d : directives) {
                out.str(d.packageName()).u16(d.flags()).u32(d.to().size());
                for (var to : d.to()) out.str(to);
            }
        }

        private static List<Directive> directives(Codec.Reader in) {
            int n = in.count();
            var out = new ArrayList<Directive>(n);
            for (int i = 0; i < n; i++) out.add(new Directive(in.str(), in.u16(), strings(in)));
            return List.copyOf(out);
        }
    }

    // ---- a field ------------------------------------------------------------------------------------------------------------

    /**
     * A {@code ConstantValue}: {@code tag} is the constant-pool tag (3 int, 4 float, 5 long, 6 double, 8 string); {@code bits} the
     * value (a float or double as its raw bits) and {@code text} the string of a tag 8.
     */
    public record Constant(int tag, long bits, String text) { }

    /** {@code u16 access || opt<str> signature || opt<constant> || warnings}, a constant being {@code u8 tag || payload}. */
    public record Field(int access, String signature, Constant constant, Warnings warnings) {
        public byte[] encode() {
            var out = new Codec.Writer(32).u16(access).optStr(signature);
            out.u8(constant == null ? 0 : 1);
            if (constant != null) {
                out.u8(constant.tag());
                switch (constant.tag()) {
                    case 3, 4 -> out.u32(constant.bits() & 0xFFFFFFFFL);
                    case 5, 6 -> out.u64(constant.bits());
                    case 8 -> out.utf16(constant.text());
                    default -> throw new IllegalArgumentException("Unexpected ConstantValue tag " + constant.tag());
                }
            }
            warnings.encode(out);
            return out.toBytes();
        }

        public static Field decode(byte[] res) {
            var in = new Codec.Reader(res);
            int access = in.u16();
            String signature = in.optStr();
            if (in.u8() == 0) return new Field(access, signature, null, Warnings.decode(in));
            int tag = in.u8();
            var constant = switch (tag) {
                case 3, 4 -> new Constant(tag, in.u32(), null);
                case 5, 6 -> new Constant(tag, in.u64(), null);
                case 8 -> new Constant(tag, 0, in.utf16());
                default -> throw new IllegalArgumentException("Unexpected ConstantValue tag " + tag);
            };
            return new Field(access, signature, constant, Warnings.decode(in));
        }
    }

    // ---- a method -----------------------------------------------------------------------------------------------------------

    /** {@code u16 access || opt<str> signature || list<str> thrown || opt<value> annotationDefault || warnings}. */
    public record Method(int access, String signature, List<String> thrown, Ann.Val defaultValue, Warnings warnings) {
        public byte[] encode() {
            var out = new Codec.Writer(32).u16(access).optStr(signature).u32(thrown.size());
            for (var t : thrown) out.str(t);
            if (defaultValue == null) out.u8(0); else { out.u8(1); Ann.encode(out, defaultValue); }
            warnings.encode(out);
            return out.toBytes();
        }

        public static Method decode(byte[] res) {
            var in = new Codec.Reader(res);
            int access = in.u16();
            String signature = in.optStr();
            var thrown = strings(in);
            return new Method(access, signature, thrown, in.u8() == 1 ? Ann.decodeValue(in) : null, Warnings.decode(in));
        }
    }

    private static List<String> strings(Codec.Reader in) {
        int n = in.count();
        var out = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) out.add(in.str());
        return List.copyOf(out);
    }
}
