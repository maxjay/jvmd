package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.Bind;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code Built} (stage 2, 2.6): module and scope to source leaf, for the routes of later modules to bind against. It carries the
 * {@link MachineLeaf} and not only its key, so a dependent reads no store for a leaf an earlier job built. A sibling entry binds to a
 * module's {@code main} leaf by exact coordinate (3.3), so that is what {@link #provider()} answers. A job registers its leaf after
 * its nodes are committed, and a dependent's job starts after its dependencies' jobs have finished: that is the hand-off.
 */
final class Built {
    private final ConcurrentHashMap<String, MachineLeaf> leaves = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MachineLeaf> byCoordinate = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, MachineLeaf> byKey = new ConcurrentHashMap<>();

    /** The leaf of {@code module} for {@code scope}; the main leaf is also what the module's coordinate resolves to. */
    void register(String module, String coordinate, int scope, MachineLeaf leaf) {
        leaves.put(module + "\0" + scope, leaf);
        byKey.put(leaf.k(), leaf);
        if (scope == 0) byCoordinate.put(coordinate, leaf);
    }

    MachineLeaf leaf(String module, int scope) { return leaves.get(module + "\0" + scope); }

    /** A leaf some job of this boot has built and committed, by its key, or null. */
    MachineLeaf byKey(Identity k) { return byKey.get(k); }

    Bind.Provider provider() {
        return coordinate -> { var leaf = byCoordinate.get(coordinate); return leaf == null ? null : leaf.k(); };
    }
}
