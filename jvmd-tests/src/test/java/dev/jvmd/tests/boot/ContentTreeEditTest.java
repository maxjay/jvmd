package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.NodeSink;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link ContentTree#apply} against its oracle: the tree an edit produces is the tree a full build over the same entries produces,
 * because the shape is a function of the entry set. Tried with the boundary rule and the cap each in charge (a huge modulus makes the cap
 * cut every chunk; a modulus of two makes every other key a boundary), with an empty base, with everything removed, and with changes at both
 * ends; and the work of a small edit is bounded by its paths.
 */
@Tag("phase-3")
class ContentTreeEditTest {
    record Shape(int b, int cap) { }

    static Stream<Object[]> shapes() {
        var out = new ArrayList<Object[]>();
        for (var digest : List.of(Sha256.INSTANCE, new TestDigests.Sha3()))
            for (var shape : List.of(new Shape(ContentTree.B, ContentTree.CAP), new Shape(4, 8), new Shape(1 << 20, 4), new Shape(2, 128), new Shape(2, 3)))
                out.add(new Object[] {digest, shape});
        return out.stream();
    }

    static final class Store implements NodeSink {
        final Map<Identity, byte[]> nodes = new HashMap<>();
        int written;
        @Override public void write(Node node) { nodes.put(node.hash(), node.bytes()); written++; }
        @Override public void flush() { }
    }

    private static byte[] key(Random random) {
        var key = new byte[1 + random.nextInt(6)];
        random.nextBytes(key);
        return key;
    }

    private static Entry entry(Digest digest, byte[] key, Random random) {
        var value = new byte[random.nextInt(3)];
        random.nextBytes(value);
        return new Entry(key, value, digest.hash(key, value, new byte[] {(byte) random.nextInt()}));
    }

    @ParameterizedTest @MethodSource("shapes")
    void anEditIsTheTreeAFullBuildOfTheNewEntriesGives(Digest digest, Shape shape) {
        var tree = new ContentTree(digest, shape.b(), shape.cap());
        for (int seed = 0; seed < 60; seed++) {
            var random = new Random(seed * 7919L + shape.b() * 31L + shape.cap());
            int size = switch (seed % 6) { case 0 -> 0; case 1 -> 1 + random.nextInt(5); case 2 -> 20 + random.nextInt(60); case 3 -> 300 + random.nextInt(400); default -> random.nextInt(2500); };
            var current = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
            for (int i = 0; i < size; i++) { var k = key(random); current.put(k, entry(digest, k, random)); }
            var baseStore = new Store();
            var base = tree.build(new ArrayList<>(current.values()), baseStore);

            // Changes: some existing keys removed, some replaced, some new keys added, and a removal of a key that is not there.
            var removed = new ArrayList<byte[]>();
            var added = new ArrayList<Entry>();
            int changes = 1 + random.nextInt(Math.max(1, Math.min(40, size / 4 + 3)));
            var existing = new ArrayList<>(current.keySet());
            for (int i = 0; i < changes; i++) {
                switch (random.nextInt(4)) {
                    case 0 -> { if (!existing.isEmpty()) removed.add(existing.get(random.nextInt(existing.size()))); }
                    case 1 -> { if (!existing.isEmpty()) { var k = existing.get(random.nextInt(existing.size())); added.add(entry(digest, k, random)); } }
                    case 2 -> added.add(entry(digest, key(random), random));
                    default -> removed.add(key(random));
                }
            }
            if (seed % 9 == 0) { for (var k : existing) removed.add(k); }                // everything goes
            if (seed % 11 == 0) added.add(new Entry(new byte[] {0}, Entry.NONE, digest.hash(new byte[] {0})));   // the smallest key
            if (seed % 13 == 0) added.add(new Entry(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, Entry.NONE, digest.hash(new byte[] {1}))); // the largest

            var expected = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
            expected.putAll(current);
            for (var k : removed) expected.remove(k);
            for (var e : added) expected.put(e.key(), e);

            var editStore = new Store();
            var edited = tree.apply(base, removed, added, baseStore.nodes::get, editStore);
            var full = tree.build(new ArrayList<>(expected.values()), new Store());
            assertThat(edited).as("seed %d, %d entries, %s", seed, size, shape).isEqualTo(full);

            // Conservation uses the actual before/after maps, including replacements, duplicate edits and absent removals.
            var conserved = base.sum();
            for (var e : current.values()) {
                var now = expected.get(e.key());
                if (now == null || !e.h().equals(now.h())) conserved = tree.sums().subtract(conserved, e.h());
            }
            for (var e : expected.values()) {
                var old = current.get(e.key());
                if (old == null || !e.h().equals(old.h())) conserved = tree.sums().add(conserved, e.h());
            }
            assertThat(edited.sum()).as("sum conservation at seed %d", seed).isEqualTo(conserved);

            // The edited tree is a tree: every node is stored, and its hashes and sums verify from its own nodes.
            var all = new HashMap<>(baseStore.nodes);
            all.putAll(editStore.nodes);
            tree.verify(edited, all::get);
        }
    }

    @ParameterizedTest @MethodSource("shapes")
    void aSmallEditReadsAndWritesNodesAlongItsPathsOnly(Digest digest, Shape shape) {
        if (shape.b() == 1 << 20) return; // the cap-only shape has no boundaries to bound the re-cut
        var tree = new ContentTree(digest, shape.b(), shape.cap());
        var random = new Random(42);
        var entries = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        while (entries.size() < 20_000) { var k = new byte[8]; random.nextBytes(k); entries.put(k, entry(digest, k, random)); }
        var baseStore = new Store();
        var base = tree.build(new ArrayList<>(entries.values()), baseStore);
        assertThat(base.level()).isGreaterThanOrEqualTo(2);

        var victim = new ArrayList<>(entries.keySet()).get(10_000);
        var newKey = new byte[8];
        random.nextBytes(newKey);
        var reads = new AtomicInteger();
        var editStore = new Store();
        var edited = tree.apply(base, List.of(victim), List.of(entry(digest, newKey, random)), h -> { reads.incrementAndGet(); return baseStore.nodes.get(h); }, editStore);

        entries.remove(victim);
        var added = entry(digest, newKey, new Random(1));
        entries.put(newKey, added);
        // The oracle again, on the entries as they stand (the added entry's value is taken from the edit itself).
        var replaced = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        replaced.putAll(entries);
        tree.verify(edited, h -> editStore.nodes.containsKey(h) ? editStore.nodes.get(h) : baseStore.nodes.get(h));
        assertThat(edited.count()).isEqualTo(base.count());
        // Two changes, each along one path of depth+1 nodes, plus the chunks the cap and boundaries re-cut around them: a few dozen, never thousands.
        int depth = base.level() + 1;
        assertThat(editStore.written).as("nodes written").isLessThanOrEqualTo(2 * 8 * depth);
        assertThat(reads.get()).as("nodes read").isLessThanOrEqualTo(2 * 8 * depth + 2 * shape.cap() * depth);
        assertThat(baseStore.nodes.size()).isGreaterThan(300);
    }

    @Test void anEditThatChangesNothingIsTheBase() {
        var digest = Sha256.INSTANCE;
        var tree = new ContentTree(digest);
        var store = new Store();
        var random = new Random(3);
        var entries = new ArrayList<Entry>();
        var keys = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        for (int i = 0; i < 100; i++) { var k = key(random); keys.put(k, entry(digest, k, random)); }
        entries.addAll(keys.values());
        var base = tree.build(entries, store);
        assertThat(tree.apply(base, List.of(), List.of(), store.nodes::get, new Store())).isEqualTo(base);
        assertThat(tree.apply(base, List.of(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9}), List.of(), store.nodes::get, new Store())).isEqualTo(base);
    }
}
