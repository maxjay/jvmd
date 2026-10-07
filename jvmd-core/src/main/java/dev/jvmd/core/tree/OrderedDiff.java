package dev.jvmd.core.tree;

import java.util.Arrays;
import java.util.List;

/** Myers' bidirectional edit frontier: O((n+m)D) comparison work, O(n+m) workspace, no n*m table.
 * Equal ends are trimmed at each split. A move is a removal and an addition; duplicate occurrences keep their positions.
 * D is the insertion/deletion distance of the unmatched run, so the time bound is still quadratic for large D. */
final class OrderedDiff {
    private final List<Diff.Positioned> a, b, removed, added;
    private final Diff.ListWork work;

    OrderedDiff(List<Diff.Positioned> a, List<Diff.Positioned> b, List<Diff.Positioned> removed,
                List<Diff.Positioned> added, Diff.ListWork work) {
        this.a = a; this.b = b; this.removed = removed; this.added = added; this.work = work;
    }

    private boolean same(int x, int y) {
        if (work != null) work.comparisons++;
        var p = a.get(x).element(); var q = b.get(y).element();
        return Arrays.equals(p.key(), q.key()) && p.h().equals(q.h());
    }

    void run(int x0, int x1, int y0, int y1) {
        while (x0 < x1 && y0 < y1 && same(x0, y0)) { x0++; y0++; }
        while (x0 < x1 && y0 < y1 && same(x1 - 1, y1 - 1)) { x1--; y1--; }
        if (x0 == x1) { added.addAll(b.subList(y0, y1)); return; }
        if (y0 == y1) { removed.addAll(a.subList(x0, x1)); return; }
        if (x1 - x0 == 1) {
            int match = y0;
            while (match < y1 && !same(x0, match)) match++;
            if (match == y1) { removed.add(a.get(x0)); added.addAll(b.subList(y0, y1)); }
            else { added.addAll(b.subList(y0, match)); added.addAll(b.subList(match + 1, y1)); }
            return;
        }
        if (y1 - y0 == 1) {
            int match = x0;
            while (match < x1 && !same(match, y0)) match++;
            if (match == x1) { added.add(b.get(y0)); removed.addAll(a.subList(x0, x1)); }
            else { removed.addAll(a.subList(x0, match)); removed.addAll(a.subList(match + 1, x1)); }
            return;
        }
        // Arrays belong only to split(), and are no longer retained during either recursive half.
        var split = split(x0, x1, y0, y1);
        if (split == null) { removed.addAll(a.subList(x0, x1)); added.addAll(b.subList(y0, y1)); return; }
        int x = x0 + split[0], y = y0 + split[1];
        if (x == x0 && y == y0 || x == x1 && y == y1) throw new IllegalStateException("Ordered diff did not advance");
        run(x0, x, y0, y); run(x, x1, y, y1);
    }

    private int[] split(int x0, int x1, int y0, int y1) {
        int n = x1 - x0, m = y1 - y0, max = (n + m + 1) / 2, offset = max + 1;
        var forward = new int[2 * max + 3]; var reverse = new int[forward.length];
        if (work != null) work.maxFrontierCells = Math.max(work.maxFrontierCells, forward.length + reverse.length);
        Arrays.fill(forward, -1); Arrays.fill(reverse, -1);
        forward[offset + 1] = reverse[offset + 1] = 0;
        int delta = n - m; boolean odd = (delta & 1) != 0;
        int fStart = 0, fEnd = 0, rStart = 0, rEnd = 0;
        for (int d = 0; d <= max; d++) {
            for (int k = -d + fStart; k <= d - fEnd; k += 2) {
                int at = offset + k;
                int x = k == -d || k != d && forward[at - 1] < forward[at + 1] ? forward[at + 1] : forward[at - 1] + 1;
                int y = x - k;
                while (x < n && y < m && same(x0 + x, y0 + y)) { x++; y++; }
                forward[at] = x;
                if (x > n) fEnd += 2;
                else if (y > m) fStart += 2;
                else if (odd) {
                    int other = offset + delta - k;
                    if (other >= 0 && other < reverse.length && reverse[other] != -1 && x >= n - reverse[other])
                        return new int[]{x, y};
                }
            }
            for (int k = -d + rStart; k <= d - rEnd; k += 2) {
                int at = offset + k;
                int x = k == -d || k != d && reverse[at - 1] < reverse[at + 1] ? reverse[at + 1] : reverse[at - 1] + 1;
                int y = x - k;
                while (x < n && y < m && same(x1 - x - 1, y1 - y - 1)) { x++; y++; }
                reverse[at] = x;
                if (x > n) rEnd += 2;
                else if (y > m) rStart += 2;
                else if (!odd) {
                    int other = offset + delta - k;
                    if (other >= 0 && other < forward.length && forward[other] != -1 && forward[other] >= n - x)
                        return new int[]{forward[other], forward[other] - (delta - k)};
                }
            }
        }
        return null;
    }
}
