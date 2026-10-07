package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.local.Proof;
import dev.jvmd.index.layer.local.ProofCollector;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.UsesRecord;
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

    public record Body(Proof proof, UsesRecord uses) { }

    /** A failed simple-name lookup may have seen an inaccessible or ambiguous, but present, declaration. */
    public static Body body(ContentTree tree, MachineLeaf own, Route route, ProcessorRecords.Context processor,
                            ProofCollector.Body collected, Function<byte[], byte[]> records) {
        return body(tree,own,route,processor,collected,java.util.List.of(),records);
    }

    /** The complete detached proof is observable only after native reader questions have been attached. */
    public static Body body(ContentTree tree, MachineLeaf own, Route route, ProcessorRecords.Context processor,
                            ProofCollector.Body collected, java.util.List<Proof.ReaderRead> readers, Function<byte[], byte[]> records) {
        var read = new DefinerIndex.Reader(tree, own, route, records);
        var ranges = new TreeSet<>(collected.ranges());
        var absent = new TreeSet<String>();
        var present = new java.util.HashSet<String>();
        for (String type : collected.typeLookups()) {
            if (read.absent(type)) absent.add(type);
            else { present.add(type); ranges.add(new Proof.Range(Proof.T,type,Keys.TYPE,"")); }
        }
        var spans = new TreeMap<UsesRecord.Use, TreeSet<UsesRecord.Span>>();
        for (var use : collected.uses().uses()) {
            var key = new UsesRecord.Use(use.tree() == UsesRecord.D && present.contains(use.type()) ? Proof.T : use.tree(),
                    use.type(),use.kind(),use.name(),java.util.List.of());
            spans.computeIfAbsent(key,ignored -> new TreeSet<>()).addAll(use.spans());
        }
        var uses = new ArrayList<UsesRecord.Use>();
        spans.forEach((key,positions) -> uses.add(new UsesRecord.Use(key.tree(),key.type(),key.kind(),key.name(),new ArrayList<>(positions))));
        return new Body(proof(tree,own,route,processor,ranges,absent,read).withReaderReads(readers),new UsesRecord(uses));
    }

    public static Proof proof(ContentTree tree, MachineLeaf own, Route route, ProcessorRecords.Context processor,
                              Collection<Proof.Range> ranges, Collection<String> absent, Function<byte[], byte[]> records) {
        var read = new DefinerIndex.Reader(tree, own, route, records);
        return proof(tree,own,route,processor,ranges,absent,read);
    }

    private static Proof proof(ContentTree tree, MachineLeaf own, Route route, ProcessorRecords.Context processor,
                               Collection<Proof.Range> ranges, Collection<String> absent, DefinerIndex.Reader read) {
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
