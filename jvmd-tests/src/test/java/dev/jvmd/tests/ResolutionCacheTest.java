package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: graph cache keyed by root, parents and settings content. */
@Tag("phase-2")
class ResolutionCacheTest {
    @TempDir Path temp;
    @Test void diskCacheSurvivesRestartAndParentAndSettingsEditsInvalidateIt() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        Path project = MavenFixtures.project(temp.resolve("project"), "");
        Path parent = temp.resolve("pom.xml");
        Files.writeString(parent, "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><properties><java.version>17</java.version></properties></project>");
        Files.writeString(project.resolve("pom.xml"), Files.readString(project.resolve("pom.xml")).replace("</modelVersion>", "</modelVersion><parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent>"));
        Path settings = temp.resolve("settings.xml"); Files.writeString(settings, "<settings/>");
        var environment = new MavenEnvironment(config, settings);
        String fingerprint;
        try (var resolver = new MavenResolver(config, environment)) { fingerprint = resolver.resolve(project).fingerprint(); }
        try (var resolver = new MavenResolver(config, environment)) {
            assertThat(resolver.resolve(project).cached()).isTrue();
            Files.writeString(parent, Files.readString(parent).replace("<java.version>17", "<java.version>21"));
            var parentChanged = resolver.resolve(project);
            assertThat(parentChanged.cached()).isFalse();
            assertThat(parentChanged.modules().getFirst().release()).isEqualTo("21");
            Files.writeString(settings, "<settings> </settings>");
            var changed = resolver.resolve(project);
            assertThat(changed.cached()).isFalse();
            assertThat(changed.fingerprint()).isNotEqualTo(fingerprint);
            var originalTime = Files.getLastModifiedTime(project.resolve("pom.xml"));
            String original = Files.readString(project.resolve("pom.xml"));
            Files.writeString(project.resolve("pom.xml"), original.replace("<artifactId>app</artifactId><version>1</version>", "<artifactId>app</artifactId><version>2</version>"));
            Files.setLastModifiedTime(project.resolve("pom.xml"), originalTime);
            assertThat(resolver.resolve(project).modules().getFirst().gav()).isEqualTo("fixture:app:2");
        }
    }
}
