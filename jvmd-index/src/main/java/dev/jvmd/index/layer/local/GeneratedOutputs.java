package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * GEN is an input derivation, its value is an exact output-set root, and GS holds individual content-addressed bytes.
 * The tree's sum is structural metadata only: no result key or validity proof consumes it.
 */
public final class GeneratedOutputs {
    private GeneratedOutputs() { }
    public record Output(int kind, String path, byte[] bytes) { }

    /** The same observed inputs produced different exact output sets: never overwrite the earlier derivation. */
    public static final class Inconsistent extends IllegalStateException {
        public Inconsistent() { super("Identical processor derivation inputs produced different output sets"); }
    }

    public static Root persist(Digest digest, ContentTree tree, LocalStore store, NodeSink sink, Identity derivation, List<Output> outputs) {
        // Store puts are thread-local batches. Serialize publication and flush before unlocking so another module cannot
        // miss an in-flight GEN value (or write the same GS/blob twice). This lock spans only generated-manifest storage.
        synchronized (store) { return persistLocked(digest, tree, store, sink, derivation, outputs); }
    }

    private static Root persistLocked(Digest digest, ContentTree tree, LocalStore store, NodeSink sink, Identity derivation, List<Output> outputs) {
        var entries = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        var seen = new HashSet<Identity>();
        for (var output : outputs) {
            var key = Keys.generatedOutputKey(output.kind(), output.path());
            var content = digest.hash(output.bytes());
            if (entries.putIfAbsent(key, new Entry(key, content.bytes(), digest.hash(key, content.view()))) != null)
                throw new IllegalArgumentException("Duplicate generated output: " + output.path());
            if (seen.add(content) && store.get(LocalStore.generatedSourceKey(content)) == null)
                store.put(LocalStore.generatedSourceKey(content), output.bytes());
        }
        var nodes = new HashSet<Identity>();
        var root = tree.build(new ArrayList<>(entries.values()), new NodeSink() {
            @Override public void write(dev.jvmd.core.tree.Node node) {
                if (nodes.add(node.hash()) && store.get(MachineStore.nodeKey(node.hash())) == null) sink.write(node);
            }
            @Override public void flush() { sink.flush(); }
        });
        var key = LocalStore.generatedKey(derivation);
        var previous = store.get(key);
        if (previous != null) {
            if (!DefinerIndex.decodeRoot(previous, digest.width()).hash().equals(root.hash())) throw new Inconsistent();
        } else {
            sink.flush(); // make all referenced nodes and blobs visible before publishing their derivation
            store.put(key, DefinerIndex.encodeRoot(root));
        }
        store.flush();
        return root;
    }
}
