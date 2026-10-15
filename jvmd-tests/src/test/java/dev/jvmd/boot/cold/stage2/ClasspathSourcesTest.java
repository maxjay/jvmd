package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * Header compilation never takes a declaration from a source file on the classpath (stage 2, C.1; amendments 4.7): a jar that bundles
 * {@code .java} files contributes its classes and nothing else, with or without processors.
 */
@Tag("phase-3")
class ClasspathSourcesTest {
    @TempDir Path temp;

    @AfterAll static void release() { Stage2Support.release(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aSourceFileInAJarIsNotADeclaration(boolean processing) throws Exception {
        var repo = temp.resolve("repository");
        // A jar with a source file and no class for it, as a sources jar or a jar that bundles its sources would have.
        Stage2Support.pack(repo.resolve("q/q/1/q-1.jar"), Map.of("q/OnlySource.java", Stage2Support.text("package q; public class OnlySource { }")));
        var root = temp.resolve("project");
        Stage2Support.write(root, Map.of("a/src/main/java/p/A.java", "package p; public class A { public q.OnlySource field; }"));
        var json = Stage2Support.model(root, new Stage2Support.Mod("a", "g:a:1", List.of(Stage2Support.Dep.jar("q:q:1", "q/q/1/q-1.jar"))));
        if (processing) {
            var doc = (ObjectNode) new ObjectMapper().readTree(json);
            ((ObjectNode) doc.withArray("modules").get(0).get("scopes").get("main")).putObject("processing").put("mode", "full");
            json = new ObjectMapper().writeValueAsBytes(doc);
        }
        var digest = Sha256.INSTANCE;
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, repo, ClassFacts::of)
                .run(Stage2Support.jdkOnly(digest).copy(), ProjectModel.parse(json));
        assertThat(result.faults()).as("q.OnlySource has no class, so it does not resolve").anyMatch(f -> f.contains("p/A.java") && f.contains("OnlySource"));
    }
}
