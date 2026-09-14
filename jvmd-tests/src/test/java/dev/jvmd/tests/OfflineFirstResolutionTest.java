package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: one online fill followed by a fully offline graph. */
@Tag("phase-2")
class OfflineFirstResolutionTest {
    @TempDir Path temp;
    @Test void missingArtifactsAreFilledFromARepositoryAndSubsequentQueriesStayOffline() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        Path remote = Files.createDirectory(temp.resolve("remote"));
        MavenFixtures.artifact(remote, "lib", "1", "");
        Path project = MavenFixtures.project(temp.resolve("project"), "<repositories><repository><id>fixture</id><url>" + remote.toUri() + "</url></repository></repositories><dependencies>" + MavenFixtures.dependency("lib", "1") + "</dependencies>");
        try (var resolver = new MavenResolver(config)) {
            var graph = resolver.resolve(project);
            assertThat(graph.warnings()).contains("offline_miss: completed one online fill pass");
            assertThat(graph.offline()).isTrue();
            assertThat(graph.classpath()).anyMatch(p -> p.endsWith("lib-1.jar"));
            try (var paths = Files.walk(remote)) { for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
            assertThat(resolver.resolve(project).cached()).isTrue();
        }
    }
}
