package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Identity;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * An edit of a stored {@link ContentTree}: the tree that results from removing and adding entries, found by rewriting the paths the
 * changes touch and reusing every subtree they do not (stage 2, 5.2; used by the definer index).
 *
 * <p>The tree is walked left to right once, feeding a fresh {@link Chunker}. A subtree with no change in its key range is handed to the
 * builder whole, one level up from where it hangs, when the builder is at a boundary (nothing below that level is open): the builder then
 * holds exactly what a full build would hold there, and the subtree's own chunking is what a full build would give it. A subtree with a
 * change is read and its children walked the same way, down to the leaf chunk, whose entries are merged with the changes. After a
 * change the builder may be mid-chunk (a removed boundary, an inserted entry moving a cap cut); the walk then reads on through the next
 * subtrees until a chunk ends where an old one ended, and from there reuses again. Typical unconditioned hash boundaries permit
 * local resynchronisation; adversarial cap-only runs can instead require a linear suffix rewrite.
 *
 * <p>The one subtree a boundary rule cannot vouch for is the last of a level, which a full build closes only because the input ended.
 * It is reused only if it also ended on a boundary of its own, so that appending to the tree cannot be mistaken for starting a new chunk.
 */
final class Edit {
    private record Change(byte[] key, Entry entry) { }

    private final ContentTree tree;
    private final Function<Identity, byte[]> reader;
    private final List<Change> changes;
    private final Chunker out;
    private int next;

    Edit(ContentTree tree, List<byte[]> removed, List<Entry> added, Function<Identity, byte[]> reader, NodeSink sink) {
        this.tree = tree;
        this.reader = reader;
        // An entry added under a key that is also removed replaces it; a key listed twice keeps its last word.
        var byKey = new java.util.TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        for (var key : removed) byKey.put(key, null);
        for (var entry : added) byKey.put(entry.key(), entry);
        this.changes = byKey.entrySet().stream().map(e -> new Change(e.getKey(), e.getValue())).toList();
        this.out = tree.chunker(sink);
    }

    Root run(Root base) {
        if (changes.isEmpty()) return base;
        walk(new Node.Child(base.hash(), base.sum(), base.count(), new byte[0]), base.level(), true, null);
        return out.finish();
    }

    /** True if some change is still to be applied below {@code hi} (exclusive; null is unbounded). */
    private boolean pending(byte[] hi) { return next < changes.size() && (hi == null || Arrays.compareUnsigned(changes.get(next).key(), hi) < 0); }

    private void walk(Node.Child ref, int level, boolean rightmost, byte[] hi) {
        List<Entry> entries = null;
        List<Node.Child> children = null;
        if (!pending(hi) && out.emptyThrough(level)) {
            if (!rightmost) { out.inject(level, ref); return; }
            var bytes = reader.apply(ref.hash());
            boolean boundary;
            if (level == 0) {
                entries = Node.entries(bytes, tree.digest().width());
                boundary = !entries.isEmpty() && (entries.size() == out.cap() || out.boundary(entries.getLast().key()));
            } else {
                children = Node.children(bytes, tree.digest().width());
                boundary = children.size() == out.cap() || out.boundary(children.getLast().hash().view());
            }
            if (boundary) { out.inject(level, ref); return; }
        }
        if (level == 0) {
            if (entries == null) entries = Node.entries(reader.apply(ref.hash()), tree.digest().width());
            merge(entries, hi); return;
        }
        if (children == null) children = Node.children(reader.apply(ref.hash()), tree.digest().width());
        for (int i = 0; i < children.size(); i++)
            walk(children.get(i), level - 1, rightmost && i == children.size() - 1, i + 1 < children.size() ? children.get(i + 1).first() : hi);
    }

    /** The entries of one leaf chunk merged with the changes in its range, into the builder. */
    private void merge(List<Entry> entries, byte[] hi) {
        int i = 0;
        while (i < entries.size() || pending(hi)) {
            int c = i == entries.size() ? 1 : !pending(hi) ? -1 : Arrays.compareUnsigned(entries.get(i).key(), changes.get(next).key());
            if (c < 0) out.add(entries.get(i++));
            else {
                var change = changes.get(next++);
                if (change.entry() != null) out.add(change.entry());
                if (c == 0) i++; // the change replaces or removes this entry
            }
        }
    }

}
