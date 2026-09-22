package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import dev.jvmd.core.RequestScope;

/** A bounded, coalescing queue of detached facts. Compiler responses never wait for the writer. */
public final class SourceIndexPublisher implements AutoCloseable {
    public record Delta(FileSemanticContribution contribution,String semanticHash,List<Map<String,Object>> symbols,
                        int tier,List<IndexService.SourceEdge> edges,long bytes,
                        String moduleId,String contextFingerprint){
        public Delta {
            contribution=Objects.requireNonNull(contribution);semanticHash=Objects.requireNonNull(semanticHash);
            if(tier!=2)throw new IllegalArgumentException("Only complete attribution may replace source state");
            symbols=List.copyOf(symbols);edges=List.copyOf(edges);
            moduleId=Objects.requireNonNull(moduleId);contextFingerprint=Objects.requireNonNull(contextFingerprint);
        }
        public Path file(){return contribution.file();}
        public String sourceHash(){return contribution.sourceHash();}
    }
    @FunctionalInterface public interface Sink {void publish(Delta delta)throws Exception;}
    private final Sink sink;
    private final long budget;
    private final LinkedHashMap<Path,Delta> pending=new LinkedHashMap<>();
    private record Cause(RequestScope.Context context,long enqueued){}
    // Attribution follows exactly the bounded pending queue; no history survives removal.
    private final Map<Path,Cause> causes=RequestScope.TRACING?new HashMap<>():null;
    private final LinkedHashMap<Path,String> published=new LinkedHashMap<>();
    private final LongAdder enqueued=new LongAdder(),writes=new LongAdder(),coalesced=new LongAdder(),skipped=new LongAdder(),failed=new LongAdder(),dropped=new LongAdder();
    private long bytes;
    private boolean closing;
    private Thread worker;
    private volatile String failure;
    public SourceIndexPublisher(Sink sink,long budget){this.sink=Objects.requireNonNull(sink);this.budget=Math.max(1024,budget);}

    public synchronized void enqueue(Delta delta){
        if(closing)return;
        var queued=pending.get(delta.file());
        if(queued!=null&&queued.semanticHash().equals(delta.semanticHash())){skipped.increment();return;}
        if(delta.semanticHash().equals(published.get(delta.file()))&&queued==null){skipped.increment();return;}
        var old=pending.remove(delta.file());if(causes!=null)causes.remove(delta.file());if(old!=null){bytes-=old.bytes();coalesced.increment();RequestScope.count("publications_coalesced",1);}
        if(delta.bytes()>budget){dropped.increment();return;}
        while(bytes+delta.bytes()>budget&&!pending.isEmpty()){
            var victim=pending.keySet().iterator().next();bytes-=pending.remove(victim).bytes();if(causes!=null)causes.remove(victim);dropped.increment();RequestScope.count("publications_dropped",1);
        }
        pending.put(delta.file(),delta);bytes+=delta.bytes();enqueued.increment();
        if(causes!=null)causes.put(delta.file(),new Cause(RequestScope.detached(),System.nanoTime()));
        if(worker==null)worker=Thread.ofVirtual().name("jvmd-source-publisher").start(this::run);
        notifyAll();
    }
    private void run(){
        while(true){
            Delta delta;
            Cause cause;
            synchronized(this){
                while(pending.isEmpty()&&!closing)try{wait();}catch(InterruptedException interrupted){return;}
                if(pending.isEmpty())return;
                delta=pending.remove(pending.keySet().iterator().next());bytes-=delta.bytes();
                cause=causes==null?null:causes.remove(delta.file());
                if(delta.semanticHash().equals(published.get(delta.file()))){skipped.increment();continue;}
            }
            try{
                if(cause==null)sink.publish(delta);
                else RequestScope.with(cause.context(),()->{
                    RequestScope.queued("facts.queue",cause.enqueued());
                    try(var span=RequestScope.stage("facts.background_publish")){
                        try{sink.publish(delta);}catch(Exception failure){span.outcome("failed");throw failure;}
                    }
                    return null;
                });
                writes.increment();
                synchronized(this){published.put(delta.file(),delta.semanticHash());while(published.size()>32768)published.remove(published.keySet().iterator().next());}
            }catch(Exception error){failed.increment();failure=error.getClass().getSimpleName()+": "+error.getMessage();}
        }
    }
    public synchronized Map<String,Object> status(){
        var status=new LinkedHashMap<String,Object>();status.put("queued_files",pending.size());status.put("queued_bytes",bytes);status.put("budget_bytes",budget);
        status.put("enqueued",enqueued.sum());status.put("writes",writes.sum());status.put("coalesced",coalesced.sum());status.put("skipped",skipped.sum());status.put("failures",failed.sum());status.put("dropped",dropped.sum());
        if(failure!=null)status.put("last_failure",failure);return status;
    }
    @Override public void close()throws InterruptedException{
        Thread thread;
        synchronized(this){closing=true;notifyAll();thread=worker;}
        if(thread!=null){
            thread.join(60000);
            if(thread.isAlive()){thread.interrupt();thread.join(5000);}
            if(thread.isAlive())throw new IllegalStateException("Source publisher did not stop; index handles remain open");
        }
    }
}
