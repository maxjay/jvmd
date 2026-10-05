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
    private final AtomicLong nodes = new AtomicLong();
    private final AtomicLong produced = new AtomicLong();

    /** The sink every tree of this boot writes through: it drops a node whose hash was already written. */
    public NodeSink through(MachineStore store) {
        return new NodeSink() {
            @Override public void write(Node node) {
                produced.incrementAndGet();
                if (hashes.add(node.hash())) { nodes.incrementAndGet(); store.write(node); }
            }
            @Override public void flush() { store.flush(); }
        };
    }

    public long count() { return nodes.get(); }

    /** Every node any tree produced, including those dropped because an equal node was already written: produced - count is the sharing. */
    public long produced() { return produced.get(); }
}
