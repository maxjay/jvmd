package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The integration test of stage 2: a real multi-module Maven project, its model obtained from Maven itself by the test-only helper
 * ({@link MavenModelHelper}), booted end to end, and every module and scope compared with the class files Maven's own javac wrote
 * (invariant 7.3.1). Dependencies come from the developer's local repository and are mostly not in MACHINE, so this is also 3.15 at
 * the scale of a real classpath.
 */
@Tag("phase-3")
class MavenProjectTest {
    static Stream<Object[]> cases() {
        return Stream.of(Sha256.INSTANCE, new Digests.Sha3()).flatMap(d -> Stream.of(Stage2Support.FEATURE, 21).map(release -> new Object[] {d, release}));
    }

    @AfterAll static void release() { Stage2Support.release(); }

    static Path repository() {
        var configured = System.getProperty("maven.repo.local");
        return configured != null ? Path.of(configured) : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /** Three modules: a library on Jackson, a core module that is the whole rich fixture plus a use of the library, an application with tests. */
    static void generate(Path root, int release) throws IOException {
        var files = new LinkedHashMap<String, String>();
        files.put("pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>corp.it</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>lib</module><module>core</module><module>app</module></modules>
                  <properties><maven.compiler.release>%d</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <build><pluginManagement><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.8.1</version></plugin>
                  </plugins></pluginManagement></build>
                </project>
                """.formatted(release));
        files.put("lib/pom.xml", module("lib", """
                <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.22.2</version></dependency>"""));
        files.put("core/pom.xml", module("core", """
                <dependency><groupId>corp.it</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>"""));
        files.put("app/pom.xml", module("app", """
                <dependency><groupId>corp.it</groupId><artifactId>core</artifactId><version>1.0</version></dependency>
                <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.14.4</version><scope>test</scope></dependency>"""));
        files.put("lib/src/main/java/lib/Json.java", """
                package lib;
                import com.fasterxml.jackson.annotation.JsonProperty;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import java.util.Map;
                public class Json {
                    public static final ObjectMapper MAPPER = new ObjectMapper();
                    public record Field(@JsonProperty("n") String name, int value) { }
                    public static <T> T read(String text, Class<T> type) throws com.fasterxml.jackson.core.JsonProcessingException { return MAPPER.readValue(text, type); }
                    public static Map<String, Object> asMap(Object value) { return MAPPER.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
                }
                """);
        // lib and core are JPMS modules: Maven compiles them in module mode and writes a module-info.class, whose descriptor fact stage 2
        // reads from the parsed module-info.java (E.3). The rich fixture brings its own descriptor, which is replaced by core's.
        files.put("lib/src/main/java/module-info.java", "module corp.lib { requires transitive com.fasterxml.jackson.databind; exports lib; }");
        files.put("core/src/main/java/module-info.java", "module corp.core { requires transitive corp.lib; requires java.logging; exports core; exports fx; }");
        for (var e : Fixtures.rich().entrySet()) if (!e.getKey().equals("module-info.java")) files.put("core/src/main/java/" + e.getKey(), e.getValue());
        files.put("core/src/main/java/core/Service.java", """
                package core;
                import fx.Box;
                import lib.Json;
                public class Service<T extends Comparable<T>> extends Box<T> {
                    public Json.Field field() { return new Json.Field("x", 1); }
                    public <V> V parse(String text, Class<V> type) throws com.fasterxml.jackson.core.JsonProcessingException { return Json.read(text, type); }
                }
                """);
        files.put("app/src/main/java/app/Main.java", "package app; public class Main { core.Service<String> service; public static void main(String[] args) { } }");
        files.put("app/src/test/java/app/MainTest.java", """
                package app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                class MainTest { @Test void exists() { assertNotNull(new Main()); } }
                """);
        for (var e : files.entrySet()) {
            var file = root.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
    }

    private static String module(String name, String dependencies) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>corp.it</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>%s</artifactId>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(name, dependencies);
    }

    /** The leaves of Maven's own class output of every module and scope, indexed by stage 1 into a store of their own. */
    static Map<String, MachineLeaf> outputLeaves(Digest digest, MavenModelHelper.Model model, Path work) throws IOException {
        return outputLeaves(digest, model, work, new InMemoryLocalStore());
    }

    /** As above, into {@code store}, so that the caller can read the nodes under the leaves. */
    static Map<String, MachineLeaf> outputLeaves(Digest digest, MavenModelHelper.Model model, Path work, InMemoryLocalStore store) throws IOException {
        var locations = new ArrayList<Enumerate.Location>();
        var names = new LinkedHashMap<String, String>();
        for (var m : model.modules()) {
            if (m.packaging().equals("pom")) continue;
            for (var scope : new String[] {"main", "test"}) {
                var dir = scope.equals("main") ? m.classes() : m.testClasses();
                var entries = new LinkedHashMap<String, byte[]>();
                if (Files.isDirectory(dir)) try (var walk = Files.walk(dir)) {
                    for (var p : walk.filter(p -> p.toString().endsWith(".class")).sorted().toList())
                        entries.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
                var name = m.name() + "/" + scope + ".jar";
                var jar = Stage2Support.pack(work.resolve(name), entries);
                locations.add(Enumerate.jar(name, jar));
                names.put(m.name() + "/" + scope, name);
            }
        }
        new Stage1(digest, new ContentTree(digest), Stage2Support.FEATURE, 4, ClassFacts::of).run(store, locations);
        var out = new LinkedHashMap<String, MachineLeaf>();
        for (var e : names.entrySet()) {
            var k = MachineTree.decodePath(store.get(MachineStore.pathKey(e.getValue())), digest.width()).k();
            out.put(e.getKey(), MachineLeaf.decode(store.get(MachineStore.leafKey(k)), digest.width()));
        }
        return out;
    }

    @ParameterizedTest @MethodSource("cases")
    void aColdBootOverARealMavenProjectMatchesMavensOwnClassOutput(Digest digest, int release) throws Exception {
        var root = Files.createTempDirectory("stage2-maven");
        var work = Files.createTempDirectory("stage2-maven-out");
        try {
            generate(root, release);
            var model = MavenModelHelper.build(root, repository(), release);
            var machine = Stage2Support.jdkOnly(digest).copy();
            var parsed = ProjectModel.parse(model.json());
            var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 4, repository(), ClassFacts::of).run(machine, parsed);

            assertThat(result.faults()).as("faults").isEmpty();
            assertThat(result.modules()).isEqualTo(3);
            assertThat(result.indexedOnTheSpot()).as("dependencies MACHINE never saw were indexed on the spot").isGreaterThan(5);
            var maven = outputLeaves(digest, model, work);
            int equal = 0;
            for (var e : maven.entrySet()) {
                var source = MachineLeaf.decode(machine.get(MachineStore.leafKey(result.leaves().get(e.getKey()))), digest.width());
                var expected = e.getValue();
                assertThat(source.factCount()).as("facts of %s", e.getKey()).isEqualTo(expected.factCount());
                assertThat(source.r()).as("r of %s", e.getKey()).isEqualTo(expected.r());
                assertThat(source.oHash()).as("O root of %s", e.getKey()).isEqualTo(expected.oHash());
                assertThat(source.nHash()).as("N root of %s", e.getKey()).isEqualTo(expected.nHash());
                assertThat(source.eHash()).as("E root of %s", e.getKey()).isEqualTo(expected.eHash());
                assertThat(source.eSum()).as("E sum of %s", e.getKey()).isEqualTo(expected.eSum());
                equal++;
            }
            assertThat(equal).isEqualTo(6);
            assertThat(machine.hasLocalRoot(Stage2.projectKey(digest, parsed))).isTrue();
        } finally { Stage2Support.delete(root); Stage2Support.delete(work); }
    }
}
