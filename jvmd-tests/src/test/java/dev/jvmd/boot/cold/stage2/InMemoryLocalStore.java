package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Node;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An in-memory {@link LocalStore} for the stage 2 invariants. It records every read (by record kind) and the order of sync and
 * putLocalRoot (invariant 11), commits each thread's batch only on flush like the real store, and can be copied so that one MACHINE
 * boot of the JDK serves many tests.
 */
final class InMemoryLocalStore implements LocalStore {
    private TreeMap<byte[], byte[]> records = new TreeMap<>(Arrays::compareUnsigned);
    private final ThreadLocal<List<byte[][]>> pending = ThreadLocal.withInitial(ArrayList::new);
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    InMemoryLocalStore copy() {
        var out = new InMemoryLocalStore();
        synchronized (records) { out.records.putAll(records); }
        return out;
    }

    private void put(byte[] key, byte[] value) { pending.get().add(new byte[][] {key, value}); }

    private byte[] read(String kind, byte[] key) {
        events.add("read:" + kind);
        synchronized (records) { return records.get(key); }
    }

    @Override public void write(Node node) { put(MachineStore.nodeKey(node.hash()), node.bytes()); }
    @Override public void putLeaf(Identity k, byte[] leaf) { put(MachineStore.leafKey(k), leaf); }
    @Override public void putPath(String location, byte[] value) { put(MachineStore.pathKey(location), value); }
    @Override public void putModule(Identity projectKey, String module, byte[] value) { put(LocalStore.moduleKey(projectKey, module), value); }
    @Override public void putRoute(Identity projectKey, String module, int scope, byte[] value) { put(LocalStore.routeKey(projectKey, module, scope), value); }
    @Override public void putFile(Identity projectKey, String path, byte[] value) { put(LocalStore.fileKey(projectKey, path), value); }
    @Override public void putDisjoint(Identity leafSetExt, byte[] value) { put(LocalStore.disjointKey(leafSetExt), value); }
    @Override public void putSibling(Identity leafSetSib, byte[] value) { put(LocalStore.siblingKey(leafSetSib), value); }
    @Override public void putConflicts(Identity routeHash, byte[] value) { put(LocalStore.conflictsKey(routeHash), value); }
    @Override public void putConsumer(Identity kappa, Identity leafSetExt, byte[] value) { put(LocalStore.consumerKey(kappa, leafSetExt), value); }
    @Override public void putReverse(int kind, byte[] key, Identity projectKey, byte[] value) { put(LocalStore.reverseKey(kind, key, projectKey), value); }
    @Override public void putResult(Identity kappa, Identity leafSetExt, byte[] value) { put(LocalStore.resultKey(kappa, leafSetExt), value); }
    @Override public void putStub(Identity k, byte[] value) { put(LocalStore.stubKey(k), value); }
    @Override public void putStubType(Identity stKey, byte[] value) { put(LocalStore.stubTypeKey(stKey), value); }

    @Override public void flush() {
        var batch = pending.get();
        if (batch.isEmpty()) return;
        synchronized (records) { for (var kv : batch) records.put(kv[0], kv[1]); }
        batch.clear();
    }

    @Override public void putRoot(byte[] value) { synchronized (records) { records.put(MachineStore.ROOT_KEY, value); } }

    @Override public void putLocalRoot(Identity projectKey, byte[] value) {
        events.add("putLocalRoot");
        synchronized (records) {
            var previous = records.get(LocalStore.localRootKey(projectKey));
            if (previous != null) {
                int n = 1;
                while (records.containsKey(LocalStore.localRootHistoryKey(projectKey, n))) n++;
                records.put(LocalStore.localRootHistoryKey(projectKey, n), previous);
            }
            records.put(LocalStore.localRootKey(projectKey), value);
        }
    }

    @Override public void sync() { events.add("sync"); }
    @Override public boolean hasRoot() { synchronized (records) { return records.containsKey(MachineStore.ROOT_KEY); } }
    @Override public boolean hasLocalRoot(Identity projectKey) { return read("LROOT", LocalStore.localRootKey(projectKey)) != null; }

    @Override public byte[] getLeaf(Identity k) { return read("L", MachineStore.leafKey(k)); }
    @Override public byte[] getNode(Identity hash) { return read("N", MachineStore.nodeKey(hash)); }
    @Override public byte[] getPath(String location) { return read("P", MachineStore.pathKey(location)); }
    @Override public byte[] getMachineRoot() { return read("ROOT", MachineStore.ROOT_KEY); }
    @Override public byte[] getModule(Identity projectKey, String module) { return read("MOD", LocalStore.moduleKey(projectKey, module)); }
    @Override public byte[] getRoute(Identity projectKey, String module, int scope) { return read("RT", LocalStore.routeKey(projectKey, module, scope)); }
    @Override public byte[] getFile(Identity projectKey, String path) { return read("F", LocalStore.fileKey(projectKey, path)); }
    @Override public byte[] getDisjoint(Identity leafSetExt) { return read("DD", LocalStore.disjointKey(leafSetExt)); }
    @Override public byte[] getSibling(Identity leafSetSib) { return read("DS", LocalStore.siblingKey(leafSetSib)); }
    @Override public byte[] getConflicts(Identity routeHash) { return read("DC", LocalStore.conflictsKey(routeHash)); }
    @Override public byte[] getConsumer(Identity kappa, Identity leafSetExt) { return read("C", LocalStore.consumerKey(kappa, leafSetExt)); }
    @Override public byte[] getReverse(int kind, byte[] key, Identity projectKey) { return read("X", LocalStore.reverseKey(kind, key, projectKey)); }
    @Override public byte[] getResult(Identity kappa, Identity leafSetExt) { return read("RS", LocalStore.resultKey(kappa, leafSetExt)); }
    @Override public byte[] getStub(Identity k) { return read("S", LocalStore.stubKey(k)); }
    @Override public byte[] getStubType(Identity stKey) { return read("ST", LocalStore.stubTypeKey(stKey)); }
    @Override public byte[] getLocalRoot(Identity projectKey) { return read("LROOT", LocalStore.localRootKey(projectKey)); }

    /** Every read, by kind, up to the first putLocalRoot (or all of them if none): what invariant 11 inspects. */
    List<String> readsBeforeRoot() {
        var out = new ArrayList<String>();
        synchronized (events) {
            for (var e : events) { if (e.equals("putLocalRoot")) break; if (e.startsWith("read:")) out.add(e.substring(5)); }
        }
        return out;
    }

    List<String> events() { synchronized (events) { return List.copyOf(events); } }

    byte[] get(byte[] key) { synchronized (records) { return records.get(key); } }
    Map<byte[], byte[]> snapshot() { synchronized (records) { return new TreeMap<>(records); } }

    /** The records whose key starts with the ASCII tag followed by {@code |} (or, for the single-byte machine tags, the tag alone). */
    Map<byte[], byte[]> withPrefix(String tag) {
        var prefix = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var out = new TreeMap<byte[], byte[]>(Arrays::compareUnsigned);
        synchronized (records) {
            for (var e : records.entrySet()) {
                var k = e.getKey();
                if (k.length > prefix.length && Arrays.equals(Arrays.copyOf(k, prefix.length), prefix) && k[prefix.length] == '|') out.put(k, e.getValue());
            }
        }
        return out;
    }
}
