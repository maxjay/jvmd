package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The definer index {@code D(route)} (stage 2, 2.4, 3.6, 3.17 and 5.5): {@code typeKey -> k} for every type on a bound route, the
 * first leaf in route order that declares it. It is a fold over the multiset of bound leaves and is split in two: the disjoint
 * part (types declared by exactly one leaf) depends on the multiset alone and is keyed by {@code leafSet}; the conflict table
 * (types declared by more than one) also depends on order and is keyed by {@code routeHash}.
 *
 * <p>Both stored identities are resolution-level: an entry's {@code h} is {@code Digest(typeKey || oSum)}, never a function of
 * {@code k}, so a jar swapped for an API-identical source leaf changes the stored {@code k} and leaves every definer identity
 * unchanged (B.4).
 */
public final class DefinerIndex {
    private DefinerIndex() { }

    /** A type as one leaf declares it: where to find it, and what it resolves to. */
    record Def(Identity k, Identity oSum) { }

    /**
     * What a built index leaves behind for building the next one by difference: for each type key (as ISO-8859-1 text, whose order
     * is the unsigned byte order) the leaves that declare it, and the multiset of leaves it covers, sorted. Immutable: a derived
     * state shares the lists of every type the difference did not touch.
     */
    public static final class State {
        final Map<String, List<Def>> counts;
        final List<Identity> leaves;

        State(Map<String, List<Def>> counts, List<Identity> leaves) { this.counts = counts; this.leaves = leaves; }

        /** The sorted multiset of leaf keys this state covers. */
        public List<Identity> leaves() { return leaves; }
    }

    public record Result(State state, Root disjoint, Root conflicts) { }

    /**
     * Builds the index of {@code sequence} from {@code base} by adding the leaves it lacks and removing the ones the route does not
     * have (3.17), or from nothing when {@code base} is null.
     *
     * @param leafOf reads {@code L|k}
     * @param reader reads {@code N|hash}
     */
    public static Result build(Digest digest, ContentTree tree, List<Identity> sequence, State base, Function<Identity, MachineLeaf> leafOf,
                               Function<Identity, byte[]> reader, NodeSink sink) {
        var sorted = new ArrayList<>(sequence);
        sorted.sort(Identity::compareTo);
        var removed = new ArrayList<Identity>();
        var added = new ArrayList<Identity>();
        difference(base == null ? List.of() : base.leaves, sorted, removed, added);

        var counts = new HashMap<String, List<Def>>();
        if (base != null) counts.putAll(base.counts);
        for (var k : removed) {
            tree.forEach(leafOf.apply(k).oHash(), reader, entry -> {
                var key = text(entry.key());
                var defs = new ArrayList<>(counts.get(key));
                for (int i = 0; i < defs.size(); i++) if (defs.get(i).k().equals(k)) { defs.remove(i); break; }
                if (defs.isEmpty()) counts.remove(key); else counts.put(key, List.copyOf(defs));
            });
        }
        for (var k : added) {
            tree.forEach(leafOf.apply(k).oHash(), reader, entry -> {
                var key = text(entry.key());
                var defs = new ArrayList<Def>(counts.getOrDefault(key, List.of()));
                defs.add(new Def(k, entry.h()));
                counts.put(key, List.copyOf(defs));
            });
        }

        // The route order decides only which definer is first, and only where there is more than one.
        var position = new HashMap<Identity, Integer>();
        for (int i = 0; i < sequence.size(); i++) position.putIfAbsent(sequence.get(i), i);

        var disjoint = new ArrayList<Entry>();
        var conflicts = new ArrayList<Entry>();
        for (var e : counts.entrySet()) {
            var key = e.getKey().getBytes(StandardCharsets.ISO_8859_1);
            var defs = e.getValue();
            if (defs.size() == 1) {
                var def = defs.get(0);
                disjoint.add(new Entry(key, def.k().bytes(), digest.hash(key, def.oSum().view())));
                continue;
            }
            var ordered = new ArrayList<>(defs);
            ordered.sort((a, b) -> Integer.compare(position.get(a.k()), position.get(b.k())));
            var first = ordered.get(0);
            var value = new Codec.Writer(64 + ordered.size() * digest.width()).id(first.k()).u32(ordered.size());
            for (var d : ordered) value.id(d.k());
            conflicts.add(new Entry(key, value.toBytes(), digest.hash(key, first.oSum().view())));
        }
        disjoint.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        conflicts.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        return new Result(new State(counts, List.copyOf(sorted)), tree.build(disjoint, sink), tree.build(conflicts, sink));
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
        var removed = new ArrayList<Identity>();
        var added = new ArrayList<Identity>();
        difference(a, b, removed, added);
        return removed.size() + added.size();
    }

    private static String text(byte[] key) { return new String(key, StandardCharsets.ISO_8859_1); }

    /** B.4: {@code id root.hash || id root.sum || u32 count || u8 level}, the value of {@code DD|} and {@code DC|}. */
    public static byte[] encodeRoot(Root root) {
        return new Codec.Writer(80).id(root.hash()).id(root.sum()).u32(root.count()).u8(root.level()).toBytes();
    }

    public static Root decodeRoot(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        return new Root(in.id(width), in.id(width), in.count(), in.u8());
    }
}
