package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The class memo (stage 1, 3.8): class key {@code κ = Digest(class bytes)} to its facts, so a class file that appears again, byte
 * for byte, is not parsed again. Adjacent versions of a library share most class files, and an internal library published in
 * hundreds of versions shares nearly all of them.
 *
 * <p>It changes nothing about the output: with or without it the same leaves and the same ROOT. A memo hit still inflates the
 * entry (that is how {@code κ} is known) and skips only the parse. Faults are not memoized.
 *
 * <p>Scope: all versions of an artifact sit under one directory ({@code group/artifact/version/file.jar}), so the memo lives
 * while jobs of that directory are running and is released when the last of them finishes. A JDK module is its own scope.
 * Memory is therefore bounded by the facts of the artifact directories in flight, not by the repository.
 */
final class ClassMemo {
    /** A parse that may fault. */
    @FunctionalInterface interface Parse { ClassFacts get() throws ClassFacts.Fault; }

    /** Carries a {@link ClassFacts.Fault} out of {@code computeIfAbsent}, which leaves no entry behind. */
    private static final class Unparsable extends RuntimeException {
        final ClassFacts.Fault fault;
        Unparsable(ClassFacts.Fault fault) { super(fault); this.fault = fault; }
    }

    /** The classes seen so far in one artifact directory. Facts are immutable, so jobs of the directory share them freely. */
    static final class Scope {
        private final ConcurrentHashMap<Identity, ClassFacts> byContent = new ConcurrentHashMap<>();

        ClassFacts get(Identity kappa) { return byContent.get(kappa); }

        /** Parses {@code kappa} exactly once per scope even when jobs of the directory ask at the same moment. */
        ClassFacts getOrParse(Identity kappa, Parse parse) throws ClassFacts.Fault {
            try {
                return byContent.computeIfAbsent(kappa, k -> {
                    try { return parse.get(); } catch (ClassFacts.Fault fault) { throw new Unparsable(fault); }
                });
            } catch (Unparsable unparsable) { throw unparsable.fault; }
        }
    }

    private final ConcurrentHashMap<String, Scope> scopes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> pending = new HashMap<>();

    /** @param locations every location of the boot, so a scope can be released when its last job is done */
    ClassMemo(List<Enumerate.Location> locations) {
        for (var location : locations) pending.computeIfAbsent(scopeOf(location), k -> new AtomicInteger()).incrementAndGet();
    }

    /** {@code group/artifact} for {@code group/artifact/version/file.jar}; the module for a JDK module; "" for a top-level jar. */
    static String scopeOf(Enumerate.Location location) {
        if (location.isModule()) return location.name();
        var parent = Path.of(location.name()).getParent();
        var grandparent = parent == null ? null : parent.getParent();
        return grandparent == null ? "" : grandparent.toString().replace('\\', '/');
    }

    Scope open(Enumerate.Location location) { return scopes.computeIfAbsent(scopeOf(location), k -> new Scope()); }

    /** This location's job is over, whatever it did. The scope is dropped when no job of its directory is left. */
    void release(Enumerate.Location location) {
        var scope = scopeOf(location);
        if (pending.get(scope).decrementAndGet() == 0) scopes.remove(scope);
    }
}
