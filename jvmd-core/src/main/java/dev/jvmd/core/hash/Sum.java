package dev.jvmd.core.hash;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The algebraic fingerprint of a set (stage 1, 2.1): the sum of its elements' identities as integers mod p, where p is the
 * largest prime below 2^(8*width). A sum is an identity to compare and update, never a storage key.
 */
public final class Sum {
    private static final ConcurrentHashMap<Integer, Sum> BY_WIDTH = new ConcurrentHashMap<>();
    private final int width;
    private final byte[] p;
    /** 2^(8*width) - p. Prime gaps are tiny, so it fits an int; it makes "subtract p" into "add c and drop the carry". */
    private final int c;

    private Sum(int width) {
        this.width = width;
        var modulus = BigInteger.ONE.shiftLeft(8 * width);
        var candidate = modulus.subtract(BigInteger.ONE);
        while (!candidate.isProbablePrime(64)) candidate = candidate.subtract(BigInteger.ONE);
        var gap = modulus.subtract(candidate);
        if (gap.bitLength() > 30) throw new IllegalStateException("Unexpected prime gap for width " + width);
        this.c = gap.intValueExact();
        this.p = pad(candidate.toByteArray(), width);
    }

    public static Sum forWidth(int width) { return BY_WIDTH.computeIfAbsent(width, Sum::new); }

    public int width() { return width; }
    public Identity zero() { return Identity.zero(width); }

    /** (a + b) mod p. Inputs are read as unsigned integers; a value at or above p is reduced first. */
    public Identity add(Identity a, Identity b) {
        var x = reduce(a.view());
        var y = reduce(b.view());
        var out = new byte[width];
        int carry = 0;
        for (int i = width - 1; i >= 0; i--) {
            int v = (x[i] & 0xFF) + (y[i] & 0xFF) + carry;
            out[i] = (byte) v;
            carry = v >>> 8;
        }
        if (carry != 0 || Arrays.compareUnsigned(out, p) >= 0) addSmall(out, c);
        return Identity.of(out);
    }

    /** (a - b) mod p: the O(1) removal of one element. */
    public Identity subtract(Identity a, Identity b) {
        var y = reduce(b.view());
        var negated = new byte[width];
        boolean zero = true;
        for (byte v : y) if (v != 0) { zero = false; break; }
        if (!zero) {
            int borrow = 0;
            for (int i = width - 1; i >= 0; i--) {
                int v = (p[i] & 0xFF) - (y[i] & 0xFF) - borrow;
                negated[i] = (byte) v;
                borrow = v < 0 ? 1 : 0;
            }
        }
        return add(a, Identity.of(negated));
    }

    private byte[] reduce(byte[] value) {
        if (Arrays.compareUnsigned(value, p) < 0) return value;
        var out = value.clone();
        addSmall(out, c);
        return out;
    }

    /** Adds a small non-negative int, dropping any carry out of the top byte (that drop is the "- p"). */
    private static void addSmall(byte[] value, int small) {
        long carry = small;
        for (int i = value.length - 1; i >= 0 && carry != 0; i--) {
            long v = (value[i] & 0xFFL) + carry;
            value[i] = (byte) v;
            carry = v >>> 8;
        }
    }

    private static byte[] pad(byte[] big, int width) {
        var out = new byte[width];
        int from = Math.max(0, big.length - width);
        System.arraycopy(big, from, out, width - (big.length - from), big.length - from);
        return out;
    }
}
