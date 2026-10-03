package dev.jvmd.core.hash;

import java.util.Arrays;

/** An opaque value of {@link Digest#width()} bytes (stage 1, 2.1). Never a hex string: there is deliberately no text form. */
public final class Identity implements Comparable<Identity> {
    private final byte[] bytes;

    private Identity(byte[] bytes) { this.bytes = bytes; }

    /** Takes ownership of the array; callers must not modify it afterwards. */
    public static Identity of(byte[] bytes) { return new Identity(bytes); }
    public static Identity zero(int width) { return new Identity(new byte[width]); }

    public int width() { return bytes.length; }
    /** A copy of the bytes. */
    public byte[] bytes() { return bytes.clone(); }
    /** The bytes without a copy, for codecs that only read them. */
    public byte[] view() { return bytes; }

    @Override public int compareTo(Identity other) { return Arrays.compareUnsigned(bytes, other.bytes); }
    @Override public boolean equals(Object other) { return other instanceof Identity i && Arrays.equals(bytes, i.bytes); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public String toString() { return "Identity[" + bytes.length + "]"; }
}
