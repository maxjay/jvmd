package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code C|} record (stage 2, B.5): the proof of one file's result, every identity attribution resolved against, at the
 * tightest kind that covers the use. Written by attribution, not by a cold boot. No kind's identity involves {@code k} (3.5).
 */
public record ConsumerRecord(List<Dependency> dependencies) {
    public static final int MEMBER = 1, OVERLOAD_GROUP = 2, TYPE = 3, PACKAGE = 4, NEGATIVE = 5, DEFINER = 6;

    /** {@code identity} is what was seen; {@code key} is the dependency's key bytes exactly as the reverse index keys them (B.9). */
    public record Dependency(int kind, Identity identity, byte[] key) { }

    public byte[] encode() {
        var out = new Codec.Writer(64 + dependencies.size() * 64).u32(dependencies.size());
        for (var d : dependencies) out.u8(d.kind()).id(d.identity()).raw(d.key());
        return out.toBytes();
    }

    /** The key is self-delimiting per kind (B.5): a member key, or one or two zstr. */
    public static ConsumerRecord decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        int n = in.count();
        var out = new ArrayList<Dependency>(n);
        for (int i = 0; i < n; i++) {
            int kind = in.u8();
            var identity = in.id(width);
            out.add(new Dependency(kind, identity, key(in, kind)));
        }
        return new ConsumerRecord(List.copyOf(out));
    }

    private static byte[] key(Codec.Reader in, int kind) {
        var key = new java.io.ByteArrayOutputStream();
        int zstrs = switch (kind) {
            case MEMBER -> 3; // m(f) = zstr owner || u8 kind || zstr name || zstr descriptor
            case OVERLOAD_GROUP -> 2;
            case TYPE, PACKAGE, NEGATIVE, DEFINER -> 1;
            default -> throw new IllegalArgumentException("Unknown dependency kind " + kind);
        };
        for (int z = 0; z < zstrs; z++) {
            if (kind == MEMBER && z == 1) key.write(in.u8()); // the member kind byte sits between owner and name
            for (int b; (b = in.u8()) != 0; ) key.write(b);
            key.write(0);
        }
        return key.toByteArray();
    }
}
