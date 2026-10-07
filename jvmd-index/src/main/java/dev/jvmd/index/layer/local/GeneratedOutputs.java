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

    /** Equal entry count plus distinct exact keys and content ids proves equality of the complete output map. No blobs are read. */
    public static boolean matches(ContentTree tree, Root expected, java.util.function.Function<Identity, byte[]> nodes, List<Output> outputs) {
        if (expected.count() != outputs.size()) return false;
        var seen = new HashSet<java.nio.ByteBuffer>();
        for (var output : outputs) {
            var key = Keys.generatedOutputKey(output.kind(), output.path());
            if (!seen.add(java.nio.ByteBuffer.wrap(key))) return false;
            var entry = tree.get(expected.hash(), nodes, key);
            if (entry == null || !Arrays.equals(entry.value(), tree.digest().hash(output.bytes()).view())) return false;
        }
        return true;
    }

    public static Root persist(Digest digest, ContentTree tree, LocalStore store, NodeSink sink, Identity derivation, List<Output> outputs) {
        return persist(digest,tree,store,sink,derivation,outputs,null);
    }
    /** A caller with known derivation ancestry can edit its prior manifest. A cold caller supplies no invented parent. */
    public static Root persist(Digest digest,ContentTree tree,LocalStore store,NodeSink sink,Identity derivation,List<Output> outputs,Root parent) {
        // Store puts are thread-local batches. Serialize publication and flush before unlocking so another module cannot
        // miss an in-flight GEN value (or write the same GS/blob twice). This lock spans only generated-manifest storage.
        synchronized (store) { return persistLocked(digest, tree, store, sink, derivation, outputs,parent); }
    }

    private static Root persistLocked(Digest digest, ContentTree tree, LocalStore store, NodeSink sink, Identity derivation, List<Output> outputs,Root parent) {
        var derivationKey=LocalStore.generatedKey(derivation);
        var previous=store.get(derivationKey);
        if(previous!=null) {
            var root=DefinerIndex.decodeRoot(previous,digest.width());
            if(!matches(tree,root,id->store.get(MachineStore.nodeKey(id)),outputs))throw new Inconsistent();
            return root;
        }
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
        Root root;
        if(parent==null)root=tree.build(entries.values(),sink);
        else {
            var removed=new ArrayList<byte[]>();
            tree.forEach(parent.hash(),id->store.get(MachineStore.nodeKey(id)),entry->{
                var next=entries.get(entry.key());
                if(next==null || !next.h().equals(entry.h()))removed.add(entry.key());else entries.remove(entry.key());
            });
            root=tree.apply(parent,removed,new ArrayList<>(entries.values()),id->store.get(MachineStore.nodeKey(id)),sink);
        }
        sink.flush(); // nodes/blobs are visible before publishing their derivation
        store.put(derivationKey, DefinerIndex.encodeRoot(root));
        store.flush();
        return root;
    }
}
