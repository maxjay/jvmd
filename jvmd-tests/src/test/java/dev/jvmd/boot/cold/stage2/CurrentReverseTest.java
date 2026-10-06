package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import dev.jvmd.index.rocks.layer.Generation;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class CurrentReverseTest {
    @TempDir Path directory;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    private static final Diff.Result NONE = new Diff.Result(List.of(), List.of());
    private static final ReverseIndex.Dependency T = new ReverseIndex.Dependency(ReverseIndex.T, "q/K", Keys.FIELD, "VALUE");

    @ParameterizedTest @MethodSource("digests")
    void flushingADuplicatePublishesTheOriginalWorkersPendingNode(Digest digest) throws Exception {
        var store = new InMemoryLocalStore();
        var written = new dev.jvmd.boot.cold.stage1.Written();
        var sink = written.throughShared(store);
        var node = Node.leaf(digest, dev.jvmd.core.hash.Sum.forWidth(digest.width()),
                List.of(new Entry(new byte[]{1}, Entry.NONE, digest.hash(new byte[]{1}))));
        var producer = new Thread(() -> sink.write(node));
        producer.start(); producer.join(); // it deliberately has not flushed
        sink.write(node);
        sink.flush();
        assertThat(store.get(MachineStore.nodeKey(node.hash()))).isEqualTo(node.bytes());
        assertThat(written.count()).isOne();
        assertThat(written.produced()).isEqualTo(2);
    }

    private static byte[] root(Digest digest, LocalStore store, List<byte[]> keys) {
        var sorted = new TreeSet<byte[]>(Arrays::compareUnsigned); sorted.addAll(keys);
        var entries = sorted.stream().map(k -> new Entry(k, Entry.NONE, digest.hash(Entry.NONE))).toList();
        var tree = new ContentTree(digest).build(entries, store); store.flush();
        return LocalRoot.encode(digest, LocalFormat.of(Format.of(digest, Stage2Support.FEATURE)), tree,
                digest.hash(new byte[]{1}), digest.hash(new byte[]{2}));
    }

    /** Counts keys that actually cross the storage boundary, and every query point read. */
    private static final class Meter implements java.lang.reflect.InvocationHandler {
        final LocalStore delegate; int hits, gets;
        Meter(LocalStore delegate) { this.delegate = delegate; }
        LocalStore view() { return (LocalStore) java.lang.reflect.Proxy.newProxyInstance(LocalStore.class.getClassLoader(),
                new Class<?>[]{LocalStore.class}, this); }
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if (method.getName().equals("get")) gets++;
            if (method.getName().equals("forEachKey")) {
                var callback = (java.util.function.Consumer<byte[]>) args[1];
                args[1] = (java.util.function.Consumer<byte[]>) key -> { hits++; callback.accept(key); };
            }
            try { return method.invoke(delegate, args); }
            catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void currentFanoutIsIndependentOfHistoricalChurnInMemoryAndRocks(Digest digest) throws Exception {
        var generation = Generation.of(directory, Format.of(digest, Stage2Support.FEATURE));
        try (var machine = generation.create()) { machine.putRoot(new byte[]{1}); }
        try (var rocks = generation.openLocal()) {
            for (LocalStore store : List.of(new InMemoryLocalStore(), rocks)) {
                var project = digest.hash(new byte[]{3});
                byte[] live = T.key(project, "live.java");
                assertThatThrownBy(() -> store.put(live, Entry.NONE)).isInstanceOf(IllegalArgumentException.class);
                var first = root(digest, store, List.of(live));
                store.putLocalRoot(digest, project, first);
                for (int round = 0; round < 12; round++) {
                    int version = round;
                    var historical = new ArrayList<byte[]>();
                    historical.add(live);
                    IntStream.range(0, 500).forEach(i -> historical.add(T.key(project, "old-"+version+"-"+i+".java")));
                    var unpublished = root(digest, store, historical);
                    assertThat(ReverseIndex.consumers(digest, store, T)).hasSize(1);
                    store.putLocalRoot(digest, project, unpublished);
                    assertThat(ReverseIndex.consumers(digest, store, T)).hasSize(501);
                    store.putLocalRoot(digest, project, first);
                    var meter = new Meter(store);
                    assertThat(ReverseIndex.consumers(digest, meter.view(), T)).containsExactly(new ReverseIndex.Consumer(project, "live.java"));
                    assertThat(meter.hits).isOne();
                    assertThat(meter.gets).as("no LOCAL membership or per-project root read").isZero();
                }
                var historical = LocalRoot.decode(digest, store.get(LocalStore.localRootHistoryKey(project, 2)));
                new ContentTree(digest).verify(historical.local(), id -> store.get(MachineStore.nodeKey(id)));
                assertThat(historical.local().count()).isEqualTo(501);
                var bad = first.clone(); bad[bad.length-1] ^= 1;
                assertThatThrownBy(() -> store.putLocalRoot(digest, project, bad)).isInstanceOf(IllegalStateException.class);
                assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(first);
                assertThat(ReverseIndex.consumers(digest, store, T)).hasSize(1);
            }
        }
        try (var reopened = generation.openLocal()) {
            var meter = new Meter(reopened);
            assertThat(ReverseIndex.consumers(digest, meter.view(), T)).hasSize(1);
            assertThat(meter.hits).isOne(); assertThat(meter.gets).isZero();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void zeroProjectionsIgnoreValueChangesButObserveKeyPresenceChanges(Digest digest) {
        var store = new InMemoryLocalStore(); var project = digest.hash(new byte[]{4});
        var n = new ReverseIndex.Dependency(ReverseIndex.N, "q/Base", Keys.TYPE, "Inner");
        var d = new ReverseIndex.Dependency(ReverseIndex.D, "q/K", Keys.TYPE, "");
        store.putLocalRoot(digest, project, root(digest, store, List.of(T.key(project, "t.java"), n.key(project, "n.java"), d.key(project, "d.java"))));
        byte[] nk = Keys.nameKey("Inner", Keys.TYPE, "q/Base", Keys.typeKey("q/Base$Inner"));
        var oldN = new Entry(nk, Entry.NONE, digest.hash(new byte[]{1}));
        var newN = new Entry(nk, Entry.NONE, digest.hash(new byte[]{2}));
        var oldD = new Entry(Keys.ownerKey("q/K"), Entry.NONE, oldN.h());
        var newD = new Entry(Keys.ownerKey("q/K"), Entry.NONE, newN.h());
        assertThat(ReverseIndex.candidates(digest, store, new ReverseIndex.Delta(NONE,
                new Diff.Result(List.of(oldN), List.of(newN)), new Diff.Result(List.of(oldD), List.of(newD)), NONE))).isEmpty();
        assertThat(ReverseIndex.candidates(digest, store, new ReverseIndex.Delta(NONE,
                new Diff.Result(List.of(), List.of(newN)), new Diff.Result(List.of(oldD), List.of()), NONE)))
                .extracting(ReverseIndex.Consumer::path).containsExactly("d.java", "n.java");
        var member = new Entry(Keys.memberKey("q/K", Keys.FIELD, "VALUE", "I"), Entry.NONE, oldN.h());
        assertThat(ReverseIndex.candidates(digest, store, new ReverseIndex.Delta(
                new Diff.Result(List.of(member), List.of(new Entry(member.key(), Entry.NONE, newN.h()))), NONE, NONE, NONE)))
                .containsExactly(new ReverseIndex.Consumer(project, "t.java"));
    }

    @ParameterizedTest @MethodSource("digests")
    void exactDefinerDeltaIgnoresLoserOrderButReachesAllKindsOnEqualSumWinnerSwap(Digest digest) {
        var store = new InMemoryLocalStore(); var project = digest.hash(new byte[]{9});
        var n = new ReverseIndex.Dependency(ReverseIndex.N, "q/K", Keys.TYPE, "Inner");
        var method = new ReverseIndex.Dependency(ReverseIndex.T, "q/K", Keys.METHOD, "method");
        var d = new ReverseIndex.Dependency(ReverseIndex.D, "q/K", Keys.TYPE, "");
        store.putLocalRoot(digest, project, root(digest, store, List.of(T.key(project, "field.java"),
                n.key(project, "n.java"), method.key(project, "method.java"), d.key(project, "d.java"))));
        var a = digest.hash(new byte[]{1}); var b = digest.hash(new byte[]{2}); var c = digest.hash(new byte[]{3});
        var key = Keys.ownerKey("q/K"); var h = digest.hash(new byte[]{4});
        var before = new Entry(key, new Codec.Writer().id(a).u32(3).id(a).id(b).id(c).toBytes(), h);
        var losers = new Entry(key, new Codec.Writer().id(a).u32(3).id(a).id(c).id(b).toBytes(), h);
        assertThat(ReverseIndex.candidates(digest, store, new ReverseIndex.Delta(NONE, NONE, NONE,
                new Diff.Result(List.of(before), List.of(losers))))).isEmpty();
        var winner = new Entry(key, new Codec.Writer().id(b).u32(3).id(b).id(a).id(c).toBytes(), h);
        assertThat(ReverseIndex.candidates(digest, store, new ReverseIndex.Delta(NONE, NONE, NONE,
                new Diff.Result(List.of(before), List.of(winner)))))
                .extracting(ReverseIndex.Consumer::path).containsExactly("field.java", "method.java", "n.java");
    }
}
