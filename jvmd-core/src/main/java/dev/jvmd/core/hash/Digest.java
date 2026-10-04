package dev.jvmd.core.hash;

/** The collision-resistant hash (stage 1, 2.1). Its name is part of FORMAT, so changing it is a new generation, never a migration. */
public interface Digest {
    /** Incremental hashing, for inputs too large to hold as one array (a jar mapped from disk, {@code lib/modules}). */
    interface Hasher {
        void update(byte[] bytes, int offset, int length);
        Identity finish();
    }

    String name();
    /** Output width in bytes. */
    int width();
    Hasher hasher();

    /** Hashes the concatenation of the parts without copying them together. */
    default Identity hash(byte[]... parts) {
        var hasher = hasher();
        for (var part : parts) hasher.update(part, 0, part.length);
        return hasher.finish();
    }
}
