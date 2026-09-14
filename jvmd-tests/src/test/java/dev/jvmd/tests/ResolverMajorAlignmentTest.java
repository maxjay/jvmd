package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import dev.jvmd.core.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: configured bundle and detected Maven major must agree. */
@Tag("phase-2")
class ResolverMajorAlignmentTest {
    @TempDir Path temp;
    @Test void wrapperMismatchIsReportedAndNeverProducesAMisleadingGraph() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        Path project = MavenFixtures.project(temp.resolve("project"), "");
        Files.writeString(project.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://example.invalid/apache-maven-4.0.0-bin.zip");
        assertThat(new MavenEnvironment(config).versionWarnings(project)).contains("Maven major mismatch: build=4, resolver=3");
        try (var resolver = new MavenResolver(config)) {
            assertThatThrownBy(() -> resolver.resolve(project)).isInstanceOfSatisfying(RpcException.class, e -> assertThat(e.code()).isEqualTo(-32003));
        }
    }
}
