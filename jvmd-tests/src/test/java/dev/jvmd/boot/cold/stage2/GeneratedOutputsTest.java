package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.index.layer.local.GeneratedOutputs;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("phase-3")
class GeneratedOutputsTest {
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    private GeneratedOutputs.Output output(String path, String text) { return new GeneratedOutputs.Output(0, path, Stage2Support.text(text)); }

    @ParameterizedTest @MethodSource("digests")
    void concurrentDerivationsShareContentAndRejectAConflictingValue(Digest digest) throws Exception {
        var tree = new ContentTree(digest);
        var store = new InMemoryLocalStore();
        var firstId = digest.hash(Stage2Support.text("first"));
        var secondId = digest.hash(Stage2Support.text("second"));
        var bytes = List.of(output("A.java", "shared"));
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            var tasks = List.<java.util.concurrent.Callable<dev.jvmd.core.tree.Root>>of(
                    () -> { start.await(); return GeneratedOutputs.persist(digest, tree, store, store, firstId, bytes); },
                    () -> { start.await(); return GeneratedOutputs.persist(digest, tree, store, store, secondId, bytes); });
            var results = workers.invokeAll(tasks);
            assertThat(results.get(0).get().hash()).isEqualTo(results.get(1).get().hash());
        }
        assertThat(store.writes(LocalStore.generatedSourceKey(digest.hash(Stage2Support.text("shared"))))).isEqualTo(1);
        assertThat(store.nodeWriteCount()).isEqualTo(1);
        assertThat(store.get(LocalStore.generatedKey(firstId))).isEqualTo(store.get(LocalStore.generatedKey(secondId)));

        var conflict = new InMemoryLocalStore();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            var accepted = new java.util.concurrent.atomic.AtomicInteger();
            var rejected = new java.util.concurrent.atomic.AtomicInteger();
            var tasks = java.util.stream.Stream.of("one", "two").<java.util.concurrent.Callable<Void>>map(text -> () -> {
                start.await();
                try {
                    GeneratedOutputs.persist(digest, tree, conflict, conflict, firstId, List.of(output("A.java", text)));
                    accepted.incrementAndGet();
                } catch (GeneratedOutputs.Inconsistent expected) { rejected.incrementAndGet(); }
                return null;
            }).toList();
            for (var result : workers.invokeAll(tasks)) result.get();
            assertThat(accepted.get()).isEqualTo(1);
            assertThat(rejected.get()).isEqualTo(1);
            assertThat(conflict.writes(LocalStore.generatedKey(firstId))).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void manifestsShareBlobsAreDeterministicAndDiffExactly(Digest digest) {
        var tree = new ContentTree(digest);
        var store = new InMemoryLocalStore();
        var id = digest.hash(Stage2Support.text("first inputs"));
        var first = List.of(output("p/A.java", "A"), output("p/B.java", "B"), output("p/C.java", "C"));
        var root = GeneratedOutputs.persist(digest, tree, store, store, id, first);
        store.flush();
        var repeatStore = store.copy();
        var reversed = new ArrayList<>(first);
        Collections.reverse(reversed);
        var repeated = GeneratedOutputs.persist(digest, tree, repeatStore, repeatStore, id, reversed);
        repeatStore.flush();
        assertThat(repeated).isEqualTo(root);
        assertThat(repeatStore.recordWriteCount()).isZero();
        assertThat(repeatStore.nodeWriteCount()).isZero();
        var clean = new InMemoryLocalStore();
        assertThat(GeneratedOutputs.persist(digest, tree, clean, clean, id, reversed)).isEqualTo(root);

        var changed = List.of(output("p/A.java", "A"), output("p/C.java", "changed C"), output("p/D.java", "D"));
        var secondId = digest.hash(Stage2Support.text("second inputs"));
        var second = GeneratedOutputs.persist(digest, tree, repeatStore, repeatStore, secondId, changed);
        repeatStore.flush();
        var diff = Diff.trees(digest, root, second, h -> repeatStore.get(MachineStore.nodeKey(h)));
        assertThat(diff.removed().stream().map(e -> Keys.generatedOutputPath(e.key()))).containsExactly("p/B.java", "p/C.java");
        assertThat(diff.added().stream().map(e -> Keys.generatedOutputPath(e.key()))).containsExactly("p/C.java", "p/D.java");
        assertThat(repeatStore.writes(LocalStore.generatedSourceKey(digest.hash(Stage2Support.text("A"))))).isZero();
        assertThat(repeatStore.writes(LocalStore.generatedSourceKey(digest.hash(Stage2Support.text("changed C"))))).isEqualTo(1);
        assertThatThrownBy(() -> GeneratedOutputs.persist(digest, tree, store, store, id, changed)).isInstanceOf(GeneratedOutputs.Inconsistent.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void changingOneOfManyOutputsWritesOnlyNewBlobsAndLocalTreePaths(Digest digest) {
        var tree = new ContentTree(digest);
        var store = new InMemoryLocalStore();
        var outputs = new ArrayList<GeneratedOutputs.Output>();
        for (int i = 0; i < 2000; i++) outputs.add(output("p/Generated" + i + ".java", "content " + i));
        var before = GeneratedOutputs.persist(digest, tree, store, store, digest.hash(Stage2Support.text("before")), outputs);
        store.flush();
        var editedStore = store.copy();
        outputs.set(1000, output("p/Generated1000.java", "changed"));
        var after = GeneratedOutputs.persist(digest, tree, editedStore, editedStore, digest.hash(Stage2Support.text("after")), outputs);
        editedStore.flush();
        assertThat(Diff.trees(digest, before, after, h -> editedStore.get(MachineStore.nodeKey(h))).added()).hasSize(1);
        assertThat(editedStore.recordWriteCount()).isEqualTo(2); // one GS blob and one GEN derivation
        assertThat(editedStore.nodeWriteCount()).isBetween(1L, 2L * (after.level() + 1) + 1);
    }
}
