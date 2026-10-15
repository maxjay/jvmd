package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Identity;
import java.util.Arrays;

/**
 * The primitive encodings of stage 1, appendix A.1: fixed-width big-endian integers, length-prefixed strings, NUL-terminated
 * strings for keys, raw identities. Strings are lossless modified UTF-8 ({@link Mutf8}, amendments 2.1). No varints and no library
 * framing, so a codec is reproducible from the appendix alone.
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
        /** {@code str}: u32 byte length, then modified UTF-8. */
        public Writer str(String s) {
            int n = Mutf8.length(s);
            u32(n);
            ensure(n);
            length = Mutf8.encode(s, buffer, length);
            return this;
        }
        /** {@code zstr}: modified UTF-8, then one 0x00. NUL inside the string is {@code C0 80}, so the 0x00 only ever terminates. */
        public Writer zstr(String s) {
            ensure(Mutf8.length(s) + 1);
            length = Mutf8.encode(s, buffer, length);
            return u8(0);
        }
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
            var out = Arrays.copyOfRange(bytes, position, position + n);
            position += n;
            return out;
        }
        /** @throws IllegalArgumentException if the bytes are not canonical modified UTF-8 */
        public String str() {
            int n = count();
            var out = Mutf8.decode(bytes, position, n);
            position += n;
            return out;
        }
        public Identity id(int width) { return Identity.of(raw(width)); }
        public byte[] lenBytes() { return raw(count()); }
        /**
         * {@code zstr}: modified UTF-8 up to a 0x00, which is consumed and not part of the string.
         *
         * @throws IllegalArgumentException if there is no 0x00, or the bytes before it are not canonical modified UTF-8
         */
        public String zstr() {
            int end = position;
            while (end < bytes.length && bytes[end] != 0) end++;
            if (end == bytes.length) throw new IllegalArgumentException("Unterminated zstr at byte " + position);
            var out = Mutf8.decode(bytes, position, end - position);
            position = end + 1;
            return out;
        }
        /** {@code opt<str>}: a presence byte, then the string if it is 1. */
        public String optStr() { return u8() == 1 ? str() : null; }
        /** {@code opt<id>}. */
        public Identity optId(int width) { return u8() == 1 ? id(width) : null; }
    }
}
