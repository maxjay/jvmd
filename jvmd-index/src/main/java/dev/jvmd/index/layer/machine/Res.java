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

    // ---- a type -------------------------------------------------------------------------------------------------------------

    public record Component(String name, String descriptor, String signature) { }

    /**
     * {@code u8 kind || u16 access || opt<str> signature || opt<str> super || list<str> interfaces || list<str> permits ||
     * opt<str> nestHost || opt<str> outer || list<(str name || str descriptor || opt<str> signature)> components ||
     * [kind 4: opt<annotation>[5] metas] || [kind 5: module]}.
     *
     * @param metas  for an annotation type, one slot per {@link #META_ANNOTATIONS} entry, null where the type does not carry it
     * @param module for a module descriptor (kind 5), the {@code Module} attribute; null if there is none
     */
    public record Type(int kind, int access, String signature, String superName, List<String> interfaces, List<String> permits,
                       String nestHost, String outer, List<Component> components, List<Ann> metas, Module module) {
        public static final int ANNOTATION = 4, MODULE = 5;

        public byte[] encode() {
            var out = new Codec.Writer(128).u8(kind).u16(access).optStr(signature).optStr(superName);
            out.u32(interfaces.size());
            for (var i : interfaces) out.str(i);
            out.u32(permits.size());
            for (var p : permits) out.str(p);
            out.optStr(nestHost).optStr(outer);
            out.u32(components.size());
            for (var c : components) out.str(c.name()).str(c.descriptor()).optStr(c.signature());
            if (kind == ANNOTATION) for (var meta : metas) { if (meta == null) out.u8(0); else { out.u8(1); meta.encode(out); } }
            if (kind == MODULE && module != null) module.encode(out);
            return out.toBytes();
        }

        public static Type decode(byte[] res) {
            var in = new Codec.Reader(res);
            int kind = in.u8(), access = in.u16();
            String signature = in.optStr(), superName = in.optStr();
            var interfaces = strings(in);
            var permits = strings(in);
            String nestHost = in.optStr(), outer = in.optStr();
            int n = in.count();
            var components = new ArrayList<Component>(n);
            for (int i = 0; i < n; i++) components.add(new Component(in.str(), in.str(), in.optStr()));
            var metas = new ArrayList<Ann>();
            if (kind == ANNOTATION) for (int i = 0; i < META_ANNOTATIONS.size(); i++) metas.add(in.u8() == 1 ? Ann.decode(in) : null);
            Module module = kind == MODULE && in.remaining() > 0 ? Module.decode(in) : null;
            return new Type(kind, access, signature, superName, interfaces, permits, nestHost, outer, List.copyOf(components), metas, module);
        }
    }

    // ---- a module descriptor ------------------------------------------------------------------------------------------------

    public record Requires(String module, int flags, String version) { }

    /** An {@code exports} or {@code opens} directive: the package (internal form) and the modules it is qualified to. */
    public record Directive(String packageName, int flags, List<String> to) { }

    public record Provides(String service, List<String> with) { }

    /**
     * {@code str name || u16 flags || opt<str> version || list<requires> || list<exports> || list<opens> || list<str> uses ||
     * list<provides>}: the {@code Module} attribute, with every name resolved.
     */
    public record Module(String name, int flags, String version, List<Requires> requires, List<Directive> exports, List<Directive> opens,
                         List<String> uses, List<Provides> provides) {
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
            return new Module(name, flags, version, List.copyOf(requires), exports, opens, uses, List.copyOf(provides));
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

    /** {@code u16 access || opt<str> signature || opt<constant>}, a constant being {@code u8 tag || payload}. */
    public record Field(int access, String signature, Constant constant) {
        public byte[] encode() {
            var out = new Codec.Writer(32).u16(access).optStr(signature);
            if (constant == null) return out.u8(0).toBytes();
            out.u8(1).u8(constant.tag());
            switch (constant.tag()) {
                case 3, 4 -> out.u32(constant.bits() & 0xFFFFFFFFL);
                case 5, 6 -> out.u64(constant.bits());
                case 8 -> out.str(constant.text());
                default -> throw new IllegalArgumentException("Unexpected ConstantValue tag " + constant.tag());
            }
            return out.toBytes();
        }

        public static Field decode(byte[] res) {
            var in = new Codec.Reader(res);
            int access = in.u16();
            String signature = in.optStr();
            if (in.u8() == 0) return new Field(access, signature, null);
            int tag = in.u8();
            var constant = switch (tag) {
                case 3, 4 -> new Constant(tag, in.u32(), null);
                case 5, 6 -> new Constant(tag, in.u64(), null);
                case 8 -> new Constant(tag, 0, in.str());
                default -> throw new IllegalArgumentException("Unexpected ConstantValue tag " + tag);
            };
            return new Field(access, signature, constant);
        }
    }

    // ---- a method -----------------------------------------------------------------------------------------------------------

    /** {@code u16 access || opt<str> signature || list<str> thrown || opt<value> annotationDefault}. */
    public record Method(int access, String signature, List<String> thrown, Ann.Val defaultValue) {
        public byte[] encode() {
            var out = new Codec.Writer(32).u16(access).optStr(signature).u32(thrown.size());
            for (var t : thrown) out.str(t);
            if (defaultValue == null) out.u8(0); else { out.u8(1); Ann.encode(out, defaultValue); }
            return out.toBytes();
        }

        public static Method decode(byte[] res) {
            var in = new Codec.Reader(res);
            int access = in.u16();
            String signature = in.optStr();
            var thrown = strings(in);
            return new Method(access, signature, thrown, in.u8() == 1 ? Ann.decodeValue(in) : null);
        }
    }

    private static List<String> strings(Codec.Reader in) {
        int n = in.count();
        var out = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) out.add(in.str());
        return List.copyOf(out);
    }
}
