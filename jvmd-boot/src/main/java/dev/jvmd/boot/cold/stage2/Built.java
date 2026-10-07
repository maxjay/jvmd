package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.Bind;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code Built} (stage 2, 2.6): module and scope to source leaf, for the routes of later modules to bind against. It carries the
 * {@link MachineLeaf} and not only its key, so a dependent reads no store for a leaf an earlier job built. A sibling entry binds to a
 * module's {@code main} leaf by exact coordinate (3.3), so that is what {@link #provider()} answers. A job registers its leaf after
 * its nodes are committed, and a dependent's job starts after its dependencies' jobs have finished: that is the hand-off.
 */
final class Built {
    private final ConcurrentHashMap<String, MachineLeaf> leaves = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Identity> annotations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HeaderView.Roots> views = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bind.Leaf> byCoordinate = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, MachineLeaf> byKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, CompletableFuture<MachineLeaf>> claims = new ConcurrentHashMap<>();

    /** The leaf of {@code module} for {@code scope}; the main leaf is also what the module's coordinate resolves to. */
    void register(String module, String coordinate, int scope, MachineLeaf leaf, Identity a) {
        register(module,coordinate,scope,leaf,a,null);
    }
    void register(String module, String coordinate, int scope, MachineLeaf leaf, Identity a, HeaderView.Roots view) {
        leaves.put(module + "\0" + scope, leaf);
        annotations.put(module + "\0" + scope, a);
        byKey.put(leaf.k(), leaf);
        if(view!=null)views.put(module+"\0"+scope,view);
        if (scope == 0) byCoordinate.put(coordinate, new Bind.Leaf(leaf.k(), a,view==null?null:view.reader()));
    }

    MachineLeaf leaf(String module, int scope) { return leaves.get(module + "\0" + scope); }

    Identity a(String module, int scope) { return annotations.get(module + "\0" + scope); }
    dev.jvmd.index.layer.local.SourceLeaf source(String module,int scope) {
        var view=views.get(module+"\0"+scope);
        return new dev.jvmd.index.layer.local.SourceLeaf(leaf(module,scope).k(),a(module,scope),
                view==null?null:view.classes(),view==null?null:view.reader());
    }

    /** A leaf some job of this boot has built and committed, by its key, or null. */
    MachineLeaf byKey(Identity k) { return byKey.get(k); }

    /**
     * The one place a source leaf is claimed: the first job to reach {@code k} runs {@code make}, which finds or writes the leaf and commits
     * its nodes, and every other job with the same {@code k} waits for that leaf. Two modules whose APIs are equal build it once.
     */
    MachineLeaf once(Identity k, Supplier<MachineLeaf> make) {
        var mine = new CompletableFuture<MachineLeaf>();
        var claimed = claims.putIfAbsent(k, mine);
        if (claimed != null) return claimed.join();
        try {
            var leaf = make.get();
            mine.complete(leaf);
            return leaf;
        } catch (RuntimeException | Error failed) {
            mine.completeExceptionally(failed);
            throw failed;
        }
    }

    Bind.Provider provider() {
        return byCoordinate::get;
    }
}
