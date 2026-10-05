package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.DefinerIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code IndexMemo} (stage 2, 2.6, 3.6 and 3.17): the built definer states, per kind, so that the next index of a kind is folded from
 * the nearest one by leaf difference. Nearest is the state of the same kind with the smallest symmetric difference of leaf keys; an
 * external state is never a base for a sibling one, because they hold different leaves and change at different rates. It also
 * remembers which definer records this boot has already put into the project's tree, and which conflict tables it has written.
 */
final class IndexMemo {
    enum Kind { EXTERNAL, SIBLING }

    private record Entry(Kind kind, Identity key, DefinerIndex.State state) { }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<Entry> recorded = new HashSet<>();
    private final Set<Identity> conflicts = new HashSet<>();

    /** The state already folded for this exact leaf set, or null. */
    synchronized DefinerIndex.State stateFor(Kind kind, Identity key) {
        for (var e : entries) if (e.kind() == kind && e.key().equals(key)) return e.state();
        return null;
    }

    /** The state of {@code kind} whose leaves differ least from {@code sorted} (sorted distinct leaves), or null if none is known. */
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

    synchronized void put(Kind kind, Identity key, DefinerIndex.State state) {
        entries.removeIf(e -> e.kind() == kind && e.key().equals(key));
        entries.add(new Entry(kind, key, state));
    }

    /** True the first time it is asked for this record in this boot: the caller then puts the record into the project's tree. */
    synchronized boolean claimRecord(Kind kind, Identity key) { return recorded.add(new Entry(kind, key, null)); }

    /** True the first time it is asked for this route in this boot: the caller then builds and writes its conflict table. */
    synchronized boolean claimConflicts(Identity routeHash) { return conflicts.add(routeHash); }

    /** How many distinct leaf sets have an index, external and sibling: the {@code U} of the cost model (7.2). */
    synchronized int distinctLeafSets() { return recorded.size(); }
}
