package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Content identities with a Unix change-time/inode fast path and a conservative fallback. */
public final class FileStateRegistry {
    private record Stamp(Object size, Object modified, Object changed, Object inode) { }
    private record Entry(Stamp stamp, String hash) { }
    private final Map<Path, Entry> files = new LinkedHashMap<>(256, .75f, true);
    private long hashes, hits, bytes;

    public synchronized String hash(Path file) throws IOException {
        file = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) { files.remove(file); return "missing"; }
        Stamp before = stamp(file);
        var previous = files.get(file);
        if (before != null && previous != null && before.equals(previous.stamp())) {
            hits++; return previous.hash();
        }
        // Do not associate bytes read during a concurrent write with a later file stamp.
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] content = Files.readAllBytes(file);
            String hash = Hashing.sha256(content); hashes++; bytes += content.length;
            Stamp after = stamp(file);
            if (before == null || before.equals(after)) {
                files.put(file, new Entry(after, hash));
                while (files.size() > 32768) files.remove(files.keySet().iterator().next());
                return hash;
            }
            before = after;
        }
        throw new IOException("Source changed repeatedly while reading: " + file);
    }

    private static Stamp stamp(Path file) throws IOException {
        try {
            var values = Files.readAttributes(file, "unix:size,lastModifiedTime,ctime,ino");
            return new Stamp(values.get("size"), values.get("lastModifiedTime"), values.get("ctime"), values.get("ino"));
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // A preserved mtime is not enough evidence on a provider without change time.
            return null;
        }
    }

    public synchronized void forget(Path file) { files.remove(file.toAbsolutePath().normalize()); }
    public synchronized Map<String, Object> status() {
        return Map.of("entries", files.size(), "hashes", hashes, "stat_hits", hits, "bytes_hashed", bytes);
    }
}
