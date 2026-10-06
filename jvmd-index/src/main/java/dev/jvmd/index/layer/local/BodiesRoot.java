package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;

/** Stage 3 B.7: a bodies generation names, but never replaces, the committed LOCAL tree it extended. */
public record BodiesRoot(String format, Identity bodiesRoot, Identity localRoot, Identity machineRoot, Identity modelHash) {
    public static String format(String localFormat) { return localFormat + ";bodies=4"; }
    public boolean current(LocalRoot local) {
        return format.equals(format(local.format())) && localRoot.equals(local.local().hash())
                && machineRoot.equals(local.machineRoot()) && modelHash.equals(local.modelHash());
    }
    public byte[] encode() { return new Codec.Writer().str(format).id(bodiesRoot).id(localRoot).id(machineRoot).id(modelHash).toBytes(); }
    public static BodiesRoot decode(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        var root = new BodiesRoot(in.str(), in.id(width), in.id(width), in.id(width), in.id(width));
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing bodies-root bytes");
        return root;
    }
}
