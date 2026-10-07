package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.Sum;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The one tree type of every layer (stage 1, 2.3): entries sorted by key, cut into content-defined chunks, with a hash and a sum
 * at every node. The shape is a function of the entry set alone, so equal sets give equal roots in any build order; changing d
 * entries reuses subtrees once chunk boundaries resynchronise. Locality depends on the boundary distribution; cap-only
 * runs can require a linear rewrite. The identity of any key range is a sum of O(depth) node sums (fixed fan-out cap).
 */
public final class ContentTree {
    /** Boundary modulus: a chunk ends after a key whose Hash64 is 0 mod B. Chosen in the design (2.3); re-measured per rule 7. */
    public static final int B = 32;
    /** Hard cap on a chunk: 4 * B, so a run of keys with no boundary cannot make an unbounded node (2.3). */
    public static final int CAP = 128;

    private final Digest digest;
    private final Sum sums;
    private final int b;
    private final int cap;

    public ContentTree(Digest digest) { this(digest, B, CAP); }

    public ContentTree(Digest digest, int b, int cap) {
        if (Integer.bitCount(b) != 1) throw new IllegalArgumentException("B must be a power of two");
        if (cap < 2) throw new IllegalArgumentException("CAP must be at least 2");
        this.digest = digest;
        this.sums = Sum.forWidth(digest.width());
        this.b = b;
        this.cap = cap;
    }

    public Digest digest() { return digest; }
    public Sum sums() { return sums; }

    /** A streaming builder over this tree's shape; add entries in key order, then call {@link Chunker#finish()}. */
    public Chunker chunker(NodeSink sink) { return new Chunker(digest, b, cap, sink, 0); }

    /** Builds a tree from entries already sorted by key. */
    public Root build(Iterable<Entry> sorted, NodeSink sink) {
        var chunker = chunker(sink);
        for (var entry : sorted) chunker.add(entry);
        return chunker.finish();
    }

    /**
     * The tree that results from removing the keys {@code removed} from {@code base} and adding {@code added} (an entry whose key is
     * already there replaces it), written to {@code sink}. It is the root a full {@link #build} over the new entries would give, because
     * the shape is a function of the entry set. It reuses unchanged subtrees after boundary resynchronisation; a cap-only
     * run can require linear work even for one insertion or deletion. See {@link Edit}.
     */
    public Root apply(Root base, List<byte[]> removed, List<Entry> added, Function<Identity, byte[]> reader, NodeSink sink) {
        return new Edit(this, removed, added, reader, sink).run(base);
    }

    /**
     * The sum of {@code h} over entries with {@code from <= key < to} (null bounds are open), reading O(depth) nodes: a child
     * wholly inside the range contributes its stored sum without being read.
     */
    public Identity rangeSum(Root root, Function<Identity, byte[]> reader, byte[] from, byte[] to) {
        return range(root.hash(), reader, from, to, null, sums.zero());
    }

    /** Sum over all keys beginning with prefix; all-FF prefixes have no finite upper bound. */
    public Identity rangeSum(Identity root, Function<Identity, byte[]> reader, byte[] prefix) {
        return range(root, reader, prefix, prefixEnd(prefix), null, sums.zero());
    }

    public static byte[] prefixEnd(byte[] prefix) {
        for (int i = prefix.length - 1; i >= 0; i--) {
            if ((prefix[i] & 0xFF) == 0xFF) continue;
            var end = Arrays.copyOf(prefix, i + 1);
            end[i]++;
            return end;
        }
        return null;
    }

    private Identity range(Identity hash, Function<Identity, byte[]> reader, byte[] from, byte[] to, byte[] nodeEnd, Identity acc) {
        var bytes = reader.apply(hash);
        int width = digest.width();
        if (Node.level(bytes) == 0) {
            var result = new Identity[]{acc};
            Node.entries(bytes, width, from, to, false, e -> result[0] = sums.add(result[0], e.h()));
            return result[0];
        }
        var children = Node.children(bytes, width);
        for (int i = 0; i < children.size(); i++) {
            var child = children.get(i);
            byte[] end = i + 1 < children.size() ? children.get(i + 1).first() : nodeEnd;
            boolean entirelyBefore = from != null && end != null && Arrays.compareUnsigned(end, from) <= 0;
            boolean entirelyAfter = to != null && Arrays.compareUnsigned(child.first(), to) >= 0;
            if (entirelyBefore || entirelyAfter) continue;
            // The child covers [child.first, end); end == null means unbounded above.
            boolean inside = (from == null || Arrays.compareUnsigned(child.first(), from) >= 0)
                    && (to == null || (end != null && Arrays.compareUnsigned(end, to) <= 0));
            if (inside) { acc = sums.add(acc, child.sum()); continue; }
            acc = range(child.hash(), reader, from, to, end, acc);
        }
        return acc;
    }

    /** The entry with exactly this key, or null: one node read per level. */
    public Entry get(Identity hash, Function<Identity, byte[]> reader, byte[] key) {
        var bytes = reader.apply(hash);
        int width = digest.width();
        if (Node.level(bytes) == 0) {
            var result = new Entry[1];
            // key followed by zero is the immediate lexicographic successor of the exact byte string.
            Node.entries(bytes, width, key, Arrays.copyOf(key, key.length + 1), true, e -> result[0] = e);
            return result[0];
        }
        // The child that can hold the key is the last one whose first key is not above it.
        Node.Child holder = null;
        for (var child : Node.children(bytes, width)) {
            if (Arrays.compareUnsigned(child.first(), key) > 0) break;
            holder = child;
        }
        return holder == null ? null : get(holder.hash(), reader, key);
    }

    /** Every entry under {@code hash} in key order: one sequential read of the stored tree. */
    public void forEach(Identity hash, Function<Identity, byte[]> reader, java.util.function.Consumer<Entry> out) {
        var bytes = reader.apply(hash);
        int width = digest.width();
        if (Node.level(bytes) == 0) { for (var e : Node.entries(bytes, width)) out.accept(e); return; }
        for (var child : Node.children(bytes, width)) forEach(child.hash(), reader, out);
    }

    /** Entries under a rooted prefix. Nonoverlapping child intervals are never fetched. */
    public void forEach(Identity hash, Function<Identity, byte[]> reader, byte[] prefix, java.util.function.Consumer<Entry> out) {
        entries(hash, reader, prefix, prefixEnd(prefix), null, out);
    }

    private void entries(Identity hash, Function<Identity, byte[]> reader, byte[] from, byte[] to, byte[] nodeEnd,
                         java.util.function.Consumer<Entry> out) {
        var bytes = reader.apply(hash);
        if (Node.level(bytes) == 0) { Node.entries(bytes, digest.width(), from, to, true, out); return; }
        var children = Node.children(bytes, digest.width());
        for (int i = 0; i < children.size(); i++) {
            var child = children.get(i);
            var end = i + 1 < children.size() ? children.get(i + 1).first() : nodeEnd;
            if (from != null && end != null && Arrays.compareUnsigned(end, from) <= 0
                    || to != null && Arrays.compareUnsigned(child.first(), to) >= 0) continue;
            entries(child.hash(), reader, from, to, end, out);
        }
    }

    /** Recomputes every hash, sum and count under the root from the stored bytes; throws if any disagrees. */
    public void verify(Root root, Function<Identity, byte[]> reader) {
        var node = check(root.hash(), reader);
        if (!node.sum().equals(root.sum()) || node.count() != root.count() || node.level() != root.level())
            throw new IllegalStateException("Root does not match its node");
    }

    private Node check(Identity hash, Function<Identity, byte[]> reader) {
        var bytes = reader.apply(hash);
        if (!digest.hash(bytes).equals(hash)) throw new IllegalStateException("Node bytes do not match their hash");
        int width = digest.width();
        int level = Node.level(bytes);
        if (level == 0) {
            List<Entry> entries = Node.entries(bytes, width);
            return Node.leaf(digest, sums, entries);
        }
        var children = Node.children(bytes, width);
        for (var child : children) {
            var below = check(child.hash(), reader);
            if (!below.sum().equals(child.sum()) || below.count() != child.count() || below.level() != level - 1)
                throw new IllegalStateException("Child summary does not match its node");
        }
        return Node.interior(digest, sums, level, children);
    }
}
