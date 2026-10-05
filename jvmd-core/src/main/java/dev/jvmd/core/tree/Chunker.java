package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Hash64;
import dev.jvmd.core.hash.Sum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The streaming builder of one {@link ContentTree} (stage 1, 5.2). Entries are added in key order; a chunk ends after an entry
 * whose key hashes to a boundary or when it holds {@code cap} entries, and each closed chunk is written to the sink and added to
 * the chunker one level up. Only one open chunk per level is live, so memory does not depend on the entry count.
 *
 * <p>An edit ({@link ContentTree#apply}) feeds the same builder, and can also hand it a whole existing subtree at the level it belongs
 * to ({@link #inject}) when nothing below that level is open: the builder then holds exactly what a full build would hold at that point,
 * so the tree it finishes is the one a full build of the new entries would produce.
 */
public final class Chunker {
    private final Digest digest;
    private final Sum sums;
    private final int boundaryMask;
    private final int cap;
    private final NodeSink sink;
    private final int level;
    private final List<Entry> openEntries = new ArrayList<>();
    private final List<Node.Child> openChildren = new ArrayList<>();
    private Chunker parent;
    private byte[] previousKey;
    /** How many nodes of this level this builder has closed: a level that closed none and holds one child is the root, never a wrapper. */
    private int closed;

    Chunker(Digest digest, int b, int cap, NodeSink sink, int level) {
        this.digest = digest;
        this.sums = Sum.forWidth(digest.width());
        this.boundaryMask = b - 1;
        this.cap = cap;
        this.sink = sink;
        this.level = level;
    }

    /** Adds the next entry of a level-0 chunker. Keys must be strictly increasing. */
    public void add(Entry entry) {
        if (level != 0) throw new IllegalStateException("Entries are added at level 0 only");
        if (previousKey != null && Arrays.compareUnsigned(previousKey, entry.key()) >= 0)
            throw new IllegalArgumentException("Entries must be added in strictly increasing key order");
        previousKey = entry.key();
        openEntries.add(entry);
        if (boundary(entry.key()) || openEntries.size() == cap) close();
    }

    /** An interior chunker fed by another builder (ContentList): adds one child of the level below. */
    void addChild(Node.Child child) { add(child); }

    private void add(Node.Child child) {
        openChildren.add(child);
        // Above level 0 the key is the child's hash, so the same boundary rule applies unchanged.
        if (boundary(child.hash().view()) || openChildren.size() == cap) close();
    }

    /** True if a chunk ends after a key (or a child hash) that hashes to a boundary. */
    boolean boundary(byte[] key) { return (Hash64.of(key) & boundaryMask) == 0; }

    int cap() { return cap; }

    /**
     * Hands this level-0 builder a finished node of {@code childLevel} as the next child of the level above it. Valid only when no
     * chunk at levels {@code 0..childLevel} is open ({@link #emptyThrough}): the node then starts where a full build would start it.
     */
    void inject(int childLevel, Node.Child child) { chunkerAt(childLevel + 1).add(child); }

    /** True if no chunk at levels {@code 0..upTo} holds anything: the next item at level {@code upTo} starts a fresh chunk. */
    boolean emptyThrough(int upTo) {
        for (Chunker c = this; c != null && c.level <= upTo; c = c.parent)
            if (!c.openEntries.isEmpty() || !c.openChildren.isEmpty()) return false;
        return true;
    }

    private Chunker chunkerAt(int target) {
        Chunker c = this;
        while (c.level < target) {
            if (c.parent == null) c.parent = new Chunker(digest, boundaryMask + 1, cap, sink, c.level + 1);
            c = c.parent;
        }
        return c;
    }

    private void close() {
        Node node;
        if (level == 0) {
            if (openEntries.isEmpty()) return;
            node = Node.leaf(digest, sums, openEntries);
            openEntries.clear();
        } else {
            if (openChildren.isEmpty()) return;
            node = Node.interior(digest, sums, level, openChildren);
            openChildren.clear();
        }
        sink.write(node);
        closed++;
        if (parent == null) parent = new Chunker(digest, boundaryMask + 1, cap, sink, level + 1);
        parent.add(node.asChild());
    }

    /** Closes every level and returns the root. A root with one child is that child; it is never wrapped. */
    public Root finish() {
        if (level != 0) throw new IllegalStateException("finish() belongs to the level-0 chunker");
        close();
        var root = parent == null ? null : parent.finishUp();
        if (root != null) return root;
        var empty = Node.leaf(digest, sums, List.of());
        sink.write(empty);
        return new Root(empty.hash(), empty.sum(), 0, 0);
    }

    /** Finishes from this level up; null if nothing was ever added at this level or above. */
    Root finishUp() {
        boolean fresh = closed == 0;
        if (fresh && openChildren.isEmpty()) return parent == null ? null : parent.finishUp();
        if (fresh && openChildren.size() == 1 && (parent == null || parent.untouched())) {
            var only = openChildren.get(0);
            return new Root(only.hash(), only.sum(), only.count(), level - 1);
        }
        close();
        return parent.finishUp();
    }

    private boolean untouched() { return closed == 0 && openChildren.isEmpty() && (parent == null || parent.untouched()); }
}
