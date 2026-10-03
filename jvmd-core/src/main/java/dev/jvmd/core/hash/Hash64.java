package dev.jvmd.core.hash;

/**
 * A fast uniform 64-bit non-cryptographic hash, used only for chunk boundaries (stage 1, 2.1). FNV-1a over the bytes, then the
 * murmur3 64-bit finalizer to spread the low bits that the boundary test reads. Boundaries need uniformity, not secrecy.
 */
public final class Hash64 {
    private Hash64() { }

    public static long of(byte[] key) {
        long h = 0xcbf29ce484222325L;
        for (byte b : key) { h ^= b & 0xFF; h *= 0x100000001b3L; }
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
