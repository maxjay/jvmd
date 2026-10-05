package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Hash64;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.Sum;
import java.util.ArrayList;
import java.util.List;

/**
 * The ordered sibling of {@link ContentTree} (stage 2, 3.4 and 5.1): elements in the caller's order, nothing sorted, cut into
 * chunks by {@code boundary(element.id)} and {@code CAP}, with the same Merkle hash and sum above level 0. A separate type and
 * not a flag on the tree: a route is never mistaken for a sorted set, because its level-0 nodes carry the list marker.
 */
public final class ContentList {
    private final Digest digest;
    private final Sum sums;
    private final int boundaryMask;
    private final int cap;

    public ContentList(Digest digest) { this(digest, ContentTree.B, ContentTree.CAP); }

    public ContentList(Digest digest, int b, int cap) {
        if (Integer.bitCount(b) != 1) throw new IllegalArgumentException("B must be a power of two");
        if (cap < 2) throw new IllegalArgumentException("CAP must be at least 2");
        this.digest = digest;
        this.sums = Sum.forWidth(digest.width());
        this.boundaryMask = b - 1;
        this.cap = cap;
    }

    public Digest digest() { return digest; }

    /** A streaming builder: {@link Builder#add} elements in order, then {@link Builder#finish()}. */
    public Builder builder(NodeSink sink) { return new Builder(sink); }

    /** Builds a list from elements in the given order; each element is {@code (id, h)} as an {@link Entry} with key = id. */
    public Root build(List<Entry> elements, NodeSink sink) {
        var builder = builder(sink);
        for (var element : elements) builder.add(element.key(), element.h());
        return builder.finish();
    }

    public final class Builder {
        private final NodeSink sink;
        private final List<Entry> open = new ArrayList<>();
        private final Chunker upper;

        private Builder(NodeSink sink) {
            this.sink = sink;
            this.upper = new Chunker(digest, boundaryMask + 1, cap, sink, 1);
        }

        /** The next element: {@code id} orders nothing, it only decides where chunks end; {@code h} is what the sum adds. */
        public void add(byte[] id, Identity h) {
            open.add(new Entry(id, Entry.NONE, h));
            if ((Hash64.of(id) & boundaryMask) == 0 || open.size() == cap) close();
        }

        private void close() {
            if (open.isEmpty()) return;
            var node = Node.list(digest, sums, open);
            open.clear();
            sink.write(node);
            upper.addChild(node.asChild());
        }

        public Root finish() {
            close();
            var root = upper.finishUp();
            if (root != null) return root;
            var empty = Node.list(digest, sums, List.of());
            sink.write(empty);
            return new Root(empty.hash(), empty.sum(), 0, 0);
        }
    }
}
