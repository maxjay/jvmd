package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: idle timeout and clean shutdown resource flushing. */
@Tag("phase-1")
class IdleShutdownTest {
    @TempDir Path temp;
    @Test void expiryFlushesResourcesAndReleasesSocket() throws Exception {
        var config = TestSupport.config(temp, Duration.ofMillis(80));
        var flushed = new AtomicBoolean();
        try (var sessions = new Sessions(); var server = new UnixServer(config,
                new Dispatcher(sessions, new Metrics()), () -> flushed.set(true))) {
            server.start();
            assertThat(server.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(flushed).isTrue();
            assertThat(config.socket()).doesNotExist();
        }
    }
}
