package dev.jvmd.index.rocks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The shared native budget is a capacity target, not a read-failure threshold: when blocks pinned by
 * concurrent readers (plus pinned index and filter blocks and charged memtables) reach the budget,
 * reads still succeed and the cache evicts back once they are released. A strict limit made such
 * reads fail with "Insert failed due to LRU cache being full" (seen as faulted session.open and
 * diagnostics requests on the apache/maven lifecycle).
 */
class RocksMemoryTest {
    @TempDir Path root;

    @Test void readsSucceedWhilePinnedBlocksExceedTheBudgetAndTheCacheShrinksBack()throws Exception{
        long budget=8L*1024*1024;
        try(var memory=new RocksMemory(budget);var options=memory.options(64);var db=RocksDB.open(options,root.toString())){
            var value=new byte[1024];
            for(int i=0;i<32_000;i++)db.put(key(i),value);
            try(var flush=new FlushOptions().setWaitForFlush(true)){db.flush(flush);}
            // Hold far more data blocks than the budget: every open, positioned iterator pins its block.
            var open=new ArrayList<RocksIterator>();
            try{
                for(int i=0;i<32_000;i+=4){var iterator=db.newIterator();iterator.seek(key(i));assertThat(iterator.isValid()).isTrue();open.add(iterator);}
                assertThat(memory.status().get("cache_pinned_bytes")).as("pinned beyond the budget").satisfies(pinned->assertThat((Long)pinned).isGreaterThan(budget));
                assertThat(db.get(key(31_999))).as("a read while pinned blocks exceed the budget").isNotNull();
            }finally{open.forEach(RocksIterator::close);}
            for(int i=0;i<32_000;i+=97)db.get(key(i));
            assertThat((Long)memory.status().get("cache_usage_bytes")).as("released blocks are evicted back under the budget").isLessThanOrEqualTo(budget);
        }
    }
    /**
     * Databases share one write-buffer budget. A write to one database must not wait indefinitely
     * for memtable memory held by other, idle databases: with stalling enabled nothing flushes them,
     * so the writer (and every request queued behind the lock it held) hung.
     */
    @Test void aWriteDoesNotStallOnMemtablesHeldByOtherIdleDatabases()throws Exception{
        long budget=8L*1024*1024;var memory=new RocksMemory(budget);
        var databases=new ArrayList<RocksDB>();var options=new ArrayList<Options>();
        for(int d=0;d<8;d++){var o=memory.options(64);options.add(o);databases.add(RocksDB.open(o,root.resolve("db"+d).toString()));}
        var value=new byte[1024];
        // Seven databases each take ~700 KiB of memtable, below their own 1 MiB write buffer (so none
        // flushes itself) but together far over the shared 2 MiB write-buffer budget, and then idle.
        // The eighth keeps writing.
        var done=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().submit(()->{
            for(int d=0;d<7;d++)for(int i=0;i<700;i++)databases.get(d).put(key(i),value);
            for(int i=0;i<2_000;i++)databases.get(7).put(key(i),value);
            return true;
        });
        try{done.get(60,java.util.concurrent.TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException stalled){
            // The writer is stuck in native code; closing would wait behind it. Leave it to the JVM's exit.
            fail("a write stalled for 60 s on memtables held by idle databases");
        }
        assertThat(databases.get(7).get(key(1_999))).isNotNull();
        databases.forEach(RocksDB::close);options.forEach(Options::close);memory.close();
    }
    private static byte[] key(int i){return String.format("key-%08d",i).getBytes(StandardCharsets.UTF_8);}
}
