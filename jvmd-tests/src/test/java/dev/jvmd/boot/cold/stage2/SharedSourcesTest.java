package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Demo plan A2: a source file belongs to one module and one scope, because its {@code F} and {@code X} records are keyed by its path.
 * A source root at or under another module's or scope's root is a model fault. The roots compared are those that exist, as written
 * and as real paths.
 */
@Tag("phase-3")
class SharedSourcesTest {
    @TempDir Path root;

    @AfterAll static void release() { Stage2Support.release(); }

    @BeforeEach void layout() throws Exception {
        for (var dir : List.of("a/src/main/java/p", "a/src/main/java2", "a/src/test/java", "b/src/main/java", "b/src/test/java", "c/src/t", "elsewhere/java/p"))
            Files.createDirectories(root.resolve(dir));
    }

    private static Stage2Support.Mod mod(String name, List<String> main, List<String> test) {
        return new Stage2Support.Mod(name, "g:" + name + ":1", Stage2Support.FEATURE, List.of(), main, List.of(), test, List.of());
    }

    private static final Stage2Support.Mod A = mod("a", List.of("a/src/main/java"), List.of("a/src/test/java"));

    private ProjectModel model(Stage2Support.Mod... modules) { return ProjectModel.parse(Stage2Support.model(root, modules)); }

    private void overlaps(ProjectModel model, String... named) {
        var fault = assertThatThrownBy(model::validate).isInstanceOf(ProjectModel.Fault.class).hasMessageContaining("overlap");
        for (var n : named) fault.hasMessageContaining(n);
    }

    @Test void aRootUnderAnotherModulesRootIsAFaultWhicheverIsListedFirst() {
        var inner = mod("b", List.of("a/src/main/java/p"), List.of());
        overlaps(model(A, inner), "a (main)", "b (main)");
        overlaps(model(inner, A), "a (main)", "b (main)");
    }

    @Test void theSameRootSpelledAnotherWayIsTheSameRoot() {
        overlaps(model(A, mod("b", List.of("a/src/main/java"), List.of())), "a (main)", "b (main)");
        overlaps(model(A, mod("b", List.of(root.resolve("a/src/main/java").toString()), List.of())), "b (main)");
        overlaps(model(A, mod("b", List.of("b/../a/src/main/java/p"), List.of())), "b (main)");
    }

    @Test void aTestRootInsideItsOwnMainRootIsAFault() {
        overlaps(model(mod("c", List.of("c/src"), List.of("c/src/t"))), "c (main)", "c (test)");
    }

    @Test void aLinkIntoAnotherModulesRootIsAFault() throws Exception {
        Files.delete(root.resolve("b/src/main/java"));
        Files.createSymbolicLink(root.resolve("b/src/main/java"), Path.of("../../../a/src/main/java"));
        assertThat(Files.isDirectory(root.resolve("b/src/main/java"))).isTrue();
        overlaps(model(A, mod("b", List.of("b/src/main/java"), List.of())), "b (main)", "really");
    }

    @Test void everyOverlapIsNamedInOneFault() {
        overlaps(model(A, mod("b", List.of("a/src/main/java/p"), List.of("a/src/test/java")), mod("c", List.of("c/src"), List.of("c/src/t"))),
                "b (main)", "b (test)", "c (test)");
    }

    @Test void separateNestedOrMissingRootsAreFine() {
        // A sibling directory whose name only starts the same, and roots nested within one scope.
        model(A, mod("b", List.of("a/src/main/java2", "b/src/main/java", "b/src/main/java/../java"), List.of("b/src/test/java"))).validate();
        // A layout Maven builds: main is src, and the default test root, which the resolver always lists, does not exist.
        model(mod("c", List.of("c/src"), List.of("c/src/test/java"))).validate();
    }

    @Test void anInvalidRootIsAModelFault() {
        var bad = mod("b", List.of("b/src\0main"), List.of());
        assertThatThrownBy(() -> model(A, bad).validate()).isInstanceOf(ProjectModel.Fault.class).hasMessageContaining("invalid source root");
    }

    @Test void anOverlappingModelWritesNoRoot() {
        var digest = Sha256.INSTANCE;
        var model = model(A, mod("b", List.of("a/src/main/java"), List.of()));
        var store = Stage2Support.jdkOnly(digest).copy();
        assertThatThrownBy(() -> new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, root.resolve("repository"), ClassFacts::of).run(store, model))
                .isInstanceOf(ProjectModel.Fault.class);
        assertThat(store.hasLocalRoot(Stage2.projectKey(digest, model))).isFalse();
    }

    /** A root that is itself a link is compiled through its target, with paths under the root as written; a walk alone finds nothing. */
    @Test void aRootThatIsALinkIsWalked() throws Exception {
        Files.writeString(root.resolve("elsewhere/java/p/D.java"), "package p; public class D { }");
        Files.createDirectories(root.resolve("d/src/main"));
        Files.createSymbolicLink(root.resolve("d/src/main/java"), Path.of("../../../elsewhere/java"));
        var model = model(mod("d", List.of("d/src/main/java"), List.of()));
        model.validate();
        assertThat(ModuleJob.sources(model, List.of("d/src/main/java"))).extracting(ModuleJob.Found::path).containsExactly("d/src/main/java/p/D.java");
    }
}
