package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.*;

/** Shared parsing/publication budget; nested publications reuse their caller's admission. */
final class RocksArtifactAdmission implements ArtifactAdmission {
    private static final long UNIT=1024L*1024L;
    private final ThreadLocal<Boolean> artifactPermit=ThreadLocal.withInitial(()->false);
    private final Semaphore budget;
    private final int totalUnits;
    private final AtomicLong waitNanos=new AtomicLong();
    private final AtomicInteger unitsInFlight=new AtomicInteger(),peakUnits=new AtomicInteger(),artifactsInFlight=new AtomicInteger();

    RocksArtifactAdmission(long maxEstimatedBytes){
        if(maxEstimatedBytes<UNIT)throw new IllegalArgumentException("maxEstimatedBytes must be at least 1 MiB");
        totalUnits=(int)Math.min(Integer.MAX_VALUE,Math.max(1,(maxEstimatedBytes+UNIT-1)/UNIT));
        budget=new Semaphore(totalUnits,true);
    }

    @Override public AutoCloseable acquireArtifact(Path path)throws Exception{
        if(artifactPermit.get())return ()->{};
        long estimate=8L*UNIT;
        long estimateStarted=dev.jvmd.core.BootEvents.nanos();
        if(Files.isRegularFile(path)&&path.toString().endsWith(".jar")){
            long enumerated=0;
            try(var jar=new java.util.jar.JarFile(path.toFile())){
                var entries=jar.entries();
                while(entries.hasMoreElements()){
                    var entry=entries.nextElement();enumerated++;
                    if(entry.getName().endsWith(".class")||entry.getName().endsWith(".java"))
                        estimate=Math.min((long)totalUnits*UNIT,estimate+Math.max(0,entry.getSize())*12L+1024L);
                }
            }
            dev.jvmd.core.BootEvents.timed("admission.estimate_jar_open",estimateStarted);dev.jvmd.core.BootEvents.count("admission.estimate_entries",enumerated);
        }
        int units=(int)Math.min(totalUnits,Math.max(1,(estimate+UNIT-1)/UNIT));
        long waiting=System.nanoTime();budget.acquire(units);waitNanos.addAndGet(System.nanoTime()-waiting);
        dev.jvmd.core.BootEvents.timed("admission.artifact_wait",waiting);dev.jvmd.core.BootEvents.count("admission.units_in_flight",units);
        if(dev.jvmd.core.BootEvents.ENABLED){
            dev.jvmd.core.BootEvents.count("admission.estimated_units",units);
            if(units>=totalUnits)dev.jvmd.core.BootEvents.count("admission.whole_budget_artifacts",1);
            if(System.nanoTime()-waiting>1_000_000L)dev.jvmd.core.BootEvents.count("admission.waited_over_1ms",1);
        }
        int active=unitsInFlight.addAndGet(units);peakUnits.accumulateAndGet(active,Math::max);artifactPermit.set(true);
        if(dev.jvmd.core.BootEvents.ENABLED){
            int artifacts=artifactsInFlight.incrementAndGet();
            dev.jvmd.core.BootEvents.maximum("admission.artifacts_in_flight",artifacts);dev.jvmd.core.BootEvents.maximum("admission.units_in_flight",active);
        }
        return ()->{artifactPermit.remove();unitsInFlight.addAndGet(-units);budget.release(units);dev.jvmd.core.BootEvents.count("admission.units_in_flight",-units);
            if(dev.jvmd.core.BootEvents.ENABLED)artifactsInFlight.decrementAndGet();};
    }

    AutoCloseable acquirePublication(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        int units=artifactPermit.get()?0:(int)Math.min(totalUnits,Math.max(1,(estimatedBytes(facts,classReferences)+UNIT-1)/UNIT));
        long waiting=System.nanoTime();
        // A fair Semaphore can queue even acquire(0) behind another waiter. An admitted
        // artifact must publish and release its existing capacity before that waiter proceeds.
        try{if(units>0)budget.acquire(units);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        waitNanos.addAndGet(System.nanoTime()-waiting);
        int active=unitsInFlight.addAndGet(units);peakUnits.accumulateAndGet(active,Math::max);
        return ()->{unitsInFlight.addAndGet(-units);budget.release(units);};
    }

    private static long estimatedBytes(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences){
        return UNIT+facts.symbols().size()*192L+facts.relationships().size()*96L+classReferences.size()*64L;
    }
    Map<String,Object> status(){
        return Map.of("budget_bytes",(long)totalUnits*UNIT,"estimated_bytes_in_flight",(long)unitsInFlight.get()*UNIT,
                "peak_estimated_bytes_in_flight",(long)peakUnits.get()*UNIT,"budget_wait_ms",Math.round(waitNanos.get()/1000.0)/1000.0,
                "admission_waiters",budget.getQueueLength());
    }
}
