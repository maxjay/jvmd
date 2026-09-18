package dev.jvmd.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Per-RPC execution identity and request-stable memoization that can cross isolated module actors. */
public final class RequestScope {
    @FunctionalInterface public interface ThrowingSupplier<T>{T get()throws Exception;}
    private static final Object NULL=new Object();
    public record Context(long id,String method,ConcurrentMap<Object,Object> values){}
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
    @SuppressWarnings("unchecked")
    public static <T> T memo(Object key,ThrowingSupplier<T> supplier)throws Exception{
        var context=current.get();if(context==null)return supplier.get();
        Object cached=context.values().get(key);
        if(cached!=null)return cached==NULL?null:(T)cached;
        T value=supplier.get();Object stored=value==null?NULL:value;
        Object prior=context.values().putIfAbsent(key,stored);
        Object result=prior==null?stored:prior;
        return result==NULL?null:(T)result;
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
