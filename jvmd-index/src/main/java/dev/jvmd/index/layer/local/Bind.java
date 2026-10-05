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

    /** A layer that may supply the leaf of a coordinate, by exact coordinate. */
    @FunctionalInterface public interface Provider {
        /** The leaf key, or null if this layer has none for the coordinate. */
        Identity leafFor(String coordinate);
    }

    public static final Provider NONE = coordinate -> null;

    /**
     * session over built over default. The list of bound {@code k} becomes a {@link ContentList}: {@code routeHash} is its root hash,
     * {@code R} its root sum ({@code Σ r}). {@code leafSetExt} and {@code leafSetSib} are the digests of the sorted distinct external
     * and sibling {@code k}. An entry with no binding (a jar whose file is missing) is left out of the sequence and reported in
     * {@link Bound#unbound()}; a sibling entry whose module has not been built is a bug in the build order and throws.
     */
    public static Bound bind(Digest digest, List<RouteEntry> entries, Provider session, Provider built, Function<Identity, MachineLeaf> leafOf, NodeSink sink) {
        var bindings = new ArrayList<Bound.Binding>(entries.size());
        var unbound = new ArrayList<String>();
        for (var entry : entries) {
            Identity k = session.leafFor(entry.coordinate());
            var origin = Bound.Origin.SESSION;
            if (k == null) { k = built.leafFor(entry.coordinate()); origin = Bound.Origin.SIBLING; }
            if (k == null) {
                origin = Bound.Origin.EXTERNAL;
                k = switch (entry) {
                    case RouteEntry.Jar j -> j.defaultK();
                    case RouteEntry.Jrt j -> j.k();
                    case RouteEntry.Sibling s -> throw new IllegalStateException("Module " + s.module() + " is bound before it is built");
                };
            }
            if (k == null) { unbound.add(entry.coordinate()); continue; }
            bindings.add(new Bound.Binding(entry, k, origin));
        }
        var list = new ContentList(digest).builder(sink);
        var sequence = new ArrayList<Identity>(bindings.size());
        for (var binding : bindings) {
            sequence.add(binding.k());
            list.add(binding.k().view(), leafOf.apply(binding.k()).r());
        }
        var root = list.finish();
        var partial = new Bound(List.copyOf(bindings), List.copyOf(sequence), List.copyOf(unbound), root.hash(), root.sum(), null, null);
        return new Bound(partial.bindings(), partial.sequence(), partial.unbound(), root.hash(), root.sum(),
                leafSet(digest, partial.external()), leafSet(digest, partial.sibling()));
    }

    /** {@code Digest(sort(distinct(k)))} over the unsigned bytes: the same for any order of the same leaves, and a leaf listed twice is one leaf. */
    public static Identity leafSet(Digest digest, List<Identity> leaves) {
        var sorted = leaves.stream().distinct().sorted().toList();
        var hasher = digest.hasher();
        for (var k : sorted) { var bytes = k.view(); hasher.update(bytes, 0, bytes.length); }
        return hasher.finish();
    }
}
