package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.DefinerIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code IndexMemo} (stage 2, 2.6 and 3.17): {@code leafSet ->} the built definer index and the leaf multiset it covers, so the
 * next route's index is built from the nearest one by difference. Nearest is the one with the smallest symmetric difference of
 * leaf keys. It also remembers which {@code DD|} and {@code DC|} records this boot has already written.
 */
final class IndexMemo {
    private record Entry(Identity leafSet, DefinerIndex.State state) { }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<Identity> disjointWritten = new HashSet<>();
    private final Set<Identity> conflictsWritten = new HashSet<>();

    /** The state whose leaves differ least from {@code sorted} (a sorted leaf multiset), or null if none is known. */
    synchronized DefinerIndex.State nearest(List<Identity> sorted) {
        DefinerIndex.State best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (var e : entries) {
            int d = DefinerIndex.distance(e.state().leaves(), sorted);
            if (d < bestDistance) { best = e.state(); bestDistance = d; }
        }
        return best;
    }

    synchronized void put(Identity leafSet, DefinerIndex.State state) {
        entries.removeIf(e -> e.leafSet().equals(leafSet));
        entries.add(new Entry(leafSet, state));
    }

    /** True if both records of this route were written already this boot. */
    synchronized boolean written(Identity leafSet, Identity routeHash) { return disjointWritten.contains(leafSet) && conflictsWritten.contains(routeHash); }

    synchronized void markWritten(Identity leafSet, Identity routeHash) { disjointWritten.add(leafSet); conflictsWritten.add(routeHash); }

    /** How many distinct leaf sets have an index: the {@code U} of the cost model (7.2). */
    synchronized int distinctLeafSets() { return disjointWritten.size(); }
}
