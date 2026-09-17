package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** A bounded, coalescing queue of detached facts. Compiler responses never wait for the writer. */
public final class SourceIndexPublisher implements AutoCloseable {
    public record Delta(Path file,String sourceHash,String semanticHash,List<Map<String,Object>> symbols,
                        int tier,List<IndexService.SourceEdge> edges,long bytes){
        public Delta {file=file.toAbsolutePath().normalize();symbols=List.copyOf(symbols);edges=List.copyOf(edges);}
    }
    @FunctionalInterface public interface Sink {void publish(Delta delta)throws Exception;}
    private final Sink sink;
    private final long budget;
    private final LinkedHashMap<Path,Delta> pending=new LinkedHashMap<>();
    private final LinkedHashMap<Path,String> published=new LinkedHashMap<>();
    private final LongAdder enqueued=new LongAdder(),writes=new LongAdder(),coalesced=new LongAdder(),skipped=new LongAdder(),failed=new LongAdder(),dropped=new LongAdder();
    private long bytes;
    private boolean closing;
    private Thread worker;
    private volatile String failure;
    public SourceIndexPublisher(Sink sink,long budget){this.sink=Objects.requireNonNull(sink);this.budget=Math.max(1024,budget);}

    public synchronized void enqueue(Delta delta){
        if(closing)return;
        if(delta.semanticHash().equals(published.get(delta.file()))&&!pending.containsKey(delta.file())){skipped.increment();return;}
        var old=pending.remove(delta.file());if(old!=null){bytes-=old.bytes();coalesced.increment();}
        if(delta.bytes()>budget){dropped.increment();return;}
        while(bytes+delta.bytes()>budget&&!pending.isEmpty()){
            var victim=pending.keySet().iterator().next();bytes-=pending.remove(victim).bytes();dropped.increment();
        }
        pending.put(delta.file(),delta);bytes+=delta.bytes();enqueued.increment();
        if(worker==null)worker=Thread.ofVirtual().name("jvmd-source-publisher").start(this::run);
        notifyAll();
    }
    private void run(){
        while(true){
            Delta delta;
            synchronized(this){
                while(pending.isEmpty()&&!closing)try{wait();}catch(InterruptedException interrupted){return;}
                if(pending.isEmpty())return;
                delta=pending.remove(pending.keySet().iterator().next());bytes-=delta.bytes();
            }
            try{
                sink.publish(delta);writes.increment();
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
        if(thread!=null){thread.join(5000);if(thread.isAlive())thread.interrupt();}
    }
}
