package dev.jvmd.core;

import java.util.concurrent.atomic.AtomicLong;

/** Per-RPC execution identity used to memoize request-stable preparation without leaking across requests. */
public final class RequestScope {
    @FunctionalInterface public interface ThrowingSupplier<T>{T get()throws Exception;}
    public record Context(long id,String method){}
    private static final AtomicLong sequence=new AtomicLong();
    private static final ThreadLocal<Context> current=new ThreadLocal<>();
    private static final ThreadLocal<java.util.Map<Object,Object>> values=new ThreadLocal<>();
    private RequestScope(){}

    public static <T> T call(String method,ThrowingSupplier<T> supplier)throws Exception{
        if(current.get()!=null)return supplier.get();
        current.set(new Context(sequence.incrementAndGet(),method));values.set(new java.util.HashMap<>());
        try{return supplier.get();}finally{current.remove();values.remove();}
    }
    @SuppressWarnings("unchecked")
    public static <T> T memo(Object key,ThrowingSupplier<T> supplier)throws Exception{
        var cache=values.get();if(cache==null)return supplier.get();
        if(cache.containsKey(key))return (T)cache.get(key);
        T value=supplier.get();cache.put(key,value);return value;
    }
    public static Context current(){return current.get();}
    public static long id(){var value=current.get();return value==null?0:value.id();}
    public static String method(){var value=current.get();return value==null?null:value.method();}
}
