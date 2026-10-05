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

    /** The text after the last '/' and the last '$' (A.6). */
    public static String simpleName(String internalName) {
        int cut = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
        return internalName.substring(cut + 1);
    }

    private static int nul(byte[] bytes) {
        int end = 0;
        while (bytes[end] != 0) end++;
        return end;
    }
}
