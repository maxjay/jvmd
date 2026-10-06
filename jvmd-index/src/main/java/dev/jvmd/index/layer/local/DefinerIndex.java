package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The definer index {@code D(route)} (stage 2, 2.4, 3.6, 3.17 and 5.5): {@code typeKey -> k} for every type on a bound route, the
 * first leaf in route order that declares it. It is three records, split by how often their inputs change:
 *
 * <ul>
 *   <li>the <b>external</b> disjoint index over the JDK and jar leaves, keyed by {@code leafSetExt}: large, shared across projects,
 *       and untouched by an edit;</li>
 *   <li>the <b>sibling</b> disjoint index over this project's leaves, keyed by {@code leafSetSib}: small, rebuilt on an edit;</li>
 *   <li>the <b>conflict table</b>, keyed by {@code routeHash}: every type declared by more than one leaf anywhere on the route, with
 *       its route-order definer. It is the only place the two parts meet.</li>
 * </ul>
 *
 * Each disjoint part is a fold of a commutative merge over the <em>distinct</em> leaves it covers, so it depends on the set of leaves
 * and not on their order, and it is built from a supplied ancestor by adding and removing leaves. A leaf listed twice on a
 * classpath is one leaf and is never in conflict with itself.
 *
 * <p>Every stored identity is resolution-level: an entry's {@code h} is {@code Digest(typeKey || oSum)}, never a function of
 * {@code k}, so a jar swapped for an API-identical source leaf changes the stored {@code k} and leaves every definer identity
 * unchanged (B.4).
 */
public final class DefinerIndex {
    private DefinerIndex() { }

    /** A type as one leaf declares it: where to find it, and what it resolves to. */
    record Def(Identity k, Identity oSum) { }

    /**
     * What a fold leaves behind for building the next one by difference: for each type (by internal name; names become key bytes only
     * where an entry is written) the leaves that declare it, the types declared by more than one leaf, and the sorted distinct leaves it
     * covers. Immutable: a derived state shares unchanged map branches as well as the lists they hold.
     */
    public static final class State {
        final DefinerCounts counts;
        final List<Identity> leaves;
        /** The types whose definers differ from the state this one was folded from, and the disjoint tree of that state: an edit's input. */
        final Set<String> touched;
        final Root baseDisjoint;
        private volatile Root disjoint;

        State(DefinerCounts counts, List<Identity> leaves, Set<String> touched, Root baseDisjoint) {
            this.counts = counts;
            this.leaves = leaves;
            this.touched = touched;
            this.baseDisjoint = baseDisjoint;
        }

        /** The disjoint tree of this state, once it has been built or read; null before. A state is a base for another only after. */
        public Root disjoint() { return disjoint; }

        public void disjoint(Root root) { this.disjoint = root; }

        /** The sorted distinct leaf keys this state covers. */
        public List<Identity> leaves() { return leaves; }
    }

    /**
     * Folds {@code distinct} (sorted, without repeats) from {@code base} by adding the leaves it lacks and removing the ones it has
     * and {@code distinct} does not (3.17), or from nothing when {@code base} is null. Reads the {@code O} tree of each changed leaf
     * once; the {@code O} trees of unchanged leaves are never opened.
     *
     * @param leafOf reads {@code L|k}
     * @param reader reads {@code N|hash}
     */
    public static State fold(ContentTree tree, List<Identity> distinct, State base, Function<Identity, MachineLeaf> leafOf, Function<Identity, byte[]> reader) {
        var removed = new ArrayList<Identity>();
        var added = new ArrayList<Identity>();
        difference(base == null ? List.of() : base.leaves, distinct, removed, added);

        var counts = base == null ? DefinerCounts.EMPTY : base.counts;
        var changed = new HashMap<String, List<Def>>();
        for (var k : removed) {
            tree.forEach(leafOf.apply(k).oHash(), reader, entry -> {
                var key = Keys.ownerOf(entry.key());
                var defs = changed.computeIfAbsent(key, t -> new ArrayList<>(counts.getOrDefault(t, List.of())));
                for (int i = 0; i < defs.size(); i++) if (defs.get(i).k().equals(k)) { defs.remove(i); break; }
            });
        }
        for (var k : added) {
            tree.forEach(leafOf.apply(k).oHash(), reader, entry -> {
                var key = Keys.ownerOf(entry.key());
                var defs = changed.computeIfAbsent(key, t -> new ArrayList<>(counts.getOrDefault(t, List.of())));
                defs.add(new Def(k, entry.h()));
            });
        }
        return new State(counts.with(changed), List.copyOf(distinct), Set.copyOf(changed.keySet()), base == null ? null : base.disjoint);
    }

    /**
     * The disjoint tree of a state: every type declared by exactly one of its leaves, {@code typeKey -> k}, {@code h = Digest(typeKey ||
     * oSum)}. A state folded from another one edits that state's tree by the types the fold touched, O(changed types * depth) nodes
     * ({@link ContentTree#apply}), and is the same tree a build over all its types would give; one folded from nothing is built.
     *
     * @param reader reads {@code N|hash}: the base tree's nodes
     */
    public static Root disjoint(Digest digest, ContentTree tree, State state, Function<Identity, byte[]> reader, NodeSink sink) {
        if (state.baseDisjoint != null) {
            var removed = new ArrayList<byte[]>(state.touched.size());
            var added = new ArrayList<Entry>();
            for (var type : state.touched) {
                var key = Keys.ownerKey(type);
                removed.add(key); // a key the base did not hold is a removal of nothing
                var defs = state.counts.get(type);
                if (defs != null && defs.size() == 1) added.add(entryOf(digest, key, defs.get(0)));
            }
            return tree.apply(state.baseDisjoint, removed, added, reader, sink);
        }
        var entries = new ArrayList<Entry>();
        for (var e : state.counts.entrySet()) {
            if (e.getValue().size() != 1) continue;
            entries.add(entryOf(digest, Keys.ownerKey(e.getKey()), e.getValue().get(0)));
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        return tree.build(entries, sink);
    }

    private static Entry entryOf(Digest digest, byte[] key, Def def) { return new Entry(key, def.k().bytes(), digest.hash(key, def.oSum().view())); }

    /**
     * The conflict table of a route: every type declared by more than one distinct leaf across both parts, {@code typeKey -> (first,
     * all)} with {@code first} the earliest in {@code sequence}, {@code h = Digest(typeKey || first.oSum)}.
     *
     * <p>It is computed by probing: every sibling type is looked up in the external state, O(project types), and the external state is
     * never iterated. The only other candidates are the types some part already knows to be multiple, which each state maintains as a
     * subtree count while it is folded, so singleton branches are skipped without walking the external type universe.
     */
    public static Root conflicts(Digest digest, ContentTree tree, State external, State sibling, List<Identity> sequence, NodeSink sink) {
        var resolver = new Resolver(external, sibling, sequence);
        var candidates = new HashSet<String>();
        external.counts.forEachMultiple(candidates::add);
        candidates.addAll(sibling.counts.keySet());
        var entries = new ArrayList<Entry>();
        for (var key : candidates) {
            var defs = resolver.definers(key);
            if (defs.size() < 2) continue;
            var bytes = Keys.ownerKey(key);
            var value = new Codec.Writer(64 + defs.size() * digest.width()).id(defs.get(0).k()).u32(defs.size());
            for (var d : defs) value.id(d.k());
            entries.add(new Entry(bytes, value.toBytes(), digest.hash(bytes, defs.get(0).oSum().view())));
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        return tree.build(entries, sink);
    }

    /** Resolves a type key through the two parts the way a lookup does: the definer that is first in route order, and what it resolves to. */
    public static final class Resolver {
        private final State external, sibling;
        private final Map<Identity, Integer> position = new HashMap<>();

        public Resolver(State external, State sibling, List<Identity> sequence) {
            this.external = external;
            this.sibling = sibling;
            for (int i = 0; i < sequence.size(); i++) position.putIfAbsent(sequence.get(i), i);
        }

        /** The distinct leaves that declare the type, in route order. */
        List<Def> definers(String key) {
            var all = new ArrayList<Def>();
            for (var d : external.counts.getOrDefault(key, List.of())) all.add(d);
            for (var d : sibling.counts.getOrDefault(key, List.of())) if (all.stream().noneMatch(a -> a.k().equals(d.k()))) all.add(d);
            all.sort((a, b) -> Integer.compare(position.get(a.k()), position.get(b.k())));
            return all;
        }

        /** The {@code oSum} of the first definer of a type, or null if no leaf of the route declares it. */
        public Identity oSum(String internalName) {
            var defs = definers(internalName);
            return defs.isEmpty() ? null : defs.get(0).oSum();
        }

        public Identity definer(String internalName) {
            var defs = definers(internalName);
            return defs.isEmpty() ? null : defs.getFirst().k();
        }
    }

    /** Point reads of committed indexes, with the current own module preceding the route. */
    public static final class Reader {
        private final ContentTree tree;
        private final MachineLeaf own;
        private final Function<byte[], byte[]> records;
        private final Function<Identity, byte[]> nodes;
        private final Root dd, ds, dc;
        private final Map<Identity, MachineLeaf> leaves = new HashMap<>();

        public Reader(ContentTree tree, MachineLeaf own, Route route, Function<byte[], byte[]> records) {
            this.tree = tree;
            this.own = own;
            this.records = records;
            this.nodes = h -> records.apply(dev.jvmd.index.layer.machine.MachineStore.nodeKey(h));
            int width = tree.digest().width();
            dd = decodeRoot(records.apply(LocalStore.disjointKey(route.leafSetExt())), width);
            ds = decodeRoot(records.apply(LocalStore.siblingKey(route.leafSetSib())), width);
            dc = decodeRoot(records.apply(LocalStore.conflictsKey(route.routeHash())), width);
        }

        public Root external() { return dd; }
        public Root sibling() { return ds; }
        public Root conflicts() { return dc; }
        public Function<Identity, byte[]> nodes() { return nodes; }

        public MachineLeaf definer(String type) {
            var key = Keys.ownerKey(type);
            if (tree.get(own.oHash(), nodes, key) != null) return own;
            // A cross-part collision also occurs in each disjoint index. DC must win first.
            var entry = tree.get(dc.hash(), nodes, key);
            if (entry == null) entry = tree.get(ds.hash(), nodes, key);
            if (entry == null) entry = tree.get(dd.hash(), nodes, key);
            if (entry == null) return null;
            var k = new Codec.Reader(entry.value()).id(tree.digest().width());
            return leaves.computeIfAbsent(k, h -> MachineLeaf.decode(records.apply(dev.jvmd.index.layer.machine.MachineStore.leafKey(h)), tree.digest().width()));
        }

        public boolean absent(String type) {
            var key = Keys.ownerKey(type);
            var zero = tree.sums().zero();
            return tree.rangeSum(own.oHash(), nodes, key).equals(zero)
                    && tree.rangeSum(dd.hash(), nodes, key).equals(zero)
                    && tree.rangeSum(ds.hash(), nodes, key).equals(zero)
                    && tree.rangeSum(dc.hash(), nodes, key).equals(zero);
        }
    }

    /** The multiset difference of two sorted key lists: {@code removed = old \ now}, {@code added = now \ old}. */
    static void difference(List<Identity> old, List<Identity> now, List<Identity> removed, List<Identity> added) {
        int i = 0, j = 0;
        while (i < old.size() || j < now.size()) {
            int c = i == old.size() ? 1 : j == now.size() ? -1 : old.get(i).compareTo(now.get(j));
            if (c < 0) removed.add(old.get(i++));
            else if (c > 0) added.add(now.get(j++));
            else { i++; j++; }
        }
    }

    /** The size of the symmetric difference of two sorted leaf lists: how far {@code a} is from {@code b}. */
    public static int distance(List<Identity> a, List<Identity> b) {
        int i = 0, j = 0, distance = 0;
        while (i < a.size() && j < b.size()) {
            int comparison = a.get(i).compareTo(b.get(j));
            if (comparison < 0) { i++; distance++; }
            else if (comparison > 0) { j++; distance++; }
            else { i++; j++; }
        }
        return distance + a.size() - i + b.size() - j;
    }

    /** B.4: {@code id root.hash || id root.sum || u32 count || u8 level}, the value of {@code DD|}, {@code DS|} and {@code DC|}. */
    public static byte[] encodeRoot(Root root) {
        return new Codec.Writer(80).id(root.hash()).id(root.sum()).u32(root.count()).u8(root.level()).toBytes();
    }

    public static Root decodeRoot(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        return new Root(in.id(width), in.id(width), in.count(), in.u8());
    }
}
