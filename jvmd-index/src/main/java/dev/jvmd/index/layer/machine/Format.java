package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Digest;

/**
 * FORMAT (stage 1, B.5): the layout version, the digest, the JDK feature version and the parser version. Each changes what a leaf
 * key means, so a change to any is a new generation directory and a cold boot, never a migration.
 */
public record Format(int layout, String digestName, int jdkFeature, String parser) {
    /** The storage family. 4 separates T/res from A/tail, binds fact hashes to keys and carries a beside k. Incompatible fact projections bump PARSER too. */
    public static final int LAYOUT = 4;
    /**
     * Bumped when {@link ClassFacts} would produce different facts for the same class bytes. "4": retain full warning annotations in
     * A/EA. "7": every string is lossless modified UTF-8 (amendments 2.1), so a String constant or annotation value with NUL or an
     * unpaired surrogate is kept exactly. "5" and "6" named other codecs on the unmerged stage 3 branch and are skipped.
     */
    public static final String PARSER = "7";

    public static Format of(Digest digest, int jdkFeature) { return new Format(LAYOUT, digest.name(), jdkFeature, PARSER); }

    @Override public String toString() { return "layout=" + layout + ";digest=" + digestName + ";jdk=" + jdkFeature + ";parser=" + parser; }

    /** The generation directory name: FORMAT with {@code ;} replaced by {@code _}. */
    public String directoryName() { return toString().replace(';', '_'); }
}
