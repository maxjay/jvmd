package dev.jvmd.tests;

import dev.jvmd.core.Hashing;
import dev.jvmd.core.Json;
import dev.jvmd.index.*;
import dev.jvmd.index.rocks.RocksArtifactRepository;
import dev.jvmd.index.segment.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One measured process of the W8 MACHINE re-decision (strict task W8). Run in a fresh JVM per
 * backend and phase so process-level peaks (RSS, native allocation) belong to one backend:
 * <pre>
 *   seed    &lt;rocks|native&gt; &lt;corpus dir&gt; &lt;state dir&gt; &lt;result.json&gt;   build the store from every jar, then first-use queries
 *   restart &lt;rocks|native&gt; &lt;corpus dir&gt; &lt;state dir&gt; &lt;result.json&gt;   open the built store and answer the first query
 * </pre>
 * The workload is sampled from the same corpus with a fixed seed, so both backends answer the same
 * exact, prefix (owner members) and substring queries.
 */
public final class MachineDecisionRun {
    private MachineDecisionRun(){}

    record Artifact(String name,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences) { }

    /** glibc {@code mallinfo2}: in-use heap bytes ({@code uordblks}) plus mmapped blocks ({@code hblkhd}). */
    private static final MethodHandle MALLINFO2;
    private static final StructLayout MALLINFO=MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("arena"),ValueLayout.JAVA_LONG.withName("ordblks"),ValueLayout.JAVA_LONG.withName("smblks"),
            ValueLayout.JAVA_LONG.withName("hblks"),ValueLayout.JAVA_LONG.withName("hblkhd"),ValueLayout.JAVA_LONG.withName("usmblks"),
            ValueLayout.JAVA_LONG.withName("fsmblks"),ValueLayout.JAVA_LONG.withName("uordblks"),ValueLayout.JAVA_LONG.withName("fordblks"),
            ValueLayout.JAVA_LONG.withName("keepcost"));
    static{
        MethodHandle handle=null;
        try{
            var linker=Linker.nativeLinker();
            handle=linker.defaultLookup().find("mallinfo2").map(symbol->linker.downcallHandle(symbol,FunctionDescriptor.of(MALLINFO))).orElse(null);
        }catch(RuntimeException unavailable){}
        MALLINFO2=handle;
    }
    static long mallocInUse(){
        if(MALLINFO2==null)return -1;
        try(var arena=Arena.ofConfined()){
            var info=(MemorySegment)MALLINFO2.invokeExact((SegmentAllocator)arena);
            return info.get(ValueLayout.JAVA_LONG,7*8)+info.get(ValueLayout.JAVA_LONG,4*8);
        }catch(Throwable failure){return -1;}
    }
    static long status(String field){
        try{
            for(String line:Files.readAllLines(Path.of("/proc/self/status")))
                if(line.startsWith(field+":"))return Long.parseLong(line.replaceAll("[^0-9]",""))*1024;
        }catch(Exception unavailable){}
        return -1;
    }
    static long totalAllocated(){return ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).getTotalThreadAllocatedBytes();}
    static long peakHeap(){
        long total=0;
        for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP&&pool.getPeakUsage()!=null)total+=pool.getPeakUsage().getUsed();
        return total;
    }
    static long bytes(Path directory)throws Exception{
        try(var files=Files.walk(directory)){return files.filter(Files::isRegularFile).mapToLong(path->{try{return Files.size(path);}catch(Exception e){return 0;}}).sum();}
    }
    static List<Path> jars(Path corpus)throws Exception{
        try(var walk=Files.walk(corpus)){return walk.filter(path->path.toString().endsWith(".jar")).sorted().toList();}
    }
    static String name(Path corpus,Path jar){return corpus.relativize(jar).toString().replace('/','_');}

    public static void main(String[] args)throws Exception{
        String mode=args[0],backend=args[1];Path corpus=Path.of(args[2]),state=Path.of(args[3]),result=Path.of(args[4]);
        var out=new LinkedHashMap<String,Object>();out.put("backend",backend);out.put("mode",mode);
        if(mode.equals("seed"))seed(backend,corpus,state,out);else restart(backend,state,out);
        Files.writeString(result,Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }

    private static void seed(String backend,Path corpus,Path state,Map<String,Object> out)throws Exception{
        Files.createDirectories(state);
        var mallocPeak=new AtomicLong(Math.max(0,mallocInUse()));
        var sampler=Thread.ofPlatform().daemon().start(()->{
            while(!Thread.currentThread().isInterrupted()){
                long now=mallocInUse();mallocPeak.accumulateAndGet(now,Math::max);
                try{Thread.sleep(20);}catch(InterruptedException stop){return;}
            }
        });
        long allocatedBefore=totalAllocated(),started=System.nanoTime();
        var manifest=new LinkedHashMap<String,String>();var samples=new ArrayList<String[]>();var random=new Random(42);
        long symbols=0;int published=0;
        RocksArtifactRepository rocks=backend.equals("rocks")?new RocksArtifactRepository(state.resolve("rocks")):null;
        try{
            for(Path jar:jars(corpus)){
                var content=new BinaryReader().read(jar,false);if(content.symbols().isEmpty())continue;
                var facts=ArtifactIndexFormat.from(content,ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures"));
                String name=name(corpus,jar);symbols+=facts.symbols().size();published++;
                if(rocks!=null){rocks.publish(facts,CodeReader.classReferences(content.models().values()));manifest.put(name,facts.key().cacheKey());}
                else{
                    Path segment=state.resolve("native").resolve(name+".seg");MachineSegment.write(facts,segment);
                    try(var base=MachineSegment.open(segment,false)){MachineAccelerators.write(base,state.resolve("native").resolve(name+".acc"),512);}
                    manifest.put(name,name);
                }
                // Reservoir-free fixed-rate sampling keeps the workload identical across backends.
                for(var symbol:facts.symbols())if(random.nextInt(200)==0)samples.add(new String[]{name,symbol.key(),symbol.name()==null?"":symbol.name(),symbol.kind()});
            }
            out.put("build_ms",(System.nanoTime()-started)/1e6);
            out.put("jars",published);out.put("symbols",symbols);
            out.put("heap_allocation_bytes",totalAllocated()-allocatedBefore);
            out.put("peak_heap_bytes",peakHeap());
            sampler.interrupt();sampler.join();
            out.put("peak_native_malloc_bytes",mallocPeak.get());
            out.put("peak_rss_bytes",status("VmHWM"));
            if(rocks!=null){rocks.close();rocks=null;}
            out.put("disk_bytes",bytes(state.resolve(backend.equals("rocks")?"rocks":"native")));
            Files.writeString(state.resolve("manifest.json"),Json.MAPPER.writeValueAsString(manifest));
            Files.writeString(state.resolve("workload.json"),Json.MAPPER.writeValueAsString(samples));
            out.putAll(queries(backend,state,manifest,samples));
        }finally{if(rocks!=null)rocks.close();sampler.interrupt();}
    }

    private static void restart(String backend,Path state,Map<String,Object> out)throws Exception{
        @SuppressWarnings("unchecked") Map<String,String> manifest=Json.MAPPER.readValue(Files.readString(state.resolve("manifest.json")),LinkedHashMap.class);
        var samples=Json.MAPPER.readValue(Files.readString(state.resolve("workload.json")),String[][].class);
        var first=samples[0];long started=System.nanoTime();
        if(backend.equals("rocks")){
            try(var rocks=new RocksArtifactRepository(state.resolve("rocks"))){rocks.binaryId(manifest.get(first[0]),first[1]);out.put("restart_first_query_ms",(System.nanoTime()-started)/1e6);}
        }else{
            // Restart opens the segment directory lazily: only the artifact the first query names is mapped.
            try(var segment=MachineSegment.open(state.resolve("native").resolve(first[0]+".seg"),true)){
                segment.exact(first[1]);out.put("restart_first_query_ms",(System.nanoTime()-started)/1e6);
            }
        }
    }

    private static Map<String,Object> queries(String backend,Path state,Map<String,String> manifest,List<String[]> samples)throws Exception{
        var result=new LinkedHashMap<String,Object>();
        long[] exactNanos=new long[samples.size()],prefixNanos=new long[samples.size()],substringNanos=new long[samples.size()];
        if(backend.equals("rocks")){
            try(var rocks=new RocksArtifactRepository(state.resolve("rocks"))){
                for(int i=0;i<samples.size();i++){
                    var q=samples.get(i);String key=manifest.get(q[0]);
                    long t=System.nanoTime();var id=rocks.binaryId(key,q[1]);if(id!=null)rocks.symbol(key,id);exactNanos[i]=System.nanoTime()-t;
                    String owner=q[1].contains("#")?q[1].substring(0,q[1].indexOf('#')):q[1];
                    t=System.nanoTime();rocks.ownerMembers(key,owner,q[2].length()>2?q[2].substring(0,2):q[2],50,null);prefixNanos[i]=System.nanoTime()-t;
                    String needle=q[2].length()>=3?q[2].substring(0,3):"get";
                    t=System.nanoTime();int found=0;for(int candidate:rocks.substringIds(key,needle,10_000))if(rocks.symbol(key,candidate).name().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))&&++found==50)break;
                    substringNanos[i]=System.nanoTime()-t;
                }
            }
        }else{
            var segments=new HashMap<String,MachineSegment>();var accelerators=new HashMap<String,MachineAccelerators>();
            try{
                // Open every store before timing, as the RocksDB column families are opened once above.
                for(String name:manifest.keySet()){
                    var segment=MachineSegment.open(state.resolve("native").resolve(name+".seg"),false);segments.put(name,segment);
                    accelerators.put(name,MachineAccelerators.open(segment,state.resolve("native").resolve(name+".acc")));
                }
                for(int i=0;i<samples.size();i++){
                    var q=samples.get(i);
                    long t=System.nanoTime();
                    var segment=segments.get(q[0]);
                    int id=segment.exact(q[1]);if(id>=0)segment.symbol(id);exactNanos[i]=System.nanoTime()-t;
                    String owner=q[1].contains("#")?q[1].substring(0,q[1].indexOf('#')):q[1];
                    t=System.nanoTime();int ownerId=segment.exact(owner);if(ownerId>=0)segment.members(ownerId,q[2].length()>2?q[2].substring(0,2):q[2],50);prefixNanos[i]=System.nanoTime()-t;
                    String needle=q[2].length()>=3?q[2].substring(0,3):"get";
                    t=System.nanoTime();
                    accelerators.get(q[0]).substring(needle,50);substringNanos[i]=System.nanoTime()-t;
                }
            }finally{accelerators.values().forEach(MachineAccelerators::close);segments.values().forEach(MachineSegment::close);}
        }
        result.put("queries",samples.size());
        result.put("exact_p50_us",percentile(exactNanos,.5));result.put("exact_p95_us",percentile(exactNanos,.95));
        result.put("prefix_p50_us",percentile(prefixNanos,.5));result.put("prefix_p95_us",percentile(prefixNanos,.95));
        result.put("substring_p50_us",percentile(substringNanos,.5));result.put("substring_p95_us",percentile(substringNanos,.95));
        return result;
    }
    static double percentile(long[] nanos,double p){
        var sorted=nanos.clone();Arrays.sort(sorted);if(sorted.length==0)return 0;
        return sorted[Math.min(sorted.length-1,(int)Math.ceil(p*sorted.length)-1)]/1000.0;
    }
}
