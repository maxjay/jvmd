package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * The class memo (stage 1, 2.6 and 3.8): {@code (crc32, size) -> (κ -> Φ)}, where {@code κ = Digest(class bytes)}. A class file
 * that appears again, byte for byte, is not parsed again. Adjacent versions of a library share most class files, and an internal
 * library republished unchanged shares nearly all of them. The memo is always on.
 *
 * <p>It changes nothing about the output: with or without it the same leaves and the same ROOT. Faults are not memoized.
 *
 * <p><b>Cost.</b> {@code crc32} and {@code size} are free from a jar's central directory, so the first occurrence of a class is a
 * plain miss: it is not hashed at all. Only when a second class lands in the same {@code (crc32, size)} bucket is {@code κ}
 * computed, to confirm that it really is the same class (a CRC match is not byte equality). To be able to confirm, the first
 * occurrence keeps its bytes until then; after that only its {@code κ} and facts are kept. Bytes are therefore held for classes
 * seen once in an artifact directory, until the directory's last job ends.
 *
 * <p>A {@code jrt:} module has no central directory, so its classes get {@code crc32} over the bytes just read (a fraction of a
 * digest's cost) and share the same code path.
 *
 * <p><b>Scope.</b> All versions of an artifact sit under one directory ({@code group/artifact/version/file.jar}), so the memo
 * lives while jobs of that directory are running and is released when the last of them finishes. A JDK module is its own scope.
 */
final class ClassMemo {
    /** One distinct class in a bucket. {@code bytes} is dropped once {@code kappa} is known. */
    private static final class Candidate {
        byte[] bytes;
        Identity kappa;
        final ClassFacts facts;
        Candidate(byte[] bytes, Identity kappa, ClassFacts facts) { this.bytes = bytes; this.kappa = kappa; this.facts = facts; }
    }

    /** All classes with one {@code (crc32, size)}. Guarded by its own monitor: the same class is parsed once, other buckets are free. */
    private static final class Bucket {
        final List<Candidate> candidates = new ArrayList<>(1);
    }

    /** A parse that may fault. */
    @FunctionalInterface interface Parse { ClassFacts get() throws ClassFacts.Fault; }

    /** The classes seen so far in one artifact directory. */
    static final class Scope {
        private final Digest digest;
        private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

        Scope(Digest digest) { this.digest = digest; }

        /**
         * The facts of {@code bytes}, parsing at most once per distinct content in this scope.
         *
         * @param crc  the entry's CRC-32 from the central directory, or -1 when unknown (a module): computed from the bytes
         * @param size the entry's size from the central directory, or -1 when unknown
         */
        ClassFacts get(long crc, long size, byte[] bytes, Parse parse) throws ClassFacts.Fault {
            if (crc < 0) { var c = new CRC32(); c.update(bytes); crc = c.getValue(); }
            // 32 bits of crc and 32 of size: the bucket key. A collision only puts two classes in one bucket, where κ decides.
            var key = (crc << 32) ^ ((size >= 0 ? size : bytes.length) & 0xFFFFFFFFL);
            var bucket = buckets.computeIfAbsent(key, k -> new Bucket());
            synchronized (bucket) {
                if (bucket.candidates.isEmpty()) {
                    var facts = parse.get();                         // a first occurrence: a miss, and never hashed
                    bucket.candidates.add(new Candidate(bytes, null, facts));
                    return facts;
                }
                var kappa = digest.hash(bytes);
                for (var candidate : bucket.candidates) {
                    if (candidate.kappa == null) { candidate.kappa = digest.hash(candidate.bytes); candidate.bytes = null; }
                    if (candidate.kappa.equals(kappa)) return candidate.facts;
                }
                var facts = parse.get();
                bucket.candidates.add(new Candidate(null, kappa, facts));
                return facts;
            }
        }
    }

    private final Digest digest;
    private final ConcurrentHashMap<String, Scope> scopes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> pending = new HashMap<>();

    /** @param locations every location of the boot, so a scope can be released when its last job is done */
    ClassMemo(Digest digest, List<Enumerate.Location> locations) {
        this.digest = digest;
        for (var location : locations) pending.computeIfAbsent(scopeOf(location), k -> new AtomicInteger()).incrementAndGet();
    }

    /** {@code group/artifact} for {@code group/artifact/version/file.jar}; the module for a JDK module; "" for a top-level jar. */
    static String scopeOf(Enumerate.Location location) {
        if (location.isModule()) return location.name();
        var parent = Path.of(location.name()).getParent();
        var grandparent = parent == null ? null : parent.getParent();
        return grandparent == null ? "" : grandparent.toString().replace('\\', '/');
    }

    Scope open(Enumerate.Location location) { return scopes.computeIfAbsent(scopeOf(location), k -> new Scope(digest)); }

    /** This location's job is over, whatever it did. The scope is dropped when no job of its directory is left. */
    void release(Enumerate.Location location) {
        var scope = scopeOf(location);
        if (pending.get(scope).decrementAndGet() == 0) scopes.remove(scope);
    }
}
