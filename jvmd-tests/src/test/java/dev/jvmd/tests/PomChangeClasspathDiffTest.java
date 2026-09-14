package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: re-resolve changed POMs and publish the classpath delta. */
@Tag("phase-2")
class PomChangeClasspathDiffTest {
    @TempDir Path temp;
    @Test void dependencyEditChangesTheClasspathGenerationAndPaths() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        MavenFixtures.artifact(config.m2Repo(), "lib", "1", "");
        MavenFixtures.artifact(config.m2Repo(), "lib", "2", "");
        Path project = MavenFixtures.project(temp.resolve("project"), "<dependencies>" + MavenFixtures.dependency("lib", "1") + "</dependencies>");
        try (var app = new Application(config)) {
            String id = TestSupport.open(app, project);
            var session = app.sessions().get(id);
            Object old = session.state("classpath_generation");
            Files.writeString(project.resolve("pom.xml"), Files.readString(project.resolve("pom.xml")).replace(MavenFixtures.dependency("lib", "1"), MavenFixtures.dependency("lib", "2")));
            var response = TestSupport.request(app.dispatcher(), "deps.graph", Map.of("session", id));
            assertThat(response.has("error")).isFalse();
            assertThat(session.state("classpath_generation")).isNotEqualTo(old);
            assertThat(session.state("classpath_diff").toString()).contains("added=", "removed=", "lib-1.jar", "lib-2.jar");
        }
    }
}
