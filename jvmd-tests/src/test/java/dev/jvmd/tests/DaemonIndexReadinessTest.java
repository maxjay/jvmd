package dev.jvmd.tests;

import dev.jvmd.core.Config;
import dev.jvmd.dist.Application;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonIndexReadinessTest {
    @TempDir Path temp;

    @Test void sessionOpenReturnsOnlyAfterTheInitialRepositoryScanIsReady() throws Exception {
        var base = TestSupport.config(temp, Duration.ofHours(4));
        IndexFixtures.jar(
                base.m2Repo().resolve("fixture/sample/1"),
                "sample-1",
                IndexFixtures.generic(),
                false);
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        var config = new Config(
                base.jdkHome(),
                base.jbrHome(),
                base.m2Repo(),
                base.mavenMajor(),
                base.idleTimeout(),
                base.heapCeilingMb(),
                true,
                base.stateDir(),
                base.socket(),
                base.hotswapAgent());

        String property = "jvmd.index.scan.initial_delay_seconds";
        String previous = System.getProperty(property);
        System.setProperty(property, "0");
        try (var app = new Application(config)) {
            var opened = TestSupport.request(
                    app.dispatcher(),
                    "session.open",
                    Map.of("root", workspace.toString()));
            assertThat(opened.has("error")).withFailMessage(opened.toPrettyString()).isFalse();

            var index = TestSupport.request(app.dispatcher(), "daemon.status", Map.of())
                    .path("result").path("result").path("index");
            assertThat(index.path("phase").asText()).isEqualTo("ready");
            assertThat(index.path("timings").path("scans").asLong()).isGreaterThanOrEqualTo(1L);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
