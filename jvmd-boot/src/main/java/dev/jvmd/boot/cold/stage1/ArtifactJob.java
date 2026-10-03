package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Step 2 (stage 1, 4 and 5.4): one location in, one leaf (or an attach) out. Facts are streamed in owner order into the chunker
 * of {@code T}, so a job's memory is one open chunk per level plus the {@code N}, {@code E} and {@code O} buffers of its jar,
 * independent of jar size.
 */
final class ArtifactJob {

    private final Digest digest;
    private final ContentTree tree;
    private final int jdkFeature;
    private final Seen seen;
    private final Leaves leaves;
    private final NodeSink sink;
    private final MachineStore store;
    private final Stage1.Parser parser;
    private final ConcurrentLinkedQueue<String> locationFaults;
    /** Null when the class memo is off. */
    private final ClassMemo memo;

    ArtifactJob(Digest digest, ContentTree tree, int jdkFeature, Seen seen, Leaves leaves, Written written, MachineStore store,
                Stage1.Parser parser, ConcurrentLinkedQueue<String> locationFaults, ClassMemo memo) {
        this.memo = memo;
        this.digest = digest;
        this.tree = tree;
        this.jdkFeature = jdkFeature;
        this.seen = seen;
        this.leaves = leaves;
        this.sink = written.through(store);
        this.store = store;
        this.parser = parser;
        this.locationFaults = locationFaults;
    }

    void run(Enumerate.Location location) {
        try { work(location); } finally { if (memo != null) memo.release(location); }
    }

    /** Φ of one class entry, through the memo when it is on. A memo hit is re-checked against the owner the entry name promises. */
    private ClassFacts facts(ClassMemo.Scope scope, byte[] classBytes, String owner) throws ClassFacts.Fault {
        if (scope == null) return parser.parse(digest, classBytes, owner);
        var known = scope.getOrParse(digest.hash(classBytes), () -> parser.parse(digest, classBytes, owner));
        if (known.ownerKey().equals(owner)) return known;
        // Same bytes, but this entry's name promises another owner: parse for real so the mismatch is the fault it must be.
        return parser.parse(digest, classBytes, owner);
    }

    private void work(Enumerate.Location location) {
        byte[] bytes = null;
        Identity bh;
        if (location.isModule()) {
            bh = location.bh();
        } else {
            try {
                bytes = Files.readAllBytes(location.file());
            } catch (IOException unreadable) {
                // No bytes, so no byte hash: the path table records the zero identity and no leaf (3.9).
                locationFaults.add(location.name() + ": " + unreadable.getMessage());
                var none = Identity.zero(digest.width());
                seen.observe(location, none);
                if (seen.claim(none)) leaves.fault(none);
                return;
            }
            bh = digest.hash(bytes);
        }
        seen.observe(location, bh);
        if (!seen.claim(bh)) return; // another location holds these bytes; its job decides this location's leaf

        Entries entries;
        List<Entries.Item> classes;
        try {
            entries = location.isModule() ? Entries.module(location.jrt(), location.module()) : Entries.zip(bytes, jdkFeature);
            classes = entries.classes();
        } catch (IOException unreadable) {
            locationFaults.add(location.name() + ": " + unreadable.getMessage());
            leaves.fault(bh);
            return;
        }

        var sums = tree.sums();
        var chunker = tree.chunker(sink);
        var names = new ArrayList<Entry>();
        var edges = new ArrayList<Entry>();
        var types = new ArrayList<Entry>();
        var faults = new ArrayList<String>();
        String owner = null;
        var ownerSum = sums.zero();
        var total = sums.zero();
        var scope = memo == null ? null : memo.open(location);
        for (var item : classes) {
            ClassFacts facts;
            try {
                facts = facts(scope, entries.read(item), item.owner());
            } catch (ClassFacts.Fault | IOException fault) {
                faults.add(item.path());
                continue;
            }
            if (!facts.ownerKey().equals(owner)) {
                if (owner != null) types.add(typeEntry(owner, ownerSum));
                owner = facts.ownerKey();
                ownerSum = sums.zero();
            }
            for (var fact : facts.facts()) {
                chunker.add(fact.entry());
                ownerSum = sums.add(ownerSum, fact.h());
                total = sums.add(total, fact.h());
            }
            names.addAll(facts.byName());
            edges.addAll(facts.edges());
        }
        if (owner != null) types.add(typeEntry(owner, ownerSum));
        bytes = null; // release the jar before N, E and O are built

        var tRoot = chunker.finish();
        var k = tRoot.hash();
        var r = tRoot.sum();
        if (!r.equals(total)) throw new IllegalStateException("Invariant: running sum differs from the tree sum in " + location.name());
        if (!leaves.claim(k, location.name())) {
            leaves.attach(k, bh);
            sink.flush();
            return;
        }

        names.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        edges.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        var nRoot = tree.build(names, sink);
        var eRoot = tree.build(edges, sink);
        var oRoot = tree.build(types, sink);
        if (!nRoot.sum().equals(r) || !oRoot.sum().equals(r))
            throw new IllegalStateException("Invariant: N or O sum differs from the leaf sum in " + location.name());
        var leaf = new MachineLeaf(k, r, nRoot.hash(), nRoot.level(), eRoot.hash(), eRoot.sum(), eRoot.level(), oRoot.hash(), oRoot.level(),
                tRoot.count(), types.size(), eRoot.count(), List.copyOf(faults));
        // L itself is written at commit, once the canonical owner of k is settled (see Leaves); only its nodes are flushed here.
        leaves.register(k, location.name(), leaf, bh);
        sink.flush();
    }

    private Entry typeEntry(String owner, Identity sum) {
        return new Entry(new dev.jvmd.core.tree.Codec.Writer(owner.length() + 1).zstr(owner).toBytes(), Entry.NONE, sum);
    }
}
