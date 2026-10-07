package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.*;
import java.util.*;
import java.util.function.Function;

/** Exact current units with a C result but no admitted CI receipt. This is a selector, not a proof input. */
public final class BodyAdmission {
    private BodyAdmission() { }

    public static Root apply(ContentTree tree, Function<Identity,byte[]> nodes, NodeSink sink, Root before,
                             Map<SourceUnit,? extends Collection<Entry>> changed, Set<SourceUnit> retired,
                             boolean complete) {
        if (before == null) { before = tree.build(List.of(), sink); sink.flush(); }
        var removed = new TreeSet<byte[]>(Arrays::compareUnsigned);
        retired.forEach(unit -> removed.add(unit.encode()));
        // Only cold complete selection discovers old omissions. Delta publication never enumerates units.
        if (complete) tree.forEach(before.hash(), nodes, entry -> {
            var in = new Codec.Reader(entry.key()); var unit = SourceUnit.decode(in);
            if (in.remaining() != 0) throw new IllegalStateException("Invalid admission unit");
            if (!changed.containsKey(unit)) removed.add(entry.key());
        });
        var added = new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
        changed.forEach((unit, entries) -> {
            var key = unit.encode(); removed.add(key);
            if (unit.path().isEmpty()) return; // scope OUT / compatibility complete selection
            Entry proof = null; boolean admitted = false;
            for (var entry : entries) {
                if (BodyRecords.tag(entry.key(), "C")) {
                    if (proof != null) throw new IllegalArgumentException("Multiple unit proofs");
                    proof = entry;
                }
                if (BodyRecords.tag(entry.key(), "CI")) admitted = true;
            }
            // Descriptor CFs have no C and need no ordinary-body admission.
            if (proof != null && !admitted) {
                var value = proof.h().view();
                added.put(key, new Entry(key, value, tree.digest().hash(key, value)));
            }
        });
        return tree.apply(before, List.copyOf(removed), List.copyOf(added.values()), nodes, sink);
    }
}
