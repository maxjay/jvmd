package dev.jvmd.index.layer.local;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("phase-3")
class DefinerStateTest {
    static Stream<Digest> digests() {
        return Stream.of(Sha256.INSTANCE, new Digest() {
            public String name() { return "SHA3-256"; }
            public int width() { return 32; }
            public Hasher hasher() {
                try {
                    var md = MessageDigest.getInstance(name());
                    return new Hasher() {
                        public void update(byte[] b, int off, int len) { md.update(b, off, len); }
                        public Identity finish() { return Identity.of(md.digest()); }
                    };
                } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
            }
        });
    }

    private static final class Fixture implements NodeSink {
        final Digest digest;
        final ContentTree tree;
        final Map<Identity, byte[]> nodes = new HashMap<>();
        final Map<Identity, MachineLeaf> leaves = new HashMap<>();
        final List<Identity> opened = new ArrayList<>();
        Fixture(Digest digest) { this.digest = digest; tree = new ContentTree(digest); }
        public void write(Node node) { nodes.put(node.hash(), node.bytes()); }
        public void flush() { }
        Identity id(String name) { return digest.hash(name.getBytes(StandardCharsets.UTF_8)); }
        Identity leaf(String name, List<String> types) {
            var entries = types.stream().sorted().map(t -> new Entry(Keys.ownerKey(t), new byte[0], id(name + t))).toList();
            var owners = tree.build(entries, this);
            var k = id(name);
            leaves.put(k, new MachineLeaf(k, owners.sum(), owners.hash(), owners.level(), owners.hash(), owners.sum(), owners.level(),
                    owners.hash(), owners.level(), types.size(), types.size(), 0));
            return k;
        }
        DefinerIndex.State fold(List<Identity> keys, DefinerIndex.State base) {
            var state = DefinerIndex.fold(tree, keys.stream().distinct().sorted().toList(), Bind.leafSetRoot(digest, keys, this), base, k -> { opened.add(k); return leaves.get(k); }, nodes::get);
            state.disjoint(DefinerIndex.disjoint(digest, tree, state, nodes::get, this));
            return state;
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void aSmallLeafDeltaSharesTheRestOfTheUniverseAndPreservesEverySnapshot(Digest digest) {
        var f = new Fixture(digest);
        var names = java.util.stream.IntStream.range(0, 32_768).mapToObj(i -> "p/T" + String.format(java.util.Locale.ROOT, "%05d", i)).toList();
        var large = f.leaf("large", names);
        var old = f.leaf("old", List.of("p/T10000", "p/Removed"));
        var replacement = f.leaf("new", List.of("p/T10000", "p/Added"));
        var base = f.fold(List.of(large, old), null);
        var before = Map.copyOf(base.counts);
        var baseEntries = Collections.newSetFromMap(new IdentityHashMap<Map.Entry<String, List<DefinerIndex.Def>>, Boolean>());
        baseEntries.addAll(base.counts.entrySet());
        f.opened.clear();
        var derived = f.fold(List.of(large, replacement), base);
        assertThat(f.opened).containsExactlyInAnyOrder(old, replacement);
        assertThat(base.counts).isEqualTo(before);
        long shared = derived.counts.entrySet().stream().filter(baseEntries::contains).count();
        assertThat(shared).as("unchanged map structure must be shared, not just the lists stored in copied entries")
                .isGreaterThan(names.size() - 128);
        var scratch = f.fold(List.of(large, replacement), null);
        assertThat(derived.disjoint()).isEqualTo(scratch.disjoint());
        var empty = f.fold(List.of(), null);
        for (var order : List.of(List.of(large, replacement), List.of(replacement, large))) {
            assertThat(DefinerIndex.conflicts(digest, f.tree, derived, empty, order, f))
                    .isEqualTo(DefinerIndex.conflicts(digest, f.tree, scratch, empty, order, f));
        }
        var returned = f.fold(List.of(large, old), derived);
        assertThat(returned.disjoint()).isEqualTo(base.disjoint());
        assertThat(base.counts).isEqualTo(before);
        var noChange = f.fold(List.of(large, replacement), derived);
        assertThat(noChange.counts).as("an empty semantic delta shares the entire count map").isSameAs(derived.counts);
    }

    @ParameterizedTest @MethodSource("digests")
    void persistedStateReopensLazilyAndContinuesFromOnlyChangedOwners(Digest digest) {
        var f = new Fixture(digest);
        var names = java.util.stream.IntStream.range(0, 32768).mapToObj(i -> "p/T"+i).toList();
        var large = f.leaf("large", names);
        var old = f.leaf("old", List.of("p/T100", "p/Removed"));
        var replacement = f.leaf("new", List.of("p/T100", "p/Added"));
        var base = f.fold(List.of(large, old), null);
        var bytes = DefinerIndex.persist(f.tree, base, f.nodes::get, f);
        f.opened.clear();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var restored = DefinerIndex.restore(f.tree, base.leafSet(), bytes, id -> { reads.incrementAndGet(); return f.nodes.get(id); }, f);
        assertThat(reads.get()).as("opening exact roots must not reconstruct any counts").isZero();
        assertThat(f.opened).isEmpty();
        var derived = f.fold(List.of(large, replacement), restored);
        assertThat(f.opened).containsExactlyInAnyOrder(old, replacement);
        assertThat(reads.get()).as("touched DF paths, not the 32768-type universe").isLessThan(100);
        var saved = DefinerIndex.persist(f.tree, derived, f.nodes::get, f);
        var scratch = f.fold(List.of(large, replacement), null);
        assertThat(saved).as("canonical multimap and projection roots do not depend on derivation history")
                .isEqualTo(DefinerIndex.persist(f.tree, scratch, f.nodes::get, f));
        assertThat(DefinerIndex.persist(f.tree, restored, f.nodes::get, f)).isEqualTo(bytes);
    }

    @ParameterizedTest @MethodSource("digests")
    void longBranchingHistoriesMatchAFreshMapAndShareUnchangedEntries(Digest digest) {
        var f = new Fixture(digest);
        var first = new DefinerIndex.Def(f.id("a"), f.id("a-sum"));
        var second = new DefinerIndex.Def(f.id("b"), f.id("b-sum"));
        var state = DefinerCounts.EMPTY;
        var expected = new java.util.TreeMap<String, List<DefinerIndex.Def>>();
        var snapshots = new ArrayList<Map.Entry<DefinerCounts, Map<String, List<DefinerIndex.Def>>>>();
        var random = new java.util.Random(0x5eed);
        for (int step = 0; step < 3_000; step++) {
            // Ordered growth/deletion exercises both extremes; the tail repeatedly changes conflict membership and branches.
            int index = step < 1_000 ? step : step < 2_000 ? 1_999 - step : random.nextInt(1_500);
            String key = "p/T" + String.format(java.util.Locale.ROOT, "%05d", index);
            List<DefinerIndex.Def> value = step < 1_000 ? List.of(first) : step < 2_000 ? List.of()
                    : switch (random.nextInt(3)) { case 0 -> List.of(); case 1 -> List.of(first); default -> List.of(first, second); };
            var before = state;
            state = state.with(Map.of(key, value));
            if (value.isEmpty()) expected.remove(key); else expected.put(key, value);
            assertThat(state).isEqualTo(expected);
            assertThat(state.containsKey(key)).isEqualTo(expected.containsKey(key));
            var fallback = List.of(second);
            assertThat(state.getOrDefault("not/a/type", fallback)).isSameAs(fallback);
            if (step % 41 == 0) {
                var oldEntries = Collections.newSetFromMap(new IdentityHashMap<Map.Entry<String, List<DefinerIndex.Def>>, Boolean>());
                oldEntries.addAll(before.entrySet());
                long shared = state.entrySet().stream().filter(oldEntries::contains).count();
                assertThat(shared).as("one update must copy only a balanced search path").isGreaterThanOrEqualTo(Math.max(0, before.size() - 32));
                var multiples = new java.util.TreeSet<String>();
                state.forEachMultiple(multiples::add);
                assertThat(multiples).containsExactlyElementsOf(expected.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::getKey).toList());
                snapshots.add(Map.entry(state, Map.copyOf(expected)));
            }
        }
        for (var snapshot : snapshots) assertThat(snapshot.getKey()).isEqualTo(snapshot.getValue());
        // Derive from an old branch after the newer branch has been edited and deleted repeatedly.
        var ancestor = snapshots.get(snapshots.size() / 3);
        var branched = ancestor.getKey().with(Map.of("q/Branch", List.of(second)));
        var oracle = new java.util.TreeMap<>(ancestor.getValue());
        oracle.put("q/Branch", List.of(second));
        assertThat(branched).isEqualTo(oracle);
        assertThat(ancestor.getKey()).isEqualTo(ancestor.getValue());
    }

    @ParameterizedTest @MethodSource("digests")
    void persistentLeafSetsSkipUnchangedChunksAcrossLargeUniverses(Digest digest) {
        var f = new Fixture(digest);
        var all = java.util.stream.IntStream.range(0, 16384).mapToObj(i -> f.leaf("leaf" + i, List.of())).toList();
        var base = f.fold(all, null);
        var edited = new ArrayList<>(all);
        var removed = edited.remove(8000);
        var added = f.leaf("replacement", List.of("p/New"));
        edited.add(added);
        f.opened.clear();
        var next = f.fold(edited, base);
        assertThat(f.opened).containsExactlyInAnyOrder(removed, added);
        assertThat(next.leafSetEntriesCompared()).as("only differing leaf-set chunks are compared").isBetween(1L, 512L);
        Collections.reverse(edited);
        edited.add(added); // neither order nor repeated membership changes a set
        f.opened.clear();
        var same = f.fold(edited, next);
        assertThat(same.leafSet()).isEqualTo(next.leafSet());
        assertThat(same.leafSetEntriesCompared()).isZero();
        assertThat(f.opened).isEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void conflictApplyMatchesFreshConstructionForMovesInsertionsRemovalsAndRepeatedLeaves(Digest digest) {
        var f = new Fixture(digest);
        var large = f.leaf("large", java.util.stream.IntStream.range(0, 32768).mapToObj(i -> "large/T"+i).toList());
        var a = f.leaf("a", List.of("p/K", "p/A"));
        var b = f.leaf("b", List.of("p/K", "p/B"));
        var c = f.leaf("c", List.of("p/K", "p/C"));
        var d = f.leaf("d", List.of("p/A"));
        var empty = f.fold(List.of(), null);
        var order = List.of(large, a, b, c);
        var state = f.fold(order, null);
        DefinerIndex.persist(f.tree, state, f.nodes::get, f);
        var route = route(f, order);
        var conflicts = DefinerIndex.conflicts(digest, f.tree, state, empty, order, f);
        for (var nextOrder : List.of(List.of(large, b, a, c), List.of(large, b, c, a),
                List.of(large, b, c, a, a), List.of(large, b, c, a, d), List.of(large, c, a, d),
                List.of(large, a, d), List.of(large, a), List.of(large, a))) {
            var next = f.fold(nextOrder, state);
            var nextRoute = route(f, nextOrder);
            f.opened.clear();
            var result = DefinerIndex.conflicts(f.tree, next, empty, nextOrder, route, nextRoute, conflicts,
                    k -> { f.opened.add(k); return f.leaves.get(k); }, f.nodes::get, f);
            assertThat(f.opened).as("the unchanged 32768-type leaf must never be opened").doesNotContain(large);
            assertThat(result.ownerOpens()).isEqualTo(f.opened.size()).isLessThanOrEqualTo(2);
            assertThat(result.touched()).isLessThanOrEqualTo(4);
            assertThat(result.root()).isEqualTo(DefinerIndex.conflicts(digest, f.tree, next, empty, nextOrder, f));
            if (route.hash().equals(nextRoute.hash())) {
                assertThat(result.touched()).isZero();
                assertThat(result.ownerOpens()).isZero();
            }
            state = next; route = nextRoute; conflicts = result.root();
        }
    }

    private static dev.jvmd.core.tree.Root route(Fixture f, List<Identity> order) {
        var list = new dev.jvmd.core.tree.ContentList(f.digest).builder(f);
        for (var k : order) list.add(k.bytes(), f.leaves.get(k).r());
        return list.finish();
    }
}
