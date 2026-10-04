package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Digest;

/**
 * FORMAT (stage 1, B.5): the layout version, the digest, the JDK feature version and the parser version. Each changes what a leaf
 * key means, so a change to any is a new generation directory and a cold boot, never a migration.
 */
public record Format(int layout, String digestName, int jdkFeature, String parser) {
    /** Bumped when any byte layout in appendices A or B changes. "2": faults left L (B.4) and entered the P record (B.5). */
    public static final int LAYOUT = 2;
    /** Bumped when {@link ClassFacts} would produce different facts for the same class bytes. "2": the outer class joined res and annotations became inline structural values. */
    public static final String PARSER = "2";

    public static Format of(Digest digest, int jdkFeature) { return new Format(LAYOUT, digest.name(), jdkFeature, PARSER); }

    @Override public String toString() { return "layout=" + layout + ";digest=" + digestName + ";jdk=" + jdkFeature + ";parser=" + parser; }

    /** The generation directory name: FORMAT with {@code ;} replaced by {@code _}. */
    public String directoryName() { return toString().replace(';', '_'); }
}
