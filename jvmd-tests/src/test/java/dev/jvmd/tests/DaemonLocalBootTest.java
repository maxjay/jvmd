package dev.jvmd.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.dist.Application;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Demo plan step 1 in the daemon: opening a Maven project boots its LOCAL layer in the background once MACHINE is committed, and
 * {@code daemon.status} reports it under {@code layered_index}.
 */
@Tag("phase-3")
class DaemonLocalBootTest {
    @TempDir Path temp;

    @Test void openingAMavenProjectBootsItsLocalLayerAfterMachine() throws Exception {
        var root = MavenFixtures.project(temp.resolve("app"), "<properties><maven.compiler.release>17</maven.compiler.release></properties>");
        Files.createDirectories(root.resolve("src/main/java/app"));
        Files.writeString(root.resolve("src/main/java/app/Main.java"), "package app; public class Main { public static void main(String[] args) { } }");
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            TestSupport.open(app, root);
            assertThat(layered(app).path("machine").path("state").asText()).isEqualTo("not_started");
            assertThat(project(app).path("state").asText()).as("a session opened before MACHINE waits for it").isEqualTo("waiting_for_machine");

            app.bootMachine();
            assertThat(layered(app).path("machine").path("state").asText()).isEqualTo("cold_booted");
            var booted = await(app, "cold_booted");
            assertThat(booted.path("modules").asInt()).isEqualTo(1);
            assertThat(booted.path("source_files").asInt()).isEqualTo(1);
            assertThat(booted.path("faults").asInt()).isZero();
            assertThat(booted.path("root").asText()).hasSize(16);

            TestSupport.open(app, root);
            assertThat(await(app, "current").path("root").isMissingNode()).as("opened again with nothing changed, the root is kept").isTrue();

            Files.writeString(root.resolve("src/main/java/app/Added.java"), "package app; class Added { }");
            TestSupport.open(app, root);
            var rebuilt = await(app, "cold_booted");
            assertThat(rebuilt.path("source_files").asInt()).isEqualTo(2);
            assertThat(rebuilt.path("root").asText()).isNotEqualTo(booted.path("root").asText());
        }
    }

    @Test void anApplicationWhoseMachineNeverBootsBootsNothing() throws Exception {
        var root = MavenFixtures.project(temp.resolve("app"), "");
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            TestSupport.open(app, root);
            TestSupport.open(app, root);
            assertThat(project(app).path("state").asText()).isEqualTo("waiting_for_machine");
        }
        assertThat(Files.exists(temp.resolve("state/machine"))).as("no generation is created").isFalse();
    }

    private static JsonNode layered(Application app) {
        return TestSupport.request(app.dispatcher(), "daemon.status", Map.of()).path("result").path("result").path("layered_index");
    }

    /** The one project the status reports. */
    private static JsonNode project(Application app) {
        var projects = layered(app).path("projects");
        assertThat(projects.size()).as(projects.toPrettyString()).isEqualTo(1);
        return projects.elements().next();
    }

    /** Waits until the boot just requested has settled, and checks it settled as expected. */
    private static JsonNode await(Application app, String state) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            var project = project(app);
            var now = project.path("state").asText();
            if (!Set.of("waiting_for_machine", "queued", "booting").contains(now)) {
                assertThat(now).as(project.toPrettyString()).isEqualTo(state);
                return project;
            }
            assertThat(System.nanoTime()).as("still " + project.toPrettyString()).isLessThan(deadline);
            Thread.sleep(20);
        }
    }
}
