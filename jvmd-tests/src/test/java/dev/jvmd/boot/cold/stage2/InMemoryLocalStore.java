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
    private final List<byte[]> recordWrites = Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.atomic.AtomicLong nodeWrites = new java.util.concurrent.atomic.AtomicLong();

    long recordWriteCount() { return recordWrites.size(); }
    long nodeWriteCount() { return nodeWrites.get(); }

    InMemoryLocalStore copy() {
        var out = new InMemoryLocalStore();
        synchronized (records) { out.records.putAll(records); }
        return out;
    }

    @Override public void put(byte[] key, byte[] value) {
        if (key[0] != 'N') recordWrites.add(key);
        pending.get().add(new byte[][] {key, value});
    }

    long writes(byte[] key) {
        synchronized (recordWrites) { return recordWrites.stream().filter(k -> Arrays.equals(k, key)).count(); }
    }

    /** The record under a key, recorded by kind: the tag before the first {@code |} of a LOCAL key, or the one-byte tag of a MACHINE key. */
    @Override public byte[] get(byte[] key) {
        events.add("read:" + kindOf(key));
        synchronized (records) { return records.get(key); }
    }

    private static final List<String> LOCAL_TAGS = List.of("LROOT", "MOD", "RT", "F", "DD", "DS", "DC", "C", "X", "RS", "ST", "S", "PROC", "PD", "RES", "GEN", "GS");

    private static String kindOf(byte[] key) {
        if (Arrays.equals(key, MachineStore.ROOT_KEY)) return "ROOT";
        for (var tag : LOCAL_TAGS) {
            var bytes = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (key.length > bytes.length && key[bytes.length] == '|' && Arrays.equals(key, 0, bytes.length, bytes, 0, bytes.length)) return tag;
        }
        return String.valueOf((char) key[0]);
    }

    @Override public void write(Node node) { nodeWrites.incrementAndGet(); put(MachineStore.nodeKey(node.hash()), node.bytes()); }
    @Override public void putLeaf(Identity k, byte[] leaf) { put(MachineStore.leafKey(k), leaf); }
    @Override public void putPath(String location, byte[] value) { put(MachineStore.pathKey(location), value); }

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
    /** Every read, by kind, up to the first putLocalRoot (or all of them if none): what invariant 11 inspects. */
    List<String> readsBeforeRoot() {
        var out = new ArrayList<String>();
        synchronized (events) {
            for (var e : events) { if (e.equals("putLocalRoot")) break; if (e.startsWith("read:")) out.add(e.substring(5)); }
        }
        return out;
    }

    List<String> events() { synchronized (events) { return List.copyOf(events); } }

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
