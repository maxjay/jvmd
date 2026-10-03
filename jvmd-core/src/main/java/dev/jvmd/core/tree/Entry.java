package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Identity;

/**
 * One entry of a {@link ContentTree}: a key that orders it, a value that is stored and covered by node hashes, and an
 * identity {@code h} that contributes to sums. {@code value} may be empty ("none").
 */
public record Entry(byte[] key, byte[] value, Identity h) {
    public static final byte[] NONE = new byte[0];
}
