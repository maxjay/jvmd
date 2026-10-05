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

    /**
     * Finishes an interior chunker whose children came from a level-0 builder; returns null if it was never fed (the caller then has
     * an empty list). A single child is the root, never wrapped.
     */
    Root finishFrom() {
        if (parent == null && openChildren.size() == 1) {
            var only = openChildren.get(0);
            return new Root(only.hash(), only.sum(), only.count(), level - 1);
        }
        if (openChildren.isEmpty() && parent == null) return null;
        return finishUp();
    }

    private void add(Node.Child child) {
        openChildren.add(child);
        // Above level 0 the key is the child's hash, so the same boundary rule applies unchanged.
        if (boundary(child.hash().view()) || openChildren.size() == cap) close();
    }

    private boolean boundary(byte[] key) { return (Hash64.of(key) & boundaryMask) == 0; }

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
        if (parent == null) parent = new Chunker(digest, boundaryMask + 1, cap, sink, level + 1);
        parent.add(node.asChild());
    }

    /** Closes every level and returns the root. A root with one child is that child; it is never wrapped. */
    public Root finish() {
        if (level != 0) throw new IllegalStateException("finish() belongs to the level-0 chunker");
        close();
        if (parent == null) {
            var empty = Node.leaf(digest, sums, List.of());
            sink.write(empty);
            return new Root(empty.hash(), empty.sum(), 0, 0);
        }
        return parent.finishUp();
    }

    private Root finishUp() {
        if (openChildren.size() == 1 && parent == null) {
            var only = openChildren.get(0);
            return new Root(only.hash(), only.sum(), only.count(), levelOfChild());
        }
        close();
        return parent.finishUp();
    }

    private int levelOfChild() { return level - 1; }
}
