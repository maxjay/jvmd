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
    @Override public void putPath(String location, byte[] value) { machine.putPath(location, value); }
    @Override public void putRoot(byte[] value) { machine.putRoot(value); }
    @Override public void sync() { machine.sync(); }
    @Override public boolean hasRoot() { return machine.hasRoot(); }

    // ---- LOCAL records ---------------------------------------------------------------------------------------------------
    @Override public void putModule(Identity projectKey, String module, byte[] value) { machine.put(LocalStore.moduleKey(projectKey, module), value); }
    @Override public void putRoute(Identity projectKey, String module, int scope, byte[] value) { machine.put(LocalStore.routeKey(projectKey, module, scope), value); }
    @Override public void putFile(Identity projectKey, String path, byte[] value) { machine.put(LocalStore.fileKey(projectKey, path), value); }
    @Override public void putDisjoint(Identity leafSetExt, byte[] value) { machine.put(LocalStore.disjointKey(leafSetExt), value); }
    @Override public void putSibling(Identity leafSetSib, byte[] value) { machine.put(LocalStore.siblingKey(leafSetSib), value); }
    @Override public void putConflicts(Identity routeHash, byte[] value) { machine.put(LocalStore.conflictsKey(routeHash), value); }
    @Override public void putConsumer(Identity kappa, Identity leafSetExt, byte[] value) { machine.put(LocalStore.consumerKey(kappa, leafSetExt), value); }
    @Override public void putReverse(int kind, byte[] key, Identity projectKey, byte[] value) { machine.put(LocalStore.reverseKey(kind, key, projectKey), value); }
    @Override public void putResult(Identity kappa, Identity leafSetExt, byte[] value) { machine.put(LocalStore.resultKey(kappa, leafSetExt), value); }
    @Override public void putStub(Identity k, byte[] value) { machine.put(LocalStore.stubKey(k), value); }
    @Override public void putStubType(Identity stKey, byte[] value) { machine.put(LocalStore.stubTypeKey(stKey), value); }

    /** The root commits at once. A previous root of the project moves to {@code LROOT|projectKey|n} in the same atomic write (9.1). */
    @Override public void putLocalRoot(Identity projectKey, byte[] value) {
        var records = new ArrayList<byte[][]>(2);
        var previous = machine.get(LocalStore.localRootKey(projectKey));
        if (previous != null) {
            int n = 1;
            while (machine.get(LocalStore.localRootHistoryKey(projectKey, n)) != null) n++;
            records.add(new byte[][] {LocalStore.localRootHistoryKey(projectKey, n), previous});
        }
        records.add(new byte[][] {LocalStore.localRootKey(projectKey), value});
        machine.commit(List.copyOf(records));
    }

    @Override public boolean hasLocalRoot(Identity projectKey) { return machine.get(LocalStore.localRootKey(projectKey)) != null; }
    @Override public byte[] getLeaf(Identity k) { return machine.get(MachineStore.leafKey(k)); }
    @Override public byte[] getNode(Identity hash) { return machine.get(MachineStore.nodeKey(hash)); }
    @Override public byte[] getPath(String location) { return machine.get(MachineStore.pathKey(location)); }
    @Override public byte[] getMachineRoot() { return machine.get(MachineStore.ROOT_KEY); }
    @Override public byte[] getModule(Identity projectKey, String module) { return machine.get(LocalStore.moduleKey(projectKey, module)); }
    @Override public byte[] getRoute(Identity projectKey, String module, int scope) { return machine.get(LocalStore.routeKey(projectKey, module, scope)); }
    @Override public byte[] getFile(Identity projectKey, String path) { return machine.get(LocalStore.fileKey(projectKey, path)); }
    @Override public byte[] getDisjoint(Identity leafSetExt) { return machine.get(LocalStore.disjointKey(leafSetExt)); }
    @Override public byte[] getSibling(Identity leafSetSib) { return machine.get(LocalStore.siblingKey(leafSetSib)); }
    @Override public byte[] getConflicts(Identity routeHash) { return machine.get(LocalStore.conflictsKey(routeHash)); }
    @Override public byte[] getConsumer(Identity kappa, Identity leafSetExt) { return machine.get(LocalStore.consumerKey(kappa, leafSetExt)); }
    @Override public byte[] getReverse(int kind, byte[] key, Identity projectKey) { return machine.get(LocalStore.reverseKey(kind, key, projectKey)); }
    @Override public byte[] getResult(Identity kappa, Identity leafSetExt) { return machine.get(LocalStore.resultKey(kappa, leafSetExt)); }
    @Override public byte[] getStub(Identity k) { return machine.get(LocalStore.stubKey(k)); }
    @Override public byte[] getStubType(Identity stKey) { return machine.get(LocalStore.stubTypeKey(stKey)); }
    @Override public byte[] getLocalRoot(Identity projectKey) { return machine.get(LocalStore.localRootKey(projectKey)); }

    /** For tests: every key in the store, in order. */
    public List<byte[]> keys() { return machine.keys(); }

    @Override public void close() { machine.close(); }
}
