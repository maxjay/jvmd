package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import dev.jvmd.index.rocks.layer.Generation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class ProcessorHistoryTest {
    @TempDir Path directory;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void concurrentHistorySurvivesReopenWithoutChangingProjectSnapshotsOrOtherProcessorBytes(Digest digest) throws Exception {
        var tree = new ContentTree(digest); var format = Format.of(digest, Stage2Support.FEATURE);
        var generation = Generation.of(directory.resolve("index"), format);
        try (var store = generation.create()) {
            new Stage1(digest, tree, Stage2Support.FEATURE, 2, ClassFacts::of)
                    .run(store, Files.createDirectories(directory.resolve("repository")), List.of());
        }
        var project = digest.hash(new byte[] {1}); var firstBytes = digest.hash(new byte[] {2}); var nextBytes = digest.hash(new byte[] {3});
        byte[] local;
        var overlay = new ProcessorRecords.Capability(ProcessorRecords.ISOLATING, ProcessorRecords.OVERLAY);
        var generator = new ProcessorRecords.Capability(ProcessorRecords.AGGREGATING, ProcessorRecords.GENERATOR);
        var violated = new ProcessorRecords.Capability(ProcessorRecords.ISOLATING, ProcessorRecords.VIOLATED);
        try (var store = generation.openLocal()) {
            var root = tree.build(List.of(), store); store.flush(); store.sync();
            local = LocalRoot.encode(digest, LocalFormat.of(format), root, project, project);
            store.putLocalRoot(digest, project, local);
            store.observeProcessor(nextBytes, "fixture.Processor", overlay);
            store.observeProcessor(firstBytes, "fixture.Other", overlay);
            try (var threads = java.util.concurrent.Executors.newFixedThreadPool(3)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                var futures = List.of(overlay, generator, violated).stream().map(observation -> threads.submit(() -> {
                    start.await();
                    for (int i = 0; i < 20; i++) store.observeProcessor(firstBytes, "fixture.Processor", observation);
                    return null;
                })).toList();
                start.countDown(); for (var future : futures) future.get();
            }
            store.sync();
        }
        try (var store = generation.openLocal()) {
            assertThat(ProcessorRecords.Capability.decode(store.get(LocalStore.processorKey(firstBytes, "fixture.Processor"))))
                    .isEqualTo(new ProcessorRecords.Capability(ProcessorRecords.NONE, ProcessorRecords.VIOLATED));
            assertThat(ProcessorRecords.Capability.decode(store.get(LocalStore.processorKey(nextBytes, "fixture.Processor")))).isEqualTo(overlay);
            assertThat(ProcessorRecords.Capability.decode(store.get(LocalStore.processorKey(firstBytes, "fixture.Other")))).isEqualTo(overlay);
            assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(local);
            assertThat(store.get(LocalStore.localRootHistoryKey(project, 1))).isNull();
            var root = LocalRoot.decode(digest, local).local(); tree.verify(root, id -> store.get(MachineStore.nodeKey(id)));
            assertThat(tree.get(root.hash(), id -> store.get(MachineStore.nodeKey(id)), LocalStore.processorKey(firstBytes, "fixture.Processor"))).isNull();
        }
    }
}
