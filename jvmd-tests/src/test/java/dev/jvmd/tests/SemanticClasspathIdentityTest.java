package dev.jvmd.tests;

import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class SemanticClasspathIdentityTest {
    @TempDir Path root;

    @Test void orderedWorkspaceClasspathUsesResolutionSemanticsNotRawJarContent()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        String first="package fixture; public class Sample { public int value(){ return 1; } }";
        Path jar=IndexFixtures.jar(repo,"sample",first,true);
        try(var index=new IndexService(root.resolve("index.db"),repo)){
            index.indexJar(jar,"fixture:sample:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            var before=index.store().semanticClasspathIdentity("w").orElseThrow();

            index.indexSources(jar.resolveSibling("sample-sources.jar"));
            assertThat(index.store().semanticClasspathIdentity("w")).contains(before);

            IndexFixtures.jar(repo,"sample",first.replace("return 1;","return 2;"),true);
            index.indexJar(jar,"fixture:sample:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            var bodyOnly=index.store().semanticClasspathIdentity("w").orElseThrow();
            assertThat(bodyOnly).isEqualTo(before);

            IndexFixtures.jar(repo,"sample",first.replace(" } }"," } public int extra(){ return 3; } }"),true);
            index.indexJar(jar,"fixture:sample:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            assertThat(index.store().semanticClasspathIdentity("w").orElseThrow()).isNotEqualTo(before);
        }
    }
}
