package dev.jvmd.core.tree;

import java.util.*;

/**
 * Writes a layer: its records go to the store in bounded staged batches, then the store is synced
 * once, then the root is written. The root therefore exists only if every record it covers is
 * durable. Staging holds at most one batch, so its memory is bounded by the batch size and not by
 * the size of the layer. A failure before {@link #root} leaves no root.
 */
public final class Commit {
    /** The storage a commit writes to. */
    public interface Store {
        /** Write one batch of records. It need not be durable until {@link #sync}. */
        void stage(List<Map.Entry<byte[],byte[]>> batch)throws Exception;
        /** Make every staged batch durable. */
        void sync()throws Exception;
        /** Durably write the root, after which the layer is committed. */
        void root(byte[] root)throws Exception;
    }

    private final Store store;
    private final long batchBytes;
    private final List<Map.Entry<byte[],byte[]>> pending=new ArrayList<>();
    private long pendingBytes,peakBytes,records,batches;
    private boolean finished;

    public Commit(Store store,long batchBytes){
        if(batchBytes<=0)throw new IllegalArgumentException("Commit batch size must be positive");
        this.store=Objects.requireNonNull(store);this.batchBytes=batchBytes;
    }

    public void put(byte[] key,byte[] value)throws Exception{
        if(finished)throw new IllegalStateException("Commit is finished");
        pending.add(Map.entry(key,value));pendingBytes+=key.length+value.length;records++;
        peakBytes=Math.max(peakBytes,pendingBytes);
        if(pendingBytes>=batchBytes)stage();
    }

    /** Stage the remaining records, sync, then write the root. */
    public void root(Root root)throws Exception{
        if(finished)throw new IllegalStateException("Commit is finished");
        stage();store.sync();store.root(root.encode());finished=true;
    }

    /** Largest number of record bytes staged in memory at once. */
    public long peakBytes(){return peakBytes;}
    public long records(){return records;}
    public long batches(){return batches;}

    private void stage()throws Exception{
        if(pending.isEmpty())return;
        store.stage(List.copyOf(pending));pending.clear();pendingBytes=0;batches++;
    }
}
