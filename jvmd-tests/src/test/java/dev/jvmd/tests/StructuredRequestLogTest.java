package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: structured logging and per-method percentiles. */
@Tag("phase-1")
class StructuredRequestLogTest {
    @Test void percentilesAndFaultCounterReflectObservedSamples() {
        var metrics = new Metrics();
        for (int i = 1; i <= 100; i++) metrics.record("probe", "s1", i * 1_000_000L, Envelope.of(2, "live", Map.of()), i == 100);
        var snapshot = Json.MAPPER.valueToTree(metrics.snapshot());
        assertThat(snapshot.path("methods").path("probe").path("p50_ms").asDouble()).isEqualTo(50);
        assertThat(snapshot.path("methods").path("probe").path("p95_ms").asDouble()).isEqualTo(95);
        assertThat(snapshot.path("faults").asLong()).isEqualTo(1);
    }
}
