package dev.jvmd.boot.cold.stage3;

import java.util.*;
import java.util.function.Consumer;

/** Bootstrap callback only. No compiler classes, decoding, lookups or application instrumentation. */
public final class NativeReaderTap {
    private NativeReaderTap() { }
    private static final Set<String> INSTALLED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String drift;
    private static final ThreadLocal<Session> ACTIVE=new ThreadLocal<>();
    private static final StackWalker CALLER=StackWalker.getInstance();
    private static final class Session {
        final Consumer<Object[]> consumer;
        String failure;
        boolean callback;
        int globalTypes,methodScans;
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
        if(!INSTALLED.contains("com/sun/tools/javac/comp/Resolve") || !INSTALLED.contains("com/sun/tools/javac/comp/Check")
                || !INSTALLED.contains("com/sun/tools/javac/code/Scope$ScopeImpl"))return "Resolution bridge hooks absent";
        return null;
    }
    public static String finish() {
        var session=ACTIVE.get();ACTIVE.remove();
        if(session==null)return "Reader capture was not installed";
        if(session.globalTypes!=0 || session.methodScans!=0)return "Unbalanced native resolution capture";
        var problem=status();return problem==null?session.failure:problem;
    }
    public static void enter(String operation) {
        var s=ACTIVE.get();if(s==null)return;
        if(operation.equals("global"))s.globalTypes++;else s.methodScans++;
    }
    public static void leave(String operation) {
        var s=ACTIVE.get();if(s==null)return;
        if(operation.equals("global"))s.globalTypes--;else s.methodScans--;
    }
    /** Wrap only an actual native iterable; never query a scope or advance its iterator for capture. */
    public static Iterable<?> scope(Iterable<?> original,String operation,Object scope,Object name) {
        var session=ACTIVE.get();
        if(session==null || session.callback || (operation.equals("names")?session.globalTypes==0:session.methodScans==0))return original;
        // Completion/deproxy can run inside a global lookup. Its scopes are reader questions,
        // not member-type questions. Classify the actual native caller across lazy scope adapters.
        if(operation.equals("names") && !CALLER.walk(frames->frames
                .filter(f->f.getClassName().startsWith("com.sun.tools.javac."))
                .filter(f->!f.getClassName().startsWith("com.sun.tools.javac.code.Scope")
                        && !f.getClassName().startsWith("com.sun.tools.javac.util.Iterators"))
                .findFirst().map(f->f.getClassName().equals("com.sun.tools.javac.comp.Resolve")
                        && f.getMethodName().equals("findGlobalType")).orElse(false)))return original;
        return ()->new Iterator<Object>() {
            final Iterator<?> iterator=original.iterator();
            boolean seen,reported,namedObserved;
            @Override public boolean hasNext() {
                boolean result=iterator.hasNext();
                if(ACTIVE.get()==session) {
                    if(operation.equals("methods") && session.methodScans>0 && !reported) {
                        reported=true;event("resolution-methods",null,scope,null,null);
                    } else if(operation.equals("names") && session.globalTypes>0) {
                        if(!namedObserved){namedObserved=true;event("import-member-type",null,scope,name,null);}
                        if(result)seen=true;
                        else if(!seen && !reported){reported=true;event("package-absence",null,scope,name,null);}
                    }
                }
                return result;
            }
            @Override public Object next(){var value=iterator.next();seen=true;return value;}
            @Override public void remove(){iterator.remove();}
        };
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
