package dev.jvmd.index.rocks.layer;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Node;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.ArrayList;
import java.util.List;

/**
 * The key-value {@link LocalStore} (stage 2, 6 and 9.1): the same RocksDB instance as MACHINE's, with LOCAL records under their own
 * key prefixes. It adds no engine setting of its own; it wraps the machine store and writes raw records through it.
 */
public final class RocksLocalStore implements LocalStore, AutoCloseable {
    private final RocksMachineStore machine;

    RocksLocalStore(RocksMachineStore machine) { this.machine = machine; }

    // ---- MachineStore ----------------------------------------------------------------------------------------------------
    @Override public void write(Node node) { machine.write(node); }
    @Override public void flush() { machine.flush(); }
    @Override public void putLeaf(Identity k, byte[] leaf) { machine.putLeaf(k, leaf); }
    @Override public void putAnnotationLeaf(Identity a, byte[] roots) { machine.putAnnotationLeaf(a, roots); }
    @Override public void putPath(String location, byte[] value) { machine.putPath(location, value); }
    @Override public void putRoot(byte[] value) { machine.putRoot(value); }
    @Override public void sync() { machine.sync(); }
    @Override public boolean hasRoot() { return machine.hasRoot(); }

    // ---- records ---------------------------------------------------------------------------------------------------------
    @Override public void put(byte[] key, byte[] value) {
        if (dev.jvmd.index.layer.local.ReverseIndex.isHeaderKey(key))
            throw new IllegalArgumentException("Current reverse keys are published with LROOT");
        machine.put(key, value);
    }
    @Override public byte[] get(byte[] key) { return machine.get(key); }

    @Override public void forEachKey(byte[] prefix, java.util.function.Consumer<byte[]> action) { machine.forEachKey(prefix, action); }

    /** The root commits at once. A previous root of the project moves to {@code LROOT|projectKey|n} in the same atomic write (9.1). */
    @Override public void putLocalRoot(dev.jvmd.core.hash.Digest digest, Identity projectKey, byte[] value) {
        synchronized (machine) {
        var records = new ArrayList<byte[][]>(2);
        var previous = machine.get(LocalStore.localRootKey(projectKey));
        records.addAll(dev.jvmd.index.layer.local.ReverseIndex.publication(digest, this, previous, value));
        if (previous != null) {
            int n = 1;
            while (machine.get(LocalStore.localRootHistoryKey(projectKey, n)) != null) n++;
            records.add(new byte[][] {LocalStore.localRootHistoryKey(projectKey, n), previous});
        }
        records.add(new byte[][] {LocalStore.localRootKey(projectKey), value});
        machine.commit(List.copyOf(records));
        }
    }


    @Override public void putBodiesRoot(Identity projectKey, byte[] value) {
        var records = new ArrayList<byte[][]>(2);
        var previous = machine.get(LocalStore.bodiesRootKey(projectKey));
        if (previous != null) {
            int n = 1;
            while (machine.get(LocalStore.bodiesRootHistoryKey(projectKey, n)) != null) n++;
            records.add(new byte[][] {LocalStore.bodiesRootHistoryKey(projectKey, n), previous});
        }
        records.add(new byte[][] {LocalStore.bodiesRootKey(projectKey), value});
        machine.commit(List.copyOf(records));
    }

    /** For tests: every key in the store, in order. */
    public List<byte[]> keys() { return machine.keys(); }

    @Override public void close() { machine.close(); }
}
