package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;

/**
 * L (stage 1, 2.4 and B.4): the identity and roots of one API, a function of {@code k} alone. Nothing per jar file is in it:
 * faults are a fact about one file and live in that location's path record (A.7, B.5). {@code rootN.sum} and {@code rootO.sum}
 * are not stored: both equal {@code r} (A.6), and a reader that wants to check that recomputes it. T's level is not stored
 * either; a node's first byte says it.
 */
public record MachineLeaf(Identity k, Identity r, Identity nHash, int nLevel, Identity eHash, Identity eSum, int eLevel,
                          Identity oHash, int oLevel, int factCount, int typeCount, int edgeCount) {
    public byte[] encode() {
        var out = new Codec.Writer(256);
        out.id(k).id(r).id(nHash).u8(nLevel).id(eHash).id(eSum).u8(eLevel).id(oHash).u8(oLevel);
        out.u32(factCount).u32(typeCount).u32(edgeCount);
        return out.toBytes();
    }

    public static MachineLeaf decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        var k = in.id(width);
        var r = in.id(width);
        var nHash = in.id(width);
        int nLevel = in.u8();
        var eHash = in.id(width);
        var eSum = in.id(width);
        int eLevel = in.u8();
        var oHash = in.id(width);
        int oLevel = in.u8();
        return new MachineLeaf(k, r, nHash, nLevel, eHash, eSum, eLevel, oHash, oLevel, in.count(), in.count(), in.count());
    }
}
