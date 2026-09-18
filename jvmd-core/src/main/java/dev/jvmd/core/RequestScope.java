package dev.jvmd.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Per-RPC execution identity and request-stable memoization that can cross isolated module actors. */
public final class RequestScope {
    @FunctionalInterface public interface ThrowingSupplier<T>{T get()throws Exception;}
    private static final Object NULL=new Object();
    public record Context(long id,String method,ConcurrentMap<Object,CompletableFuture<Object>> values){}
    private static final AtomicLong sequence=new AtomicLong();
    private static final ThreadLocal<Context> current=new ThreadLocal<>();
    private RequestScope(){}

    public static <T> T call(String method,ThrowingSupplier<T> supplier)throws Exception{
        if(current.get()!=null)return supplier.get();
        var context=new Context(sequence.incrementAndGet(),method,new ConcurrentHashMap<>());
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
}
