package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code X|} record (stage 2, B.9): the consumers of one identity, keyed by the dependency's key bytes so that a changed type
 * is one range read. Attribution writes it beside each consumer record; stage 2 writes the kind 7 (header) entries beside the file rows.
 */
public record ReverseIndex(List<Consumer> consumers) {
    /** A consumer is a file under the stable part of the route it was resolved under (B.9). */
    public record Consumer(Identity kappa, Identity leafSetExt) { }

    public byte[] encode() {
        var out = new Codec.Writer(8 + consumers.size() * 64).u32(consumers.size());
        for (var c : consumers) out.id(c.kappa()).id(c.leafSetExt());
        return out.toBytes();
    }

    public static ReverseIndex decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        int n = in.count();
        var out = new ArrayList<Consumer>(n);
        for (int i = 0; i < n; i++) out.add(new Consumer(in.id(width), in.id(width)));
        return new ReverseIndex(List.copyOf(out));
    }
}
