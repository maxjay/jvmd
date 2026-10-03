package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.boot.BootDecision;
import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.Config;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.rocks.layer.Generation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The RocksDB store and the boot decision (stage 1, 5.6 and 9): four record kinds, ROOT last, no fallthrough to a cold boot. */
@Tag("phase-3")
class RocksMachineBootTest {
    @TempDir Path temp;

    private List<Enumerate.Location> locations(Path repository) throws Exception {
        return Enumerate.repository(repository);
    }

    private Path repository() throws Exception {
        var repo = temp.resolve("repository");
        BootFixtures.jar(repo, "g/one/1/one-1.jar", MachineColdBootTest.T1, MachineColdBootTest.library(""));
        BootFixtures.jar(repo, "g/two/1/two-1.jar", MachineColdBootTest.T1, MachineColdBootTest.generated(60, -1));
        return repo;
    }

    @Test void theRocksStoreHoldsExactlyTheFourRecordKindsAndTheSameRootAsMemory() throws Exception {
        var digest = Sha256.INSTANCE;
        var repo = repository();
        var memory = new InMemoryMachineStore();
        var expected = new Stage1(digest, new ContentTree(digest), 25, 4, ClassFacts::of).run(memory, locations(repo));

        var generation = Generation.of(temp.resolve("index"), Format.of(digest, 25));
        assertThat(generation.hasRoot()).isFalse();
        try (var store = generation.create()) {
            var result = new Stage1(digest, new ContentTree(digest), 25, 4, ClassFacts::of).run(store, locations(repo));
            assertThat(result.root()).isEqualTo(expected.root());
            assertThat(store.get(MachineStore.ROOT_KEY)).isEqualTo(memory.root());
            var kinds = new TreeMap<String, Long>();
            for (var key : store.keys()) kinds.merge(Arrays.equals(key, MachineStore.ROOT_KEY) ? "ROOT" : String.valueOf((char) (key[0] & 0xFF)), 1L, Long::sum);
            assertThat(kinds.keySet()).containsExactlyInAnyOrder("L", "N", "P", "ROOT");
            assertThat(kinds).isEqualTo(memory.kinds());
            new ContentTree(digest).verify(MachineTree.decodeRoot(digest, store.get(MachineStore.ROOT_KEY)).root(), h -> store.get(MachineStore.nodeKey(h)));
        }
        assertThat(generation.hasRoot()).isTrue();
        try (var reopened = generation.open()) { assertThat(reopened.get(MachineStore.ROOT_KEY)).isEqualTo(memory.root()); }
    }

    @Test void aCommittedGenerationIsNeverReplacedAndAnUncommittedOneAlwaysIs() throws Exception {
        var digest = Sha256.INSTANCE;
        var generation = Generation.of(temp.resolve("index"), Format.of(digest, 25));
        // A boot that died before its root: nodes on disk, no ROOT. The next cold boot starts from nothing.
        try (var store = generation.create()) {
            var one = new Stage1(digest, new ContentTree(digest), 25, 2, ClassFacts::of);
            store.putPath("stale", new byte[] {1});
            store.flush();
        }
        assertThat(generation.hasRoot()).isFalse();
        try (var store = generation.create()) {
            assertThat(store.get(MachineStore.pathKey("stale"))).as("the unfinished generation was replaced").isNull();
            new Stage1(digest, new ContentTree(digest), 25, 2, ClassFacts::of).run(store, locations(repository()));
        }
        assertThat(generation.hasRoot()).isTrue();
        assertThatThrownBy(generation::create).isInstanceOf(IllegalStateException.class).hasMessageContaining("already committed");
        try (var store = generation.open()) { assertThat(store.hasRoot()).isTrue(); }
    }

    @Test void bootDecisionColdBootsOnceThenSkipsACommittedGenerationWithOneLineAndChangesNothing() throws Exception {
        var repo = repository();
        var config = new Config(Path.of(System.getProperty("java.home")), null, repo, 3, Duration.ofHours(1), 512, false, temp.resolve("state"), temp.resolve("daemon.sock"));
        var index = temp.resolve("machine");
        var lines = new java.util.ArrayList<String>();
        var logger = java.util.logging.Logger.getLogger("dev.jvmd.boot");
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { lines.add(new java.util.logging.SimpleFormatter().formatMessage(r)); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            var first = BootDecision.machine(index, config);
            assertThat(first).isPresent();
            var result = first.get();
            // The two jars, and every module of the running JDK.
            assertThat(result.locations()).isGreaterThan(2);
            assertThat(result.leaves()).isGreaterThan(2);
            assertThat(result.faults()).as("no JDK module and no fixture jar should fault").isEmpty();
            assertThat(lines).anyMatch(l -> l.startsWith("machine cold boot: locations="));
            var generation = Generation.of(index, Format.of(Sha256.INSTANCE, Runtime.version().feature()));
            assertThat(generation.hasRoot()).isTrue();
            byte[] root;
            try (var store = generation.open()) { root = store.get(MachineStore.ROOT_KEY); }

            lines.clear();
            // A second start does not fail and does not boot again: one line, and the generation is untouched.
            assertThat(BootDecision.machine(index, config)).isEmpty();
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains(generation.directory().toString()).contains("warm boot not implemented, skipping").doesNotContain("\n");
            try (var store = generation.open()) { assertThat(store.get(MachineStore.ROOT_KEY)).as("nothing was deleted or rewritten").isEqualTo(root); }
            assertThat(Files.list(index).map(p -> p.getFileName().toString()).toList()).containsExactly("layout=1_digest=SHA-256_jdk=" + Runtime.version().feature() + "_parser=2");
        } finally { logger.removeHandler(handler); }
    }
}
