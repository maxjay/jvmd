package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: verbose conflict losers remain in the graph with winners. */
@Tag("phase-2")
class ConflictLosersTest {
    @TempDir Path temp;
    @Test void mavenChoosesTheNearestVersionAndKeepsTheLosingReason() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        MavenFixtures.artifact(config.m2Repo(), "lib", "1", "");
        MavenFixtures.artifact(config.m2Repo(), "lib", "2", "");
        MavenFixtures.artifact(config.m2Repo(), "middle", "1", "<dependencies>" + MavenFixtures.dependency("lib", "1") + "</dependencies>");
        Path project = MavenFixtures.project(temp.resolve("project"), "<dependencies>" + MavenFixtures.dependency("lib", "2") + MavenFixtures.dependency("middle", "1") + "</dependencies>");
        try (var resolver = new MavenResolver(config)) {
            var graph = resolver.resolve(project);
            assertThat(graph.nodes()).anyMatch(n -> n.gav().equals("fixture:lib:1") && n.winner() != null && n.winner().endsWith("fixture:lib:jar:2"));
            assertThat(graph.classpath()).anyMatch(p -> p.endsWith("lib-2.jar")).noneMatch(p -> p.endsWith("lib-1.jar"));
        }
    }
}
