package dev.jvmd.core;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Implements section 5: bounded one-hour latency history and structured request logs. */
public final class Metrics {
    private record Sample(long timestamp, long nanos, boolean fault) { }
    private final Map<String, ArrayDeque<Sample>> samples = new LinkedHashMap<>();
    private long faults;
    public synchronized void record(String method, String session, long nanos, Envelope result, boolean fault) {
        long now = System.currentTimeMillis();
        var history = samples.computeIfAbsent(method, _ -> new ArrayDeque<>());
        history.addLast(new Sample(now, nanos, fault));
        while (history.size() > 10000 || (!history.isEmpty() && history.getFirst().timestamp < now - 3600000)) history.removeFirst();
        if (fault) faults++;
        try {
            System.err.println(Json.MAPPER.writeValueAsString(Map.of("method", method, "session", session,
                    "tier", result.tier(), "latency_ms", nanos / 1e6, "truncated", result.truncated(), "faults", fault ? 1 : 0)));
        } catch (java.io.IOException ignored) { }
    }
    public synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        long cutoff = System.currentTimeMillis() - 3600000;
        samples.forEach((method, history) -> {
            while (!history.isEmpty() && history.getFirst().timestamp < cutoff) history.removeFirst();
            var durations = history.stream().mapToLong(Sample::nanos).sorted().toArray();
            if (durations.length > 0) result.put(method, Map.of("count", durations.length,
                    "p50_ms", durations[(durations.length - 1) / 2] / 1e6,
                    "p95_ms", durations[(int) Math.ceil(durations.length * .95) - 1] / 1e6));
        });
        return Map.of("methods", result, "faults", faults);
    }
}
