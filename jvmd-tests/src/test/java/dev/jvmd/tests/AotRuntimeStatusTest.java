package dev.jvmd.tests;

import dev.jvmd.core.AotStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: auto fallback reports used or rejected with a reason. */
@Tag("phase-1")
class AotRuntimeStatusTest {
    @TempDir Path temp;
    @Test void actualSuccessfulTrainingLogAndRejectionLogAreDistinguished() throws Exception {
        Path successful = TestSupport.repo().resolve("jvmd-dist/target/training-state/use.log");
        assertThat(AotStatus.read(successful)).isEqualTo("used");
        Path rejected = temp.resolve("aot.log");
        Files.writeString(rejected, "[info][aot] Opened AOT cache test.aot\n[warning][aot] classpath mismatch\n");
        assertThat(AotStatus.read(rejected)).startsWith("rejected:").contains("mismatch");
        assertThat(AotStatus.read(temp.resolve("absent.log"))).startsWith("rejected:");
    }
}
