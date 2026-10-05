package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code F|} record (stage 2, 2.4 and B.3): one source file with its content key, its stamp, the sum of the facts it declares
 * and the declarations that faulted. {@code path} is relative to the project root and is the record's key, not part of its value.
 *
 * @param typeKeys the keys of the types the file declares, as {@code O} keys ({@code zstr internalName})
 */
public record FileRow(String path, Identity kappa, long size, long mtimeNanos, Identity sum, List<String> typeKeys, List<Fault> faults) {
    /** One fault: {@code m} is the declaration's key (empty when the whole file is the fault, a parse error) and {@code reason} javac's words. */
    public record Fault(byte[] m, String reason) { }

    /**
     * {@code id κ || u64 size || i64 mtime || id sum || list<zstr> typeKeys || list<(lenBytes m || str reason)> faults}. B.3 writes
     * {@code m} as a {@code zstr}, but a member key contains NUL bytes itself, so a fault's {@code m} carries its length instead.
     */
    public byte[] encode() {
        var out = new Codec.Writer(256).id(kappa).u64(size).i64(mtimeNanos).id(sum).u32(typeKeys.size());
        for (var t : typeKeys) out.zstr(t);
        out.u32(faults.size());
        for (var f : faults) out.lenBytes(f.m()).str(f.reason());
        return out.toBytes();
    }

    public static FileRow decode(String path, byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        var kappa = in.id(width);
        long size = in.u64(), mtime = in.i64();
        var sum = in.id(width);
        int n = in.count();
        var types = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) types.add(zstr(in));
        int f = in.count();
        var faults = new ArrayList<Fault>(f);
        for (int i = 0; i < f; i++) faults.add(new Fault(in.lenBytes(), in.str()));
        return new FileRow(path, kappa, size, mtime, sum, List.copyOf(types), List.copyOf(faults));
    }

    private static String zstr(Codec.Reader in) {
        var out = new java.io.ByteArrayOutputStream();
        for (int b; (b = in.u8()) != 0; ) out.write(b);
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
