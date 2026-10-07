package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Identity;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The primitive encodings of stage 1, appendix A.1: fixed-width big-endian integers, length-prefixed UTF-8 strings,
 * NUL-terminated strings for keys, raw identities. No varints and no library framing, so a codec is reproducible from the
 * appendix alone.
 */
public final class Codec {
    private Codec() { }

    public static final class Writer {
        private byte[] buffer;
        private int length;

        public Writer() { this(64); }
        public Writer(int capacity) { buffer = new byte[Math.max(16, capacity)]; }

        private void ensure(int more) {
            if (length + more > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + more));
        }
        public int length() { return length; }
        public Writer u8(int v) { ensure(1); buffer[length++] = (byte) v; return this; }
        public Writer u16(int v) { ensure(2); buffer[length++] = (byte) (v >>> 8); buffer[length++] = (byte) v; return this; }
        public Writer u32(long v) {
            ensure(4);
            for (int shift = 24; shift >= 0; shift -= 8) buffer[length++] = (byte) (v >>> shift);
            return this;
        }
        public Writer u64(long v) {
            ensure(8);
            for (int shift = 56; shift >= 0; shift -= 8) buffer[length++] = (byte) (v >>> shift);
            return this;
        }
        public Writer i64(long v) { return u64(v); }
        public Writer raw(byte[] bytes) { return raw(bytes, 0, bytes.length); }
        public Writer raw(byte[] bytes, int from, int to) {
            ensure(to - from);
            System.arraycopy(bytes, from, buffer, length, to - from);
            length += to - from;
            return this;
        }
        /** {@code str}: u32 byte length, then standard UTF-8. */
        public Writer str(String s) {
            var bytes = s.getBytes(StandardCharsets.UTF_8);
            u32(bytes.length);
            return raw(bytes);
        }
        /** Java text: u32 UTF-16 code-unit count, then u16 code units, including NUL and unpaired surrogates. */
        public Writer utf16(String s) {
            u32(s.length());
            for (int i = 0; i < s.length(); i++) u16(s.charAt(i));
            return this;
        }
        /** {@code zstr}: UTF-8 bytes then one 0x00. Java identifiers, internal names and descriptors cannot contain 0x00. */
        public Writer zstr(String s) { raw(s.getBytes(StandardCharsets.UTF_8)); return u8(0); }
        public Writer id(Identity id) { return raw(id.view()); }
        /** {@code opt<str>}. */
        public Writer optStr(String s) { if (s == null) return u8(0); u8(1); return str(s); }
        public Writer optId(Identity id) { if (id == null) return u8(0); u8(1); return id(id); }
        /** u32 length then the bytes: {@code opt<bytes>} without the presence byte. */
        public Writer lenBytes(byte[] bytes) { u32(bytes.length); return raw(bytes); }
        public byte[] toBytes() { return Arrays.copyOf(buffer, length); }
    }

    public static final class Reader {
        private final byte[] bytes;
        private int position;

        public Reader(byte[] bytes) { this.bytes = bytes; }
        public int position() { return position; }
        public int remaining() { return bytes.length - position; }
        public int u8() { return bytes[position++] & 0xFF; }
        public int u16() { return (u8() << 8) | u8(); }
        public long u32() { return ((long) u16() << 16) | u16(); }
        /** A u32 that must fit a Java int: counts and lengths. */
        public int count() {
            long v = u32();
            if (v > Integer.MAX_VALUE) throw new IllegalStateException("Count out of range: " + v);
            return (int) v;
        }
        public long u64() { return (u32() << 32) | u32(); }
        public long i64() { return u64(); }
        public byte[] raw(int n) {
            java.util.Objects.checkFromIndexSize(position, n, bytes.length);
            var out = Arrays.copyOfRange(bytes, position, position + n);
            position += n;
            return out;
        }
        /** Consume a checked byte range without allocating a copy. */
        public void skip(int n) {
            java.util.Objects.checkFromIndexSize(position, n, bytes.length);
            position += n;
        }
        public String str() { return new String(raw(count()), StandardCharsets.UTF_8); }
        /** Exact Java text; check the declared length before allocating or consuming its code units. */
        public String utf16() {
            if (remaining() < 4) throw new IllegalArgumentException("Truncated UTF-16 length");
            long length = u32();
            if (length > remaining() / 2) throw new IllegalArgumentException("Truncated UTF-16 text");
            var text = new char[(int) length];
            for (int i = 0; i < text.length; i++) text[i] = (char) u16();
            return new String(text);
        }
        public Identity id(int width) { return Identity.of(raw(width)); }
        public byte[] lenBytes() { return raw(count()); }
        /** {@code zstr}: UTF-8 bytes up to a 0x00, which is consumed and not part of the string. */
        public String zstr() {
            int start = position;
            while (bytes[position] != 0) position++;
            var out = new String(bytes, start, position - start, StandardCharsets.UTF_8);
            position++;
            return out;
        }
        /** {@code opt<str>}: a presence byte, then the string if it is 1. */
        public String optStr() { return u8() == 1 ? str() : null; }
        /** {@code opt<id>}. */
        public Identity optId(int width) { return u8() == 1 ? id(width) : null; }
    }
}
