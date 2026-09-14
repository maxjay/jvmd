package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: bounded deps graph with selection reasons. */
@Tag("phase-2")
class DependencyGraphToolTest {
    @TempDir Path temp;
    @Test void graphIsTieredAndHonorsDepthAndLimit() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        MavenFixtures.artifact(config.m2Repo(), "lib", "1", "");
        Path project = MavenFixtures.project(temp.resolve("project"), "<dependencies>" + MavenFixtures.dependency("lib", "1") + "</dependencies>");
        try (var app = new Application(config)) {
            String id = TestSupport.open(app, project);
            var full = TestSupport.request(app.dispatcher(), "deps.graph", Map.of("session", id, "depth", 2)).path("result");
            assertThat(full.path("tier").asInt()).isEqualTo(2);
            assertThat(full.path("result").path("nodes")).hasSize(2);
            assertThat(full.toString()).contains("selected by Maven");
            var limited = TestSupport.request(app.dispatcher(), "deps.graph", Map.of("session", id, "limit", 1)).path("result");
            assertThat(limited.path("truncated").asBoolean()).isTrue();
            assertThat(limited.path("cursor").isTextual()).isTrue();
        }
    }
}
