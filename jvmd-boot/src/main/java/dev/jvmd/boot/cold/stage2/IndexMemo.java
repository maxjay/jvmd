package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.DefinerIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@code IndexMemo} (stage 2, 2.6, 3.6 and 3.17): the built definer states, per kind, so that the next index of a kind is folded from
 * the nearest one by leaf difference. Nearest is the state of the same kind with the smallest symmetric difference of leaf keys; an
 * external state is never a base for a sibling one, because they hold different leaves and change at different rates.
 *
 * <p>A state is folded once per {@code (kind, key)} in a boot, however many jobs want it at the same moment: the first to ask claims
 * the key and folds, and the others wait for that fold and take its result ({@link #once}). Without the claim, sixteen workers whose
 * routes share one external leaf set would each fold the same JDK.
 */
final class IndexMemo {
    enum Kind { EXTERNAL, SIBLING }

    private record Key(Kind kind, Identity key) { }

    private record Entry(Kind kind, Identity key, DefinerIndex.State state) { }

    private final ConcurrentHashMap<Key, CompletableFuture<DefinerIndex.State>> folds = new ConcurrentHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
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
            synchronized (this) { entries.add(new Entry(kind, key, state)); }
            (kind == Kind.EXTERNAL ? externalFolds : siblingFolds).incrementAndGet();
            mine.complete(state);
            return state;
        } catch (RuntimeException | Error failed) {
            mine.completeExceptionally(failed);
            throw failed;
        }
    }

    /** The finished state of {@code kind} whose leaves differ least from {@code sorted} (sorted distinct leaves), or null if none is known. */
    synchronized DefinerIndex.State nearest(Kind kind, List<Identity> sorted) {
        DefinerIndex.State best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (var e : entries) {
            if (e.kind() != kind) continue;
            int d = DefinerIndex.distance(e.state().leaves(), sorted);
            if (d < bestDistance) { best = e.state(); bestDistance = d; }
        }
        return best;
    }

    /** True the first time it is asked for this route in this boot: the caller then builds and writes its conflict table. */
    synchronized boolean claimConflicts(Identity routeHash) { return conflicts.add(routeHash); }

    /** How many distinct leaf sets have an index, external and sibling: the {@code U} of the cost model (7.2). */
    int distinctLeafSets() { return folds.size(); }

    /** How many folds ran, which is once per distinct leaf set. */
    int externalFolds() { return externalFolds.get(); }

    int siblingFolds() { return siblingFolds.get(); }
}
