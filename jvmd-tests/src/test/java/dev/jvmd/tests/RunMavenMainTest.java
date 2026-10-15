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
        Files.writeString(root.resolve("app/src/main/java/" + main), source("edited"));
        Files.setLastModifiedTime(root.resolve("app/src/main/java/" + main), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            assertThat(finished(start(app, root, "app.Main")).path("output").asText()).contains("edited");
            assertThat(Files.readAllBytes(built)).as("the build's class file").isEqualTo(before);
            var copies = temp.resolve("state/run-classes/modules");
            try (var found = Files.walk(copies)) { assertThat(found.anyMatch(p -> p.endsWith("app/Main.class"))).as("jvmd's own compile").isTrue(); }

            Files.writeString(root.resolve("app/src/main/java/" + main), source("rebuilt"));
            Files.setLastModifiedTime(root.resolve("app/src/main/java/" + main), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10_000));
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

    private static final String WEAVER = "<build><plugins><plugin><groupId>org.example</groupId><artifactId>weaver-maven-plugin</artifactId><version>1</version>"
            + "<executions><execution><phase>process-classes</phase><goals><goal>weave</goal></goals></execution></executions></plugin></plugins></build>";

    private static void weavingModule(Path root, String name, String dependencies, String file, String text) throws IOException {
        Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve(name + "/pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + PARENT + "<artifactId>" + name + "</artifactId>"
                + "<dependencies>" + dependencies + "</dependencies>" + WEAVER + "</project>");
        var source = root.resolve(name + "/src/main/java/" + file);
        Files.createDirectories(source.getParent());
        Files.writeString(source, text);
    }

    /**
     * A module whose POM runs a goal that rewrites classes after javac (bound to process-classes, as weaving or enhancement is) is left to
     * the build: a run uses its build output while that is current, a hot swap in it is refused, and once a source is newer than the
     * build output a run is refused rather than put a plain compile before the build's classes. A run that names the module in
     * ignore_post_compile compiles and hot swaps it as any other, into a folder of its own: a run on the build's classes never sees it.
     */
    @Test void aModuleWhoseBuildRewritesItsClassesIsLeftToTheBuild() throws Exception {
        var root = reactor("app");
        weavingModule(root, "app", "", "app/Main.java", """
                package app;
                public class Main {
                    static String value() { return new java.io.File("go").exists() ? "lazy " + Lazy.text() : "built"; }
                    public static void main(String[] args) throws Exception { for (int i = 0; i < 2400; i++) { System.out.println(value()); Thread.sleep(25); } }
                }
                """);
        var lazy = root.resolve("app/src/main/java/app/Lazy.java");
        Files.writeString(lazy, "package app; public class Lazy { static String text() { return \"woven\"; } }");
        build(root, "app", List.of());
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            String session = TestSupport.open(app, root);
            var woven = new Run(app, session, call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", true)).path("run_session").asText());
            awaitOutput(woven, "built");
            Files.writeString(lazy, Files.readString(lazy).replace("\"woven\"", "\"jvmd\""));
            Files.setLastModifiedTime(lazy, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            var refused = TestSupport.request(app.dispatcher(), "debug.op", Map.of("session", session, "run_session", woven.id(), "op", "hotswap", "args", Map.of("path", lazy.toString())));
            assertThat(refused.path("error").toString()).as(refused.toPrettyString()).contains("org.example:weaver-maven-plugin:weave@process-classes")
                    .contains("mvn -pl app -am process-classes").contains("ignore_post_compile: [\\\"fixture:app:1\\\"]");
            var blocked = TestSupport.request(app.dispatcher(), "run.start", Map.of("session", session, "target", "app.Main", "debug", false));
            assertThat(blocked.path("error").toString()).as(blocked.toPrettyString()).contains("weaver-maven-plugin").contains("Rebuild");
            var unknown = TestSupport.request(app.dispatcher(), "run.start", Map.of("session", session, "target", "app.Main", "ignore_post_compile", List.of("nothing")));
            assertThat(unknown.path("error").toString()).as(unknown.toPrettyString()).contains("names no module");

            // The woven run is still going on the build's classes; this one compiles the stale module itself.
            var plain = new Run(app, session, call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", true, "ignore_post_compile", List.of("app"))).path("run_session").asText());
            awaitOutput(plain, "built");
            Files.writeString(root.resolve("go"), "");
            awaitOutput(plain, "lazy jvmd");
            awaitOutput(woven, "lazy woven");
            assertThat(call(app, "debug.op", Map.of("session", session, "run_session", woven.id(), "op", "output", "args", Map.of())).path("output").asText())
                    .as("the run on the build's classes never loads jvmd's plain compile").doesNotContain("lazy jvmd");
            var main = root.resolve("app/src/main/java/app/Main.java");
            Files.writeString(main, Files.readString(main).replace("\"lazy \"", "\"swapped \""));
            var swapped = call(app, "debug.op", Map.of("session", session, "run_session", plain.id(), "op", "hotswap", "args", Map.of("path", main.toString())));
            assertThat(swapped.path("restart_required").asBoolean()).as(swapped.toPrettyString()).isFalse();
            awaitOutput(plain, "swapped jvmd");
            call(app, "debug.op", Map.of("session", session, "run_session", plain.id(), "op", "stop", "args", Map.of()));
            call(app, "debug.op", Map.of("session", session, "run_session", woven.id(), "op", "stop", "args", Map.of()));
        }
    }

    /** A dependency whose build rewrites its classes refuses the run of the module that needs it, once its sources are newer than its build output. */
    @Test void aStaleWovenDependencyRefusesTheRun() throws Exception {
        var root = reactor("core", "app");
        weavingModule(root, "core", "", "core/Names.java", "package core; public class Names { public static String name() { return \"core\"; } }");
        module(root, "app", dependency("fixture", "core", null), Map.of("app/Main.java", "package app; public class Main { public static void main(String[] args) { System.out.println(core.Names.name()); } }"));
        build(root, "core", List.of());
        build(root, "app", List.of(root.resolve("core/target/classes")));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            assertThat(finished(start(app, root, "app.Main")).path("output").asText()).contains("core");
            Files.setLastModifiedTime(root.resolve("core/src/main/java/core/Names.java"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            String session = TestSupport.open(app, root);
            var refused = TestSupport.request(app.dispatcher(), "run.start", Map.of("session", session, "target", "app.Main", "debug", false));
            assertThat(refused.path("error").toString()).as(refused.toPrettyString()).contains("fixture:core:1").contains("weaver-maven-plugin").contains("mvn -pl core -am process-classes");
        }
    }

    /** A class only the build has for a module (another compiler's output, a generator's) stays visible when jvmd compiles the module. */
    @Test void aClassOnlyTheBuildHasStaysOnTheModulesClasspath() throws Exception {
        var root = reactor("app");
        module(root, "app", "", Map.of("app/Main.java", "package app; public class Main { public static void main(String[] args) { System.out.println(Extra.text()); } }"));
        var extra = Files.createDirectories(temp.resolve("extra-src/app")).resolve("Extra.java");
        Files.writeString(extra, "package app; public class Extra { public static String text() { return \"from the build only\"; } }");
        var classes = Files.createDirectories(root.resolve("app/target/classes"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), extra.toString())).isZero();
        // Main is newer than the build output, so jvmd compiles the module, against the build's Extra.
        Files.setLastModifiedTime(root.resolve("app/src/main/java/app/Main.java"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            var output = finished(start(app, root, "app.Main"));
            assertThat(output.path("output").asText()).as(output.toPrettyString()).contains("from the build only");
        }
    }

    /** jvmd's compile of a module is redone when a dependency it was compiled against changed, though its own sources did not. */
    @Test void aDependencyChangeRecompilesItsDependents() throws Exception {
        var root = reactor("core", "app");
        module(root, "core", "", Map.of("core/Names.java", "package core; public class Names { public static final String NAME = \"first\"; }"));
        module(root, "app", dependency("fixture", "core", null), Map.of("app/Main.java", "package app; public class Main { public static void main(String[] args) { System.out.println(\"name \" + core.Names.NAME); } }"));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            assertThat(finished(start(app, root, "app.Main")).path("output").asText()).contains("name first");
            var names = root.resolve("core/src/main/java/core/Names.java");
            Files.writeString(names, Files.readString(names).replace("first", "second"));
            Files.setLastModifiedTime(names, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            assertThat(finished(start(app, root, "app.Main")).path("output").asText()).as("the constant is compiled into Main").contains("name second");
        }
    }

    /** A hot swap compiles exactly the files it is given, though javac would compile any other edited source it reaches. */
    @Test void aHotSwapCompilesOnlyTheNamedFiles() throws Exception {
        var root = reactor("app");
        module(root, "app", "", Map.of(
                "app/Other.java", "package app; public class Other { static String text() { return \"other\"; } }",
                "app/Main.java", """
                        package app;
                        public class Main {
                            static String value() { return "one " + Other.text(); }
                            public static void main(String[] args) throws Exception { for (int i = 0; i < 1200; i++) { System.out.println(value()); Thread.sleep(25); } }
                        }
                        """));
        build(root, "app", List.of());
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            String session = TestSupport.open(app, root);
            var run = new Run(app, session, call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", true)).path("run_session").asText());
            awaitOutput(run, "one other");
            var other = root.resolve("app/src/main/java/app/Other.java");
            Files.writeString(other, Files.readString(other).replace("\"other\"", "\"edited\""));
            var main = root.resolve("app/src/main/java/app/Main.java");
            Files.writeString(main, Files.readString(main).replace("\"one \"", "\"two \""));
            var swapped = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "hotswap", "args", Map.of("path", main.toString())));
            assertThat(swapped.path("classes")).as(swapped.toPrettyString()).hasSize(1);
            assertThat(swapped.path("classes").get(0).asText()).isEqualTo("app.Main");
            awaitOutput(run, "two other");

            // Main now calls a method only the edited Other has; Other is not passed, so the program never gets it. The compile fails
            // and nothing is swapped, rather than swap in a Main that cannot link.
            Files.writeString(other, Files.readString(other).replace("static String text()", "static String extra() { return \"extra\"; } static String text()"));
            Files.writeString(main, Files.readString(main).replace("\"two \" + Other.text()", "\"three \" + Other.extra()"));
            var refused = TestSupport.request(app.dispatcher(), "debug.op", Map.of("session", session, "run_session", run.id(), "op", "hotswap", "args", Map.of("path", main.toString())));
            assertThat(refused.path("error").toString()).as(refused.toPrettyString()).contains("extra");
            long written = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "output", "args", Map.of())).path("written").asLong();
            Thread.sleep(300);
            var after = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "output", "args", Map.of("since", written)));
            assertThat(after.path("output").asText()).as("the program runs on").contains("two other").doesNotContain("Error");
            call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "stop", "args", Map.of()));
        }
    }

    /**
     * A hot swap across modules is all or nothing: every module's files are compiled first, a module before its dependents, and then
     * redefined in one call. When one change needs a restart (a method added to a library), no class is redefined, whatever order the
     * files are given in, and the program runs on its old classes rather than call a method it does not have.
     */
    @Test void aHotSwapAcrossModulesRedefinesAllOrNothing() throws Exception {
        var root = reactor("core", "app");
        module(root, "core", "", Map.of("core/Names.java", "package core; public class Names { public static String name() { return \"core\"; } }"));
        module(root, "app", dependency("fixture", "core", null), Map.of("app/Main.java", """
                package app;
                public class Main {
                    static String value() { return "name " + core.Names.name(); }
                    public static void main(String[] args) throws Exception { for (int i = 0; i < 2400; i++) { System.out.println(value()); Thread.sleep(25); } }
                }
                """));
        build(root, "core", List.of());
        build(root, "app", List.of(root.resolve("core/target/classes")));
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            String session = TestSupport.open(app, root);
            var run = new Run(app, session, call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", true)).path("run_session").asText());
            awaitOutput(run, "name core");
            var names = root.resolve("core/src/main/java/core/Names.java");
            Files.writeString(names, Files.readString(names).replace("public static String name()", "public static String extra() { return \"extra\"; } public static String name()"));
            var main = root.resolve("app/src/main/java/app/Main.java");
            Files.writeString(main, Files.readString(main).replace("core.Names.name()", "core.Names.extra()"));
            for (var paths : List.of(List.of(names.toString(), main.toString()), List.of(main.toString(), names.toString()))) {
                var swapped = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "hotswap", "args", Map.of("paths", paths)));
                assertThat(swapped.path("restart_required").asBoolean()).as(swapped.toPrettyString()).isTrue();
                for (var module : swapped.path("modules")) assertThat(module.path("redefined").asInt()).as(swapped.toPrettyString()).isZero();
            }
            long written = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "output", "args", Map.of())).path("written").asLong();
            Thread.sleep(300);
            var after = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "output", "args", Map.of("since", written)));
            assertThat(after.path("alive").asBoolean()).as(after.toPrettyString()).isTrue();
            assertThat(after.path("output").asText()).as("the program runs on its old classes").contains("name core").doesNotContain("Error");
            call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "stop", "args", Map.of()));
        }
    }

    /**
     * A hot swap reaches classes the program has not loaded yet and classes that are new, in a module whose build was current at launch:
     * jvmd's folder for it is on the launch classpath and exists before the JVM starts, which reads a missing directory entry as a jar.
     */
    @Test void aHotSwapReachesNewAndUnloadedClassesOfAModuleTheBuildHadCurrent() throws Exception {
        var root = reactor("app");
        module(root, "app", "", Map.of(
                "app/Lazy.java", "package app; public class Lazy { static String text() { return \"old\"; } }",
                "app/Main.java", """
                        package app;
                        public class Main {
                            static String value() { return new java.io.File("go").exists() ? Lazy.text() : "waiting"; }
                            public static void main(String[] args) throws Exception { for (int i = 0; i < 2400; i++) { System.out.println("value " + value()); Thread.sleep(25); } }
                        }
                        """));
        build(root, "app", List.of());
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(1)))) {
            String session = TestSupport.open(app, root);
            var run = new Run(app, session, call(app, "run.start", Map.of("session", session, "target", "app.Main", "debug", true)).path("run_session").asText());
            awaitOutput(run, "value waiting");
            var lazy = root.resolve("app/src/main/java/app/Lazy.java");
            Files.writeString(lazy, Files.readString(lazy).replace("\"old\"", "\"new\""));
            var helper = root.resolve("app/src/main/java/app/Helper.java");
            Files.writeString(helper, "package app; public class Helper { static String text() { return \"helper\"; } }");
            var main = root.resolve("app/src/main/java/app/Main.java");
            Files.writeString(main, Files.readString(main).replace("? Lazy.text()", "? Lazy.text() + \" \" + Helper.text()"));
            var swapped = call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "hotswap",
                    "args", Map.of("paths", List.of(main.toString(), lazy.toString(), helper.toString()))));
            assertThat(swapped.path("restart_required").asBoolean()).as(swapped.toPrettyString()).isFalse();
            Files.writeString(root.resolve("go"), "");
            awaitOutput(run, "value new helper");
            call(app, "debug.op", Map.of("session", session, "run_session", run.id(), "op", "stop", "args", Map.of()));
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
