package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.Bind;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code Built} (stage 2, 2.6): module and scope to source leaf key, for the routes of later modules to bind against. A sibling
 * entry binds to a module's {@code main} leaf by exact coordinate (3.3), so that is what {@link #provider()} answers.
 */
final class Built {
    private final ConcurrentHashMap<String, Identity> leaves = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Identity> byCoordinate = new ConcurrentHashMap<>();

    /** The leaf of {@code module} for {@code scope}; the main leaf is also what the module's coordinate resolves to. */
    void register(String module, String coordinate, int scope, Identity k) {
        leaves.put(module + "\0" + scope, k);
        if (scope == 0) byCoordinate.put(coordinate, k);
    }

    Identity leaf(String module, int scope) { return leaves.get(module + "\0" + scope); }

    Bind.Provider provider() { return byCoordinate::get; }
}
