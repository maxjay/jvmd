package dev.jvmd.boot.cold.stage1;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
     * {@code META-INF/versions/V/}).
     */
    record Item(String owner, String path) { }

    /** Selected entries sorted by owner, the unsigned byte order of the internal name (see {@link #sortByOwner}). */
    List<Item> classes() throws IOException;

    /** Inflates one entry. An entry that cannot be read is a fault of that entry only. */
    byte[] read(Item item) throws IOException;

    static Entries zip(byte[] jar, int jdkFeature) throws IOException { return new ZipEntries(jar, jdkFeature); }

    static Entries module(FileSystem jrt, String module) { return new JrtEntries(jrt, module); }

    /**
     * Sorts by internal name. The design says "sort by entry name", but entry names end in {@code .class}, and '.' sorts after
     * '$': {@code Foo$Bar.class} would precede {@code Foo.class} while the keys of {@code Foo} (whose type key is
     * {@code Foo NUL ...}) precede those of {@code Foo$Bar}. Sorting the internal name, with the shorter prefix first, is exactly
     * the order of the keys, which is what the streaming chunker needs.
     */
    static void sortByOwner(List<Item> items) {
        items.sort((a, b) -> Arrays.compareUnsigned(a.owner().getBytes(StandardCharsets.UTF_8), b.owner().getBytes(StandardCharsets.UTF_8)));
    }
}

/** C.2: the central directory of a jar held in memory, with multi-release selection for one JDK feature version. */
final class ZipEntries implements Entries {
    private static final int EOCD = 0x06054b50, CENTRAL = 0x02014b50, LOCAL = 0x04034b50;
    /** A class file larger than this is not a class file anyone compiles against. Guards inflation of a hostile entry. */
    private static final int MAX_ENTRY = 64 * 1024 * 1024;

    private record Raw(String name, int method, int flags, long crc, long compressed, long size, long offset) { }

    private final byte[] bytes;
    private final int jdkFeature;
    private final Map<String, Raw> entries = new LinkedHashMap<>();

    ZipEntries(byte[] bytes, int jdkFeature) throws IOException {
        this.bytes = bytes;
        this.jdkFeature = jdkFeature;
        readCentralDirectory();
    }

    private int u16(int at) { return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8); }
    private long u32(int at) { return (u16(at) | ((long) u16(at + 2) << 16)) & 0xFFFFFFFFL; }

    private void readCentralDirectory() throws IOException {
        int end = -1;
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) {
            if (u32(i) == (EOCD & 0xFFFFFFFFL)) { end = i; break; }
        }
        if (end < 0) throw new IOException("Not a zip file: no end of central directory");
        int count = u16(end + 10);
        long size = u32(end + 12), offset = u32(end + 16);
        if (count == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) throw new IOException("Zip64 archives are not supported");
        if (offset + size > bytes.length) throw new IOException("Central directory out of range");
        int at = (int) offset;
        for (int i = 0; i < count; i++) {
            if (at + 46 > bytes.length || u32(at) != (CENTRAL & 0xFFFFFFFFL)) throw new IOException("Corrupt central directory");
            int flags = u16(at + 8), method = u16(at + 10);
            long crc = u32(at + 16), compressed = u32(at + 20), uncompressed = u32(at + 24);
            int nameLength = u16(at + 28), extra = u16(at + 30), comment = u16(at + 32);
            long local = u32(at + 42);
            if (at + 46 + nameLength > bytes.length) throw new IOException("Corrupt central directory");
            var name = new String(bytes, at + 46, nameLength, StandardCharsets.UTF_8);
            entries.putIfAbsent(name, new Raw(name, method, flags, crc, compressed, uncompressed, local));
            at += 46 + nameLength + extra + comment;
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
        var byBase = new HashMap<String, TreeMapOfVersions>();
        for (var name : entries.keySet()) {
            if (!name.endsWith(".class")) continue;
            if (name.startsWith("META-INF/versions/")) {
                if (!multiRelease) continue;
                int slash = name.indexOf('/', "META-INF/versions/".length());
                if (slash < 0) continue;
                int version;
                try { version = Integer.parseInt(name.substring("META-INF/versions/".length(), slash)); } catch (NumberFormatException e) { continue; }
                if (version < 9 || version > jdkFeature) continue;
                byBase.computeIfAbsent(name.substring(slash + 1), k -> new TreeMapOfVersions()).put(version, name);
            } else if (!name.startsWith("META-INF/")) {
                byBase.computeIfAbsent(name, k -> new TreeMapOfVersions()).put(8, name);
            }
        }
        var out = new ArrayList<Item>(byBase.size());
        for (var e : byBase.entrySet()) {
            String base = e.getKey();
            out.add(new Item(base.substring(0, base.length() - ".class".length()), e.getValue().highest()));
        }
        Entries.sortByOwner(out);
        return out;
    }

    private static final class TreeMapOfVersions {
        private int best = -1;
        private String path;
        void put(int version, String actual) { if (version > best) { best = version; path = actual; } }
        String highest() { return path; }
    }

    @Override public byte[] read(Item item) throws IOException { return inflate(entries.get(item.path())); }

    private byte[] inflate(Raw raw) throws IOException {
        if ((raw.flags() & 1) != 0) throw new IOException("Encrypted entry: " + raw.name());
        if (raw.size() > MAX_ENTRY) throw new IOException("Entry too large: " + raw.name());
        int local = (int) raw.offset();
        if (local < 0 || local + 30 > bytes.length || u32(local) != (LOCAL & 0xFFFFFFFFL)) throw new IOException("Corrupt local header: " + raw.name());
        int start = local + 30 + u16(local + 26) + u16(local + 28);
        if (start + raw.compressed() > bytes.length) throw new IOException("Entry data out of range: " + raw.name());
        byte[] out;
        if (raw.method() == 0) {
            out = Arrays.copyOfRange(bytes, start, start + (int) raw.compressed());
        } else if (raw.method() == 8) {
            out = new byte[(int) raw.size()];
            var inflater = new Inflater(true);
            try {
                inflater.setInput(bytes, start, (int) raw.compressed());
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
                out.add(new Item(relative.substring(0, relative.length() - ".class".length()), relative));
            }
        }
        Entries.sortByOwner(out);
        return out;
    }

    @Override public byte[] read(Item item) throws IOException { return Files.readAllBytes(jrt.getPath("/modules", module, item.path())); }
}
