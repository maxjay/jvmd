package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

/**
 * The one primitive behind API diffing, upgrade impact and warm boot (stage 2, 5.2): what differs between two roots, descending
 * only where hashes differ. Equal subtrees are skipped whole. Small deltas need not imply few unequal nodes: adversarial
 * cap-only runs can shift chunk boundaries across a linear suffix.
 *
 * <p>Two shapes: {@link #trees} merges by key; {@link #lists} aligns chunks and compares only the runs of chunks that differ.
 */
public final class Diff {
    private Diff() { }

    /** Entries only in the first tree (removed) or only in the second (added); an entry whose {@code h} changed is in both. */
    public record Result(List<Entry> removed, List<Entry> added) {
        public boolean isEmpty() { return removed.isEmpty() && added.isEmpty(); }
    }

    /** A list element and where it stands: an index into the list it came from. */
    public record Positioned(int position, Entry element) { }

    public record ListResult(List<Positioned> removed, List<Positioned> added) {
        public boolean isEmpty() { return removed.isEmpty() && added.isEmpty(); }
    }

    /** Optional counters for ordered alignment; node fetches are measured separately by the supplied reader. */
    public static final class ListWork {
        long comparisons;
        int maxFrontierCells;
        public long comparisons() { return comparisons; }
        public int maxFrontierCells() { return maxFrontierCells; }
    }

    /** A node as its parent names it. {@code start} is the index of its first element, which only lists use. */
    private record Ref(Identity hash, int level, byte[] first, int count, int start) { }

    public static Result trees(Digest digest, Root a, Root b, Function<Identity, byte[]> reader) {
        var removed = new ArrayList<Entry>();
        var added = new ArrayList<Entry>();
        if (a.hash().equals(b.hash())) return new Result(removed, added);
        new Walk(digest.width(), reader, false).run(List.of(root(a)), List.of(root(b)), removed, added, null, null);
        return new Result(removed, added);
    }

    /** Exact key/value/h delta, including a routing value change whose projected semantic sum stayed equal. */
    public static Result content(Digest digest, Root a, Root b, Function<Identity, byte[]> reader) {
        var removed = new ArrayList<Entry>();
        var added = new ArrayList<Entry>();
        if (!a.hash().equals(b.hash())) {
            var walk = new Walk(digest.width(), reader, false);
            walk.exact = true;
            walk.run(List.of(root(a)), List.of(root(b)), removed, added, null, null);
        }
        return new Result(removed, added);
    }

    public static ListResult lists(Digest digest, Root a, Root b, Function<Identity, byte[]> reader) {
        return lists(digest, a, b, reader, null);
    }

    public static ListResult lists(Digest digest, Root a, Root b, Function<Identity, byte[]> reader, ListWork work) {
        var removed = new ArrayList<Positioned>();
        var added = new ArrayList<Positioned>();
        if (a.hash().equals(b.hash())) return new ListResult(removed, added);
        var walk = new Walk(digest.width(), reader, true);
        walk.listWork = work;
        walk.run(List.of(root(a)), List.of(root(b)), null, null, removed, added);
        return new ListResult(removed, added);
    }

    private static Ref root(Root r) { return new Ref(r.hash(), r.level(), new byte[0], r.count(), 0); }

    private static final class Walk {
        final int width;
        final Function<Identity, byte[]> reader;
        final boolean ordered;
        boolean exact;
        ListWork listWork;

        Walk(int width, Function<Identity, byte[]> reader, boolean ordered) {
            this.width = width;
            this.reader = reader;
            this.ordered = ordered;
        }

        void run(List<Ref> a, List<Ref> b, List<Entry> removed, List<Entry> added, List<Positioned> removedAt, List<Positioned> addedAt) {
            int i = 0, j = 0;
            while (i < a.size() || j < b.size()) {
                if (i < a.size() && j < b.size() && a.get(i).hash().equals(b.get(j).hash())) { i++; j++; continue; }
                // The next point where both sides hold an equal subtree again: everything before it is one differing run.
                var later = new HashMap<Identity, Integer>();
                for (int q = b.size() - 1; q >= j; q--) later.put(b.get(q).hash(), q);
                int i2 = i, j2 = b.size();
                for (; i2 < a.size(); i2++) {
                    var hit = later.get(a.get(i2).hash());
                    if (hit != null) { j2 = hit; break; }
                }
                differing(a.subList(i, i2), b.subList(j, j2), removed, added, removedAt, addedAt);
                i = i2;
                j = j2;
            }
        }

        /** One differing run: expand the tallest nodes until both sides are level 0, then compare entries. */
        void differing(List<Ref> a, List<Ref> b, List<Entry> removed, List<Entry> added, List<Positioned> removedAt, List<Positioned> addedAt) {
            if (a.isEmpty() && b.isEmpty()) return;
            int top = 0;
            for (var r : a) top = Math.max(top, r.level());
            for (var r : b) top = Math.max(top, r.level());
            if (top == 0) { compareLeaves(a, b, removed, added, removedAt, addedAt); return; }
            var a2 = expand(a, top);
            var b2 = expand(b, top);
            if (a2.isEmpty() || b2.isEmpty()) {
                // One side has nothing here: the other is wholly removed or added, so there is nothing to align.
                for (var r : a2) leaves(r, true, removed, removedAt);
                for (var r : b2) leaves(r, false, added, addedAt);
                return;
            }
            run(a2, b2, removed, added, removedAt, addedAt);
        }

        List<Ref> expand(List<Ref> refs, int level) {
            var out = new ArrayList<Ref>();
            for (var r : refs) {
                if (r.level() != level) { out.add(r); continue; }
                int start = r.start();
                for (var c : Node.children(reader.apply(r.hash()), width)) {
                    out.add(new Ref(c.hash(), level - 1, c.first(), c.count(), start));
                    start += c.count();
                }
            }
            return out;
        }

        List<Entry> entriesOf(Ref r) {
            var bytes = reader.apply(r.hash());
            return ordered ? Node.elements(bytes, width) : Node.entries(bytes, width);
        }

        /** Every leaf entry under {@code r}, into one side's output. */
        void leaves(Ref r, boolean removedSide, List<Entry> out, List<Positioned> outAt) {
            if (r.level() > 0) {
                for (var c : expand(List.of(r), r.level())) leaves(c, removedSide, out, outAt);
                return;
            }
            int at = r.start();
            for (var e : entriesOf(r)) {
                if (ordered) outAt.add(new Positioned(at++, e)); else out.add(e);
            }
        }

        void compareLeaves(List<Ref> a, List<Ref> b, List<Entry> removed, List<Entry> added, List<Positioned> removedAt, List<Positioned> addedAt) {
            if (ordered) { compareOrdered(a, b, removedAt, addedAt); return; }
            var x = new ArrayList<Entry>();
            var y = new ArrayList<Entry>();
            for (var r : a) x.addAll(entriesOf(r));
            for (var r : b) y.addAll(entriesOf(r));
            int i = 0, j = 0;
            while (i < x.size() || j < y.size()) {
                int c = i == x.size() ? 1 : j == y.size() ? -1 : Arrays.compareUnsigned(x.get(i).key(), y.get(j).key());
                if (c < 0) removed.add(x.get(i++));
                else if (c > 0) added.add(y.get(j++));
                else {
                    if (!x.get(i).h().equals(y.get(j).h()) || exact && !Arrays.equals(x.get(i).value(), y.get(j).value())) {
                        removed.add(x.get(i)); added.add(y.get(j));
                    }
                    i++; j++;
                }
            }
        }

        /** Unmatched runs use a linear-space edit frontier; their length is not bounded by a chunk cap. */
        void compareOrdered(List<Ref> a, List<Ref> b, List<Positioned> removedAt, List<Positioned> addedAt) {
            var x = new ArrayList<Positioned>();
            var y = new ArrayList<Positioned>();
            for (var r : a) { int at = r.start(); for (var e : entriesOf(r)) x.add(new Positioned(at++, e)); }
            for (var r : b) { int at = r.start(); for (var e : entriesOf(r)) y.add(new Positioned(at++, e)); }
            new OrderedDiff(x, y, removedAt, addedAt, listWork).run(0, x.size(), 0, y.size());
        }
    }
}
