package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;

/** SL|projectKey|module|scope: the persistent own-leaf binding, committed before LROOT. */
public record SourceLeaf(Identity k, Identity a, Identity compilerView, Identity reader) {
    public SourceLeaf(Identity k,Identity a) {this(k,a,null,null);}
    public byte[] encode() { return new Codec.Writer().id(k).id(a).optId(compilerView).optId(reader).toBytes(); }
    public static SourceLeaf decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        var result=new SourceLeaf(in.id(width),in.id(width),in.optId(width),in.optId(width));
        if(in.remaining()!=0)throw new IllegalArgumentException("Trailing source-leaf binding");
        return result;
    }
}
