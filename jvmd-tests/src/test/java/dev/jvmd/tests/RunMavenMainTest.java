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
            for (var m : List.of("core", "lib", "rt", "app"))
                assertThat(root.resolve(m + "/target")).as("jvmd compiles into its own state, never the project's build output").doesNotExist();

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

    /**
     * The build's classes are never written. A module whose sources are newer than its build output runs from jvmd's own compile, and
     * once the build is current again its classes are used and jvmd's copy is dropped.
     */
    @Test void theBuildOutputIsNeverWrittenAndJvmdsCopyGoesOnceTheBuildIsCurrent() throws Exception {
        var root = reactor("app");
        var main = "app/Main.java";
        module(root, "app", "", Map.of(main, source("built")));
        build(root, "app", List.of());
        var built = root.resolve("app/target/classes/app/Main.class");
        byte[] before = Files.readAllBytes(built);
        Thread.sleep(20);
        Files.writeString(root.resolve("app/src/main/java/" + main), source("edited"));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            assertThat(finished(start(app, root, "app.Main")).path("output").asText()).contains("edited");
            assertThat(Files.readAllBytes(built)).as("the build's class file").isEqualTo(before);
            var copies = temp.resolve("state/run-classes/modules");
            try (var found = Files.walk(copies)) { assertThat(found.anyMatch(p -> p.endsWith("app/Main.class"))).as("jvmd's own compile").isTrue(); }

            Files.writeString(root.resolve("app/src/main/java/" + main), source("rebuilt"));
            Thread.sleep(20);
            build(root, "app", List.of());
            assertThat(finished(start(app, root, "app.Main")).path("output").asText()).as("the build is current again").contains("rebuilt");
            try (var found = Files.walk(copies)) { assertThat(found.anyMatch(p -> p.endsWith("app/Main.class"))).as("jvmd's copy is dropped").isFalse(); }
        }
    }

    /** A hot swap in a Maven module compiles into jvmd's folder too, and the running program picks the change up. */
    @Test void aHotSwapLeavesTheBuildOutputAlone() throws Exception {
        var root = reactor("app");
        module(root, "app", "", Map.of("app/Main.java", """
                package app;
                public class Main {
                    static String value() { return "one"; }
                    public static void main(String[] args) throws Exception {
                        for (int i = 0; i < 1200; i++) { System.out.println("value " + value()); Thread.sleep(25); }
                    }
                }
                """));
        build(root, "app", List.of());
        var built = root.resolve("app/target/classes/app/Main.class");
        byte[] before = Files.readAllBytes(built);
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            String session = TestSupport.open(app, root);
            var run = new Run(app, session, call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", true)).path("run_session").asText());
            awaitOutput(run, "value one");
            var file = root.resolve("app/src/main/java/app/Main.java");
            Files.writeString(file, Files.readString(file).replace("\"one\"", "\"two\""));
            var swapped = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "hotswap", "args", Map.of("path", file.toString())));
            assertThat(swapped.path("restart_required").asBoolean()).as(swapped.toPrettyString()).isFalse();
            awaitOutput(run, "value two");
            assertThat(Files.readAllBytes(built)).as("the build's class file").isEqualTo(before);
            call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "stop", "args", Map.of()));
        }
    }

    /**
     * A class the build rewrote after javac (here, a method added to Entity, as weaving or enhancement would) is the build's: the
     * daemon finds it by comparing the build output with LOCAL, a run keeps the build's version when it compiles the module, a hot swap
     * of its file is refused, and a run is refused once its source has changed since the build.
     */
    @Test void classesTheBuildRewroteAreLeftToTheBuild() throws Exception {
        var root = reactor("app");
        module(root, "app", "", Map.of(
                "app/Entity.java", "package app; public class Entity { public String name() { return \"entity\"; } }",
                "app/Main.java", """
                        package app;
                        public class Main {
                            static String value() { return "one"; }
                            public static void main(String[] args) throws Exception {
                                boolean woven = java.util.Arrays.stream(Entity.class.getMethods()).anyMatch(m -> m.getName().equals("_woven"));
                                System.out.println("woven=" + woven + " " + value());
                                for (int i = 0; i < 1200; i++) Thread.sleep(25);
                            }
                        }
                        """));
        build(root, "app", List.of());
        var entityClass = root.resolve("app/target/classes/app/Entity.class");
        var file = java.lang.classfile.ClassFile.of();
        Files.write(entityClass, file.transformClass(file.parse(Files.readAllBytes(entityClass)), java.lang.classfile.ClassTransform.endHandler(b ->
                b.withMethodBody("_woven", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void), java.lang.classfile.ClassFile.ACC_PUBLIC, code -> code.return_()))));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            app.bootMachine();
            String session = TestSupport.open(app, root);
            JsonNode project = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (System.nanoTime() < deadline) {
                var projects = TestSupport.request(app.dispatcher(), "daemon.status", Map.of()).path("result").path("result").path("layered_index").path("projects");
                project = projects.elements().hasNext() ? projects.elements().next() : null;
                if (project != null && project.path("state").asText().equals("cold_booted")) break;
                Thread.sleep(50);
            }
            assertThat(project.path("rewritten_by_build").path("app").path("types").asInt()).as(project.toPrettyString()).isEqualTo(1);
            assertThat(project.path("rewritten_by_build").path("app").path("examples").get(0).asText()).isEqualTo("app/Entity");

            // Main changed since the build: the module is compiled by jvmd, but the build's Entity is kept.
            var main = root.resolve("app/src/main/java/app/Main.java");
            Thread.sleep(20);
            Files.writeString(main, Files.readString(main).replace("\"one\"", "\"two\""));
            var run = new Run(app, session, call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", true)).path("run_session").asText());
            awaitOutput(run, "woven=true two");

            var entity = root.resolve("app/src/main/java/app/Entity.java");
            var refused = TestSupport.request(app.dispatcher(), "debug.op", Map.of("session", session, "run_session", run.id(), "op", "hotswap", "args", Map.of("path", entity.toString())));
            assertThat(refused.path("error").toString()).as(refused.toPrettyString()).contains("rewrites").contains("Entity.java");
            Files.writeString(main, Files.readString(main).replace("\"two\"", "\"three\""));
            var swapped = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "hotswap", "args", Map.of("path", main.toString())));
            assertThat(swapped.path("restart_required").asBoolean()).as(swapped.toPrettyString()).isFalse();
            call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "stop", "args", Map.of()));

            // Entity changed since the build: jvmd cannot make the build's version of it, so it does not run a plain one.
            Thread.sleep(20);
            Files.writeString(entity, Files.readString(entity).replace("\"entity\"", "\"changed\""));
            var blocked = TestSupport.request(app.dispatcher(), "run.start", Map.of("session", session, "target", "app.Main", "debug", false));
            assertThat(blocked.path("error").toString()).as(blocked.toPrettyString()).contains("changed since the build").contains("Rebuild");
        }
    }

    private static String source(String text) {
        return "package app; public class Main { public static void main(String[] args) { System.out.println(\"" + text + "\"); } }";
    }

    private static void awaitOutput(Run run, String text) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var output = call(run.app(), "debug.op", Map.of("session", run.session(), "run_session", run.id(), "op", "output", "args", Map.of()));
            if (output.path("output").asText().contains(text)) return;
            Thread.sleep(25);
        }
        throw new AssertionError("no \"" + text + "\" in the output");
    }

    private static JsonNode call(Application app, String method, Map<String, ?> params) {
        var response = TestSupport.request(app.dispatcher(), method, params);
        assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
        return response.path("result").path("result");
    }
}
