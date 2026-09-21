package dev.jvmd.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Content identities with a Unix change-time/inode fast path and a conservative fallback. */
public final class FileStateRegistry {
    private record Stamp(Object size, Object modified, Object changed, Object inode, boolean regular) { }
    private record Entry(Stamp stamp, String hash) { }
    private final Map<Path, Entry> files = new LinkedHashMap<>(256, .75f, true);
    private long hashes, hits, bytes;

    public synchronized String hash(Path file) throws IOException {
        file = file.toAbsolutePath().normalize();
        Stamp before;
        try { before = stamp(file); }
        catch (NoSuchFileException missing) { files.remove(file); return "missing"; }
        // The Unix stamp already contains the file kind; avoid a second metadata read.
        if (before == null ? !Files.isRegularFile(file) : !before.regular()) {
            files.remove(file); return "missing";
        }
        var previous = files.get(file);
        if (before != null && previous != null && before.equals(previous.stamp())) {
            hits++; return previous.hash();
        }
        // Do not associate bytes read during a concurrent write with a later file stamp.
        for (int attempt = 0; attempt < 3; attempt++) {
            // Classpath entries can include large native-library JARs. Hash with a
            // bounded buffer rather than retaining a whole JAR for each identity check.
            java.security.MessageDigest digest;
            try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            try (var input = Files.newInputStream(file)) {
                long length = before == null ? Files.size(file) : ((Number) before.size()).longValue();
                byte[] buffer = new byte[(int) Math.max(1, Math.min(65536, length))];
                for (int n; (n = input.read(buffer)) != -1;) { digest.update(buffer, 0, n); bytes += n; }
            }
            String hash = HexFormat.of().formatHex(digest.digest()); hashes++;
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
            var values = Files.readAttributes(file, "unix:size,lastModifiedTime,ctime,ino,isRegularFile");
            return new Stamp(values.get("size"), values.get("lastModifiedTime"), values.get("ctime"),
                    values.get("ino"), Boolean.TRUE.equals(values.get("isRegularFile")));
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
