package dev.jvmd.core;

import java.util.concurrent.atomic.AtomicLong;

/** Per-RPC execution identity used to memoize request-stable preparation without leaking across requests. */
public final class RequestScope {
    @FunctionalInterface public interface ThrowingSupplier<T>{T get()throws Exception;}
    public record Context(long id,String method){}
    private static final AtomicLong sequence=new AtomicLong();
    private static final ThreadLocal<Context> current=new ThreadLocal<>();
    private RequestScope(){}

    public static <T> T call(String method,ThrowingSupplier<T> supplier)throws Exception{
        if(current.get()!=null)return supplier.get();
        current.set(new Context(sequence.incrementAndGet(),method));
        try{return supplier.get();}finally{current.remove();}
    }
    public static Context current(){return current.get();}
    public static long id(){var value=current.get();return value==null?0:value.id();}
    public static String method(){var value=current.get();return value==null?null:value.method();}
}
