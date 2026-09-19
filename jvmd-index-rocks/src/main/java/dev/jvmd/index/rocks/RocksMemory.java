package dev.jvmd.index.rocks;

import java.util.Map;
import org.rocksdb.*;

/** One cache and write-buffer budget shared by every Rocks database in a generation. */
final class RocksMemory implements AutoCloseable {
    private final long budget;
    private final LRUCache cache;
    private final WriteBufferManager buffers;
    RocksMemory(long budget){
        RocksDB.loadLibrary();
        if(budget<8L*1024*1024)throw new IllegalArgumentException("Rocks native cache budget must be at least 8 MiB");
        this.budget=budget;cache=new LRUCache(budget,-1,true);
        // Memtables are charged to the same cache. Allow stalls rather than growing without bound.
        buffers=new WriteBufferManager(budget/4,cache,true);
    }
    Options options(int openFiles){
        return new Options().setCreateIfMissing(true).setMaxOpenFiles(openFiles).setMaxBackgroundJobs(2)
                .setWriteBufferSize(Math.min(4L*1024*1024,budget/8)).setMaxWriteBufferNumber(2)
                .setWriteBufferManager(buffers)
                .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(cache).setCacheIndexAndFilterBlocks(true)
                        .setIndexType(IndexType.kTwoLevelIndexSearch).setPartitionFilters(true).setMetadataBlockSize(4096)
                        .setCacheIndexAndFilterBlocksWithHighPriority(true).setPinTopLevelIndexAndFilter(true));
    }
    Map<String,Object> status(){return Map.of("cache_and_memtable_budget_bytes",budget,
            "cache_usage_bytes",cache.getUsage(),"cache_pinned_bytes",cache.getPinnedUsage());}
    @Override public void close(){buffers.close();cache.close();}
}
