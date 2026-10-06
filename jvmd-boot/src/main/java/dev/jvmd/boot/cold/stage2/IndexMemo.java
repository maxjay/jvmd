package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.DefinerIndex;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@code IndexMemo} (stage 2, 2.6, 3.6 and 3.17): the built definer states, per kind. The route plan supplies the parent state;
 * selecting it never searches the set of completed routes. External and sibling states remain independent.
 *
 * <p>A state is folded once per {@code (kind, key)} in a boot, however many jobs want it at the same moment: the first to ask claims
 * the key and folds, and the others wait for that fold and take its result ({@link #once}). Without the claim, sixteen workers whose
 * routes share one external leaf set would each fold the same JDK.
 */
final class IndexMemo {
    enum Kind { EXTERNAL, SIBLING }

    private record Key(Kind kind, Identity key) { }

    record States(DefinerIndex.State external, DefinerIndex.State sibling) { }

    private final ConcurrentHashMap<Key, CompletableFuture<DefinerIndex.State>> folds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, States> routes = new ConcurrentHashMap<>();
    private final Set<Identity> conflicts = new HashSet<>();
    private final AtomicInteger externalFolds = new AtomicInteger(), siblingFolds = new AtomicInteger();

    /**
     * The state of {@code (kind, key)}, folded by {@code fold} if this caller is the first to ask and taken from that fold otherwise. A
     * fold that fails fails every waiter, and the boot with it.
     */
    DefinerIndex.State once(Kind kind, Identity key, Supplier<DefinerIndex.State> fold) {
        var mine = new CompletableFuture<DefinerIndex.State>();
        var existing = folds.putIfAbsent(new Key(kind, key), mine);
        if (existing != null) return existing.join();
        try {
            var state = fold.get();
            (kind == Kind.EXTERNAL ? externalFolds : siblingFolds).incrementAndGet();
            mine.complete(state);
            return state;
        } catch (RuntimeException | Error failed) {
            mine.completeExceptionally(failed);
            throw failed;
        }
    }

    /** Published only after both disjoint roots can be read by a dependent job. */
    void route(String route, States states) { routes.put(route, states); }

    States route(String route) {
        var states = routes.get(route);
        if (states == null) throw new IllegalStateException("Route parent is not built: " + route);
        return states;
    }

    /** True the first time it is asked for this route in this boot: the caller then builds and writes its conflict table. */
    synchronized boolean claimConflicts(Identity routeHash) { return conflicts.add(routeHash); }

    /** How many distinct leaf sets have an index, external and sibling: the {@code U} of the cost model (7.2). */
    int distinctLeafSets() { return folds.size(); }

    /** How many folds ran, which is once per distinct leaf set. */
    int externalFolds() { return externalFolds.get(); }

    int siblingFolds() { return siblingFolds.get(); }
}
