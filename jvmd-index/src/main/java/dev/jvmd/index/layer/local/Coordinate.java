package dev.jvmd.index.layer.local;

/** {@code group:artifact:version} as the build tool resolved it (stage 2, 2.1). Equality is exact, version included (3.3). */
public record Coordinate(String group, String artifact, String version) {
    public Coordinate {
        if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) throw new IllegalArgumentException("Coordinate needs three parts: " + group + ":" + artifact + ":" + version);
    }

    /** @throws IllegalArgumentException unless the text has exactly three non-empty parts */
    public static Coordinate parse(String text) {
        var parts = text.split(":", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Coordinate needs three parts: " + text);
        return new Coordinate(parts[0], parts[1], parts[2]);
    }

    @Override public String toString() { return group + ":" + artifact + ":" + version; }
}
