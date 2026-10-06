package dev.jvmd.index.rocks.layer;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Node;
import dev.jvmd.index.layer.machine.MachineStore;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * The minimal RocksDB {@link MachineStore} (stage 1, 9.2): a key-value map and nothing more. Default column family, default
 * options, no SST staging, no custom comparator, no compaction policy. Engine tuning waits until LOCAL and sources exist.
 */
public final class RocksMachineStore implements MachineStore, AutoCloseable {
    static { RocksDB.loadLibrary(); }

    private final RocksDB db;
    private final Options options;
    private final WriteOptions writes = new WriteOptions();
    private final WriteOptions durable = new WriteOptions().setSync(true);
    private final ThreadLocal<WriteBatch> batches = ThreadLocal.withInitial(this::newBatch);
    private final ConcurrentLinkedQueue<WriteBatch> opened = new ConcurrentLinkedQueue<>();

    RocksMachineStore(java.nio.file.Path directory, boolean readOnly) {
        try {
            options = new Options().setCreateIfMissing(!readOnly);
            db = readOnly ? RocksDB.openReadOnly(options, directory.toString()) : RocksDB.open(options, directory.toString());
        } catch (RocksDBException e) { throw failure(e); }
    }

    private WriteBatch newBatch() {
        var batch = new WriteBatch();
        opened.add(batch);
        return batch;
    }

    private static UncheckedIOException failure(RocksDBException e) { return new UncheckedIOException(new IOException("Storage failure: " + e.getMessage(), e)); }

    @Override public void write(Node node) {
        try { batches.get().put(MachineStore.nodeKey(node.hash()), node.bytes()); } catch (RocksDBException e) { throw failure(e); }
    }
    @Override public void putLeaf(Identity k, byte[] leaf) {
        try { batches.get().put(MachineStore.leafKey(k), leaf); } catch (RocksDBException e) { throw failure(e); }
    }
    @Override public void putAnnotationLeaf(Identity a, byte[] roots) { put(MachineStore.annotationLeafKey(a), roots); }
    @Override public void putPath(String location, byte[] value) {
        try { batches.get().put(MachineStore.pathKey(location), value); } catch (RocksDBException e) { throw failure(e); }
    }
    @Override public void flush() {
        var batch = batches.get();
        try { if (batch.count() > 0) db.write(writes, batch); batch.clear(); } catch (RocksDBException e) { throw failure(e); }
    }
    @Override public void putRoot(byte[] value) {
        try { db.put(durable, MachineStore.ROOT_KEY, value); } catch (RocksDBException e) { throw failure(e); }
    }
    @Override public void sync() {
        try (var flush = new FlushOptions().setWaitForFlush(true)) {
            db.flush(flush);
            db.syncWal();
        } catch (RocksDBException e) { throw failure(e); }
    }
    @Override public boolean hasRoot() {
        try { return db.get(MachineStore.ROOT_KEY) != null; } catch (RocksDBException e) { throw failure(e); }
    }
    /** Buffers one raw record in the calling thread's batch, committed by {@link #flush()}: how {@link RocksLocalStore} writes LOCAL records. */
    void put(byte[] key, byte[] value) {
        try { batches.get().put(key, value); } catch (RocksDBException e) { throw failure(e); }
    }
    /** Commits records at once, atomically and durably. */
    void commit(java.util.List<byte[][]> records) {
        try (var batch = new WriteBatch()) {
            for (var kv : records) { if (kv[1] == null) batch.delete(kv[0]); else batch.put(kv[0], kv[1]); }
            db.write(durable, batch);
        } catch (RocksDBException e) { throw failure(e); }
    }
    /** For tests and warm boot: the value stored under a raw key, or null. */
    public byte[] get(byte[] key) {
        try { return db.get(key); } catch (RocksDBException e) { throw failure(e); }
    }
    /** Seek directly to one key range; only matching keys cross the storage boundary. */
    void forEachKey(byte[] prefix, java.util.function.Consumer<byte[]> action) {
        try (var it = db.newIterator()) {
            for (it.seek(prefix); it.isValid(); it.next()) {
                var key = it.key();
                if (key.length < prefix.length || !java.util.Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length)) break;
                action.accept(key);
            }
            it.status();
        } catch (RocksDBException e) { throw failure(e); }
    }

    /** Every key in the store, in order: lets tests assert that exactly the expected record kinds are on disk. */
    public java.util.List<byte[]> keys() {
        var out = new java.util.ArrayList<byte[]>();
        try (var it = db.newIterator()) { for (it.seekToFirst(); it.isValid(); it.next()) out.add(it.key()); }
        return out;
    }

    @Override public void close() {
        opened.forEach(WriteBatch::close);
        writes.close();
        durable.close();
        db.close();
        options.close();
    }
}
