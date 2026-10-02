package dev.jvmd.index.rocks;

import java.util.Map;
import org.rocksdb.*;

/** One cache and write-buffer budget shared by every Rocks database in a generation. */
public final class RocksMemory implements AutoCloseable {
    private final long budget;
    private final LRUCache cache;
    private final WriteBufferManager buffers;
    public RocksMemory(long budget){
        RocksDB.loadLibrary();
        if(budget<8L*1024*1024)throw new IllegalArgumentException("Rocks native cache budget must be at least 8 MiB");
        // The budget is the cache's capacity target, not a read-failure threshold: with a strict limit a
        // read fails ("LRU cache being full") whenever blocks pinned by concurrent readers, pinned index
        // and filter blocks and charged memtables momentarily reach it. Unpinned blocks are evicted back.
        this.budget=budget;cache=new LRUCache(budget,-1,false);
        // Memtables are charged to the same cache. Exceeding the write-buffer budget triggers flushes; it
        // never stalls writers: databases share this budget, and a writer stalled on memtables held by
        // other, idle databases (which nothing flushes) never resumes.
        buffers=new WriteBufferManager(budget/4,cache,false);
    }
    /** Options for opening an existing database. */
    public Options options(int openFiles){
        return new Options().setMaxOpenFiles(openFiles).setMaxBackgroundJobs(2)
                .setWriteBufferSize(Math.min(4L*1024*1024,budget/8)).setMaxWriteBufferNumber(2)
                .setWriteBufferManager(buffers)
                .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(cache).setCacheIndexAndFilterBlocks(true)
                        .setIndexType(IndexType.kTwoLevelIndexSearch).setPartitionFilters(true).setMetadataBlockSize(4096)
                        .setCacheIndexAndFilterBlocksWithHighPriority(true).setPinTopLevelIndexAndFilter(true));
    }
    /**
     * Options that create a missing database. Only the MACHINE cold boot's create stage uses them,
     * through {@link RocksIndexStorage#create}.
     */
    Options creating(int openFiles){return options(openFiles).setCreateIfMissing(true);}
    Map<String,Object> status(){return Map.of("cache_and_memtable_budget_bytes",budget,
            "cache_usage_bytes",cache.getUsage(),"cache_pinned_bytes",cache.getPinnedUsage());}
    @Override public void close(){buffers.close();cache.close();}
}
