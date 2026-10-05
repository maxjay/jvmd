package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class ProcessorConfigurationTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void configurationInsertionEditDeletionOnlyChangesTheLookupChain(Digest digest) throws Exception {
        Files.writeString(dir.resolve("lombok.config"), "config.stopBubbling = true\n");
        Files.createDirectories(dir.resolve("src/p/deep"));
        var sources = List.of("src/p/deep/A.java", "src/q/B.java");
        var before = scan(digest, sources);
        for (String path : List.of("src/p/lombok.config", "src/p/deep/lombok.config")) {
            Files.writeString(dir.resolve(path), "lombok.getter.noIsPrefix = true\n");
            var inserted = scan(digest, sources);
            assertThat(inserted.configuration.proof(sources.getFirst())).isNotEqualTo(before.configuration.proof(sources.getFirst()));
            assertThat(inserted.configuration.proof(sources.getLast())).isEqualTo(before.configuration.proof(sources.getLast()));
            var diff = difference(digest, before, inserted);
            assertThat(diff.added()).hasSize(1);
            assertThat(diff.removed()).isEmpty();
            Files.writeString(dir.resolve(path), "lombok.getter.noIsPrefix = false\n");
            var edited = scan(digest, sources);
            assertThat(edited.configuration.proof(sources.getFirst())).isNotEqualTo(inserted.configuration.proof(sources.getFirst()));
            assertThat(difference(digest, inserted, edited).added()).hasSize(1);
            assertThat(difference(digest, inserted, edited).removed()).hasSize(1);
            Files.delete(dir.resolve(path));
            var removed = scan(digest, sources);
            assertThat(removed.configuration.root()).isEqualTo(before.configuration.root());
            assertThat(removed.configuration.proof(sources.getFirst())).isEqualTo(before.configuration.proof(sources.getFirst()));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void stopBubblingExcludesTheUnobservedParent(Digest digest) throws Exception {
        Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(dir.resolve("lombok.config"), "config.stopBubbling = true\n");
        Files.writeString(dir.resolve("src/p/lombok.config"), "config.stopBubbling = true\n");
        var sources = List.of("src/p/A.java", "src/q/B.java");
        var before = scan(digest, sources);
        Files.writeString(dir.resolve("lombok.config"), "config.stopBubbling = true\nlombok.getter.noIsPrefix = true\n");
        var after = scan(digest, sources);
        assertThat(after.configuration.root()).isNotEqualTo(before.configuration.root());
        assertThat(after.configuration.proof(sources.getFirst())).isEqualTo(before.configuration.proof(sources.getFirst()));
        assertThat(after.configuration.proof(sources.getLast())).isNotEqualTo(before.configuration.proof(sources.getLast()));
    }

    private record State(ProcessorConfiguration configuration, InMemoryLocalStore store) {}
    private State scan(Digest digest, List<String> sources) throws Exception {
        var store = new InMemoryLocalStore();
        var configuration = ProcessorConfiguration.scan(digest, new ContentTree(digest), dir, sources, List.of(), store,
                h -> store.get(MachineStore.nodeKey(h)));
        store.flush();
        return new State(configuration, store);
    }
    private Diff.Result difference(Digest digest, State before, State after) {
        return Diff.trees(digest, before.configuration.root(), after.configuration.root(), h -> {
            var value = before.store.get(MachineStore.nodeKey(h));
            return value == null ? after.store.get(MachineStore.nodeKey(h)) : value;
        });
    }
}
