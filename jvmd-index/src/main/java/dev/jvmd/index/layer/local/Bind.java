package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentList;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** {@code bind} (stage 2, 3.3, 3.16 and 5.3): coordinates to leaves at read time, walking the layers from the top. */
public final class Bind {
    private Bind() { }

    /** The two independent identities a provider supplies. */
    public record Leaf(Identity k, Identity a,Identity reader) {
        public Leaf(Identity k,Identity a) {this(k,a,null);}
    }

    /** A layer that may supply the leaf of a coordinate, by exact coordinate. */
    @FunctionalInterface public interface Provider {
        /** The leaf key, or null if this layer has none for the coordinate. */
        Leaf leafFor(String coordinate);
    }

    public static final Provider NONE = coordinate -> null;

    /**
     * session over built over default. The list of bound {@code k} becomes a {@link ContentList}: {@code routeHash} is its root hash,
     * {@code R} its root sum ({@code Σ r}). {@code leafSetExt} and {@code leafSetSib} are the Merkle hashes of canonical external
     * and sibling {@code k}. An entry with no binding (a jar whose file is missing) is left out of the sequence; a sibling entry whose
     * module has not been built is a bug in the build order and throws.
     */
    public static Bound bind(Digest digest, List<RouteEntry> entries, Provider session, Provider built, Function<Identity, MachineLeaf> leafOf, NodeSink sink) {
        var bindings = new ArrayList<Bound.Binding>(entries.size());
        for (var entry : entries) {
            Leaf leaf = session.leafFor(entry.coordinate());
            var origin = Bound.Origin.SESSION;
            if (leaf == null) { leaf = built.leafFor(entry.coordinate()); origin = Bound.Origin.SIBLING; }
            if (leaf == null) {
                origin = Bound.Origin.EXTERNAL;
                leaf = switch (entry) {
                    case RouteEntry.Jar j -> j.defaultK() == null ? null : new Leaf(j.defaultK(), j.a(),j.reader());
                    case RouteEntry.Jrt j -> new Leaf(j.k(), j.a(),j.reader());
                    case RouteEntry.Sibling s -> throw new IllegalStateException("Module " + s.module() + " is bound before it is built");
                };
            }
            if (leaf == null) continue; // a jar whose file is missing binds to nothing
            bindings.add(new Bound.Binding(entry, leaf.k(), leaf.a(), origin,leaf.reader()));
        }
        var list = new ContentList(digest).builder(sink);
        var sequence = new ArrayList<Identity>(bindings.size());
        for (var binding : bindings) {
            sequence.add(binding.k());
            list.add(binding.k().view(), leafOf.apply(binding.k()).r());
        }
        var root = list.finish();
        var external = distinct(bindings, false);
        var sibling = distinct(bindings, true);
        var externalRoot = leafSetRoot(digest, external, sink);
        var siblingRoot = leafSetRoot(digest, sibling, sink);
        return new Bound(List.copyOf(bindings), List.copyOf(sequence), root.hash(), root.sum(), externalRoot.hash(), siblingRoot.hash(),
                external, sibling, root, externalRoot, siblingRoot);
    }

    private static List<Identity> distinct(List<Bound.Binding> bindings, boolean sibling) {
        return bindings.stream().filter(b -> (b.origin() == Bound.Origin.SIBLING) == sibling).map(Bound.Binding::k).distinct().sorted().toList();
    }

    /** Canonical leaf-set hash: independent of order and repeated membership. */
    public static Identity leafSet(Digest digest, List<Identity> leaves) {
        return leafSetRoot(digest, leaves, new NodeSink() {
            public void write(dev.jvmd.core.tree.Node node) { }
            public void flush() { }
        }).hash();
    }

    /** Canonical persistent set: k -> empty, h=H(k). Its Merkle root is the identity; its sum is not a proof. */
    public static dev.jvmd.core.tree.Root leafSetRoot(Digest digest, List<Identity> leaves, NodeSink sink) {
        return new dev.jvmd.core.tree.ContentTree(digest).build(leaves.stream().distinct().sorted()
                .map(k -> new dev.jvmd.core.tree.Entry(k.bytes(), dev.jvmd.core.tree.Entry.NONE, digest.hash(k.view()))).toList(), sink);
    }
}
