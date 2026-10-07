package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.BodyGeneration;
import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class BodyPlanningTest {
    @TempDir Path directory;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void f12ProductionPlannerPrunesUnrelatedLocalSubtreesAndUsesTheYieldedWitness(Digest digest) throws Exception {
        var model = ProjectModel.parse(Stage2Support.model(directory, new Stage2Support.Mod("app", "g:app:1", List.of())));
        var project = Stage2.projectKey(digest, model); var tree = new ContentTree(digest);
        var planner = Stage3.class.getDeclaredMethod("rows", ProjectModel.class, dev.jvmd.core.hash.Identity.class, LocalRoot.class, BodyGeneration.class);
        planner.setAccessible(true);
        var driver = new Stage3(digest, tree, Runtime.version().feature(), 1, directory);
        for (int unrelated : new int[]{0, 1000, 10_000}) {
            var store = new InMemoryLocalStore(); var entries = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
            var path = "app/src/main/java/A.java";
            var row = new FileRow(path, project, 1, 0, project, List.of("A"), List.of(), List.of(), project, List.of());
            var fileKey = LocalStore.fileKey(project, "app", 0, path);
            store.put(fileKey, row.encode());
            store.put(LocalStore.bodyValueKey(digest.hash(row.encode())), row.encode());
            entries.put(fileKey, new Entry(fileKey, Entry.NONE, digest.hash(row.encode())));
            for (int i = 0; i < unrelated; i++) {
                var key = new Codec.Writer().str("Z-unrelated").u32(i).toBytes();
                entries.put(key, new Entry(key, Entry.NONE, digest.hash(key)));
            }
            var root = tree.build(entries.values(), store); store.flush();
            var encoded = LocalRoot.encode(digest, "fixture;local=" + LocalFormat.LAYOUT, root, project, project);
            store.putLocalRoot(digest, project, encoded);
            var local = LocalRoot.decode(digest, encoded); var generation = BodyGeneration.begin(tree, store, project, local);
            int before = store.events().size();
            var result = (Map<?, ?>) planner.invoke(driver, model, project, local, generation);
            assertThat(result.values().stream().mapToInt(value -> ((List<?>) value).size()).sum()).isEqualTo(1);
            var events = store.events().subList(before, store.events().size());
            long nodes = events.stream().filter(e -> e.equals("read:N")).count();
            assertThat(nodes).isLessThanOrEqualTo(2L * root.level() + 1);
            assertThat(events.stream().filter(e -> e.equals("read:BV")).count()).isEqualTo(1);
            System.out.println("F12 " + digest.getClass().getSimpleName() + " unrelated=" + unrelated + " node reads=" + nodes);
            store.put(fileKey, new byte[]{42}); store.flush();
            assertThat((Map<?, ?>) planner.invoke(driver, model, project, local, generation)).isEqualTo(result);
            store.put(LocalStore.bodyValueKey(digest.hash(row.encode())), new byte[]{42}); store.flush();
            assertThatThrownBy(() -> planner.invoke(driver, model, project, local, generation))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }
}
