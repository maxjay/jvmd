package dev.jvmd.runtime;

import com.sun.jdi.*;
import dev.jvmd.core.RpcException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Implements 4.7: bounded object handles with automatic 60-second collection-pin expiry. */
public final class ObjectHandles implements AutoCloseable {
    private static final ScheduledExecutorService REAPER=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jvmd-handle-expiry").factory());
    private static final int CAPACITY=4096;
    private record Entry(ObjectReference object,long expires) { }
    private final String session;
    private final long ttl;
    private final LongSupplier clock;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Map<Long,String> identities=new HashMap<>();
    private final ScheduledFuture<?> reap;
    private long sequence;
    private boolean closed;
    public ObjectHandles(String session){this(session,Duration.ofSeconds(60),System::nanoTime);}
    public ObjectHandles(String session,Duration ttl,LongSupplier clock){
        if(ttl.isZero()||ttl.isNegative())throw new IllegalArgumentException("TTL must be positive");this.session=session;this.ttl=ttl.toNanos();this.clock=clock;reap=REAPER.scheduleWithFixedDelay(this::expire,1,1,TimeUnit.SECONDS);
    }
    public synchronized String pin(ObjectReference object){
        if(closed)throw new IllegalStateException("Run session is closed");expire();long id=object.uniqueID();String prior=identities.get(id);
        if(prior!=null){entries.put(prior,new Entry(object,clock.getAsLong()+ttl));return prior;}
        if(entries.size()>=CAPACITY)throw new RpcException(-32005,"budget_exceeded",Map.of("capability","object handles","limit",CAPACITY,"cursor",entries.keySet().iterator().next(),"reason","Release this handle or wait for its TTL before retrying"));
        object.disableCollection();String handle="obj:"+session+":"+(++sequence);entries.put(handle,new Entry(object,clock.getAsLong()+ttl));identities.put(id,handle);return handle;
    }
    public synchronized ObjectReference get(String handle){
        expire();var entry=entries.get(handle);if(entry==null)throw RpcException.invalid("Unknown or expired object handle: "+handle);
        entries.put(handle,new Entry(entry.object(),clock.getAsLong()+ttl));return entry.object();
    }
    public synchronized boolean release(String handle){
        var entry=entries.remove(handle);if(entry==null)return false;
        try{identities.remove(entry.object().uniqueID());entry.object().enableCollection();}catch(ObjectCollectedException|VMDisconnectedException ignored){}return true;
    }
    public synchronized void expire(){
        long now=clock.getAsLong();var expired=entries.entrySet().stream().filter(e->now-e.getValue().expires()>=0).map(Map.Entry::getKey).toList();expired.forEach(this::release);
    }
    public synchronized int size(){expire();return entries.size();}
    @Override public synchronized void close(){if(closed)return;closed=true;reap.cancel(false);new ArrayList<>(entries.keySet()).forEach(this::release);}
}
