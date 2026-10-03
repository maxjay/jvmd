package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Entry;

/**
 * One declared member of one class file (stage 1, 2.2): its key {@code m}, its one binary encoding {@code e} (resolution fields
 * first), and the resolution identity {@code h = Digest(resolution prefix of e)}.
 */
public record Fact(byte[] m, byte[] e, Identity h, String simpleName) {
    /** The entry of {@code T}: key m, value e, identity h. */
    public Entry entry() { return new Entry(m, e, h); }

    /** The entry of {@code N}: key (simple name, m), no value, identity h. */
    public Entry byName() {
        var key = new Codec.Writer(simpleName.length() + m.length + 2).zstr(simpleName).raw(m).toBytes();
        return new Entry(key, Entry.NONE, h);
    }
}
