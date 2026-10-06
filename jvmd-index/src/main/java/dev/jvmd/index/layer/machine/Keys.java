package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.Codec;
import java.util.Arrays;

/**
 * The keys of stage 1, appendix A.3, in one place: {@code m(f)} of a fact, the {@code O} key of the type it belongs to, and the
 * {@code E} key of an edge. Everything that writes or reads a key goes through here, so the byte layout is defined once.
 */
public final class Keys {
    private Keys() { }

    public static final int TYPE = 0, FIELD = 1, METHOD = 2;

    /** {@code m(f)} of a member: {@code zstr owner || u8 kind || zstr name || zstr descriptor}. A type's name and descriptor are empty. */
    public record Member(String owner, int kind, String name, String descriptor) {
        public byte[] encode() { return memberKey(owner, kind, name, descriptor); }

        public static Member decode(byte[] m) {
            var in = new Codec.Reader(m);
            String owner = in.zstr();
            int kind = in.u8();
            if (kind == TYPE) return new Member(owner, kind, "", "");
            return new Member(owner, kind, in.zstr(), in.zstr());
        }
    }

    /** {@code m} of a type fact: {@code zstr internalName || u8 0}. */
    public static byte[] typeKey(String internalName) {
        return new Codec.Writer(internalName.length() + 2).zstr(internalName).u8(TYPE).toBytes();
    }

    public static byte[] memberKey(String internalName, int kind, String name, String descriptor) {
        return new Codec.Writer(internalName.length() + name.length() + descriptor.length() + 6)
                .zstr(internalName).u8(kind).zstr(name).zstr(descriptor).toBytes();
    }

    /** The {@code O} key of a type: {@code zstr internalName}, the prefix of every {@code m} of its facts. */
    public static byte[] ownerKey(String internalName) { return new Codec.Writer(internalName.length() + 1).zstr(internalName).toBytes(); }

    /** Empty name selects the whole member kind; TYPE selects its single header fact. */
    public static byte[] groupKey(String type, int kind, String name) {
        var out = new Codec.Writer().zstr(type).u8(kind);
        if (kind != TYPE && !name.isEmpty()) out.zstr(name);
        return out.toBytes();
    }

    public static byte[] memberTypesKey(String type, String name) {
        return new Codec.Writer().zstr(name).u8(TYPE).zstr(type).toBytes();
    }

    /** N is one rekeyed entry per fact, with an exact Java name and direct enclosing type. */
    public static byte[] nameKey(String name, int kind, String outer, byte[] m) {
        var out = new Codec.Writer().zstr(name).u8(kind);
        if (kind == TYPE) out.zstr(outer == null ? "" : outer);
        return out.raw(m).toBytes();
    }

    /** The {@code O} key of the type a fact belongs to. */
    public static byte[] ownerKeyOf(byte[] m) { return Arrays.copyOf(m, nul(m) + 1); }

    /** The internal name of the type a fact belongs to. */
    public static String ownerOf(byte[] m) { return new String(m, 0, nul(m), java.nio.charset.StandardCharsets.UTF_8); }

    /** {@code key(edge) = zstr target || u8 kind || m(source)}. */
    public static byte[] edgeKey(String target, int kind, byte[] source) {
        return new Codec.Writer(target.length() + source.length + 2).zstr(target).u8(kind).raw(source).toBytes();
    }

    /** The target of an {@code E} key. */
    public static String edgeTarget(byte[] edgeKey) { return ownerOf(edgeKey); }

    public static int edgeKind(byte[] edgeKey) { return edgeKey[nul(edgeKey) + 1] & 0xFF; }

    /** A top-level name; '$' is a legal identifier character, never a separator here. */
    public static String topLevelName(String internalName) {
        int cut = internalName.lastIndexOf('/');
        return internalName.substring(cut + 1);
    }

    private static int nul(byte[] bytes) {
        int end = 0;
        while (bytes[end] != 0) end++;
        return end;
    }
}
