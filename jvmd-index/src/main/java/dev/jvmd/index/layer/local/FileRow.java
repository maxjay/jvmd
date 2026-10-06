package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code F|} record (stage 2, 2.4, 3.18 and B.3): one source file with its content key, its stamp, the sum of the facts it
 * declares, the declarations that faulted, and the header proof. {@code path} is relative to the project root and is the record's
 * key, not part of its value.
 *
 * @param typeKeys    the keys of the types the file declares, as {@code O} keys ({@code zstr internalName})
 * @param headerProof exact T ranges consumed by declaration attribution, including expected-zero member ranges.
 *                    The file's facts are valid while those ranges and its absences hold under the current binding.
 */
public record FileRow(String path, Identity kappa, long size, long mtimeNanos, Identity sum, List<String> typeKeys, List<Fault> faults,
                      List<Proof> headerProof, Identity ownR, List<HeaderProof.Absence> absences) {
    /** One fault: {@code m} is the declaration's key (empty when the whole file is the fault, a parse error) and {@code reason} javac's words. */
    public record Fault(byte[] m, String reason) { }

    /** A T range and its algebraic sum; TYPE selects the header, FIELD/METHOD select a named group (or the whole kind for empty name). */
    public record Proof(String typeKey, int kind, String name, Identity sum) {
        public HeaderProof.Range range() { return new HeaderProof.Range(typeKey, kind, name); }
    }

    /**
     * {@code id κ || u64 size || i64 mtime || id sum || list<zstr> typeKeys || list<(u32 len || m || str reason)> faults ||
     * list<(zstr typeKey || u8 kind || zstr name || id sum)> headerProof || id ownR || list<absence>}.
     * A fault's {@code m} is length-prefixed because a member key contains NUL bytes.
     */
    public byte[] encode() {
        var out = new Codec.Writer(256).id(kappa).u64(size).i64(mtimeNanos).id(sum).u32(typeKeys.size());
        for (var t : typeKeys) out.zstr(t);
        out.u32(faults.size());
        for (var f : faults) out.lenBytes(f.m()).str(f.reason());
        out.u32(headerProof.size());
        for (var p : headerProof) out.zstr(p.typeKey()).u8(p.kind()).zstr(p.name()).id(p.sum());
        out.id(ownR).u32(absences.size());
        for (var absence : absences) absence.encode(out);
        return out.toBytes();
    }

    public static FileRow decode(String path, byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        var kappa = in.id(width);
        long size = in.u64(), mtime = in.i64();
        var sum = in.id(width);
        int n = in.count();
        var types = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) types.add(in.zstr());
        int f = in.count();
        var faults = new ArrayList<Fault>(f);
        for (int i = 0; i < f; i++) faults.add(new Fault(in.lenBytes(), in.str()));
        int p = in.count();
        var proof = new ArrayList<Proof>(p);
        for (int i = 0; i < p; i++) proof.add(new Proof(in.zstr(), in.u8(), in.zstr(), in.id(width)));
        var ownR = in.id(width);
        int a = in.count();
        var absences = new ArrayList<HeaderProof.Absence>(a);
        for (int i = 0; i < a; i++) absences.add(HeaderProof.Absence.decode(in));
        return new FileRow(path, kappa, size, mtime, sum, List.copyOf(types), List.copyOf(faults), List.copyOf(proof), ownR, List.copyOf(absences));
    }
}
