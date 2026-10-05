package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code RS|} record (stage 2, B.6): what attribution produced for a file under a proof. Written by attribution, not by a cold
 * boot. {@code version} is the layout of this record, independent of FORMAT, so a change to it does not cold boot the project.
 */
public record ResultRecord(int version, List<Diagnostic> diagnostics, List<Reference> references, List<ClassFile> classFiles) {
    public record Diagnostic(int severity, long start, long end, String code, String message) { }

    /** Same kinds and keys as a consumer dependency (B.5). */
    public record Reference(long start, long end, int kind, Identity identity, byte[] key) { }

    /** The bytes are a blob node under {@code N|contentHash}, so identical outputs across checkouts are one record. */
    public record ClassFile(String internalName, Identity contentHash) { }

    public byte[] encode() {
        var out = new Codec.Writer(256).u8(version).u32(diagnostics.size());
        for (var d : diagnostics) out.u8(d.severity()).u32(d.start()).u32(d.end()).str(d.code()).str(d.message());
        out.u32(references.size());
        for (var r : references) out.u32(r.start()).u32(r.end()).u8(r.kind()).id(r.identity()).lenBytes(r.key());
        out.u32(classFiles.size());
        for (var c : classFiles) out.str(c.internalName()).id(c.contentHash());
        return out.toBytes();
    }

    /** A reference's key is length-prefixed here (B.6 writes it raw): a result reader has no per-kind key grammar of its own. */
    public static ResultRecord decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        int version = in.u8();
        int n = in.count();
        var diagnostics = new ArrayList<Diagnostic>(n);
        for (int i = 0; i < n; i++) diagnostics.add(new Diagnostic(in.u8(), in.u32(), in.u32(), in.str(), in.str()));
        int m = in.count();
        var references = new ArrayList<Reference>(m);
        for (int i = 0; i < m; i++) references.add(new Reference(in.u32(), in.u32(), in.u8(), in.id(width), in.lenBytes()));
        int c = in.count();
        var classFiles = new ArrayList<ClassFile>(c);
        for (int i = 0; i < c; i++) classFiles.add(new ClassFile(in.str(), in.id(width)));
        return new ResultRecord(version, List.copyOf(diagnostics), List.copyOf(references), List.copyOf(classFiles));
    }
}
