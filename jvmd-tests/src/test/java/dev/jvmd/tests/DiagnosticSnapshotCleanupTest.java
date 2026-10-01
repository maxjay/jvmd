package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Old persisted diagnostic snapshots are deleted on start, never migrated or read. */
class DiagnosticSnapshotCleanupTest {
    @TempDir Path root;

    @Test void startDeletesTheObsoleteDiagnosticsDirectory()throws Exception{
        var config=TestSupport.config(root,Duration.ofMinutes(1));
        Path obsolete=Files.createDirectories(config.stateDir().resolve("diagnostics-v2/0123/objects"));
        Files.writeString(obsolete.resolve("snapshot"),"old");
        try(var application=new Application(config)){
            assertThat(config.stateDir().resolve("diagnostics-v2")).doesNotExist();
        }
    }
}
