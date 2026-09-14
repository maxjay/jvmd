package dev.jvmd.tests;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: trained daemon cache loads in strict AOT mode. */
@Tag("phase-1")
class AotTrainingScriptTest {
    @Test void daemonTrainingWorkloadRunsWithStrictCache() throws Exception {
        var image = TestSupport.repo().resolve("jvmd-dist/target/image");
        assertThat(image.resolve("lib/jvmd/jvmd.aot")).isRegularFile();
        var log = Files.createTempFile("jvmd-strict-", ".log");
        var process = new ProcessBuilder(image.resolve("bin/java").toString(),
                "-XX:AOTCache=" + image.resolve("lib/jvmd/jvmd.aot"), "-XX:AOTMode=on",
                "-cp", image.resolve("lib/jvmd/*").toString(), "dev.jvmd.dist.Application", "--train")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
        } finally { process.destroyForcibly(); Files.deleteIfExists(log); }
    }
}
