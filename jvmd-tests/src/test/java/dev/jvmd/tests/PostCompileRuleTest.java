package dev.jvmd.tests;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.resolver.MavenResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which plugin goals of a module's build count as rewriting its classes after javac ({@code Resolution.postCompile}), read from the
 * effective model: inherited and profile-activated plugins count, a mojo's default phase counts when its descriptor is in the local
 * repository, and an execution bound to {@code none}, a pluginManagement-only execution and the known goals that only write beside the
 * classes do not.
 */
@Tag("phase-6")
class PostCompileRuleTest {
    @TempDir Path temp;

    private static String plugin(String group, String artifact, String phase, String goal) {
        return "<plugin>" + (group == null ? "" : "<groupId>" + group + "</groupId>") + "<artifactId>" + artifact + "</artifactId><version>1</version>"
                + "<executions><execution><id>x</id>" + (phase == null ? "" : "<phase>" + phase + "</phase>") + "<goals><goal>" + goal + "</goal></goals></execution></executions></plugin>";
    }

    private static void pom(Path directory, String parent, String artifact, String packaging, String body) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>fixture</groupId><artifactId>" + parent + "</artifactId><version>1</version>"
                + (parent.equals("parent") ? "" : "<relativePath>..</relativePath>") + "</parent>"
                + "<artifactId>" + artifact + "</artifactId>" + (packaging == null ? "" : "<packaging>" + packaging + "</packaging>") + body + "</project>");
    }

    @Test void theRuleReadsTheEffectiveModel() throws Exception {
        var root = Files.createDirectories(temp.resolve("reactor"));
        var modules = List.of("plain", "bundle", "antrun", "disabled", "managed", "profile", "family", "defaulted");
        var list = new StringBuilder();
        for (var m : modules) list.append("<module>").append(m).append("</module>");
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId>"
                + "<version>1</version><packaging>pom</packaging><modules>" + list + "</modules></project>");
        pom(root.resolve("plain"), "parent", "plain", null, "");
        pom(root.resolve("bundle"), "parent", "bundle", null, "<build><plugins>" + plugin("org.apache.felix", "maven-bundle-plugin", "process-classes", "manifest") + "</plugins></build>");
        pom(root.resolve("antrun"), "parent", "antrun", null, "<build><plugins>" + plugin(null, "maven-antrun-plugin", "process-classes", "run") + "</plugins></build>");
        pom(root.resolve("disabled"), "parent", "disabled", null, "<build><plugins>" + plugin("org.jacoco", "jacoco-maven-plugin", "none", "instrument") + "</plugins></build>");
        pom(root.resolve("managed"), "parent", "managed", null, "<build><pluginManagement><plugins>" + plugin("org.example", "weaver-maven-plugin", "process-classes", "weave") + "</plugins></pluginManagement></build>");
        pom(root.resolve("profile"), "parent", "profile", null, "<profiles><profile><id>weave</id><activation><activeByDefault>true</activeByDefault></activation><build><plugins>"
                + plugin("org.example", "weaver-maven-plugin", "process-classes", "weave") + "</plugins></build></profile></profiles>");
        // A parent that weaves, and its child, which inherits the plugin.
        pom(root.resolve("family"), "parent", "family", "pom", "<modules><module>child</module></modules><build><plugins>"
                + plugin("org.example", "weaver-maven-plugin", "process-classes", "weave") + "</plugins></build>");
        pom(root.resolve("family/child"), "family", "child", null, "");
        // A goal with no phase in the POM, whose mojo defaults to process-classes in its descriptor.
        pom(root.resolve("defaulted"), "parent", "defaulted", null, "<build><plugins>" + plugin("org.example", "transformer-maven-plugin", null, "transform") + "</plugins></build>");
        // Resolved once before the plugin is in the local repository: the goal's phase is unknown, so it does not count yet.
        try (var resolver = new MavenResolver(TestSupport.config(temp, Duration.ofHours(1)))) {
            assertThat(resolver.resolve(root).postCompile().get("fixture:defaulted:1")).isEmpty();
        }
        var jar = Files.createDirectories(temp.resolve("repository/org/example/transformer-maven-plugin/1")).resolve("transformer-maven-plugin-1.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/maven/plugin.xml"));
            out.write(("<plugin><groupId>org.example</groupId><artifactId>transformer-maven-plugin</artifactId><mojos>"
                    + "<mojo><goal>check</goal><phase>verify</phase></mojo><mojo><goal>transform</goal><phase>process-classes</phase></mojo></mojos></plugin>").getBytes());
        }
        // Once it is there (a Maven build downloaded it), the cached resolution is stale and the goal counts.
        try (var resolver = new MavenResolver(TestSupport.config(temp, Duration.ofHours(1)))) {
            var rule = resolver.resolve(root).postCompile();
            assertThat(rule).containsAllEntriesOf(Map.of(
                    "fixture:plain:1", List.of(),
                    "fixture:bundle:1", List.of(),
                    "fixture:antrun:1", List.of("org.apache.maven.plugins:maven-antrun-plugin:run@process-classes"),
                    "fixture:disabled:1", List.of(),
                    "fixture:managed:1", List.of(),
                    "fixture:profile:1", List.of("org.example:weaver-maven-plugin:weave@process-classes"),
                    "fixture:child:1", List.of("org.example:weaver-maven-plugin:weave@process-classes"),
                    "fixture:defaulted:1", List.of("org.example:transformer-maven-plugin:transform@process-classes")));
        }
    }
}
