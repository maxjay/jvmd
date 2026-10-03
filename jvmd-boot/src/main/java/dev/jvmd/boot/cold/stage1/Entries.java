package dev.jvmd.boot.cold.stage1;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The class entries of one location, already selected and in owner order (stage 1, step 2.3 and appendix C.2/C.3). A jar has a
 * zip central directory; a JDK module has a {@code jrt:} directory walk. {@link ArtifactJob} is otherwise the same for both.
 */
public interface Entries {
    /**
     * One selected class entry. {@code owner} is its internal name, {@code path} the actual entry (which may sit under
     * {@code META-INF/versions/V/}). {@code crc} and {@code size} come free from a jar's central directory and key the class
     * memo; a {@code jrt:} module has no central directory, so both are -1 there.
     */
    record Item(String owner, String path, long crc, long size) { }

    /** Selected entries sorted by owner, the unsigned byte order of the internal name (see {@link #sortByOwner}). */
    List<Item> classes() throws IOException;

    /** Inflates one entry. An entry that cannot be read is a fault of that entry only. */
    byte[] read(Item item) throws IOException;

    /** A jar mapped from disk. Jars over 4 GiB are jars: sizes and offsets are read as 64-bit and Zip64 is honoured. */
    static Entries zip(MemorySegment jar, int jdkFeature) throws IOException { return new ZipEntries(jar, jdkFeature); }

    /** A jar already in memory (tests). */
    static Entries zip(byte[] jar, int jdkFeature) throws IOException { return new ZipEntries(MemorySegment.ofArray(jar), jdkFeature); }

    static Entries module(FileSystem jrt, String module) { return new JrtEntries(jrt, module); }

    /**
     * Sorts by internal name. Entry names end in {@code .class}, and '.' sorts after '$': {@code Foo$Bar.class} would precede
     * {@code Foo.class} while the keys of {@code Foo} (whose type key is {@code Foo NUL ...}) precede those of {@code Foo$Bar}.
     * Internal-name order, shorter prefix first, is exactly key order, which the streaming chunker requires (C.2).
     */
    static void sortByOwner(List<Item> items) {
        items.sort((a, b) -> Arrays.compareUnsigned(a.owner().getBytes(StandardCharsets.UTF_8), b.owner().getBytes(StandardCharsets.UTF_8)));
    }
}

/** C.2: the central directory of a mapped jar, with Zip64 and multi-release selection for one JDK feature version. */
final class ZipEntries implements Entries {
    // Signatures and field offsets are those of the ZIP application note (PKWARE APPNOTE 6.3.x), sections 4.3.x and 4.5.3.
    private static final int EOCD = 0x06054b50, CENTRAL = 0x02014b50, LOCAL = 0x04034b50, ZIP64_LOCATOR = 0x07064b50, ZIP64_EOCD = 0x06064b50;
    /** The EOCD is at most 22 bytes plus a comment of at most 65535 bytes (a u16 length), so it starts within this distance of the end. */
    private static final long EOCD_WINDOW = 22 + 65535;
    private static final int U16_SATURATED = 0xFFFF;
    private static final long U32_SATURATED = 0xFFFFFFFFL;
    /** HotSpot's largest array: an entry whose inflated size cannot be a Java array is not a class file this boot can read. */
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;
    /**
     * DEFLATE's best case is a 258-byte match coded in 2 bits (a 1-bit length code and a 1-bit distance code): 258 * 8 / 2 =
     * 1032 output bytes per input byte. Any declared size above {@code 1032 * compressed + 1032} (the added 1032 covers the
     * block header and end-of-block code of a tiny stream) cannot come from a valid stream.
     */
    private static final long DEFLATE_RATIO = 1032;

    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong U64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private record Raw(String name, int method, int flags, long crc, long compressed, long size, long offset) { }

    private final MemorySegment jar;
    private final int jdkFeature;
    private final Map<String, Raw> entries = new LinkedHashMap<>();

    ZipEntries(MemorySegment jar, int jdkFeature) throws IOException {
        this.jar = jar;
        this.jdkFeature = jdkFeature;
        readCentralDirectory();
    }

    private int u16(long at) { return jar.get(U16, at) & 0xFFFF; }
    private long u32(long at) { return jar.get(U32, at) & 0xFFFFFFFFL; }
    private long u64(long at) { return jar.get(U64, at); }

    private void readCentralDirectory() throws IOException {
        long length = jar.byteSize(), end = -1;
        for (long i = length - 22; i >= Math.max(0, length - EOCD_WINDOW); i--) {
            if (u32(i) == (EOCD & 0xFFFFFFFFL)) { end = i; break; }
        }
        if (end < 0) throw new IOException("Not a zip file: no end of central directory");
        long count = u16(end + 10), size = u32(end + 12), offset = u32(end + 16);
        if (count == U16_SATURATED || size == U32_SATURATED || offset == U32_SATURATED) {
            // Zip64: the real values are in the Zip64 end-of-central-directory record, found through the locator that sits
            // immediately before the EOCD.
            long locator = end - 20;
            if (locator < 0 || u32(locator) != (ZIP64_LOCATOR & 0xFFFFFFFFL)) throw new IOException("Zip64 end-of-central-directory locator missing");
            long record = u64(locator + 8);
            if (record < 0 || record + 56 > length || u32(record) != (ZIP64_EOCD & 0xFFFFFFFFL)) throw new IOException("Corrupt Zip64 end-of-central-directory record");
            count = u64(record + 32);
            size = u64(record + 40);
            offset = u64(record + 48);
        }
        if (offset < 0 || size < 0 || offset + size > length) throw new IOException("Central directory out of range");
        // A central-directory header is at least 46 bytes, so a count beyond size / 46 cannot be real.
        if (count < 0 || count > size / 46 + 1) throw new IOException("Corrupt central directory: entry count");
        long at = offset;
        for (long i = 0; i < count; i++) {
            if (at + 46 > offset + size || u32(at) != (CENTRAL & 0xFFFFFFFFL)) throw new IOException("Corrupt central directory");
            int flags = u16(at + 8), method = u16(at + 10);
            long crc = u32(at + 16), compressed = u32(at + 20), uncompressed = u32(at + 24);
            int nameLength = u16(at + 28), extraLength = u16(at + 30), commentLength = u16(at + 32);
            long local = u32(at + 42);
            if (at + 46 + nameLength + extraLength + commentLength > offset + size) throw new IOException("Corrupt central directory");
            var nameBytes = new byte[nameLength];
            MemorySegment.copy(jar, ValueLayout.JAVA_BYTE, at + 46, nameBytes, 0, nameLength);
            var name = new String(nameBytes, StandardCharsets.UTF_8);
            if (uncompressed == U32_SATURATED || compressed == U32_SATURATED || local == U32_SATURATED) {
                // The Zip64 extended-information extra field (id 0x0001) holds, in this order and only for the fields that
                // are saturated in the header: uncompressed size, compressed size, local header offset.
                long extra = at + 46 + nameLength, extraEnd = extra + extraLength;
                boolean found = false;
                while (extra + 4 <= extraEnd) {
                    int id = u16(extra), len = u16(extra + 2);
                    long data = extra + 4;
                    if (data + len > extraEnd) break;
                    if (id == 0x0001) {
                        if (uncompressed == U32_SATURATED) { uncompressed = u64(data); data += 8; }
                        if (compressed == U32_SATURATED) { compressed = u64(data); data += 8; }
                        if (local == U32_SATURATED) local = u64(data);
                        found = true;
                        break;
                    }
                    extra = data + len;
                }
                if (!found) throw new IOException("Zip64 extra field missing for " + name);
            }
            entries.putIfAbsent(name, new Raw(name, method, flags, crc, compressed, uncompressed, local));
            at += 46 + nameLength + extraLength + commentLength;
        }
    }

    @Override public List<Item> classes() throws IOException {
        boolean multiRelease = false;
        var manifest = entries.get("META-INF/MANIFEST.MF");
        if (manifest != null) {
            try {
                var value = new Manifest(new ByteArrayInputStream(inflate(manifest))).getMainAttributes().getValue("Multi-Release");
                multiRelease = value != null && value.trim().equalsIgnoreCase("true");
            } catch (IOException unreadable) { /* a manifest that cannot be read is not multi-release */ }
        }
        // base name -> (version -> actual entry). The base entry counts as version 8.
        var byBase = new HashMap<String, Best>();
        for (var name : entries.keySet()) {
            if (!name.endsWith(".class")) continue;
            if (name.startsWith("META-INF/versions/")) {
                if (!multiRelease) continue;
                int slash = name.indexOf('/', "META-INF/versions/".length());
                if (slash < 0) continue;
                int version;
                try { version = Integer.parseInt(name.substring("META-INF/versions/".length(), slash)); } catch (NumberFormatException e) { continue; }
                if (version < 9 || version > jdkFeature) continue;
                byBase.computeIfAbsent(name.substring(slash + 1), k -> new Best()).offer(version, name);
            } else if (!name.startsWith("META-INF/")) {
                byBase.computeIfAbsent(name, k -> new Best()).offer(8, name);
            }
        }
        var out = new ArrayList<Item>(byBase.size());
        for (var e : byBase.entrySet()) {
            String base = e.getKey();
            var raw = entries.get(e.getValue().path);
            out.add(new Item(base.substring(0, base.length() - ".class".length()), raw.name(), raw.crc(), raw.size()));
        }
        Entries.sortByOwner(out);
        return out;
    }

    private static final class Best {
        private int version = -1;
        private String path;
        void offer(int candidate, String actual) { if (candidate > version) { version = candidate; path = actual; } }
    }

    @Override public byte[] read(Item item) throws IOException { return inflate(entries.get(item.path())); }

    private byte[] inflate(Raw raw) throws IOException {
        if ((raw.flags() & 1) != 0) throw new IOException("Encrypted entry: " + raw.name());
        // Sizes are checked before any buffer exists. A stored entry is its own size; a deflated one cannot exceed DEFLATE's ratio.
        if (raw.method() == 0 && raw.size() != raw.compressed()) throw new IOException("Stored entry size differs from its data: " + raw.name());
        if (raw.method() == 8 && raw.size() > DEFLATE_RATIO * raw.compressed() + DEFLATE_RATIO)
            throw new IOException("Declared size is impossible for a DEFLATE stream: " + raw.name());
        if (raw.size() > MAX_ARRAY || raw.compressed() > MAX_ARRAY || raw.size() < 0 || raw.compressed() < 0) throw new IOException("Entry too large for an array: " + raw.name());
        long local = raw.offset();
        if (local < 0 || local + 30 > jar.byteSize() || u32(local) != (LOCAL & 0xFFFFFFFFL)) throw new IOException("Corrupt local header: " + raw.name());
        long start = local + 30 + u16(local + 26) + u16(local + 28);
        if (start + raw.compressed() > jar.byteSize()) throw new IOException("Entry data out of range: " + raw.name());
        var out = new byte[(int) raw.size()];
        if (raw.method() == 0) {
            MemorySegment.copy(jar, ValueLayout.JAVA_BYTE, start, out, 0, out.length);
        } else if (raw.method() == 8) {
            var inflater = new Inflater(true);
            try {
                inflater.setInput(jar.asSlice(start, raw.compressed()).asByteBuffer());
                int total = 0;
                while (total < out.length) {
                    int n = inflater.inflate(out, total, out.length - total);
                    if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                    total += n;
                }
                if (total != out.length) throw new IOException("Truncated entry: " + raw.name());
            } catch (DataFormatException e) {
                throw new IOException("Corrupt entry: " + raw.name(), e);
            } finally { inflater.end(); }
        } else throw new IOException("Unsupported compression method " + raw.method() + ": " + raw.name());
        var crc = new CRC32();
        crc.update(out);
        if (crc.getValue() != raw.crc()) throw new IOException("CRC mismatch: " + raw.name());
        return out;
    }
}

/** C.3: the classes of one JDK module through the {@code jrt:} filesystem, which already presents a single version. */
final class JrtEntries implements Entries {
    private final FileSystem jrt;
    private final String module;

    JrtEntries(FileSystem jrt, String module) { this.jrt = jrt; this.module = module; }

    @Override public List<Item> classes() throws IOException {
        Path root = jrt.getPath("/modules", module);
        var out = new ArrayList<Item>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (var path : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                out.add(new Item(relative.substring(0, relative.length() - ".class".length()), relative, -1, -1));
            }
        }
        Entries.sortByOwner(out);
        return out;
    }

    @Override public byte[] read(Item item) throws IOException { return Files.readAllBytes(jrt.getPath("/modules", module, item.path())); }
}
