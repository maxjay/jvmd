package dev.jvmd.tests.boot;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Node;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory {@link MachineStore} for the stage 1 invariants. It records the order of reads, sync and putRoot (invariant 8) and
 * commits each thread's batch only on flush, like the real store.
 */
final class InMemoryMachineStore implements MachineStore {
    private final TreeMap<byte[], byte[]> records = new TreeMap<>(Arrays::compareUnsigned);
    private final ThreadLocal<List<byte[][]>> pending = ThreadLocal.withInitial(ArrayList::new);
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger batches = new AtomicInteger();

    @Override public void write(Node node) { pending.get().add(new byte[][] {MachineStore.nodeKey(node.hash()), node.bytes()}); }
    @Override public void putLeaf(Identity k, byte[] leaf) { pending.get().add(new byte[][] {MachineStore.leafKey(k), leaf}); }
    @Override public void putPath(String location, byte[] value) { pending.get().add(new byte[][] {MachineStore.pathKey(location), value}); }

    @Override public void flush() {
        var batch = pending.get();
        if (batch.isEmpty()) return;
        synchronized (records) { for (var kv : batch) records.put(kv[0], kv[1]); }
        batches.incrementAndGet();
        batch.clear();
    }

    @Override public void putRoot(byte[] value) {
        events.add("putRoot");
        synchronized (records) { records.put(MachineStore.ROOT_KEY, value); }
    }

    @Override public void sync() { events.add("sync"); }

    @Override public boolean hasRoot() {
        events.add("read");
        synchronized (records) { return records.containsKey(MachineStore.ROOT_KEY); }
    }

    List<String> events() { synchronized (events) { return List.copyOf(events); } }

    byte[] root() { synchronized (records) { return records.get(MachineStore.ROOT_KEY); } }
    byte[] get(byte[] key) { synchronized (records) { return records.get(key); } }
    Map<byte[], byte[]> snapshot() { synchronized (records) { return new TreeMap<>(records); } }

    long count(int prefix) {
        synchronized (records) { return records.keySet().stream().filter(k -> k.length > 0 && (k[0] & 0xFF) == prefix && k.length > 4).count(); }
    }
    /** Records by kind: L, N, P and ROOT only (the table at the end of section 4). */
    Map<String, Long> kinds() {
        var out = new TreeMap<String, Long>();
        synchronized (records) {
            for (var key : records.keySet()) {
                String kind = Arrays.equals(key, MachineStore.ROOT_KEY) ? "ROOT" : String.valueOf((char) (key[0] & 0xFF));
                out.merge(kind, 1L, Long::sum);
            }
        }
        return out;
    }
}
