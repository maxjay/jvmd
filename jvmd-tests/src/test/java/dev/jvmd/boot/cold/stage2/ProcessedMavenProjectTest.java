package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.boot.provider.MavenModel;
import dev.jvmd.core.Config;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.resolver.MavenResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Annotation processors in header compilation (amendments 4.8), against Maven's own class output: Lombok adds members and nested
 * types to the declarations, reads the lombok.config above the sources, and hibernate-jpamodelgen writes a metamodel type that a
 * header of the same module refers to. Every source leaf has exactly the k and a of the classes Maven compiled.
 */
@Tag("phase-3")
class ProcessedMavenProjectTest {
    @AfterAll static void release() { Stage2Support.release(); }

    static void generate(Path root) throws IOException {
        var files = new LinkedHashMap<String, String>();
        files.put("pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>corp.proc</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>lib</module><module>core</module></modules>
                  <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <build><pluginManagement><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version>
                      <configuration><proc>full</proc><parameters>true</parameters></configuration></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.8.1</version></plugin>
                  </plugins></pluginManagement></build>
                </project>
                """);
        // Read by Lombok from the source file's directory upwards. Both settings differ from Lombok's defaults, so a compilation that
        // misses the file is caught: fluent accessors change the API (x() for getX(), so k), and no @lombok.Generated changes a.
        files.put("lombok.config", "lombok.accessors.fluent = true\nlombok.addLombokGeneratedAnnotation = false\n");
        var lombok = "<dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.48</version><scope>provided</scope></dependency>";
        var jpa = """
                <dependency><groupId>jakarta.persistence</groupId><artifactId>jakarta.persistence-api</artifactId><version>3.1.0</version></dependency>
                <dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-jpamodelgen</artifactId><version>6.6.53.Final</version><scope>provided</scope></dependency>
                """;
        files.put("lib/pom.xml", module("lib", jpa + lombok));
        files.put("core/pom.xml", module("core", """
                <dependency><groupId>corp.proc</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                """ + jpa + lombok));
        files.put("lib/src/main/java/lib/Point.java", """
                package lib;
                @lombok.Value @lombok.Builder
                public class Point { int x; int y; String label; }
                """);
        files.put("lib/src/main/java/lib/Settings.java", """
                package lib;
                @lombok.Getter @lombok.Setter @lombok.NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
                public class Settings { private String name; private int size; public static Settings create() { return new Settings(); } }
                """);
        // The mapped superclass is in the sibling: core's metamodel extends Base_ only if the processor sees @MappedSuperclass on Base,
        // which it reads from lib's stub.
        files.put("lib/src/main/java/lib/Base.java", """
                package lib;
                @jakarta.persistence.MappedSuperclass
                public abstract class Base { @jakarta.persistence.Id protected Long id; protected String tenant; }
                """);
        files.put("core/src/main/java/core/Item.java", """
                package core;
                import jakarta.persistence.Entity;
                @Entity @lombok.Getter
                public class Item extends lib.Base { String name; int count; }
                """);
        files.put("core/src/main/java/core/Uses.java", """
                package core;
                public class Uses {
                    public lib.Point.PointBuilder builder;     // a type Lombok generates in another module, in a header
                    public Class<Item_> meta = Item_.class;    // a type a processor writes in this module, in a header
                    public int x(lib.Point p) { return p.x(); }
                }
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
                  <parent><groupId>corp.proc</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>%s</artifactId>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(name, dependencies);
    }

    @Test void processorsRunInHeaderCompilationAndMatchMavensOwnClassOutput() throws Exception {
        var digest = Sha256.INSTANCE;
        var root = Files.createTempDirectory("stage2-processed");
        var work = Files.createTempDirectory("stage2-processed-out");
        var state = Files.createTempDirectory("stage2-processed-state");
        try {
            generate(root);
            var repository = MavenProjectTest.repository();
            var jdk = Path.of(System.getProperty("java.home"));
            var config = new Config(jdk, null, repository, 3, Duration.ofHours(1), 2048, false, state, state.resolve("socket"));
            MavenModel.Result provided;
            try (var resolver = new MavenResolver(config)) { provided = MavenModel.of(resolver.resolve(root), repository, jdk); }
            var model = provided.parse();
            assertThat(model.modules()).allSatisfy(m -> assertThat(m.main().processing().mode()).isEqualTo("full"));

            var maven = MavenModelHelper.build(root, repository, 17); // Maven compiles the reactor: its class output is the oracle
            var machine = Stage2Support.jdkOnly(digest).copy();
            var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 4, repository, ClassFacts::of).run(machine, model);
            assertThat(result.faults()).as("faults").isEmpty();

            var classStore = new InMemoryLocalStore();
            var expected = MavenProjectTest.outputLeaves(digest, maven, work, classStore);
            for (var key : List.of("lib/main", "core/main")) {
                var source = MachineLeaf.decode(machine.get(MachineStore.leafKey(result.leaves().get(key))), digest.width());
                var javac = expected.get(key);
                assertThat(source.typeCount()).as("types of %s, generated ones included", key).isEqualTo(javac.typeCount());
                assertThat(source.factCount()).as("facts of %s, generated members included", key).isEqualTo(javac.factCount());
                assertThat(source.k()).as("k of %s", key).isEqualTo(javac.k());
                var binaryA = MachineTree.decodePath(classStore.get(MachineStore.pathKey(key + ".jar")), digest.width()).a();
                assertThat(result.annotations().get(key)).as("a of %s: lombok.config's settings applied", key).isEqualTo(binaryA);
            }
        } finally { Stage2Support.delete(root); Stage2Support.delete(work); Stage2Support.delete(state); }
    }
}
