package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.boot.BootDecision;
import dev.jvmd.core.Config;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.LocalFormat;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.rocks.layer.Generation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Stage 2 through {@link BootDecision} on the real store: a MACHINE boot of a small repository and the JDK, a LOCAL cold boot of a
 * project, a second start that logs the skip and touches nothing, and the records on disk, which are exactly those of the section 4
 * table with {@code C|}, {@code X|} and {@code RS|} empty.
 */
@Tag("phase-3")
class RocksLocalBootTest {
    @Test void aColdBootEndsWithALocalRootAndASecondStartSkips() throws Exception {
        var repository = Files.createTempDirectory("stage2-rocks-repo");
        var project = Files.createTempDirectory("stage2-rocks-project");
        var indexDir = Files.createTempDirectory("stage2-rocks-index");
        try {
            Stage2Support.jar(repository, Fixtures.LIB_AB_14, Fixtures.libAB(false), List.of());
            Stage2Support.jar(repository, Fixtures.LIB_X, Fixtures.libX(), List.of());
            Stage2Support.jar(repository, Fixtures.LIB_T, Fixtures.libT(), List.of());
            Stage2Support.write(project, Fixtures.multi());
            var ab = Stage2Support.Dep.jar("org.example:libAB:1.4.0", Fixtures.LIB_AB_14);
            var x = Stage2Support.Dep.jar("org.example:libX:1.0", Fixtures.LIB_X);
            var t = Stage2Support.Dep.jar("org.example:libT:1.0", Fixtures.LIB_T);
            var common = Stage2Support.Dep.module("org.example:common:1.0", "common");
            var model = ProjectModel.parse(Stage2Support.model(project,
                    new Stage2Support.Mod("common", "org.example:common:1.0", List.of(ab)),
                    new Stage2Support.Mod("server-a", "org.example:server-a:1.0", List.of(common, ab, x)).withTest(List.of(t)),
                    new Stage2Support.Mod("server-b", "org.example:server-b:1.0", List.of(x, ab, common)),
                    new Stage2Support.Mod("tool", "org.example:tool:1.0", List.of())));

            // Without a committed MACHINE there is nothing to build LOCAL on.
            assertThatThrownBy(() -> BootDecision.local(indexDir, model, repository)).isInstanceOf(IllegalStateException.class).hasMessageContaining("MACHINE");

            var config = new Config(Stage2Support.JDK, null, repository, 3, Duration.ofHours(1), 1024, false, indexDir, indexDir.resolve("sock"));
            assertThat(BootDecision.machine(indexDir, config)).isPresent();

            var first = BootDecision.local(indexDir, model, repository);
            assertThat(first).as("a cold boot").isPresent();
            assertThat(first.get().faults()).isEmpty();
            assertThat(first.get().modules()).isEqualTo(4);
            var second = BootDecision.local(indexDir, model, repository);
            assertThat(second).as("the second start logs the skip line and does nothing else").isEmpty();

            var digest = Sha256.INSTANCE;
            var format = Format.of(digest, Runtime.version().feature());
            var projectKey = Stage2.projectKey(digest, model);
            try (var store = Generation.of(indexDir, format).open()) {
                var kinds = new TreeMap<String, Integer>();
                for (var key : store.keys()) kinds.merge(kind(key), 1, Integer::sum);
                assertThat(kinds.keySet()).as("record kinds on disk").isSubsetOf("L", "N", "P", "ROOT", "AL", "SL", "S", "ST", "MOD", "RT", "F", "X", "DD", "DS", "DC", "LROOT");
                assertThat(kinds).as("C| and RS| are empty after a cold boot").doesNotContainKeys("C", "RS");
                for (var key : store.keys()) if (kind(key).equals("X")) assertThat(key[2]).as("the only X| entries are the header proofs: kind 7").isEqualTo((byte) 7);
                assertThat(kinds).containsKeys("L", "N", "P", "ROOT", "AL", "SL", "S", "ST", "MOD", "RT", "F", "X", "DD", "DS", "DC", "LROOT");
                assertThat(kinds.get("MOD")).isEqualTo(4);
                assertThat(kinds.get("RT")).isEqualTo(8);
                assertThat(kinds.get("SL")).isEqualTo(8);
                assertThat(kinds.get("F")).isEqualTo(Fixtures.multi().size());
                assertThat(kinds.get("LROOT")).isEqualTo(1);

                var root = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(projectKey)));
                assertThat(root.format()).isEqualTo(LocalFormat.of(format));
                new ContentTree(digest).verify(root.local(), h -> store.get(MachineStore.nodeKey(h)));
                for (var module : model.modules()) for (int scope : new int[] {LocalStore.MAIN, LocalStore.TEST}) {
                    var binding = dev.jvmd.index.layer.local.SourceLeaf.decode(store.get(LocalStore.sourceLeafKey(projectKey, module.name(), scope)), digest.width());
                    assertThat(store.get(MachineStore.leafKey(binding.k()))).isNotNull();
                    var annotations = dev.jvmd.index.layer.machine.AnnotationLeaf.decode(store.get(MachineStore.annotationLeafKey(binding.a())), digest.width());
                    assertThat(digest.hash(annotations.annotations().hash().view(), annotations.edges().hash().view())).isEqualTo(binding.a());
                    new ContentTree(digest).verify(annotations.annotations(), h -> store.get(MachineStore.nodeKey(h)));
                    new ContentTree(digest).verify(annotations.edges(), h -> store.get(MachineStore.nodeKey(h)));
                }
                assertThat(root.local().count()).as("every record is in the LOCAL tree").isGreaterThan(kinds.get("MOD") + kinds.get("RT") + kinds.get("F"));
            }
            // A legacy LOCAL root in an otherwise current MACHINE cannot take the warm/skip branch either.
            try (var store = Generation.of(indexDir, format).openLocal()) {
                var root = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(projectKey)));
                store.putLocalRoot(projectKey, LocalRoot.encode(digest,
                        format + ";local=1;javac=" + Runtime.version().feature(), root.local(), root.machineRoot(), root.modelHash()));
            }
            var rebuilt = BootDecision.local(indexDir, model, repository);
            assertThat(rebuilt).as("local=1 must not skip the current LOCAL cold boot").isPresent();
            assertThat(rebuilt.orElseThrow().faults()).isEmpty();
            try (var store = Generation.of(indexDir, format).open()) {
                assertThat(LocalRoot.formatOf(store.get(LocalStore.localRootKey(projectKey)))).isEqualTo(LocalFormat.of(format));
            }
            assertThat(BootDecision.local(indexDir, model, repository)).isEmpty();
        } finally { Stage2Support.delete(repository); Stage2Support.delete(project); Stage2Support.delete(indexDir); }
    }

    /** The record kind of a key, as the table in section 4 names them. */
    private static String kind(byte[] key) {
        if (new String(key, StandardCharsets.US_ASCII).equals("ROOT")) return "ROOT";
        var text = new String(key, StandardCharsets.ISO_8859_1);
        for (var tag : List.of("LROOT|", "SL|", "AL|", "MOD|", "RT|", "RS|", "DD|", "DS|", "DC|", "ST|", "F|", "C|", "X|", "S|")) if (text.startsWith(tag)) return tag.substring(0, tag.length() - 1);
        if (key[0] == 'L' && key.length == 33) return "L";
        if (key[0] == 'N' && key.length == 33) return "N";
        if (key[0] == 'P') return "P";
        return "? " + java.util.HexFormat.of().formatHex(key, 0, Math.min(8, key.length));
    }
}
