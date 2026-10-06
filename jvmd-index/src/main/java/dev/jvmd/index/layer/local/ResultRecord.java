package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/** Stage 3 B.2: RS|ACI contains class references and ordered diagnostics. Uses are a separate U|ACI record. */
public record ResultRecord(boolean attributed, List<ClassFile> classFiles, List<Diagnostic> diagnostics) {
    /** UTF-16 character offsets reported by javac; -1 (NOPOS) is encoded as the u32 all-ones sentinel. */
    public record Diagnostic(int kind, long start, long end, String code, String message) {
        public Diagnostic {
            if (kind < 0 || kind > 2) throw new IllegalArgumentException("Unknown result diagnostic kind");
            if (start < -1 || end < -1 || start >= 0xffff_ffffL || end >= 0xffff_ffffL)
                throw new IllegalArgumentException("Diagnostic position outside u32/NOPOS encoding");
        }
    }

    /** Bytes live under CF|contentHash, separate from the shared tree-node space. */
    public record ClassFile(String internalName, Identity contentHash) { }

    public ResultRecord {
        classFiles = classFiles.stream().sorted((a, b) -> java.util.Arrays.compareUnsigned(
                dev.jvmd.index.layer.machine.Keys.ownerKey(a.internalName()), dev.jvmd.index.layer.machine.Keys.ownerKey(b.internalName()))).toList();
        diagnostics = List.copyOf(diagnostics);
        String previous = null;
        for (var file : classFiles) {
            if (file.internalName().equals(previous)) throw new IllegalArgumentException("Duplicate class output " + previous);
            previous = file.internalName();
        }
    }

    public byte[] encode() {
        var out = new Codec.Writer(256).u8(attributed ? 1 : 0).u32(classFiles.size());
        for (var c : classFiles) out.zstr(c.internalName()).id(c.contentHash());
        out.u32(diagnostics.size());
        for (var d : diagnostics) out.u8(d.kind()).u32(d.start()).u32(d.end()).str(d.code()).str(d.message());
        return out.toBytes();
    }

    public static ResultRecord decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        int flag = in.u8();
        if (flag > 1) throw new IllegalArgumentException("Invalid attribution flag");
        int c = in.count();
        var classFiles = new ArrayList<ClassFile>(c);
        for (int i = 0; i < c; i++) classFiles.add(new ClassFile(in.zstr(), in.id(width)));
        int n = in.count();
        var diagnostics = new ArrayList<Diagnostic>(n);
        for (int i = 0; i < n; i++) diagnostics.add(new Diagnostic(in.u8(), position(in.u32()), position(in.u32()), in.str(), in.str()));
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing result bytes");
        return new ResultRecord(flag == 1, classFiles, diagnostics);
    }

    private static long position(long value) { return value == 0xffff_ffffL ? -1 : value; }
}
