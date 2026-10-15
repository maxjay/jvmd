package dev.jvmd.core;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Opt-in boot observation for the daemon boot/persistence assessment (benchmarks/boot). Off unless
 * {@code -Djvmd.profile.boot_events=FILE} is set; then each {@link #mark} appends one JSON line with
 * the monotonic clock (CLOCK_MONOTONIC on Linux, comparable with the harness's spawn time), the
 * cumulative allocation counter where the runtime has one, and the given fields. Counters are plain
 * adders. Nothing here changes what the daemon computes, persists or waits for.
 *
 * {@code -Djvmd.profile.boot_dump=true} additionally lets {@link #dump} write registered runtime
 * diagnostics (in-memory views, read without semantic work) after the observed endpoint.
 */
public final class BootEvents {
    public static final boolean ENABLED=System.getProperty("jvmd.profile.boot_events")!=null;
    public static final boolean DUMP=ENABLED&&Boolean.getBoolean("jvmd.profile.boot_dump");
    private static final String FILE=System.getProperty("jvmd.profile.boot_events");
    private static final Map<String,LongAdder> COUNTERS=new ConcurrentHashMap<>();
    private static final Map<String,java.util.concurrent.atomic.AtomicLong> MAXIMA=new ConcurrentHashMap<>();
    private static final Map<String,Supplier<Object>> PROVIDERS=new ConcurrentHashMap<>();
    private static final Object WRITE=new Object();
    private BootEvents(){}

    public static void count(String name,long delta){
        if(ENABLED)COUNTERS.computeIfAbsent(name,_->new LongAdder()).add(delta);
    }
    public static long nanos(){return ENABLED?System.nanoTime():0L;}
    /** Records the largest value seen under {@code name} (reported with the counters as {@code name}_max). */
    public static void maximum(String name,long value){
        if(ENABLED)MAXIMA.computeIfAbsent(name,_->new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)).accumulateAndGet(value,Math::max);
    }
    /** Adds the elapsed time since {@code started} (from {@link #nanos}) to {@code name}_ns and counts one call. */
    public static void timed(String name,long started){
        if(!ENABLED)return;
        COUNTERS.computeIfAbsent(name+"_ns",_->new LongAdder()).add(System.nanoTime()-started);
        COUNTERS.computeIfAbsent(name+"_calls",_->new LongAdder()).increment();
    }
    public static Map<String,Long> counters(){
        var result=new TreeMap<String,Long>();COUNTERS.forEach((name,value)->result.put(name,value.sum()));
        MAXIMA.forEach((name,value)->result.put(name+"_max",value.get()));return result;
    }
    /** Registers an in-memory diagnostic view, read only by {@link #dump}. */
    public static void provider(String name,Supplier<Object> value){if(DUMP)PROVIDERS.put(name,value);}

    public static void mark(String event,Object... fields){
        if(!ENABLED)return;
        long now=System.nanoTime();
        var row=new LinkedHashMap<String,Object>();
        row.put("event",event);row.put("mono_ns",now);row.put("wall_ms",System.currentTimeMillis());
        row.put("thread",Thread.currentThread().getName());row.put("allocated_bytes",allocated());
        row.put("process_cpu_ns",processCpu());
        for(int i=0;i+1<fields.length;i+=2)row.put(String.valueOf(fields[i]),fields[i+1]);
        write(row);
    }
    /** Marks {@code event} with every counter. */
    public static void markWithCounters(String event,Object... fields){
        if(!ENABLED)return;
        var extended=new Object[fields.length+2];System.arraycopy(fields,0,extended,0,fields.length);
        extended[fields.length]="counters";extended[fields.length+1]=counters();
        mark(event,extended);
    }
    /** Writes every registered diagnostic view as one line. Runs only with boot_dump. */
    public static void dump(String event){
        if(!DUMP)return;
        long started=System.nanoTime();
        var views=new TreeMap<String,Object>();
        PROVIDERS.forEach((name,provider)->{
            try{views.put(name,provider.get());}catch(RuntimeException failure){views.put(name,Map.of("error",failure.toString()));}
        });
        var row=new LinkedHashMap<String,Object>();
        row.put("event",event);row.put("mono_ns",started);row.put("dump_ns",System.nanoTime()-started);row.put("views",views);
        write(row);
    }

    private static void write(Map<String,Object> row){
        synchronized(WRITE){
            try(var out=new FileOutputStream(FILE,true)){
                out.write(Json.MAPPER.writeValueAsBytes(row));out.write('\n');
            }catch(IOException|RuntimeException ignored){/* Observation never fails the daemon. */}
        }
    }
    /** The JDK's process-wide cumulative heap allocation, or -1 where the runtime cannot report it. */
    private static long allocated(){
        try{
            var bean=ManagementFactory.getThreadMXBean();
            if(bean instanceof com.sun.management.ThreadMXBean extended&&extended.isThreadAllocatedMemorySupported()
                    &&extended.isThreadAllocatedMemoryEnabled())return extended.getTotalThreadAllocatedBytes();
        }catch(LinkageError|RuntimeException unavailable){/* jlink image without jdk.management */}
        return -1;
    }
    private static long processCpu(){
        try{
            if(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)return os.getProcessCpuTime();
        }catch(LinkageError|RuntimeException unavailable){/* jlink image without jdk.management */}
        return -1;
    }
}
