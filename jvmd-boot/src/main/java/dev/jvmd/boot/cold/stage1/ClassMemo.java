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

/**
 * The class memo (stage 1, 2.6 and 3.8): {@code (crc32, size) -> (κ -> Φ)}, where {@code κ = Digest(class bytes)}. A class file
 * that appears again, byte for byte, is not parsed again. Adjacent versions of a library share most class files, and an internal
 * library republished unchanged shares nearly all of them.
 *
 * <p>It changes nothing about the output: with or without it the same leaves and the same ROOT. Faults are not memoized.
 *
 * <p><b>It never retains class bytes.</b> A class is looked up by its {@code (crc32, size)}, which are free from a jar's central
 * directory. {@code κ} is computed from the bytes when a class is first parsed and is stored with its facts; a later class with
 * the same {@code (crc32, size)} computes its own {@code κ} and compares it, because a CRC match is not byte equality. Only
 * {@code κ} and the facts are kept, never the bytes.
 *
 * <p><b>Scope.</b> All versions of an artifact sit under one directory ({@code group/artifact/version/file.jar}), so the memo
 * lives while jobs of that directory are running and is released when the last of them finishes. A scope with exactly one
 * location (a directory with a single version, and every JDK module, which is a scope of its own) cannot repeat a class across
 * jobs, so it is a no-op: no map, no {@code κ}, no allocation. Only a scope with two or more locations pays for memoizing.
 */
final class ClassMemo {
    /** A parse that may fault. */
    @FunctionalInterface interface Parse { ClassFacts get() throws ClassFacts.Fault; }

    /** One distinct class: its κ and facts. No bytes. */
    private record Known(Identity kappa, ClassFacts facts) { }

    /** All classes with one {@code (crc32, size)}. Guarded by its own monitor: the same class is parsed once, other buckets are free. */
    private static final class Bucket {
        final List<Known> known = new ArrayList<>(1);
    }

    /** The classes seen so far in one artifact directory, or a pass-through when the directory cannot repeat a class. */
    static final class Scope {
        /** The scope of a single location: every call parses. */
        static final Scope OFF = new Scope(null);

        private final Digest digest;
        private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

        Scope(Digest digest) { this.digest = digest; }

        /**
         * The facts of {@code bytes}, parsing at most once per distinct content in this scope.
         *
         * @param crc  the entry's CRC-32 from the central directory (unused in the off scope)
         * @param size the entry's size from the central directory (unused in the off scope)
         */
        ClassFacts get(long crc, long size, byte[] bytes, Parse parse) throws ClassFacts.Fault {
            if (digest == null) return parse.get();
            var kappa = digest.hash(bytes);
            // 32 bits of crc and 32 of size: the bucket key. A collision only puts two classes in one bucket, where κ decides.
            var key = (crc << 32) ^ (size & 0xFFFFFFFFL);
            var bucket = buckets.computeIfAbsent(key, k -> new Bucket());
            synchronized (bucket) {
                for (var candidate : bucket.known) if (candidate.kappa().equals(kappa)) return candidate.facts();
                var facts = parse.get();
                bucket.known.add(new Known(kappa, facts));
                return facts;
            }
        }
    }

    private final Digest digest;
    private final ConcurrentHashMap<String, Scope> scopes = new ConcurrentHashMap<>();
    /** How many locations each scope has in this boot. Fixed at the start: whether a scope memoizes must not change as jobs finish. */
    private final Map<String, Integer> total = new HashMap<>();
    /** Jobs of each scope not yet finished, to release the scope after the last. */
    private final Map<String, AtomicInteger> pending = new HashMap<>();

    /** @param locations every location of the boot: how many a scope has decides whether it memoizes, and when it is released */
    ClassMemo(Digest digest, List<Enumerate.Location> locations) {
        this.digest = digest;
        for (var location : locations) total.merge(scopeOf(location), 1, Integer::sum);
        total.forEach((scope, n) -> pending.put(scope, new AtomicInteger(n)));
    }

    /** {@code group/artifact} for {@code group/artifact/version/file.jar}; the module for a JDK module; "" for a top-level jar. */
    static String scopeOf(Enumerate.Location location) {
        if (location.isModule()) return location.name();
        var parent = Path.of(location.name()).getParent();
        var grandparent = parent == null ? null : parent.getParent();
        return grandparent == null ? "" : grandparent.toString().replace('\\', '/');
    }

    Scope open(Enumerate.Location location) {
        var scope = scopeOf(location);
        // One location in the scope: nothing can repeat across jobs, so there is nothing to remember.
        if (total.get(scope) == 1) return Scope.OFF;
        return scopes.computeIfAbsent(scope, k -> new Scope(digest));
    }

    /** This location's job is over, whatever it did. The scope is dropped when no job of its directory is left. */
    void release(Enumerate.Location location) {
        var scope = scopeOf(location);
        if (pending.get(scope).decrementAndGet() == 0) scopes.remove(scope);
    }
}
