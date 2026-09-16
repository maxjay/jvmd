package dev.jvmd.tests;

import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Baseline-only benchmark for the pre-redesign workspace diagnostics path.
 *
 * This intentionally uses the same deterministic 40-source workload as the
 * incremental diagnostics branch, but does not depend on any of the new
 * diagnostic-store instrumentation or behavior.
 */
@Tag("phase-4")
class DiagnosticsBaselineBenchmarkTest {
    @TempDir Path temp;

    @Test void measureColdAndRepeatedWorkspaceDiagnostics() throws Exception {
        for (int i = 0; i < 40; i++) {
            Files.writeString(
                temp.resolve("Source" + i + ".java"),
                "class Source" + i + " { int value() { return \"bad\"; } }\n"
            );
        }

        try (var daemon = new AotDaemon(temp)) {
            String session = daemon.request("session.open", Map.of("root", temp.toString()))
                .path("result").path("session").asText();

            long started = System.nanoTime();
            var first = daemon.request("diag.get", Map.of("session", session, "limit", 1000));
            double coldMs = (System.nanoTime() - started) / 1e6;

            assertThat(first.path("result").path("diagnostics").isArray()).isTrue();
            assertThat(first.path("result").path("diagnostics").size()).isEqualTo(40);

            started = System.nanoTime();
            var second = daemon.request("diag.get", Map.of("session", session, "limit", 1000));
            double repeatedMs = (System.nanoTime() - started) / 1e6;

            assertThat(second.path("result").path("diagnostics"))
                .isEqualTo(first.path("result").path("diagnostics"));

            started = System.nanoTime();
            var page1 = daemon.request("diag.get", Map.of("session", session, "limit", 10));
            double page1Ms = (System.nanoTime() - started) / 1e6;
            assertThat(page1.path("truncated").asBoolean()).isTrue();

            started = System.nanoTime();
            daemon.request("diag.get", Map.of(
                "session", session,
                "limit", 10,
                "cursor", page1.path("cursor").asText()
            ));
            double page2Ms = (System.nanoTime() - started) / 1e6;

            var measured = new LinkedHashMap<String, Object>();
            measured.put("files", 40);
            measured.put("cold_ms", coldMs);
            measured.put("repeated_ms", repeatedMs);
            measured.put("page1_ms", page1Ms);
            measured.put("page2_ms", page2Ms);

            System.out.println("diagnostics-baseline-perf " + Json.MAPPER.writeValueAsString(measured));
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                TestSupport.repo().resolve("jvmd-tests/target/diagnostics-baseline-perf.json").toFile(),
                measured
            );
        }
    }
}
