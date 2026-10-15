package dev.jvmd.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.dist.Application;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code run.start} on a Maven reactor: a fully qualified main class is found by its source file, with no workspace analysis; the
 * classpath is Maven's runtime classpath in Maven's order, with every reactor module in its place, built or not; and the run's output
 * can be read back.
 */
@Tag("phase-2")
class RunMavenMainTest {
    @TempDir Path temp;

    private static final String PARENT = "<parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent>";

    private Path reactor(String... modules) throws IOException {
        var root = Files.createDirectories(temp.resolve("reactor"));
        var list = new StringBuilder();
        for (var m : modules) list.append("<module>").append(m).append("</module>");
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId>"
                + "<version>1</version><packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties>"
                + "<modules>" + list + "</modules></project>");
        return root;
    }

    private static void module(Path root, String name, String dependencies, Map<String, String> sources) throws IOException {
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name + "/pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + PARENT + "<artifactId>" + name + "</artifactId>"
                + "<dependencies>" + dependencies + "</dependencies></project>");
        for (var e : sources.entrySet()) {
            var file = root.resolve(name + "/src/main/java/" + e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
    }

    private static String dependency(String group, String artifact, String scope) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>1</version>"
                + (scope == null ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
    }

    /** Builds a module the way Maven would have: its classes in target/classes, newer than its sources. */
    private static void build(Path root, String name, List<Path> classpath) throws IOException {
        var out = Files.createDirectories(root.resolve(name + "/target/classes"));
        var files = new ArrayList<String>(List.of("-d", out.toString(), "-cp", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList())));
        try (var walk = Files.walk(root.resolve(name + "/src/main/java"))) { walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString())); }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, files.toArray(String[]::new))).isZero();
    }

    private record Run(Application app, String session, String id) { }

    private Run start(Application app, Path root, String target) {
        String session = TestSupport.open(app, root);
        var run = call(app, "run.start", Map.of("session", session, "target", target, "debug", false));
        return new Run(app, session, run.path("run_session").asText());
    }

    private static JsonNode finished(Run run) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonNode output;
        do {
            output = call(run.app(), "debug.op", Map.of("session", run.session(), "run_session", run.id(), "op", "output", "args", Map.of()));
            if (!output.path("alive").asBoolean()) return output;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("the program did not end: " + output.toPrettyString());
    }

    private static long contexts(Run run) {
        return call(run.app(), "session.status", Map.of("session", run.session())).path("analysis_contexts").path("context_constructions").asLong();
    }

    @Test void unbuiltSiblingsOfEveryRuntimeScopeAreCompiledAndOnTheClasspath() throws Exception {
        var root = reactor("core", "lib", "rt", "app");
        module(root, "core", "", Map.of("core/Core.java", "package core; public class Core { public static String name() { return \"core\"; } }"));
        module(root, "lib", dependency("fixture", "core", null), Map.of("lib/Greeting.java", "package lib; public class Greeting { public static String text() { return \"hello from lib and \" + core.Core.name(); } }"));
        module(root, "rt", "", Map.of("rt/Plugin.java", "package rt; public class Plugin { public String toString() { return \"runtime plugin\"; } }"));
        module(root, "app", dependency("fixture", "lib", null) + dependency("fixture", "rt", "runtime"), Map.of("app/Main.java", """
                package app;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println(lib.Greeting.text());
                        System.out.println(Class.forName("rt.Plugin").getDeclaredConstructor().newInstance());
                    }
                    public static class Nested { public static void main(String[] args) { System.out.println("nested main"); } }
                }
                """));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            var run = start(app, root, "app.Main");
            var output = finished(run);
            assertThat(output.path("exit_code").asInt()).as(output.path("output").asText()).isZero();
            assertThat(output.path("output").asText()).contains("hello from lib and core", "runtime plugin");
            assertThat(output.path("complete").asBoolean()).isTrue();
            assertThat(contexts(run)).as("a fully qualified main class needs no analysis of the workspace").isZero();

            var nested = start(app, root, "app.Main$Nested");
            assertThat(finished(nested).path("output").asText()).contains("nested main");
            assertThat(contexts(nested)).isZero();
        }
    }

    /** Maven's order decides which class wins: a jar listed before a built sibling shadows the sibling's class, as Maven runs it. */
    @Test void theClasspathKeepsMavensOrder() throws Exception {
        var repository = temp.resolve("repository");
        var jar = Files.createDirectories(repository.resolve("ext/shadow/1")).resolve("shadow-1.jar");
        Files.writeString(repository.resolve("ext/shadow/1/shadow-1.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>ext</groupId><artifactId>shadow</artifactId><version>1</version></project>");
        var jarSource = Files.createDirectories(temp.resolve("jar-src/shared")).resolve("Which.java");
        Files.writeString(jarSource, "package shared; public class Which { public static String which() { return \"from the jar\"; } }");
        var jarClasses = Files.createDirectories(temp.resolve("jar-classes"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", jarClasses.toString(), jarSource.toString())).isZero();
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("shared/Which.class"));
            out.write(Files.readAllBytes(jarClasses.resolve("shared/Which.class")));
        }
        var root = reactor("lib", "app");
        module(root, "lib", "", Map.of("shared/Which.java", "package shared; public class Which { public static String which() { return \"from the sibling\"; } }"));
        build(root, "lib", List.of());
        module(root, "app", dependency("ext", "shadow", null) + dependency("fixture", "lib", null),
                Map.of("app/Main.java", "package app; public class Main { public static void main(String[] args) { System.out.println(shared.Which.which()); } }"));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            var output = finished(start(app, root, "app.Main"));
            assertThat(output.path("output").asText()).as(output.toPrettyString()).contains("from the jar");
        }
    }

    @Test void aNameItCannotPlaceGoesThroughTheAnalyzerAndOutputCanBeFollowed() throws Exception {
        var root = reactor("app");
        module(root, "app", "", Map.of("app/Main.java", """
                package app;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        for (int i = 0; i < 3; i++) System.out.println("line " + i);
                        Thread.sleep(1500);
                        System.out.println("last");
                        System.exit(3);
                    }
                }
                """));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            // A nested class the file does not declare is not launched blindly: the analyzer finds nothing.
            String session = TestSupport.open(app, root);
            var missing = TestSupport.request(app.dispatcher(), "run.start", Map.of("session", session, "target", "app.Main$Missing", "debug", false));
            assertThat(missing.path("result").path("result").has("run_session")).as(missing.toPrettyString()).isFalse();

            var run = start(app, root, "Main");
            assertThat(contexts(run)).as("a short name is found by analysis").isPositive();
            JsonNode first;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            do {
                first = call(app, "debug.op", Map.of("session", run.session(), "run_session", run.id(), "op", "output", "args", Map.of()));
                Thread.sleep(20);
            } while (!first.path("output").asText().contains("line 2") && System.nanoTime() < deadline);
            assertThat(first.path("alive").asBoolean()).as("still sleeping").isTrue();
            assertThat(first.has("exit_code")).isFalse();
            var rest = finished(run);
            var since = call(app, "debug.op", Map.of("session", run.session(), "run_session", run.id(), "op", "output",
                    "args", Map.of("since", first.path("written").asLong())));
            assertThat(since.path("output").asText()).isEqualTo("last\n");
            assertThat(rest.path("exit_code").asInt()).isEqualTo(3);
            var tail = call(app, "debug.op", Map.of("session", run.session(), "run_session", run.id(), "op", "output", "args", Map.of("tail", 5)));
            assertThat(tail.path("output").asText()).isEqualTo("last\n");
            assertThat(tail.path("truncated").asBoolean()).isTrue();
        }
    }

    private static JsonNode call(Application app, String method, Map<String, ?> params) {
        var response = TestSupport.request(app.dispatcher(), method, params);
        assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
        return response.path("result").path("result");
    }
}
