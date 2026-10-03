package dev.jvmd.core.hash;

/** The collision-resistant hash (stage 1, 2.1). Its name is part of FORMAT, so changing it is a new generation, never a migration. */
public interface Digest {
    String name();
    /** Output width in bytes. */
    int width();
    /** Hashes the concatenation of the parts without copying them together. */
    Identity hash(byte[]... parts);
}
