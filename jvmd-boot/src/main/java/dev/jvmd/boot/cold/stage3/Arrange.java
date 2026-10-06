package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.local.Proof;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/** Groups collected body ranges against the current own-first binding (stage 3, 5.4). */
public final class Arrange {
    private Arrange() { }

    public static Proof proof(ContentTree tree, MachineLeaf own, Route route, ProcessorRecords.Context processor,
                              Collection<Proof.Range> ranges, Collection<String> absent, Function<byte[], byte[]> records) {
        var read = new DefinerIndex.Reader(tree, own, route, records);
        var groups = new TreeMap<String, java.util.List<Proof.Range>>();
        for (var range : new TreeSet<>(ranges)) groups.computeIfAbsent(range.type(), ignored -> new ArrayList<>()).add(range);
        var types = new ArrayList<Proof.Type>();
        for (var group : groups.entrySet()) {
            var leaf = read.definer(group.getKey());
            if (leaf == null) throw new IllegalArgumentException("Collected type has no current definer: " + group.getKey());
            var entries = new ArrayList<Proof.Entry>();
            for (var range : group.getValue()) entries.add(new Proof.Entry(range,
                    tree.rangeSum(range.form() == Proof.T ? leaf.k() : leaf.nHash(), read.nodes(), range.prefix())));
            types.add(new Proof.Type(group.getKey(), tree.rangeSum(leaf.oHash(), read.nodes(), Keys.ownerKey(group.getKey())), entries));
        }
        var absences = new TreeSet<>(absent);
        for (var type : absences) if (!read.absent(type)) throw new IllegalArgumentException("Collected type is present: " + type);
        return new Proof(new Proof.Header(route.routeHash(), read.external().sum(), read.sibling().sum(), read.conflicts().sum(), own.r(), processor),
                types, new ArrayList<>(absences));
    }
}
