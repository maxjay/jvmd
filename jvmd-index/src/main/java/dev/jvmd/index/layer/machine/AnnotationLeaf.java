package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Root;

/** AL|a: the full roots of the independently addressed annotation trees. */
public record AnnotationLeaf(Root annotations, Root edges) {
    public byte[] encode() {
        var out = new Codec.Writer();
        for (var root : new Root[] {annotations, edges})
            out.id(root.hash()).id(root.sum()).u32(root.count()).u8(root.level());
        return out.toBytes();
    }

    public static AnnotationLeaf decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        return new AnnotationLeaf(root(in, width), root(in, width));
    }

    private static Root root(Codec.Reader in, int width) {
        return new Root(in.id(width), in.id(width), in.count(), in.u8());
    }
}
