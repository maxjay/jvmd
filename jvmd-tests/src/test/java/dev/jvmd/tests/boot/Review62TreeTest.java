package dev.jvmd.tests.boot;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Hash64;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Independent work-axis regressions from the PR62 review; construction is outside query measurements. */
@Tag("phase-3")
class Review62TreeTest {
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new TestDigests.Sha3()); }
    static volatile Object observed;
    static byte[] key(int n) { return java.nio.ByteBuffer.allocate(4).putInt(n).array(); }

    @ParameterizedTest @MethodSource("digests")
    void f18OrderedAlignmentMatchesAnIndependentSmallLcsOracleWithDuplicates(Digest digest) {
        var list = new ContentList(digest, 1 << 20, 128); var random = new Random(612);
        for (int trial = 0; trial < 600; trial++) {
            var a = new ArrayList<Entry>(); var b = new ArrayList<Entry>();
            for (int i = random.nextInt(30); i > 0; i--) a.add(element(digest, random.nextInt(8)));
            for (int i = random.nextInt(30); i > 0; i--) b.add(element(digest, random.nextInt(8)));
            var store = new ContentTreeTest.MapSink(); var old = list.build(a, store); var next = list.build(b, store);
            var result = Diff.lists(digest, old, next, store.nodes::get);
            var lengths = new int[a.size() + 1][b.size() + 1];
            for (int i = a.size() - 1; i >= 0; i--) for (int j = b.size() - 1; j >= 0; j--)
                lengths[i][j] = a.get(i).h().equals(b.get(j).h()) ? lengths[i+1][j+1] + 1 : Math.max(lengths[i+1][j], lengths[i][j+1]);
            assertThat(result.removed().size() + result.added().size()).as("trial %s", trial)
                    .isEqualTo(a.size() + b.size() - 2 * lengths[0][0]);
            var removed = result.removed().stream().map(Diff.Positioned::position).collect(java.util.stream.Collectors.toSet());
            var added = result.added().stream().map(Diff.Positioned::position).collect(java.util.stream.Collectors.toSet());
            assertThat(java.util.stream.IntStream.range(0, a.size()).filter(i -> !removed.contains(i)).mapToObj(i -> a.get(i).h()).toList())
                    .isEqualTo(java.util.stream.IntStream.range(0, b.size()).filter(i -> !added.contains(i)).mapToObj(i -> b.get(i).h()).toList());
        }
    }

    private static Entry element(Digest digest, int n) {
        var id = digest.hash(key(n)); return new Entry(id.view(), Entry.NONE, id);
    }

    @ParameterizedTest @MethodSource("digests")
    void f18MisalignedChunksUseSmallEditDistanceInsteadOfAQuadraticMatrix(Digest digest) {
        var list = new ContentList(digest); var a = new ArrayList<Entry>();
        for (int i = 0; a.size() < 4096; i++) {
            var entry = element(digest, i);
            if ((Hash64.of(entry.key()) & (ContentTree.B - 1)) != 0) a.add(entry);
        }
        var store = new ContentTreeTest.MapSink(); var old = list.build(a, store);
        for (int mutation = 0; mutation < 7; mutation++) {
            var b = new ArrayList<>(a);
            switch (mutation) {
                case 0 -> b.removeFirst();
                case 1 -> b.remove(2048);
                case 2 -> b.removeLast();
                case 3 -> b.add(0, element(digest, -1));
                case 4 -> b.add(2048, element(digest, -1));
                case 5 -> b.add(element(digest, -1));
                default -> { for (int i = 500; i < 4000; i += 500) b.set(i, element(digest, -i)); }
            }
            var next = list.build(b, store); var work = new Diff.ListWork();
            long before = ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
            var result = Diff.lists(digest, old, next, store.nodes::get, work);
            long allocated = ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes() - before;
            assertThat(result.removed().size() + result.added().size()).isEqualTo(mutation < 6 ? 1 : 14);
            assertThat(work.comparisons()).isLessThan(100L * a.size());
            assertThat(work.maxFrontierCells()).isLessThanOrEqualTo(4 * (a.size() + 2));
            assertThat(allocated).isLessThan(8_000_000);
            System.out.println("F18 " + digest.getClass().getSimpleName() + " mutation=" + mutation + " comparisons=" + work.comparisons() + " allocated=" + allocated);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void f16UnselectedPayloadDoesNotBecomeQueryAllocation(Digest digest) {
        var tree = new ContentTree(digest, 1 << 20, 128);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var measurements = new ArrayList<Long>();
        for (int size : new int[]{0, 65_536, 1_048_576}) {
            var store = new ContentTreeTest.MapSink();
            var first = new Entry(key(1), new byte[]{7}, digest.hash(key(1)));
            var root = tree.build(List.of(first, new Entry(key(3), new byte[size], digest.hash(key(3)))), store);
            for (boolean range : new boolean[]{false, true}) {
                Runnable query = () -> observed = range ? tree.rangeSum(root, store.nodes::get, key(1), key(2))
                        : tree.get(root.hash(), store.nodes::get, key(1));
                for (int i = 0; i < 1000; i++) query.run();
                long before = bean.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < 100; i++) query.run();
                long allocated = (bean.getCurrentThreadAllocatedBytes() - before) / 100;
                measurements.add(allocated);
                assertThat(allocated).as("payload=%s range=%s", size, range).isLessThan(8192);
                if (range) assertThat(observed).isEqualTo(first.h());
                else assertThat(((Entry) observed).value()).containsExactly(7);
            }
            assertThat(tree.get(root.hash(), store.nodes::get, key(2))).isNull();
            assertThat(tree.rangeSum(root, store.nodes::get, key(2), key(3))).isEqualTo(tree.sums().zero());
            var selected = new ArrayList<Entry>();
            tree.forEach(root.hash(), store.nodes::get, key(1), selected::add);
            assertThat(selected).hasSize(1);
            assertThat(selected.getFirst().value()).containsExactly(7);
            var truncated = Arrays.copyOf(store.nodes.get(root.hash()), store.nodes.get(root.hash()).length - 1);
            assertThatThrownBy(() -> tree.get(root.hash(), h -> truncated, key(1))).isInstanceOf(RuntimeException.class);
        }
        System.out.println("F16 " + digest.getClass().getSimpleName() + " allocated bytes/query " + measurements);
    }

    @ParameterizedTest @MethodSource("digests")
    void f17TailInspectionFetchesEachVisitedNodeOnlyOnce(Digest digest) {
        var tree = new ContentTree(digest);
        for (int size : new int[]{32, 128, 1024, 4096}) {
            var entries = ContentTreeTest.entries(digest, size, 17);
            var store = new ContentTreeTest.MapSink(); var base = tree.build(entries, store);
            for (int operation = 0; operation < 4; operation++) {
                var expected = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
                for (var e : entries) expected.put(e.key(), e);
                var removed = new ArrayList<byte[]>(); var added = new ArrayList<Entry>();
                switch (operation) {
                    case 0 -> { var e = entries.get(size / 2); added.add(new Entry(e.key(), new byte[]{42}, digest.hash(e.key(), new byte[]{42}))); }
                    case 1 -> added.add(new Entry(ContentTreeTest.key(size * 3L), Entry.NONE, digest.hash(key(size))));
                    case 2 -> removed.add(entries.getLast().key());
                    default -> removed.add(ContentTreeTest.key(size * 3L + 1));
                }
                removed.forEach(expected::remove); added.forEach(e -> expected.put(e.key(), e));
                var reads = new HashMap<Identity, Integer>();
                var actual = tree.apply(base, removed, added, h -> { reads.merge(h, 1, Integer::sum); return store.nodes.get(h); }, new ContentTreeTest.MapSink());
                assertThat(reads.values()).allMatch(n -> n == 1);
                assertThat(actual).isEqualTo(tree.build(expected.values(), new ContentTreeTest.MapSink()));
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void f09CapOnlyAdversaryDocumentsLinearRewriteInsteadOfClaimingWorstCaseLocality(Digest digest) {
        var tree = new ContentTree(digest); var entries = new ArrayList<Entry>();
        for (int i = 0; entries.size() < 16_384; i++) if ((Hash64.of(key(i)) & (ContentTree.B - 1)) != 0)
            entries.add(new Entry(key(i), Entry.NONE, digest.hash(key(i))));
        for (int size : new int[]{4096, 16_384}) {
            var store = new ContentTreeTest.MapSink(); var source = entries.subList(0, size);
            var root = tree.build(source, store); var writes = new ContentTreeTest.MapSink();
            var actual = tree.apply(root, List.of(source.getFirst().key()), List.of(), store.nodes::get, writes);
            assertThat(actual).isEqualTo(tree.build(source.subList(1, size), new ContentTreeTest.MapSink()));
            assertThat(writes.writes).isBetween(size / ContentTree.CAP, size / ContentTree.CAP + 16);
            System.out.println("F09 " + digest.getClass().getSimpleName() + " entries=" + size + " node emissions=" + writes.writes);
        }
    }
}
