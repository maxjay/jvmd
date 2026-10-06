package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;

/** One compilation of a physical source. The same path may participate in several module scopes. */
public record SourceUnit(String module, int scope, String path) implements Comparable<SourceUnit> {
    public SourceUnit {
        if (scope != LocalStore.MAIN && scope != LocalStore.TEST) throw new IllegalArgumentException("Invalid source scope");
    }
    public void encode(Codec.Writer out) { out.zstr(module).u8(scope).zstr(path); }
    public byte[] encode() { var out = new Codec.Writer(); encode(out); return out.toBytes(); }
    public static SourceUnit decode(Codec.Reader in) { return new SourceUnit(in.zstr(), in.u8(), in.zstr()); }
    public static SourceUnit fromFileKey(byte[] key, int width) {
        var in = new Codec.Reader(key); in.raw(3 + width);
        var unit = decode(in);
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing source-unit key bytes");
        return unit;
    }
    @Override public int compareTo(SourceUnit other) {
        int c = module.compareTo(other.module);
        if (c == 0) c = Integer.compare(scope, other.scope);
        return c == 0 ? path.compareTo(other.path) : c;
    }
}
