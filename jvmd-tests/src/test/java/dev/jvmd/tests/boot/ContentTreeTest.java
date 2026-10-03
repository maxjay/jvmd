package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.Sum;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.NodeSink;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The structure every layer is built from (stage 1, 2.1 to 2.3): sums, content-defined chunking, range identities. */
@Tag("phase-3")
class ContentTreeTest {
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new TestDigests.Sha3()); }

    /** A sink that keeps every node by hash, so a tree can be read back. */
    static final class MapSink implements NodeSink {
        final Map<Identity, byte[]> nodes = new HashMap<>();
        int writes;
        @Override public void write(Node node) { nodes.put(node.hash(), node.bytes()); writes++; }
        @Override public void flush() { }
    }

    static byte[] key(long n) { return ByteBuffer.allocate(8).putLong(n).array(); }

    static List<Entry> entries(Digest digest, int n, long seed) {
        var out = new ArrayList<Entry>(n);
        var random = new Random(seed);
        for (int i = 0; i < n; i++) {
            var value = new byte[random.nextInt(24)];
            random.nextBytes(value);
            out.add(new Entry(key(i * 3L), value, digest.hash(key(i), value)));
        }
        return out;
    }

    @ParameterizedTest @MethodSource("digests")
    void sumsAreOrderIndependentAndRemovable(Digest digest) {
        var sums = Sum.forWidth(digest.width());
        var ids = new ArrayList<Identity>();
        for (int i = 0; i < 500; i++) ids.add(digest.hash(key(i)));
        var forward = sums.zero();
        for (var id : ids) forward = sums.add(forward, id);
        Collections.shuffle(ids, new Random(7));
        var shuffled = sums.zero();
        for (var id : ids) shuffled = sums.add(shuffled, id);
        assertThat(shuffled).isEqualTo(forward);
        var without = sums.subtract(forward, ids.get(0));
        assertThat(sums.add(without, ids.get(0))).isEqualTo(forward);
    }

    @Test void sumWrapsAtThePrimeJustBelowThePowerOfTwo() {
        var sums = Sum.forWidth(32);
        var max = Identity.of(new byte[32]);
        Arrays.fill(max.view(), (byte) 0xFF); // 2^256 - 1 reduces to 2^256 - 1 - p = 188
        var one = Identity.of(new byte[32]);
        one.view()[31] = 1;
        assertThat(sums.add(max, sums.zero()).view()[31] & 0xFF).isEqualTo(188);
        // (p - 1) + 1 = 0 mod p, i.e. a + (-a) = 0
        var a = new Sha256Probe().identity();
        assertThat(sums.add(a, sums.subtract(sums.zero(), a))).isEqualTo(sums.zero());
        assertThat(sums.add(one, one).view()[31] & 0xFF).isEqualTo(2);
    }

    private static final class Sha256Probe { Identity identity() { return Sha256.INSTANCE.hash(new byte[] {1, 2, 3}); } }

    @ParameterizedTest @MethodSource("digests")
    void equalSetsGiveEqualRootsAndEqualNodesWhateverTheInputOrderWas(Digest digest) {
        var tree = new ContentTree(digest);
        var sorted = entries(digest, 3000, 1);
        var shuffled = new ArrayList<>(sorted);
        Collections.shuffle(shuffled, new Random(2));
        shuffled.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        var a = new MapSink();
        var b = new MapSink();
        assertThat(tree.build(shuffled, b)).isEqualTo(tree.build(sorted, a));
        assertThat(b.nodes.keySet()).isEqualTo(a.nodes.keySet());
    }

    @Test void aOneEntryTreeIsItsLeafAndTheEmptyTreeIsDefined() {
        var digest = Sha256.INSTANCE;
        var tree = new ContentTree(digest);
        var sink = new MapSink();
        var one = tree.build(entries(digest, 1, 3), sink);
        assertThat(one.level()).isZero();
        assertThat(one.count()).isEqualTo(1);
        assertThat(sink.nodes).containsOnlyKeys(one.hash());
        var empty = tree.build(List.of(), new MapSink());
        assertThat(empty.count()).isZero();
        assertThat(empty.sum()).isEqualTo(Sum.forWidth(32).zero());
    }

    @ParameterizedTest @MethodSource("digests")
    void rangeSumsAreComputedFromWholeChildrenAndVerifyAcceptsOnlyAConsistentTree(Digest digest) {
        var tree = new ContentTree(digest);
        var all = entries(digest, 6000, 4);
        var sink = new MapSink();
        var root = tree.build(all, sink);
        assertThat(root.level()).isGreaterThan(0);
        tree.verify(root, sink.nodes::get);
        var random = new Random(5);
        for (int i = 0; i < 40; i++) {
            long lo = random.nextInt(18000), hi = lo + random.nextInt(9000);
            var expected = Sum.forWidth(digest.width()).zero();
            for (var e : all) {
                long k = ByteBuffer.wrap(e.key()).getLong();
                if (k >= lo && k < hi) expected = Sum.forWidth(digest.width()).add(expected, e.h());
            }
            assertThat(tree.rangeSum(root, sink.nodes::get, key(lo), key(hi))).isEqualTo(expected);
        }
        assertThat(tree.rangeSum(root, sink.nodes::get, null, null)).isEqualTo(root.sum());
        var victim = sink.nodes.keySet().iterator().next();
        var tampered = sink.nodes.get(victim).clone();
        tampered[tampered.length - 1] ^= 1;
        sink.nodes.put(victim, tampered);
        assertThatThrownBy(() -> tree.verify(root, sink.nodes::get)).isInstanceOf(IllegalStateException.class);
    }

    @Test void changingOneEntryRewritesOnlyAPathOfNodes() {
        var digest = Sha256.INSTANCE;
        var tree = new ContentTree(digest);
        var base = entries(digest, 20000, 6);
        var first = new MapSink();
        var root = tree.build(base, first);
        var changed = new ArrayList<>(base);
        var old = changed.get(9000);
        changed.set(9000, new Entry(old.key(), old.value(), digest.hash(old.key(), new byte[] {9})));
        var second = new MapSink();
        tree.build(changed, second);
        var added = new ArrayList<>(second.nodes.keySet());
        added.removeAll(first.nodes.keySet());
        // One chunk per level changes; a boundary shared with a neighbour can add one more per level.
        assertThat(added.size()).isLessThanOrEqualTo(2 * (root.level() + 1));
        double shared = 1.0 - (double) added.size() / second.nodes.size();
        assertThat(shared).isGreaterThan(0.99);
    }

    @Test void chunksAreEmittedWhileEntriesStreamInSoMemoryDoesNotGrowWithTheEntryCount() {
        var digest = Sha256.INSTANCE;
        var sink = new MapSink();
        var chunker = new ContentTree(digest).chunker(sink);
        int n = 200_000;
        for (int i = 0; i < n; i++) chunker.add(new Entry(key(i), Entry.NONE, digest.hash(key(i))));
        // Before finish(), all but at most one open chunk per level has already been written.
        assertThat(sink.writes).isGreaterThan(n / ContentTree.CAP - 5);
        assertThat(chunker.finish().count()).isEqualTo(n);
    }

    @Test void keysMustArriveInStrictOrder() {
        var digest = Sha256.INSTANCE;
        var chunker = new ContentTree(digest).chunker(new MapSink());
        chunker.add(new Entry(key(2), Entry.NONE, digest.hash(key(2))));
        assertThatThrownBy(() -> chunker.add(new Entry(key(1), Entry.NONE, digest.hash(key(1))))).isInstanceOf(IllegalArgumentException.class);
    }
}
