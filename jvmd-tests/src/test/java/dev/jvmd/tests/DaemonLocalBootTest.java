package dev.jvmd.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * daemon.shutdown can arrive while MACHINE boots on main. Closing cancels that boot and waits for it, so the daemon has stopped
     * writing its store before it releases its lock, and no ROOT is published.
     */
    @Test void closingDuringTheMachineBootCancelsItAndWaitsForIt() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(1));
        var app = new Application(config);
        var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var boot = Thread.ofPlatform().start(() -> { try { app.bootMachine(); } catch (Throwable t) { outcome.set(t); } });
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!layered(app).path("machine").path("state").asText().equals("booting")) {
            assertThat(System.nanoTime()).as("the boot starts").isLessThan(deadline);
            Thread.sleep(1);
        }
        app.close();
        assertThat(boot.isAlive()).as("close returns only once the boot has ended").isFalse();
        assertThat(outcome.get()).isInstanceOf(java.util.concurrent.CancellationException.class);
        var generation = dev.jvmd.index.rocks.layer.Generation.of(config.stateDir().resolve("machine"),
                dev.jvmd.index.layer.machine.Format.of(dev.jvmd.core.hash.digests.Sha256.INSTANCE, Runtime.version().feature()));
        assertThat(generation.hasRoot()).as("a cancelled boot publishes nothing").isFalse();
        assertThatThrownBy(app::bootMachine).as("a closed daemon boots nothing").isInstanceOf(java.util.concurrent.CancellationException.class);
    }

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

    /** Opened again before its boot started, a project is booted once, from the latest resolution. */
    @Test void aReopenWhileWaitingReplacesTheWaitingResolution() throws Exception {
        var root = MavenFixtures.project(temp.resolve("app"), "<properties><maven.compiler.release>17</maven.compiler.release></properties>");
        Files.createDirectories(root.resolve("src/main/java/app"));
        Files.writeString(root.resolve("src/main/java/app/Main.java"), "package app; public class Main { }");
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            TestSupport.open(app, root);
            Files.writeString(root.resolve("src/main/java/app/Added.java"), "package app; class Added { }");
            TestSupport.open(app, root);
            app.bootMachine();
            var booted = await(app, "cold_booted");
            assertThat(booted.path("source_files").asInt()).as("the second resolution's files").isEqualTo(2);
            Thread.sleep(500);
            assertThat(project(app).path("state").asText()).as("and no second boot").isEqualTo("cold_booted");
        }
    }

    @Test void aMachineThatFailsFailsTheProjectsWaitingForIt() throws Exception {
        var root = MavenFixtures.project(temp.resolve("app"), "");
        var config = TestSupport.config(temp, Duration.ofHours(1));
        // A file where the generation directory goes.
        Files.createDirectories(config.stateDir());
        Files.writeString(config.stateDir().resolve("machine"), "not a directory");
        try (var app = new Application(config)) {
            TestSupport.open(app, root);
            assertThatThrownBy(app::bootMachine).as("a start failure for main").hasMessageContaining("Not a directory");
            assertThat(layered(app).path("machine").path("state").asText()).isEqualTo("failed");
            var project = await(app, "failed");
            assertThat(project.path("error").asText()).contains("MACHINE did not boot");
        }
    }

    @Test void localBootsCanBeTurnedOff() throws Exception {
        var root = MavenFixtures.project(temp.resolve("app"), "");
        System.setProperty("jvmd.layered.local", "false");
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            TestSupport.open(app, root);
            assertThat(layered(app).path("projects").size()).isZero();
        } finally { System.clearProperty("jvmd.layered.local"); }
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
