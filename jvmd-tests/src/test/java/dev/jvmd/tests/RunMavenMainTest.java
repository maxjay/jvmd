package dev.jvmd.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.dist.Application;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code run.start} on a Maven reactor: a fully qualified main class is found by its source file, with no workspace analysis, and a
 * sibling compiled from source because it was never built is on the run's classpath. The run's output can be read back.
 */
@Tag("phase-2")
class RunMavenMainTest {
    @TempDir Path temp;

    @Test void aMainClassThatUsesAnUnbuiltSiblingRunsAndShowsItsOutput() throws Exception {
        var root = Files.createDirectories(temp.resolve("reactor"));
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId>"
                + "<version>1</version><packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties>"
                + "<modules><module>lib</module><module>app</module></modules></project>");
        var parent = "<parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent>";
        Files.createDirectories(root.resolve("lib/src/main/java/lib"));
        Files.writeString(root.resolve("lib/pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + parent + "<artifactId>lib</artifactId></project>");
        Files.writeString(root.resolve("lib/src/main/java/lib/Greeting.java"), "package lib; public class Greeting { public static String text() { return \"hello from lib\"; } }");
        Files.createDirectories(root.resolve("app/src/main/java/app"));
        Files.writeString(root.resolve("app/pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + parent + "<artifactId>app</artifactId>"
                + "<dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies></project>");
        Files.writeString(root.resolve("app/src/main/java/app/Main.java"), "package app; public class Main { public static void main(String[] args) { System.out.println(lib.Greeting.text()); } }");

        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            String session = TestSupport.open(app, root);
            var run = call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", false));
            String id = run.path("run_session").asText();
            JsonNode output;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            do {
                output = call(app, "debug.op", Map.of("session", session, "run_session", id, "op", "output", "args", Map.of()));
                if (!output.path("alive").asBoolean()) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            assertThat(output.path("alive").asBoolean()).as("the program ended").isFalse();
            assertThat(output.path("exit_code").asInt()).as(output.path("output").asText()).isZero();
            assertThat(output.path("output").asText()).contains("hello from lib");
            assertThat(call(app, "session.status", Map.of("session", session)).path("analysis_contexts").path("context_constructions").asLong())
                    .as("a fully qualified main class needs no analysis of the workspace").isZero();
        }
    }

    private static JsonNode call(Application app, String method, Map<String, ?> params) {
        var response = TestSupport.request(app.dispatcher(), method, params);
        assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
        return response.path("result").path("result");
    }
}
