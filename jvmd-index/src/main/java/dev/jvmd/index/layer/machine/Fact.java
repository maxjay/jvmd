package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.tree.Entry;

/**
 * One declaration with independent resolution and annotation projections (LAYOUT 4).
 * Each projected identity binds the value to its declaration key.
 */
public record Fact(byte[] m, byte[] res, byte[] tail, Identity h, String simpleName) {
    public static Fact of(Digest digest, byte[] m, byte[] res, byte[] tail, String simpleName) {
        return new Fact(m, res, tail, digest.hash(m, res), simpleName);
    }

    /** The entry of {@code T}: res only. */
    public Entry entry() { return new Entry(m, res, h); }

    /** An empty tail has no A entry. */
    public Entry aEntry(Digest digest) { return tail.length == 0 ? null : new Entry(m, tail, digest.hash(m, tail)); }

    /** The entry of {@code N}: exact name, kind, type's outer-or-empty, m; no value, identity h. */
    public Entry byName() {
        int kind = Keys.Member.decode(m).kind();
        var key = Keys.nameKey(simpleName, kind, kind == Keys.TYPE ? Res.Type.decode(res).outer() : null, m);
        return new Entry(key, Entry.NONE, h);
    }
}
