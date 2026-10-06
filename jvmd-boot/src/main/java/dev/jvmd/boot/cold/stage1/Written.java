package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code Written} (stage 1, 2.6; stage 2, 2.6): node hashes written this boot. A node is written once; every other write is a set
 * lookup. Stage 2 shares it.
 */
public final class Written {
    private final Set<Identity> hashes = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Identity, Node> pending = new java.util.LinkedHashMap<>();
    private final AtomicLong nodes = new AtomicLong();
    private final AtomicLong produced = new AtomicLong();

    /** The sink every tree of this boot writes through: it drops a node whose hash was already written. */
    public NodeSink through(MachineStore store) {
        return through(store, "unlabelled", false);
    }

    /** For stages that read shared roots before all jobs finish: any flush publishes every claimed node. */
    public NodeSink throughShared(MachineStore store) {
        return through(store, "unlabelled", true);
    }

    private NodeSink through(MachineStore store, String tree, boolean shared) {
        return new NodeSink() {
            @Override public void write(Node node) {
                store.nodeBuilt(tree, node);
                produced.incrementAndGet();
                if (!shared) {
                    if (hashes.add(node.hash())) { nodes.incrementAndGet(); store.write(node); }
                } else synchronized (pending) {
                    if (hashes.add(node.hash())) { nodes.incrementAndGet(); pending.put(node.hash(), node); }
                }
            }
            @Override public void flush() {
                if (!shared) { store.flush(); return; }
                // A duplicate can have been produced by another worker. Drain the shared node batch
                // before publishing any root, rather than flushing only this worker's earlier nodes.
                synchronized (pending) {
                    for (var node : pending.values()) store.write(node);
                    store.flush();
                    pending.clear();
                }
            }
            @Override public NodeSink named(String name) { return through(store, name, shared); }
        };
    }

    public long count() { return nodes.get(); }

    /** Every node any tree produced, including those dropped because an equal node was already written: produced - count is the sharing. */
    public long produced() { return produced.get(); }
}
