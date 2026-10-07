package dev.jvmd.boot.cold.stage3;

import java.util.*;
import java.util.function.Consumer;

/** Bootstrap callback only. No compiler classes, decoding, lookups or application instrumentation. */
public final class NativeReaderTap {
    private NativeReaderTap() { }
    private static final Set<String> INSTALLED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String drift;
    private static final ThreadLocal<Session> ACTIVE=new ThreadLocal<>();
    private static final class Session {
        final Consumer<Object[]> consumer;
        String failure;
        boolean callback;
        Session(Consumer<Object[]> consumer) {this.consumer=consumer;}
    }
    public static void installed(String name) {INSTALLED.add(name);}
    public static void drift(String reason) {drift=reason;}
    public static boolean begin(Consumer<Object[]> consumer) {
        if(ACTIVE.get()!=null)throw new IllegalStateException("Nested Stage 3 reader capture");
        ACTIVE.set(new Session(consumer));return drift==null;
    }
    public static String status() {
        if(drift!=null)return drift;
        if(!INSTALLED.contains("com/sun/tools/javac/jvm/ClassReader")
                || !INSTALLED.contains("com/sun/tools/javac/jvm/ClassReader$AnnotationDeproxy")
                || !INSTALLED.contains("com/sun/tools/javac/code/Symbol$ClassSymbol"))return "Reader bridge hooks absent";
        return null;
    }
    public static String finish() {
        var session=ACTIVE.get();ACTIVE.remove();
        if(session==null)return "Reader capture was not installed";
        var problem=status();return problem==null?session.failure:problem;
    }
    /** All arguments are already computed by native execution. Callback failures never replace native exceptions. */
    public static void event(String operation,Object value,Object requester,Object argument,Object failure) {
        var session=ACTIVE.get();if(session==null || session.callback)return;
        session.callback=true;
        try {session.consumer.accept(new Object[]{operation,value,requester,argument,failure});}
        catch(Throwable ex) {session.failure="Reader capture failed: "+ex.getClass().getName()+": "+ex.getMessage();}
        finally {session.callback=false;}
    }
}
