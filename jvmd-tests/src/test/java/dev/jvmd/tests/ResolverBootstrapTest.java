package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import dev.jvmd.core.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: RepositorySystemSupplier and simple local repository. */
@Tag("phase-2")
class ResolverBootstrapTest {
    @TempDir Path temp;
    @Test void effectiveModelInheritsPropertiesAndResolvesAnUnmarkedLocalArtifact() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        Path project = Files.createDirectory(temp.resolve("project"));
        Path parent = temp.resolve("pom.xml");
        Files.writeString(parent, "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><properties><dependency.version>1</dependency.version></properties></project>");
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>app</artifactId><dependencies><dependency><groupId>fixture</groupId><artifactId>lib</artifactId><version>${dependency.version}</version></dependency></dependencies></project>");
        Path lib = Files.createDirectories(config.m2Repo().resolve("fixture/lib/1"));
        Files.writeString(lib.resolve("lib-1.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>lib</artifactId><version>1</version></project>");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(lib.resolve("lib-1.jar")))) { }
        Files.writeString(lib.resolve("_remote.repositories"), "lib-1.jar>unlisted-private-repo=\nlib-1.pom>unlisted-private-repo=\n");
        try (var resolver = new MavenResolver(config)) {
            var result = resolver.resolve(project);
            assertThat(result.offline()).isTrue();
            assertThat(result.modules().getFirst().gav()).isEqualTo("fixture:app:1");
            assertThat(result.classpath()).contains(lib.resolve("lib-1.jar").toString());
            assertThat(result.nodes()).anyMatch(n -> n.gav().equals("fixture:lib:1") && n.winner() == null);
            var cached = resolver.resolve(project);
            assertThat(cached.cached()).isTrue();
            assertThat(cached.fingerprint()).isEqualTo(result.fingerprint());
        }
    }
}
