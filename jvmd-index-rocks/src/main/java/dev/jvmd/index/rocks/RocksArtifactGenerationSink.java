package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.*;

/** Bounded caller-parallel publisher for immutable RocksDB artifact generations. */
public final class RocksArtifactGenerationSink implements ArtifactGenerationSink {
    private static final long UNIT=1024L*1024L;
    private final RocksArtifactRepository repository;
    private final Semaphore budget;
    private final int totalUnits;
    private final AtomicLong published=new AtomicLong(),reused=new AtomicLong(),waitNanos=new AtomicLong();
    private final AtomicInteger unitsInFlight=new AtomicInteger(),peakUnits=new AtomicInteger();

    public RocksArtifactGenerationSink(Path root,long maxEstimatedBytes)throws Exception{
        if(maxEstimatedBytes<UNIT)throw new IllegalArgumentException("maxEstimatedBytes must be at least 1 MiB");
        this.repository=new RocksArtifactRepository(root);
        this.totalUnits=(int)Math.min(Integer.MAX_VALUE,Math.max(1,(maxEstimatedBytes+UNIT-1)/UNIT));
        this.budget=new Semaphore(totalUnits,true);
    }

    @Override public void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        int units=Math.min(totalUnits,Math.max(1,(int)((estimatedBytes(facts,classReferences)+UNIT-1)/UNIT)));
        long waiting=System.nanoTime();
        try{budget.acquire(units);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        waitNanos.addAndGet(System.nanoTime()-waiting);
        int active=unitsInFlight.addAndGet(units);peakUnits.accumulateAndGet(active,Math::max);
        try{
            var result=repository.publish(facts,classReferences);
            if(result.reused())reused.incrementAndGet();else published.incrementAndGet();
        }finally{
            unitsInFlight.addAndGet(-units);budget.release(units);
        }
    }

    @Override public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("backend","rocksdb-sst");result.put("published",published.get());result.put("reused",reused.get());
        result.put("budget_bytes",(long)totalUnits*UNIT);result.put("estimated_bytes_in_flight",(long)unitsInFlight.get()*UNIT);
        result.put("peak_estimated_bytes_in_flight",(long)peakUnits.get()*UNIT);result.put("budget_wait_ms",Math.round(waitNanos.get()/1000.0)/1000.0);
        try{result.put("repository",repository.status());}catch(Exception e){result.put("repository_error",e.toString());}
        return Map.copyOf(result);
    }

    private static long estimatedBytes(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences){
        return UNIT+facts.symbols().size()*192L+facts.relationships().size()*96L+classReferences.size()*64L;
    }

    @Override public void close(){repository.close();}
}
