package dev.jvmd.tests;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import dev.jvmd.index.rocks.RocksArtifactRepository;
import dev.jvmd.index.segment.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phases 11–12 MACHINE storage comparison (architecture §54, §97–100, §113–114).
 *
 * Builds the same real artifacts (every jar on the test classpath) into RocksDB, a minimal native
 * segment and a native segment plus accelerators, then runs an identical query workload. Results
 * are printed as Markdown and written to {@code target/benchmarks/machine-storage.md}.
 * Run with: {@code mvn -pl jvmd-tests test -Dtest=MachineStorageBenchmark -DexcludedGroups=corpus}
 */
@Tag("benchmark")
class MachineStorageBenchmark {
    @TempDir Path root;
    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");
    private static final int SAMPLE=2000;

    record Artifact(String name,Path jar,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences) { }
    record Workload(List<String[]> exact,List<String[]> members,List<String[]> substrings,List<String[]> incoming) { }

    static long allocated(){
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    static Map<String,Long> process(){
        var result=new LinkedHashMap<String,Long>();
        try{
            for(String line:Files.readAllLines(Path.of("/proc/self/status")))
                if(line.startsWith("VmRSS:"))result.put("rss_kb",Long.parseLong(line.replaceAll("[^0-9]","")));
            String[] stat=Files.readString(Path.of("/proc/self/stat")).replaceAll("^.*\\) ","").split(" ");
            result.put("minor_faults",Long.parseLong(stat[7]));result.put("major_faults",Long.parseLong(stat[9]));
        }catch(Exception unavailable){result.put("unavailable",1L);}
        result.put("heap_used_kb",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024);
        return result;
    }
    static boolean dropPageCache(){
        try{Files.writeString(Path.of("/proc/sys/vm/drop_caches"),"1");return true;}catch(Exception denied){return false;}
    }
    static double percentile(long[] nanos,double p){
        var sorted=nanos.clone();Arrays.sort(sorted);if(sorted.length==0)return 0;
        return sorted[Math.min(sorted.length-1,(int)Math.ceil(p*sorted.length)-1)]/1000.0;
    }
    static String latency(long[] nanos){return String.format(Locale.ROOT,"%.1f / %.1f",percentile(nanos,.5),percentile(nanos,.95));}
    static long[] time(int count,IntSupplier body){
        var result=new long[count];long sink=0;
        for(int i=0;i<count;i++){long started=System.nanoTime();sink+=body.getAsInt();result[i]=System.nanoTime()-started;}
        if(sink==Long.MIN_VALUE)System.out.print("");
        return result;
    }

    static List<Artifact> corpus()throws Exception{
        var result=new ArrayList<Artifact>();
        for(String entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator)){
            if(!entry.endsWith(".jar"))continue;Path jar=Path.of(entry);
            var content=new BinaryReader().read(jar,false);if(content.symbols().isEmpty())continue;
            var facts=ArtifactIndexFormat.from(content,ArtifactIndexFormat.key(Hashing.sha256(jar),"signatures"));
            result.add(new Artifact(jar.getFileName().toString(),jar,facts,CodeReader.classReferences(content.models().values())));
        }
        result.sort(Comparator.comparing(Artifact::name));
        return result;
    }
    static Workload workload(List<Artifact> artifacts){
        var random=new Random(42);var exact=new ArrayList<String[]>();var members=new ArrayList<String[]>();
        var substrings=new ArrayList<String[]>();var incoming=new ArrayList<String[]>();
        var all=new ArrayList<String[]>();
        for(var artifact:artifacts)for(var symbol:artifact.facts().symbols())all.add(new String[]{artifact.name(),symbol.key(),symbol.name(),symbol.kind()});
        for(int i=0;i<SAMPLE;i++){
            var pick=all.get(random.nextInt(all.size()));
            exact.add(random.nextInt(10)==0?new String[]{pick[0],pick[1]+"$Missing"}:new String[]{pick[0],pick[1]});
        }
        var owners=all.stream().filter(value->TYPES.contains(value[3])).toList();
        for(int i=0;i<SAMPLE/4;i++){
            var pick=owners.get(random.nextInt(owners.size()));
            String prefix=switch(random.nextInt(3)){case 0->"";case 1->"get";default->"is";};
            members.add(new String[]{pick[0],pick[1],prefix});
        }
        for(int i=0;i<SAMPLE/10;i++){
            var pick=all.get(random.nextInt(all.size()));String name=pick[2];
            if(name.length()<4){i--;continue;}
            int start=random.nextInt(name.length()-3);substrings.add(new String[]{pick[0],name.substring(start,Math.min(name.length(),start+3+random.nextInt(3)))});
        }
        for(var artifact:artifacts)for(var edge:artifact.facts().relationships())if(random.nextInt(50)==0)incoming.add(new String[]{artifact.name(),edge.target(),edge.kind()});
        Collections.shuffle(incoming,random);
        return new Workload(exact,members,substrings,incoming.subList(0,Math.min(SAMPLE/4,incoming.size())));
    }

    @Test void compareRocksDbWithMinimalAndAcceleratedNativeSegments()throws Exception{
        var artifacts=corpus();var workload=workload(artifacts);
        long symbols=artifacts.stream().mapToLong(artifact->artifact.facts().symbols().size()).sum();
        long relationships=artifacts.stream().mapToLong(artifact->artifact.facts().relationships().size()).sum();
        var rows=new LinkedHashMap<String,Map<String,String>>();
        var notes=new ArrayList<String>();
        notes.add("corpus: "+artifacts.size()+" jars on the test classpath, "+symbols+" symbols, "+relationships+" relationships");
        notes.add("page cache drop for cold reads: "+(dropPageCache()?"available":"unavailable (cold = first query after a fresh open)"));

        // ---------------- RocksDB
        var rocks=new LinkedHashMap<String,String>();Path rocksRoot=root.resolve("rocks");
        long allocation=allocated();long started=System.nanoTime();
        var keys=new HashMap<String,String>();
        try(var repository=new RocksArtifactRepository(rocksRoot)){
            for(var artifact:artifacts){repository.publish(artifact.facts(),artifact.classReferences());keys.put(artifact.name(),artifact.facts().key().cacheKey());}
            rocks.put("build",perSecond(symbols,System.nanoTime()-started));rocks.put("build heap allocation",mb(allocated()-allocation));
        }
        long rocksBytes=bytes(rocksRoot);rocks.put("disk bytes",mb(rocksBytes));rocks.put("bytes/symbol",String.format(Locale.ROOT,"%.1f",rocksBytes/(double)symbols));
        started=System.nanoTime();
        try(var repository=new RocksArtifactRepository(rocksRoot)){
            rocks.put("open time",ms(System.nanoTime()-started));
            var first=workload.exact().getFirst();long firstStarted=System.nanoTime();
            repository.binaryId(keys.get(first[0]),first[1]);rocks.put("restart → first query",ms(System.nanoTime()-firstStarted+(firstStarted-started)));
            // Unmeasured warm-up so every variant is compared with a warm JIT.
            for(var q:workload.exact()){var id=repository.binaryId(keys.get(q[0]),q[1]);if(id!=null)repository.symbol(keys.get(q[0]),id);}
            for(var q:workload.members()){repository.ownerMembers(keys.get(q[0]),q[1],q[2],50,null);rocksRange(repository,keys.get(q[0]),q[1],q[2]);}
            for(var q:workload.incoming())repository.incoming(keys.get(q[0]),q[1],Set.of(q[2]),10_000);
            dropPageCache();
            rocks.put("exact lookup cold",latency(time(Math.min(200,workload.exact().size()),indexer(i->{var q=workload.exact().get(i);
                try{var id=repository.binaryId(keys.get(q[0]),q[1]);return id==null?-1:repository.symbol(keys.get(q[0]),id).id();}catch(Exception e){throw new IllegalStateException(e);}}))));
            var before=process();allocation=allocated();
            rocks.put("exact lookup hot",latency(time(workload.exact().size(),indexer(i->{var q=workload.exact().get(i);
                try{var id=repository.binaryId(keys.get(q[0]),q[1]);return id==null?-1:repository.symbol(keys.get(q[0]),id).id();}catch(Exception e){throw new IllegalStateException(e);}}))));
            rocks.put("member prefix (limit 50)",latency(time(workload.members().size(),indexer(i->{var q=workload.members().get(i);
                try{return repository.ownerMembers(keys.get(q[0]),q[1],q[2],50,null).symbols().size();}catch(Exception e){throw new IllegalStateException(e);}}))));
            rocks.put("range identity (full range)",latency(time(workload.members().size(),indexer(i->{var q=workload.members().get(i);
                try{return rocksRange(repository,keys.get(q[0]),q[1],q[2]);}catch(Exception e){throw new IllegalStateException(e);}}))));
            rocks.put("substring search",latency(time(workload.substrings().size(),indexer(i->{var q=workload.substrings().get(i);
                try{int found=0;String needle=q[1].toLowerCase(Locale.ROOT);
                    for(int id:repository.substringIds(keys.get(q[0]),q[1],10_000))if(repository.symbol(keys.get(q[0]),id).name().toLowerCase(Locale.ROOT).contains(needle)&&++found==50)break;
                    return found;}catch(Exception e){throw new IllegalStateException(e);}}))));
            rocks.put("relationship traversal (incoming)",latency(time(workload.incoming().size(),indexer(i->{var q=workload.incoming().get(i);
                return repository.incoming(keys.get(q[0]),q[1],Set.of(q[2]),10_000).size();}))));
            rocks.put("query heap allocation",mb(allocated()-allocation));
            var after=process();
            rocks.put("RSS (process)",mb(after.getOrDefault("rss_kb",0L)*1024));rocks.put("major / minor faults (queries)",
                    (after.getOrDefault("major_faults",0L)-before.getOrDefault("major_faults",0L))+" / "+(after.getOrDefault("minor_faults",0L)-before.getOrDefault("minor_faults",0L)));
            rocks.put("mapped bytes","n/a (block cache)");
        }
        rows.put("RocksDB",rocks);

        // ---------------- native (minimal) and native + accelerators
        Path nativeRoot=Files.createDirectories(root.resolve("native"));
        var minimal=new LinkedHashMap<String,String>();var accelerated=new LinkedHashMap<String,String>();
        allocation=allocated();started=System.nanoTime();
        for(var artifact:artifacts)MachineSegment.write(artifact.facts(),nativeRoot.resolve(artifact.name()+".seg"));
        long baseBuild=System.nanoTime()-started;long baseAllocation=allocated()-allocation;
        minimal.put("build",perSecond(symbols,baseBuild));minimal.put("build heap allocation",mb(baseAllocation));
        long segmentBytes=bytes(nativeRoot);
        minimal.put("disk bytes",mb(segmentBytes));minimal.put("bytes/symbol",String.format(Locale.ROOT,"%.1f",segmentBytes/(double)symbols));
        allocation=allocated();started=System.nanoTime();
        {
            var segments=openAll(artifacts,nativeRoot,true);
            for(var artifact:artifacts)MachineAccelerators.write(segments.get(artifact.name()),nativeRoot.resolve(artifact.name()+".acc"),512);
            segments.values().forEach(MachineSegment::close);
        }
        long accBuild=System.nanoTime()-started;long accelerationBytes=bytes(nativeRoot)-segmentBytes;
        accelerated.put("build",perSecond(symbols,baseBuild+accBuild));accelerated.put("build heap allocation",mb(baseAllocation+allocated()-allocation));
        accelerated.put("disk bytes",mb(segmentBytes+accelerationBytes));
        accelerated.put("bytes/symbol",String.format(Locale.ROOT,"%.1f",(segmentBytes+accelerationBytes)/(double)symbols));

        for(boolean withAccelerators:List.of(false,true)){
            var row=withAccelerators?accelerated:minimal;
            {
                dropPageCache();long unverified=System.nanoTime();
                var quick=openAll(artifacts,nativeRoot,false);
                row.put("open time (checksums verified at publication)",ms(System.nanoTime()-unverified));
                quick.values().forEach(MachineSegment::close);
            }
            dropPageCache();started=System.nanoTime();
            var segments=openAll(artifacts,nativeRoot,true);
            var acc=new HashMap<String,MachineAccelerators>();
            if(withAccelerators)for(var artifact:artifacts)acc.put(artifact.name(),MachineAccelerators.open(segments.get(artifact.name()),nativeRoot.resolve(artifact.name()+".acc")));
            row.put("open time",ms(System.nanoTime()-started)+" (CRC verified)");
            var first=workload.exact().getFirst();long firstStarted=System.nanoTime();segments.get(first[0]).exact(first[1]);
            row.put("restart → first query",ms(System.nanoTime()-firstStarted+(firstStarted-started)));
            for(var q:workload.exact()){var segment=segments.get(q[0]);int id=segment.exact(q[1]);if(id>=0)segment.symbol(id);}
            for(var q:workload.members()){var segment=segments.get(q[0]);int owner=segment.exact(q[1]);if(owner>=0){segment.members(owner,q[2],50);segment.memberRangeIdentity(owner,q[2]);}}
            for(var q:workload.incoming())if(withAccelerators)acc.get(q[0]).incoming(q[1],q[2]);
            for(var q:workload.substrings())if(withAccelerators)acc.get(q[0]).substring(q[1],50);
            dropPageCache();
            row.put("exact lookup cold",latency(time(Math.min(200,workload.exact().size()),indexer(i->{var q=workload.exact().get(i);
                var segment=segments.get(q[0]);int id=segment.exact(q[1]);return id<0?-1:segment.symbol(id).id();}))));
            var before=process();allocation=allocated();
            row.put("exact lookup hot",latency(time(workload.exact().size(),indexer(i->{var q=workload.exact().get(i);
                var segment=segments.get(q[0]);int id=segment.exact(q[1]);return id<0?-1:segment.symbol(id).id();}))));
            row.put("member prefix (limit 50)",latency(time(workload.members().size(),indexer(i->{var q=workload.members().get(i);
                var segment=segments.get(q[0]);int owner=segment.exact(q[1]);return owner<0?0:segment.members(owner,q[2],50).size();}))));
            row.put("range identity (full range)",latency(time(workload.members().size(),indexer(i->{var q=workload.members().get(i);
                var segment=segments.get(q[0]);int owner=segment.exact(q[1]);return owner<0?0:segment.memberRangeIdentity(owner,q[2]).hashCode();}))));
            row.put("substring search",latency(time(workload.substrings().size(),indexer(i->{var q=workload.substrings().get(i);
                return withAccelerators?acc.get(q[0]).substring(q[1],50).size():segments.get(q[0]).substringScan(q[1],50).size();}))));
            row.put("relationship traversal (incoming)",latency(time(workload.incoming().size(),indexer(i->{var q=workload.incoming().get(i);
                return withAccelerators?acc.get(q[0]).incoming(q[1],q[2]).size():segments.get(q[0]).incomingScan(q[1],q[2]).size();}))));
            row.put("query heap allocation",mb(allocated()-allocation));
            var after=process();
            row.put("RSS (process)",mb(after.getOrDefault("rss_kb",0L)*1024));
            row.put("major / minor faults (queries)",(after.getOrDefault("major_faults",0L)-before.getOrDefault("major_faults",0L))+" / "
                    +(after.getOrDefault("minor_faults",0L)-before.getOrDefault("minor_faults",0L)));
            long mapped=segments.values().stream().mapToLong(MachineSegment::mappedBytes).sum()+acc.values().stream().mapToLong(MachineAccelerators::mappedBytes).sum();
            row.put("mapped bytes",mb(mapped));
            if(withAccelerators){
                long absent=0,asked=0;
                for(var q:workload.exact())if(q[1].endsWith("$Missing")){asked++;if(acc.get(q[0]).type(q[1])==MachineAccelerators.Membership.ABSENT)absent++;}
                notes.add("membership filter rejected "+absent+" of "+asked+" absent exact lookups without touching canonical data");
                notes.add("large owners (≥512 members) recorded for checkpoints: "+acc.values().stream().mapToInt(MachineAccelerators::largeOwners).sum());
            }
            acc.values().forEach(MachineAccelerators::close);segments.values().forEach(MachineSegment::close);
        }
        rows.put("Minimal native",minimal);rows.put("Native + accelerators",accelerated);
        {
            var sections=new TreeMap<String,Long>();
            var segments=openAll(artifacts,nativeRoot,false);
            for(var segment:segments.values())segment.sectionBytes().forEach((name,size)->sections.merge(name,size,Long::sum));
            segments.values().forEach(MachineSegment::close);
            var breakdown=new StringBuilder("native section bytes: ");
            sections.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed())
                    .forEach(entry->breakdown.append(entry.getKey()).append('=').append(mb(entry.getValue())).append(", "));
            notes.add(breakdown.substring(0,breakdown.length()-2));
            notes.add("accelerator bytes (filters + trigram postings + reverse CSR + checkpoints): "+mb(accelerationBytes));
            // §42: measure block compression of the dominant string data before choosing a format.
            long raw=0,compressed=0;
            for(var artifact:artifacts){
                byte[] file=Files.readAllBytes(nativeRoot.resolve(artifact.name()+".seg"));raw+=file.length;
                var deflater=new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
                for(int offset=0;offset<file.length;offset+=64*1024){
                    deflater.reset();deflater.setInput(file,offset,Math.min(64*1024,file.length-offset));deflater.finish();
                    var out=new byte[64*1024+1024];while(!deflater.finished())compressed+=deflater.deflate(out);
                }
                deflater.end();
            }
            notes.add(String.format(Locale.ROOT,"64 KiB block compression (deflate, fastest) of native segments: %s → %s (%.1f B/symbol)",
                    mb(raw),mb(compressed),compressed/(double)symbols));
        }

        notes.addAll(ownerDistribution(artifacts));
        notes.addAll(exactLookupExperiments(artifacts,nativeRoot,workload));
        write("machine-storage.md","MACHINE storage comparison (Phase 11–12)",rows,notes);
    }

    private static int rocksRange(RocksArtifactRepository repository,String cacheKey,String owner,String prefix)throws Exception{
        var accumulator=new AlgebraicAccumulator("semantic-member-range-v1");String cursor=null;
        do{
            var page=repository.ownerMembers(cacheKey,owner,prefix,256,cursor);
            for(var symbol:page.symbols())accumulator.add(symbol.resolution().symbolKey(),symbol.resolution().identity());
            cursor=page.cursor();
        }while(cursor!=null);
        return accumulator.identity().hashCode();
    }
    private static Map<String,MachineSegment> openAll(List<Artifact> artifacts,Path directory,boolean verify)throws Exception{
        var result=new HashMap<String,MachineSegment>();
        for(var artifact:artifacts)result.put(artifact.name(),MachineSegment.open(directory.resolve(artifact.name()+".seg"),verify));
        return result;
    }

    /** §50: owner/prefix range sizes decide whether large-owner checkpoints could pay for themselves. */
    private static List<String> ownerDistribution(List<Artifact> artifacts){
        var sizes=new ArrayList<Integer>();
        for(var artifact:artifacts){
            var counts=new HashMap<Integer,Integer>();
            for(var symbol:artifact.facts().symbols())if(symbol.ownerId()>=0)counts.merge(symbol.ownerId(),1,Integer::sum);
            sizes.addAll(counts.values());
        }
        sizes.sort(Comparator.naturalOrder());
        if(sizes.isEmpty())return List.of();
        return List.of(String.format(Locale.ROOT,"owner member counts: p50=%d p95=%d p99=%d max=%d (owners=%d, ≥512 members: %d)",
                sizes.get(sizes.size()/2),sizes.get((int)(sizes.size()*.95)),sizes.get((int)(sizes.size()*.99)),sizes.getLast(),
                sizes.size(),sizes.stream().filter(value->value>=512).count()));
    }

    /** §54: exact lookup alternatives over the same key column. Extra bytes per key, hot latency. */
    private static List<String> exactLookupExperiments(List<Artifact> artifacts,Path directory,Workload workload)throws Exception{
        var notes=new ArrayList<String>();
        var segments=openAll(artifacts,directory,false);
        try{
            var queries=workload.exact();
            var hash=new HashMap<String,Map<String,Integer>>();var fanout=new HashMap<String,int[]>();var eytzinger=new HashMap<String,int[]>();
            long keys=0;
            for(var artifact:artifacts){
                var segment=segments.get(artifact.name());int n=segment.symbolCount();keys+=n;
                var map=new HashMap<String,Integer>(n*2);for(int id=0;id<n;id++)map.put(segment.key(id),id);hash.put(artifact.name(),map);
                var table=new int[257];for(int id=0;id<n;id++){String key=segment.key(id);table[(key.isEmpty()?0:Math.min(255,key.charAt(0)))+1]++;}
                for(int i=0;i<256;i++)table[i+1]+=table[i];fanout.put(artifact.name(),table);
                var layout=new int[n+1];eytzinger(layout,new int[]{0},1,n);eytzinger.put(artifact.name(),layout);
            }
            notes.add(String.format(Locale.ROOT,"exact lookup, binary search (baseline, 0 B/key): %s µs",
                    latency(time(queries.size(),indexer(i->{var q=queries.get(i);return segments.get(q[0]).exact(q[1]);})))));
            notes.add(String.format(Locale.ROOT,"exact lookup, first-char fanout (%.2f B/key): %s µs",257*4.0*artifacts.size()/keys,
                    latency(time(queries.size(),indexer(i->{var q=queries.get(i);var segment=segments.get(q[0]);var table=fanout.get(q[0]);
                        int bucket=q[1].isEmpty()?0:Math.min(255,q[1].charAt(0));int low=table[bucket],high=table[bucket+1]-1;
                        while(low<=high){int middle=(low+high)>>>1;int c=segment.key(middle).compareTo(q[1]);if(c<0)low=middle+1;else if(c>0)high=middle-1;else return middle;}
                        return -1;})))));
            notes.add(String.format(Locale.ROOT,"exact lookup, Eytzinger layout (4.00 B/key): %s µs",
                    latency(time(queries.size(),indexer(i->{var q=queries.get(i);var segment=segments.get(q[0]);var layout=eytzinger.get(q[0]);
                        int k=1,n=segment.symbolCount();while(k<=n){int c=segment.key(layout[k]).compareTo(q[1]);if(c==0)return layout[k];k=2*k+(c<0?1:0);}
                        return -1;})))));
            notes.add(String.format(Locale.ROOT,"exact lookup, in-heap hash table as MPH proxy (~%d B/key on heap): %s µs",48,
                    latency(time(queries.size(),indexer(i->{var q=queries.get(i);return hash.get(q[0]).getOrDefault(q[1],-1);})))));
        }finally{segments.values().forEach(MachineSegment::close);}
        return notes;
    }
    private static void eytzinger(int[] layout,int[] next,int k,int n){
        if(k<=n){eytzinger(layout,next,2*k,n);layout[k]=next[0]++;eytzinger(layout,next,2*k+1,n);}
    }

    @FunctionalInterface interface IndexedBody{int apply(int index);}
    private static IntSupplier indexer(IndexedBody body){var index=new int[]{0};return ()->body.apply(index[0]++);}
    static long bytes(Path directory)throws Exception{
        try(var files=Files.walk(directory)){return files.filter(Files::isRegularFile).mapToLong(path->{try{return Files.size(path);}catch(Exception e){return 0;}}).sum();}
    }
    static String mb(long bytes){return String.format(Locale.ROOT,"%.2f MB",bytes/1024.0/1024.0);}
    static String ms(long nanos){return String.format(Locale.ROOT,"%.1f ms",nanos/1e6);}
    static String perSecond(long symbols,long nanos){return String.format(Locale.ROOT,"%,.0f symbols/s",symbols/(nanos/1e9));}

    static void write(String file,String title,Map<String,Map<String,String>> columns,List<String> notes)throws Exception{
        var metrics=new LinkedHashSet<String>();columns.values().forEach(values->metrics.addAll(values.keySet()));
        var out=new StringBuilder("## ").append(title).append("\n\n| Metric |");
        columns.keySet().forEach(name->out.append(' ').append(name).append(" |"));
        out.append("\n|---|").append("---:|".repeat(columns.size())).append('\n');
        for(String metric:metrics){
            out.append("| ").append(metric).append(" |");
            for(var values:columns.values())out.append(' ').append(values.getOrDefault(metric,"")).append(" |");
            out.append('\n');
        }
        out.append("\nLatencies are p50 / p95 in µs.\n\n");
        for(String note:notes)out.append("- ").append(note).append('\n');
        System.out.println(out);
        Path target=Path.of("target/benchmarks");Files.createDirectories(target);Files.writeString(target.resolve(file),out);
    }
}
