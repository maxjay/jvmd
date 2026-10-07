package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.*;
import java.util.*;
import java.util.function.Function;

/** Persistent unit manifests and exact reference counts. Delta publication opens only changed units and touched records. */
public final class BodySelection {
    private BodySelection() { }
    public record State(Root units, Root counts) {
        public byte[] encode() {
            return new Codec.Writer().raw(DefinerIndex.encodeRoot(units)).raw(DefinerIndex.encodeRoot(counts)).toBytes();
        }
        public static State decode(byte[] bytes, int width) {
            var in = new Codec.Reader(bytes);
            int size = 2 * width + 5;
            var state = new State(DefinerIndex.decodeRoot(in.raw(size), width), DefinerIndex.decodeRoot(in.raw(size), width));
            if (in.remaining() != 0) throw new IllegalArgumentException("Trailing selection state");
            return state;
        }
    }
    public record Delta(State state, List<Entry> removed, List<Entry> added, List<Entry> uses) { }

    public static Delta apply(ContentTree tree, Function<Identity,byte[]> nodes, NodeSink sink, State before,
                              Map<SourceUnit,? extends Collection<Entry>> changed, Set<SourceUnit> retired, boolean complete) {
        var empty = before == null ? tree.build(List.of(), sink) : null;
        if (before == null) before = new State(empty, empty);
        var units = before.units(); var counts = before.counts();
        var touched = new TreeSet<SourceUnit>(retired); touched.addAll(changed.keySet());
        if (complete) tree.forEach(units.hash(), nodes, entry -> {
            var in = new Codec.Reader(entry.key()); var unit = SourceUnit.decode(in);
            if (in.remaining() != 0) throw new IllegalStateException("Invalid selection unit");
            if (!changed.containsKey(unit)) touched.add(unit);
        });
        var unitRemoved = new ArrayList<byte[]>(); var unitAdded = new ArrayList<Entry>();
        var changes = new TreeMap<byte[],Map<Identity,Long>>(Arrays::compareUnsigned);
        var uses = new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
        for (var unit : touched) {
            if (retired.contains(unit) && changed.containsKey(unit)) throw new IllegalArgumentException("Changed and retired unit");
            var key = unit.encode(); var old = tree.get(units.hash(), nodes, key);
            Root previous = old == null ? null : DefinerIndex.decodeRoot(old.value(), tree.digest().width());
            var nextEntries = new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
            var supplied = changed.get(unit);
            if (supplied != null) for (var entry : supplied) {
                if ((!BodyRecords.isBody(entry.key()) && !BodyRecords.tag(entry.key(), "U"))
                        || BodyRecords.tag(entry.key(), "BM")) throw new IllegalArgumentException("Invalid unit body entry");
                var duplicate = nextEntries.putIfAbsent(entry.key(), entry);
                if (duplicate != null && !duplicate.h().equals(entry.h())) throw new IllegalArgumentException("Conflicting unit entries");
                if (BodyRecords.tag(entry.key(), "U")) uses.put(entry.key(), entry);
            }
            Root next = supplied == null ? null : tree.build(nextEntries.values(), sink);
            sink.flush();
            if (Objects.equals(previous, next)) continue;
            if (old != null) unitRemoved.add(key);
            if (next != null) {
                var value = DefinerIndex.encodeRoot(next);
                unitAdded.add(new Entry(key, value, tree.digest().hash(key, value)));
            }
            if (previous == null) nextEntries.values().forEach(e -> add(changes, e, 1));
            else if (next == null) tree.forEach(previous.hash(), nodes, e -> add(changes, e, -1));
            else {
                var diff = Diff.trees(tree.digest(), previous, next, nodes);
                diff.removed().forEach(e -> add(changes, e, -1)); diff.added().forEach(e -> add(changes, e, 1));
            }
        }
        var countRemoved = new ArrayList<byte[]>(); var countAdded = new ArrayList<Entry>();
        var removed = new ArrayList<Entry>(); var added = new ArrayList<Entry>();
        for (var change : changes.entrySet()) {
            if (change.getValue().values().stream().allMatch(n -> n == 0)) continue;
            var key = change.getKey(); var old = tree.get(counts.hash(), nodes, key);
            long oldCount = 0; Identity oldHash = null;
            if (old != null) {
                var in = new Codec.Reader(old.value()); oldCount = in.u32(); oldHash = in.id(tree.digest().width());
                if (oldCount == 0 || in.remaining() != 0) throw new IllegalStateException("Invalid body ownership count");
            }
            var remaining = new HashMap<>(change.getValue());
            if (oldHash != null) remaining.merge(oldHash, oldCount, Math::addExact);
            remaining.values().removeIf(n -> n == 0);
            if (remaining.values().stream().anyMatch(n -> n < 0 || n > 0xffff_ffffL))
                throw new IllegalStateException("Invalid body selection ownership");
            if (remaining.size() > 1) throw new IllegalStateException("Conflicting values selected by different units");
            var selected = remaining.entrySet().stream().findFirst().orElse(null);
            Identity hash = selected == null ? null : selected.getKey();
            long count = selected == null ? 0 : selected.getValue();
            if (oldCount == count && Objects.equals(oldHash, hash)) continue;
            if (old != null) countRemoved.add(key);
            if (hash != null) {
                var value = new Codec.Writer().u32(count).id(hash).toBytes();
                countAdded.add(new Entry(key, value, tree.digest().hash(key, value)));
            }
            if (!Objects.equals(oldHash, hash)) {
                if (oldHash != null) removed.add(new Entry(key, Entry.NONE, oldHash));
                if (hash != null) added.add(new Entry(key, Entry.NONE, hash));
            }
        }
        return new Delta(new State(tree.apply(units, unitRemoved, unitAdded, nodes, sink),
                tree.apply(counts, countRemoved, countAdded, nodes, sink)), List.copyOf(removed), List.copyOf(added), List.copyOf(uses.values()));
    }
    private static void add(Map<byte[],Map<Identity,Long>> changes, Entry entry, long direction) {
        if (!BodyRecords.tag(entry.key(), "U"))
            changes.computeIfAbsent(entry.key(), ignored -> new HashMap<>()).merge(entry.h(), direction, Math::addExact);
    }
}
