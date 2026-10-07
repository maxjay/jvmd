package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.BootDecision;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import dev.jvmd.index.rocks.layer.Generation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The production cold entry point completes both roots; a committed generation remains a warm-boot boundary. */
@Tag("phase-3")
class ColdProjectBootTest {
    @TempDir Path directory;
    private final Sha256 digest = Sha256.INSTANCE;
    private final ContentTree tree = new ContentTree(digest);
    private final String source = "app/src/main/java/p/App.java";
    private final String text = "package p; public class App { public int value() {return 7;} }";

    private ProjectModel model() {
        return ProjectModel.parse(Stage2Support.model(directory,
                new Stage2Support.Mod("app", "g:app:1", List.of()).withOptions("-g", "-parameters")));
    }
    private Path index() { return directory.resolve("index"); }
    private Generation generation() { return Generation.of(index(), Format.of(digest, Stage2Support.FEATURE)); }
    private void machine() throws Exception {
        var repository = directory.resolve("repository"); Files.createDirectories(repository);
        var config = new dev.jvmd.core.Config(Stage2Support.JDK, null, repository, 3,
                java.time.Duration.ofHours(1), 1024, false, index(), index().resolve("sock"));
        assertThat(BootDecision.machine(index(), config)).isPresent();
    }
    private byte[] headers(ProjectModel model) throws Exception {
        try (var store = generation().openLocal()) {
            new Stage2(digest, tree, Stage2Support.FEATURE, 2, directory, ClassFacts::of).run(store, model);
            return store.get(LocalStore.localRootKey(Stage2.projectKey(digest, model)));
        }
    }
    private BodiesRoot bodies(ProjectModel model) {
        try (var store = generation().openLocal()) {
            var project = Stage2.projectKey(digest, model);
            var bytes = store.get(LocalStore.bodiesRootKey(project));
            assertThat(bytes).as("the cold entry point must commit BROOT after LROOT").isNotNull();
            var result = BodiesRoot.decode(bytes, digest.width());
            var local = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(project)));
            assertThat(result.current(local)).isTrue();
            assertThat(tree.get(result.bodiesRoot(), id -> store.get(MachineStore.nodeKey(id)),
                    LocalStore.proofKey(project, "app", 0, source))).isNotNull();
            var output = DefinerIndex.decodeRoot(store.get(LocalStore.outputKey(project, "app", 0)), digest.width());
            assertThat(output.count()).isEqualTo(1);
            return result;
        }
    }

    @Test void coldStartCommitsBodiesAndACompleteStartDoesNotReadChangedSources() throws Exception {
        Stage2Support.write(directory, Map.of(source, text)); machine(); var model = model();
        var boot = BootDecision.local(index(), model, directory).orElseThrow();
        assertThat(boot.headers()).isPresent();
        var expected = Stage2Support.compile(directory.resolve("native"), Map.of("p/App.java", text), List.of("-g", "-parameters"), List.of());
        try (var store = generation().openLocal()) {
            var file = boot.bodies().scopes().get("app/main").files().getFirst().computed().result().classFiles().getFirst();
            assertThat(store.get(LocalStore.classFileKey(file.contentHash()))).isEqualTo(expected.get("p/App.class"));
        }
        var first = bodies(model);
        assertThat(directory.resolve("app/target/classes")).doesNotExist();
        Files.delete(directory.resolve(source));
        assertThat(BootDecision.local(index(), model, directory)).isEmpty();
        assertThat(bodies(model)).isEqualTo(first);
        try (var store = generation().openLocal()) {
            assertThat(store.get(LocalStore.bodiesRootHistoryKey(Stage2.projectKey(digest, model), 1))).isNull();
        }
    }

    @Test void aCommittedHeaderSnapshotResumesBodiesWithoutRewritingLocal() throws Exception {
        Stage2Support.write(directory, Map.of(source, text)); machine(); var model = model();
        byte[] local = headers(model);
        assertThat(BootDecision.local(index(), model, directory).orElseThrow().headers()).isEmpty();
        bodies(model);
        try (var store = generation().openLocal()) {
            var project = Stage2.projectKey(digest, model);
            assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(local);
            assertThat(store.get(LocalStore.localRootHistoryKey(project, 1))).isNull();
        }
    }

    @Test void aFailedResumePreservesHeadersAndCanRetryTheSameSnapshot() throws Exception {
        Stage2Support.write(directory, Map.of(source, text)); machine(); var model = model();
        byte[] local = headers(model);
        Files.writeString(directory.resolve(source), text.replace("return 7", "return 8"));
        assertThatThrownBy(() -> BootDecision.local(index(), model, directory))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Source snapshot differs from F");
        try (var store = generation().openLocal()) {
            var project = Stage2.projectKey(digest, model);
            assertThat(store.get(LocalStore.bodiesRootKey(project))).isNull();
            assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(local);
            assertThat(store.get(LocalStore.localRootHistoryKey(project, 1))).isNull();
        }
        Files.writeString(directory.resolve(source), text);
        assertThat(BootDecision.local(index(), model, directory).orElseThrow().headers()).isEmpty();
        bodies(model);
    }

    @Test void aNewHeaderCommitCompletesTheBodyDeltaAndRetainsBothHistories() throws Exception {
        Stage2Support.write(directory, Map.of(source, text)); machine(); var model = model();
        var before = BootDecision.local(index(), model, directory).orElseThrow();
        var project = Stage2.projectKey(digest, model); byte[] oldLocal;
        try (var store = generation().openLocal()) { oldLocal = store.get(LocalStore.localRootKey(project)); }
        String changed = text.replace("return 7", "return 8"); Files.writeString(directory.resolve(source), changed);
        byte[] local = headers(model);
        var after = BootDecision.local(index(), model, directory).orElseThrow();
        assertThat(after.headers()).isEmpty();
        assertThat(after.bodies().bodies()).isNotEqualTo(before.bodies().bodies());
        bodies(model);
        var expected = Stage2Support.compile(directory.resolve("native-new"), Map.of("p/App.java", changed), List.of("-g", "-parameters"), List.of());
        try (var store = generation().openLocal()) {
            assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(local);
            assertThat(store.get(LocalStore.localRootHistoryKey(project, 1))).isEqualTo(oldLocal);
            assertThat(store.get(LocalStore.localRootHistoryKey(project, 2))).isNull();
            assertThat(store.get(LocalStore.bodiesRootHistoryKey(project, 1))).isEqualTo(before.bodies().bodies().encode());
            var file = after.bodies().scopes().get("app/main").files().getFirst().computed().result().classFiles().getFirst();
            assertThat(store.get(LocalStore.classFileKey(file.contentHash()))).isEqualTo(expected.get("p/App.class"));
        }
    }

    @Test void anOlderBodiesFormatIsAColdInputWithoutDecodingItsPayload() throws Exception {
        Stage2Support.write(directory, Map.of(source, text)); machine(); var model = model();
        var first = BootDecision.local(index(), model, directory).orElseThrow();
        var project = Stage2.projectKey(digest, model); byte[] local;
        byte[] legacy = new dev.jvmd.core.tree.Codec.Writer().str(first.bodies().bodies().format().replace(";bodies=8", ";bodies=7"))
                .u8(255).toBytes(); // An old payload is opaque even when its current-format decoder would reject it.
        try (var store = generation().openLocal()) {
            local = store.get(LocalStore.localRootKey(project));
            store.put(LocalStore.bodiesRootKey(project), legacy);
            var aci = first.bodies().scopes().get("app/main").files().getFirst().computed().aci();
            store.put(LocalStore.resultKey(aci), new byte[] {99});
            store.flush(); store.sync();
        }
        var rebuilt = BootDecision.local(index(), model, directory).orElseThrow();
        assertThat(rebuilt.headers()).isEmpty();
        assertThat(bodies(model)).isEqualTo(first.bodies().bodies());
        try (var store = generation().openLocal()) {
            assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(local);
            assertThat(store.get(LocalStore.localRootHistoryKey(project, 1))).isNull();
            assertThat(store.get(LocalStore.bodiesRootHistoryKey(project, 1))).isEqualTo(legacy);
        }
    }

    @Test void changedModelCannotResumeAnUnrelatedHeaderSnapshot() throws Exception {
        Stage2Support.write(directory, Map.of(source, text)); machine(); var model = model(); byte[] local = headers(model);
        var changed = ProjectModel.parse(Stage2Support.model(directory,
                new Stage2Support.Mod("app", "g:app:1", List.of()).withOptions("-g:none")));
        assertThatThrownBy(() -> BootDecision.local(index(), changed, directory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("current committed LOCAL inputs");
        try (var store = generation().openLocal()) {
            var project = Stage2.projectKey(digest, model);
            assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(local);
            assertThat(store.get(LocalStore.bodiesRootKey(project))).isNull();
        }
    }

    @Test void compilerErrorsCompleteTheGenerationBesideHealthyClasses() throws Exception {
        Stage2Support.write(directory, Map.of(source, text,
                "app/src/main/java/p/Broken.java", "package p; class Broken { int value() {return missing;} }"));
        machine(); var model = model();
        var completed = BootDecision.local(index(), model, directory).orElseThrow();
        assertThat(completed.bodies().faults()).isEmpty();
        var scope = completed.bodies().scopes().get("app/main");
        assertThat(scope.files()).hasSize(2);
        assertThat(scope.diagnostics()).anyMatch(message -> message.diagnostic().kind() == 0);
        bodies(model);
        assertThat(BootDecision.local(index(), model, directory)).isEmpty();
    }
}
