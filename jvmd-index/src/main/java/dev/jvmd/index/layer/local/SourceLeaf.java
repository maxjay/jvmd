package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;

/** SL|projectKey|module|scope: the persistent own-leaf binding, committed before LROOT. */
public record SourceLeaf(Identity k, Identity a) {
    public byte[] encode() { return new Codec.Writer().id(k).id(a).toBytes(); }
    public static SourceLeaf decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        return new SourceLeaf(in.id(width), in.id(width));
    }
}
