package dev.jvmd.core.tree;

import java.util.Objects;

/**
 * Modified UTF-8 over Java's UTF-16 code units (amendments 2.1), the encoding of class-file strings. Every Java string round-trips
 * exactly, including NUL, unpaired surrogates, and supplementary characters (which are encoded as their two surrogates). NUL is
 * {@code C0 80}, so a 0x00 byte never occurs inside an encoded string and can terminate a {@code zstr}.
 *
 * <p>There is no 65,535-byte frame, unlike {@code DataOutputStream.writeUTF}. Decoding accepts only the one canonical encoding of
 * each code unit: no raw 0x00, no overlong form other than NUL's, and no four-byte sequence.
 */
public final class Mutf8 {
    private Mutf8() { }

    /** The length in bytes of the encoding of {@code s}. */
    public static int length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c != 0 && c <= 0x7F ? 1 : c <= 0x7FF ? 2 : 3;
        }
        return n;
    }

    public static byte[] encode(String s) {
        var out = new byte[length(s)];
        encode(s, out, 0);
        return out;
    }

    /** Writes the encoding of {@code s} into {@code out} at {@code at}, which must have room for {@link #length}; returns the end. */
    public static int encode(String s, byte[] out, int at) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != 0 && c <= 0x7F) {
                out[at++] = (byte) c;
            } else if (c <= 0x7FF) {
                out[at++] = (byte) (0xC0 | c >> 6);
                out[at++] = (byte) (0x80 | c & 0x3F);
            } else {
                out[at++] = (byte) (0xE0 | c >> 12);
                out[at++] = (byte) (0x80 | c >> 6 & 0x3F);
                out[at++] = (byte) (0x80 | c & 0x3F);
            }
        }
        return at;
    }

    /**
     * The string whose encoding is the {@code length} bytes at {@code from}.
     *
     * @throws IllegalArgumentException if they are not the canonical encoding of any string
     */
    public static String decode(byte[] in, int from, int length) {
        Objects.checkFromIndexSize(from, length, in.length);
        var out = new char[length];
        int n = 0;
        int end = from + length;
        for (int i = from; i < end; ) {
            int b = in[i] & 0xFF;
            if (b != 0 && b < 0x80) {
                out[n++] = (char) b;
                i++;
            } else if (b >= 0xC0 && b < 0xE0) {
                if (end - i < 2) throw malformed(i, "truncated two-byte sequence");
                int c = (b & 0x1F) << 6 | continuation(in, i + 1);
                if (c != 0 && c < 0x80) throw malformed(i, "overlong two-byte sequence");
                out[n++] = (char) c;
                i += 2;
            } else if (b >= 0xE0 && b < 0xF0) {
                if (end - i < 3) throw malformed(i, "truncated three-byte sequence");
                int c = (b & 0x0F) << 12 | continuation(in, i + 1) << 6 | continuation(in, i + 2);
                if (c < 0x800) throw malformed(i, "overlong three-byte sequence");
                out[n++] = (char) c;
                i += 3;
            } else {
                throw malformed(i, b == 0 ? "a 0x00 byte, which NUL is not encoded as" : b < 0xC0 ? "a continuation byte with no lead" : "a four-byte sequence");
            }
        }
        return new String(out, 0, n);
    }

    /**
     * Orders two strings as their encodings compare, unsigned byte by byte, without encoding them. That is code-unit order, except
     * that NUL ({@code C0 80}) sorts after every one-byte character and before every other character.
     */
    public static int compare(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            int x = rank(a.charAt(i)), y = rank(b.charAt(i));
            if (x != y) return Integer.compare(x, y);
        }
        return Integer.compare(a.length(), b.length());
    }

    private static int rank(char c) { return c == 0 ? 0xFF : c * 2; }

    private static int continuation(byte[] in, int at) {
        int b = in[at] & 0xFF;
        if ((b & 0xC0) != 0x80) throw malformed(at, "a missing continuation byte");
        return b & 0x3F;
    }

    private static IllegalArgumentException malformed(int at, String what) {
        return new IllegalArgumentException("Malformed modified UTF-8 at byte " + at + ": " + what);
    }
}
