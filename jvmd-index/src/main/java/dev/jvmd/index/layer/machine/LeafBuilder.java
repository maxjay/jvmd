package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Chunker;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The shape of a leaf (stage 1, 2.2 and B.4), built the same way for a jar and for a source module. Facts are added in strictly
 * increasing {@code m} and streamed into the chunker of {@code T}; the {@code N} entries, the {@code O} entries (each type's running
 * sum) and the edges are buffered. {@link #seal} finishes {@code T} and returns {@code k}; the caller then decides, by whatever it shares
 * with the other builders of its boot, whether this builder is the one that writes the leaf, and if so calls {@link #build}.
 */
public final class LeafBuilder {
    private final ContentTree tree;
    private final NodeSink sink;
    private final Chunker chunker;
    private final Chunker annotations;
    private final List<Entry> names = new ArrayList<>();
    private final List<Entry> edges = new ArrayList<>();
    private final List<Entry> annotationEdges = new ArrayList<>();
    private final List<Entry> types = new ArrayList<>();
    private byte[] owner;
    private Identity ownerSum;
    private Root t;
    private Root aTree;
    private Root eaTree;

    public LeafBuilder(ContentTree tree, NodeSink sink) {
        this.tree = tree;
        this.sink = sink;
        this.chunker = tree.chunker(sink);
        this.annotations = tree.chunker(sink);
        this.ownerSum = tree.sums().zero();
    }

    /** Adds one fact. Facts of one type are adjacent and keys strictly increase, as the chunker requires. */
    public void add(Fact fact) {
        chunker.add(fact.entry());
        var annotation = fact.aEntry(tree.digest());
        if (annotation != null) annotations.add(annotation);
        names.add(fact.byName());
        var m = fact.m();
        if (owner == null || m.length < owner.length || !Arrays.equals(m, 0, owner.length, owner, 0, owner.length)) {
            closeOwner();
            owner = Keys.ownerKeyOf(m);
        }
        ownerSum = tree.sums().add(ownerSum, fact.h());
    }

    /** Adds one edge. Equal keys are one edge. */
    public void edge(Entry edge) {
        (Keys.edgeKind(edge.key()) == Edges.ANNOTATION ? annotationEdges : edges).add(edge);
    }

    public void edges(List<Entry> edges) { edges.forEach(this::edge); }

    private void closeOwner() {
        if (owner != null) types.add(new Entry(owner, Entry.NONE, ownerSum));
        ownerSum = tree.sums().zero();
    }

    /** Finishes T, A and EA before any API-leaf deduplication; returns k and exposes the independent a via {@link #a()}. */
    public Identity seal() {
        closeOwner();
        owner = null;
        t = chunker.finish();
        aTree = annotations.finish();
        eaTree = tree.build(distinct(annotationEdges), sink);
        return t.hash();
    }

    /** Annotation identity travels beside k, never inside L|k. Available after seal. */
    public Identity a() { return tree.digest().hash(aTree.hash().view(), eaTree.hash().view()); }
    public Root annotations() { return aTree; }
    public Root annotationEdges() { return eaTree; }

    /** The {@code O} entries: one per type, its key {@code zstr internalName} and its sum. Valid after {@link #seal}. */
    public List<Entry> types() { return types; }

    /** Builds {@code N}, {@code E} and {@code O} and the leaf record. Valid after {@link #seal}; writes nodes, not the record. */
    public MachineLeaf build() {
        names.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        var n = tree.build(names, sink);
        var e = tree.build(distinct(edges), sink);
        var o = tree.build(types, sink);
        return new MachineLeaf(t.hash(), t.sum(), n.hash(), n.level(), e.hash(), e.sum(), e.level(), o.hash(), o.level(), t.count(), types.size(), e.count());
    }

    private static List<Entry> distinct(List<Entry> edges) {
        edges.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        var out = new ArrayList<Entry>(edges.size());
        for (var edge : edges) if (out.isEmpty() || !Arrays.equals(out.getLast().key(), edge.key())) out.add(edge);
        return out;
    }
}
