package dev.jvmd.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Per-RPC execution identity and request-stable memoization that can cross isolated module actors. */
public final class RequestScope {
    @FunctionalInterface public interface ThrowingSupplier<T>{T get()throws Exception;}
    private static final Object NULL=new Object();
    public record Context(long id,String method,ConcurrentMap<Object,CompletableFuture<Object>> values,
                          String workflow,String revision,Span span){}
    private static final AtomicLong sequence=new AtomicLong();
    private static final ThreadLocal<Context> current=new ThreadLocal<>();
    private RequestScope(){}

    public static <T> T call(String method,ThrowingSupplier<T> supplier)throws Exception{
        if(current.get()!=null)return supplier.get();
        var context=new Context(sequence.incrementAndGet(),method,new ConcurrentHashMap<>(),"","",null);
        current.set(context);
        try{return supplier.get();}finally{current.remove();}
    }

    /** Propagate one request identity into an isolated module actor without sharing compiler state. */
    public static <T> T with(Context context,ThrowingSupplier<T> supplier)throws Exception{
        if(context==null)return supplier.get();
        var previous=current.get();current.set(context);
        try{return supplier.get();}finally{if(previous==null)current.remove();else current.set(previous);}
    }

    /**
     * Compute a request-stable value exactly once per key, even when isolated module actors
     * reach the same preparation concurrently. Failed computations are not cached.
     */
    @SuppressWarnings("unchecked")
    public static <T> T memo(Object key,ThrowingSupplier<T> supplier)throws Exception{
        var context=current.get();if(context==null)return supplier.get();
        var promise=new CompletableFuture<Object>();
        var existing=context.values().putIfAbsent(key,promise);
        if(existing==null){
            try{
                T value=supplier.get();promise.complete(value==null?NULL:value);return value;
            }catch(Throwable error){
                context.values().remove(key,promise);promise.completeExceptionally(error);
                if(error instanceof Exception exception)throw exception;
                if(error instanceof Error fatal)throw fatal;
                throw new IllegalStateException(error);
            }
        }
        try{
            Object value=existing.get();return value==NULL?null:(T)value;
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();throw interrupted;
        }catch(ExecutionException error){
            var cause=error.getCause();
            if(cause instanceof Exception exception)throw exception;
            if(cause instanceof Error fatal)throw fatal;
            throw new IllegalStateException(cause);
        }
    }

    public static Context current(){return current.get();}
    public static long id(){var value=current.get();return value==null?0:value.id();}
    public static String method(){var value=current.get();return value==null?null:value.method();}
    public static void clearMemo(){var value=current.get();if(value!=null)value.values().clear();}
    public static <T> T isolated(ThrowingSupplier<T> work)throws Exception{
        var previous=current.get();current.remove();
        try{return work.get();}finally{if(previous!=null)current.set(previous);}
    }

    /** Opt-in diagnostics only. No event, map or clock read on the disabled path. */
    public static final boolean TRACING=Boolean.getBoolean("jvmd.trace");
    private static final AtomicLong spans=new AtomicLong();
    private static final Span DISABLED=new Span();

    public static <T> T traced(String method,String workflow,String revision,ThrowingSupplier<T> work)throws Exception{
        if(!TRACING)return call(method,work);
        var previous=current.get();
        current.set(new Context(sequence.incrementAndGet(),method,new ConcurrentHashMap<>(),workflow,revision,null));
        try(var span=stage("rpc.execute")){
            try{return work.get();}catch(Exception|Error error){span.outcome("failed");throw error;}
        }finally{if(previous==null)current.remove();else current.set(previous);}
    }

    public static Span stage(String name){
        if(!TRACING)return DISABLED;
        return new Span(name,0);
    }

    /** Queue duration uses this JVM's monotonic clock; counters belong to no executing thread. */
    public static void queued(String name,long enqueued){
        if(TRACING&&enqueued!=0)try(var span=new Span(name,enqueued)){span.outcome("started");}
    }

    public static void count(String name,long value){
        if(!TRACING)return;
        var context=current.get();
        if(context!=null&&context.span()!=null)context.span().count(name,value);
    }

    @jdk.jfr.Name("dev.jvmd.Stage")
    @jdk.jfr.Label("JVMD workflow stage")
    @jdk.jfr.Category("JVMD")
    @jdk.jfr.StackTrace(false)
    public static final class Span extends jdk.jfr.Event implements AutoCloseable {
        public String workflow,revision,method,stage,outcome="observed",cache="not recorded",counters;
        public long request,span,parent,process,startNanos,durationNanos,threadCpuNanos=-1,threadAllocatedBytes=-1;
        public boolean queued,virtualThread;
        private final transient Context previous;
        private final transient java.util.Map<String,Long> work;
        private final transient long cpuStart,allocationStart;
        private transient boolean closed;

        private Span(){previous=null;work=null;cpuStart=allocationStart=-1;}
        private Span(String name,long enqueue){
            previous=current.get();work=new java.util.LinkedHashMap<>();
            stage=name;span=spans.incrementAndGet();process=ProcessHandle.current().pid();
            if(previous!=null){
                workflow=previous.workflow();revision=previous.revision();method=previous.method();request=previous.id();
                parent=previous.span()==null?0:previous.span().span;
                current.set(new Context(request,method,previous.values(),workflow,revision,this));
            }
            queued=enqueue!=0;startNanos=queued?enqueue:System.nanoTime();
            virtualThread=Thread.currentThread().isVirtual();
            cpuStart=queued||virtualThread?-1:Counters.cpu();
            allocationStart=queued||virtualThread?-1:Counters.allocated();
            begin();
        }
        public void count(String name,long value){if(work!=null)work.merge(name,value,Long::sum);}
        public void outcome(String value){if(work!=null)outcome=value;}
        public void cache(String value){if(work!=null)cache=value;}
        public void revision(String value){if(work!=null)revision=value;}
        @Override public void close(){
            if(work==null||closed)return;
            closed=true;durationNanos=System.nanoTime()-startNanos;
            if(cpuStart>=0){long end=Counters.cpu();if(end>=cpuStart)threadCpuNanos=end-cpuStart;}
            if(allocationStart>=0){long end=Counters.allocated();if(end>=allocationStart)threadAllocatedBytes=end-allocationStart;}
            end();
            // Event serialization is excluded from this span's counters, but remains visible in its parent.
            try{counters=Json.MAPPER.writeValueAsString(work);}catch(java.io.IOException ignored){counters="{}";}
            commit();
            if(previous==null)current.remove();else current.set(previous);
        }
    }

    /** Initialized only for enabled attribution; never changes VM counter settings. */
    private static final class Counters {
        private static final java.lang.management.ThreadMXBean bean=java.lang.management.ManagementFactory.getThreadMXBean();
        static long cpu(){return bean.isCurrentThreadCpuTimeSupported()&&bean.isThreadCpuTimeEnabled()?bean.getCurrentThreadCpuTime():-1;}
        static long allocated(){
            return bean instanceof com.sun.management.ThreadMXBean extended&&extended.isThreadAllocatedMemorySupported()&&extended.isThreadAllocatedMemoryEnabled()
                    ?extended.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
        }
    }
}
