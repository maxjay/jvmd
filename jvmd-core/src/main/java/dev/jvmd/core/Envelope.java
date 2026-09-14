package dev.jvmd.core;

import java.util.List;

/** Implements 4.1: mandatory provenance and completeness on every response. */
public record Envelope(int tier, String source, boolean truncated, String cursor,
                       List<String> warnings, Object result) {
    public Envelope {
        if (tier < 0 || tier > 2) throw new IllegalArgumentException("Invalid tier");
        if (!List.of("live", "verified", "index").contains(source))
            throw new IllegalArgumentException("Invalid source");
        warnings = List.copyOf(warnings);
    }
    public static Envelope of(int tier, String source, Object result) {
        return new Envelope(tier, source, false, null, List.of(), result);
    }
    public Envelope warn(String warning) {
        var updated = new java.util.ArrayList<>(warnings);
        updated.add(warning);
        return new Envelope(tier, source, truncated, cursor, updated, result);
    }
}
