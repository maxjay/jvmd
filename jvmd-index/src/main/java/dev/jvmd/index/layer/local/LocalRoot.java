package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Root;
import java.util.Arrays;

/**
 * The {@code LROOT|} record (stage 2, B.7): the only completeness signal for a project. It names the MACHINE root it was built
 * against, so warm boot can tell whether MACHINE moved, and the digest of the model bytes, so it can tell whether the build did.
 */
public record LocalRoot(String format, Root local, Identity machineRoot, Identity modelHash, Identity digest) {
    /** {@code str FORMAT || id hash || id sum || u32 count || u8 level || id machineRoot || id modelHash || id Root}. */
    public static byte[] encode(Digest digest, String format, Root local, Identity machineRoot, Identity modelHash) {
        var body = new Codec.Writer(256).str(format).id(local.hash()).id(local.sum()).u32(local.count()).u8(local.level())
                .id(machineRoot).id(modelHash).toBytes();
        return new Codec.Writer(body.length + digest.width()).raw(body).id(digest.hash(body)).toBytes();
    }

    /** The FORMAT a root was written under: its first field, read without verifying the rest. */
    public static String formatOf(byte[] value) { return new Codec.Reader(value).str(); }

    /** Decodes and verifies the trailing digest. */
    public static LocalRoot decode(Digest digest, byte[] value) {
        int width = digest.width();
        var in = new Codec.Reader(value);
        String format = in.str();
        var hash = in.id(width);
        var sum = in.id(width);
        int count = in.count();
        int level = in.u8();
        var machine = in.id(width);
        var model = in.id(width);
        int bodyLength = in.position();
        var stored = in.id(width);
        if (!digest.hash(Arrays.copyOfRange(value, 0, bodyLength)).equals(stored)) throw new IllegalStateException("LROOT digest mismatch");
        return new LocalRoot(format, new Root(hash, sum, count, level), machine, model, stored);
    }
}
