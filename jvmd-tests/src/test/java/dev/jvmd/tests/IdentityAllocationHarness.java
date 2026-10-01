package dev.jvmd.tests;

import com.sun.management.ThreadMXBean;
import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.index.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Identity cost harness for the XXH3 migration (docs/perf/identity-*.md). Not a gate: it prints a
 * table. Corpus: every jar under {@code -Djvmd.harness.repository} (default {@code ~/.m2/repository}).
 *
 * <pre>mvn -pl jvmd-tests -am verify -DexcludedGroups= -Dgroups=corpus -Dtest=IdentityAllocationHarness -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@Tag("corpus")
class IdentityAllocationHarness {
    private static final String[] NAMES={"Map","List","Builder","Factory","Context","Config","Node","Type","Value","Result",
            "Session","Request","Response","Logger","Plugin","Event","Handler","Parser","Reader","Writer","Util","Utils","Exception"};
    private static final ThreadMXBean THREADS=(ThreadMXBean)ManagementFactory.getThreadMXBean();
    @TempDir Path root;

    @Test void report()throws Exception{
        var out=new StringBuilder("## Corpus phases\n\n");
        Path repository=Path.of(System.getProperty("jvmd.harness.repository",System.getProperty("user.home")+"/.m2/repository"));
        List<Path> jars;
        try(var files=Files.walk(repository)){
            jars=files.filter(p->p.toString().endsWith(".jar")&&!p.getFileName().toString().endsWith("-sources.jar")
                    &&!p.getFileName().toString().endsWith("-javadoc.jar")).sorted().toList();
        }
        out.append("Corpus: ").append(jars.size()).append(" jars\n\n");
        out.append("| Phase | Wall ms | Allocated MB | Digest MB | Accumulator MB | Content-hash MB | Digest+acc share | Identity CPU share |\n");
        out.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        try(var index=new IndexService(root.resolve("index.db"),repository)){
            phase(out,"seed (index + workspace)",()->{
                for(Path jar:jars){
                    try{index.indexJar(jar,"harness:"+jar.getFileName()+":1","jar");}catch(Exception ignored){}
                }
                index.loadWorkspace("w",jars.stream().map(jar->new IndexService.WorkspaceArtifact(jar.toString(),"compile")).toList(),List.of());
                return null;
            });
            var view=SemanticReadViews.machine(index.store(),"w");
            var owners=new ArrayList<String>();
            for(String name:NAMES)
                for(var type:index.store().semanticTypesByName(name,"w",64,IndexStore.SemanticLayer.MACHINE))owners.add(type.id());
            out.insert(out.indexOf("|"),"Owners queried: "+owners.size()+"\n\n");
            Runnable queries=()->{
                try{
                    for(String owner:owners){
                        view.members(owner,"",256,null);
                        view.identity(QueryProof.Domain.MEMBER_RANGE,SemanticReadView.memberIdentityKey(owner,""));
                        view.identity(QueryProof.Domain.MEMBER_RANGE,SemanticReadView.memberIdentityKey(owner,"get"));
                        view.identity(QueryProof.Domain.HIERARCHY,owner);
                    }
                }catch(Exception failed){throw new IllegalStateException(failed);}
            };
            phase(out,"first-use references",()->{queries.run();return null;});
            phase(out,"warm request (x5)",()->{for(int i=0;i<5;i++)queries.run();return null;});
        }
        residentPhases(out);
        livePhases(out);
        out.append("\nTop identity allocation callers per phase:\n\n").append(sitesOut);
        out.append("\n## Per-operation cost (single thread, after warm-up)\n\n| Operation | bytes/op | ns/op |\n|---|---:|---:|\n");
        var deep=deepType();
        var fact=resolutionFact(deep);
        micro(out,"SemanticType.identity() (4-deep generic)",deep::identity);
        micro(out,"ResolutionFact construction (identity)",()->resolutionFact(deep));
        var key="java.util.Map#put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
        var zero=AlgebraicAccumulator.Value.ZERO;
        micro(out,"AlgebraicAccumulator contribution + plus",()->zero.plus(AlgebraicAccumulator.contribution("semantic-member-range-v1",key,fact.identity())));
        micro(out,"5-part digest (strings, long, identity, list)",()->CanonicalDigestWriter.digest("harness-v1","owner",key,42L,fact.identity(),List.of("a","b")));
        String report=out.toString();
        System.out.println(report);
        Files.writeString(Path.of("target","identity-harness.md"),report);
    }

    /** Synthetic workspace source semantics: 1500 units, one type with 40 members each. */
    private void residentPhases(StringBuilder out)throws Exception{
        int units=1500,members=40;var state=new ResidentSemanticState();
        phase(out,"resident seed (60k facts)",()->{for(int u=0;u<units;u++)state.admit(unit(u,members,0));return null;});
        Runnable queries=()->{
            for(int u=0;u<units;u+=3){
                String owner="p.T"+u+"#";
                state.memberRangeIdentity(owner,"get");state.overloadGroupIdentity(owner,"get3");state.hierarchyApi(owner);
            }
            state.identity();
        };
        phase(out,"resident first-use queries",()->{queries.run();return null;});
        phase(out,"resident warm edits (300 units) + queries",()->{
            for(int u=0;u<units;u+=5)state.admit(unit(u,members,1));
            queries.run();return null;
        });
    }
    private static SemanticSnapshot unit(int u,int members,int revision){
        String owner="p.T"+u+"#",file="/src/p/T"+u+".java";
        var facts=new LinkedHashMap<String,SemanticFact>();var descriptions=new LinkedHashMap<String,SymbolDescription>();
        var self=new SemanticType.Declared(owner,"p.T"+u,List.of());
        var parent=new SemanticType.Declared("p.T"+(u/2)+"#","p.T"+(u/2),List.of());
        facts.put(owner,new SemanticFact(owner,null,"T"+u,"class","class T"+u,null,Set.of("public"),file,"p","p.T"+u,"p.T"+u,
                self,List.of(),u==0?List.of():List.of(parent),List.of(),false,"api-"+owner,"ns-T"+u,"doc-"+owner));
        for(int m=0;m<members;m++){
            String name="get"+m,id=owner+name+"().";
            var type=new SemanticType.Executable(List.of(new SemanticType.Declared("java.util.List#","java.util.List",List.of(self))),
                    m==0&&revision>0?new SemanticType.Primitive("long"):new SemanticType.Primitive("int"),List.of());
            facts.put(id,new SemanticFact(id,owner,name,"method","int "+name+"()","()I",Set.of("public"),file,"p",
                    "p.T"+u+"/"+name+"()","p.T"+u,type,List.of(),List.of(),List.of(),false,"api-"+id+revision,"ns-"+name,"doc-"+id));
        }
        for(var fact:facts.values())descriptions.put(fact.id(),new SymbolDescription(fact.id(),"doc",fact.structuralSignature(),null,fact.documentationIdentity()));
        return new SemanticSnapshot("source:"+file,file,"content-"+u+"-"+revision,facts,descriptions,"file-api-"+u+revision,"file-ns-"+u,"",Set.of());
    }

    /** Live source-state tree: 20k files across 200 directories, then 2k edits. */
    private void livePhases(StringBuilder out)throws Exception{
        Path src=root.resolve("live/src");var tree=new dev.jvmd.core.LiveStateTree(List.of(src));
        phase(out,"live tree seed (20k files)",()->{
            for(int i=0;i<20_000;i++)tree.put(dev.jvmd.core.LiveStateTree.source(src.resolve("d"+(i%200)+"/F"+i+".java"),"c"+i,"a"+i,List.of("F"+i)));
            return null;
        });
        phase(out,"live tree warm edits (2k)",()->{
            for(int i=0;i<20_000;i+=10)tree.put(dev.jvmd.core.LiveStateTree.source(src.resolve("d"+(i%200)+"/F"+i+".java"),"c'"+i,"a"+i,List.of("F"+i)));
            return null;
        });
    }

    private static SemanticType deepType(){
        var string=new SemanticType.Declared("java.lang.String","java.lang.String",List.of());
        var list=new SemanticType.Declared("java.util.List","java.util.List",List.of(string));
        var map=new SemanticType.Declared("java.util.Map","java.util.Map",List.of(string,list));
        return new SemanticType.Declared("java.util.Optional","java.util.Optional",
                List.of(new SemanticType.Wildcard(map,null)));
    }
    private static ResolutionFact resolutionFact(SemanticType type){
        return new ResolutionFact("java.util.Map#get(Ljava/lang/Object;)Ljava/lang/Object;","java.util.Map","method","get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",Set.of("public","abstract"),"java.util",
                new SemanticType.Executable(List.of(type),type,List.of()),List.of(),List.of(),false);
    }

    private static void micro(StringBuilder out,String name,Supplier<?> operation){
        Object sink=null;
        for(int i=0;i<50_000;i++)sink=operation.get();
        int iterations=200_000;
        long bytes=THREADS.getCurrentThreadAllocatedBytes(),started=System.nanoTime();
        for(int i=0;i<iterations;i++)sink=operation.get();
        long nanos=System.nanoTime()-started;bytes=THREADS.getCurrentThreadAllocatedBytes()-bytes;
        out.append("| ").append(name).append(" | ").append(bytes/iterations).append(" | ").append(nanos/iterations).append(" |\n");
        if(sink==null)throw new AssertionError();
    }

    private void phase(StringBuilder out,String name,java.util.concurrent.Callable<?> work)throws Exception{
        System.gc();
        Path file=Files.createTempFile(root,"phase",".jfr");
        long allocated;long started;
        try(var recording=new Recording()){
            recording.enable("jdk.ObjectAllocationSample").with("throttle","20000/s");
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
            recording.start();
            allocated=THREADS.getTotalThreadAllocatedBytes();started=System.nanoTime();
            work.call();
            allocated=THREADS.getTotalThreadAllocatedBytes()-allocated;started=System.nanoTime()-started;
            recording.stop();recording.dump(file);
        }
        var weights=new EnumMap<Category,Long>(Category.class);long sampled=0;var sites=new HashMap<String,Long>();
        var cpu=new EnumMap<Category,Long>(Category.class);long cpuSamples=0;
        for(RecordedEvent event:RecordingFile.readAllEvents(file)){
            String type=event.getEventType().getName();
            if(type.equals("jdk.ObjectAllocationSample")){
                long weight=event.getLong("weight");sampled+=weight;var category=category(event);weights.merge(category,weight,Long::sum);
                if(category==Category.DIGEST||category==Category.ACCUMULATOR)sites.merge(site(event),weight,Long::sum);
            }else if(type.equals("jdk.ExecutionSample")){cpuSamples++;cpu.merge(category(event),1L,Long::sum);}
        }
        double scale=sampled==0?0:(double)allocated/sampled;
        long digest=Math.round(weights.getOrDefault(Category.DIGEST,0L)*scale),accumulator=Math.round(weights.getOrDefault(Category.ACCUMULATOR,0L)*scale);
        long content=Math.round(weights.getOrDefault(Category.CONTENT_HASH,0L)*scale);
        long identityCpu=cpu.getOrDefault(Category.DIGEST,0L)+cpu.getOrDefault(Category.ACCUMULATOR,0L);
        out.append(String.format(Locale.ROOT,"| %s | %d | %.1f | %.1f | %.1f | %.1f | %.1f%% | %.1f%% (%d samples) |%n",name,started/1_000_000,
                allocated/1e6,digest/1e6,accumulator/1e6,content/1e6,allocated==0?0:100.0*(digest+accumulator)/allocated,
                cpuSamples==0?0:100.0*identityCpu/cpuSamples,cpuSamples));
        var top=sites.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(4)
                .map(e->String.format(Locale.ROOT,"%s %.1f MB",e.getKey(),e.getValue()*scale/1e6)).toList();
        if(!top.isEmpty())sitesOut.append("- ").append(name).append(": ").append(String.join("; ",top)).append('\n');
    }
    private final StringBuilder sitesOut=new StringBuilder();

    /** Nearest caller outside the identity machinery and the JDK: who asked for the identity. */
    private static String site(RecordedEvent event){
        for(RecordedFrame frame:event.getStackTrace().getFrames()){
            String type=frame.getMethod().getType().getName();
            if(!type.startsWith("dev.jvmd.")||type.equals("dev.jvmd.core.CanonicalDigestWriter")||type.equals("dev.jvmd.core.Hash256")
                    ||type.startsWith("dev.jvmd.core.AlgebraicAccumulator")||type.startsWith("dev.jvmd.core.IdentityEncoder")
                    ||type.equals("dev.jvmd.core.Xxh3")||type.equals("dev.jvmd.core.Id128"))continue;
            return type.substring(type.lastIndexOf('.')+1)+"."+frame.getMethod().getName();
        }
        return "?";
    }

    enum Category { DIGEST, ACCUMULATOR, CONTENT_HASH, OTHER }

    /** First identity-machinery frame from the allocation/execution site outward decides the category. */
    private static Category category(RecordedEvent event){
        var stack=event.getStackTrace();
        if(stack==null)return Category.OTHER;
        for(RecordedFrame frame:stack.getFrames()){
            if(!frame.isJavaFrame())continue;
            String type=frame.getMethod().getType().getName(),method=frame.getMethod().getName();
            // Accumulators and treap priorities (the BigInteger users before P3).
            if(type.startsWith("dev.jvmd.core.AlgebraicAccumulator")||type.equals("dev.jvmd.core.Hash256")&&method.equals("unsignedInteger")
                    ||type.endsWith(".ResidentSemanticState")&&method.equals("point")||type.endsWith(".ClasspathSequence")&&method.equals("item")
                    ||type.equals("dev.jvmd.core.LiveStateTree")&&Set.of("priority","contribution").contains(method))return Category.ACCUMULATOR;
            if(type.equals("dev.jvmd.core.Hashing"))return Category.CONTENT_HASH;
            if(type.equals("dev.jvmd.core.CanonicalDigestWriter")||type.equals("dev.jvmd.core.Hash256")
                    ||type.equals("dev.jvmd.core.IdentityEncoder")||type.startsWith("dev.jvmd.core.IdentityEncoder$")
                    ||type.equals("dev.jvmd.core.Xxh3")||type.equals("dev.jvmd.core.Id128"))return Category.DIGEST;
            if(type.equals("dev.jvmd.core.LiveStateTree")&&Set.of("digest","write","fingerprint").contains(method))return Category.DIGEST;
            if(type.equals("dev.jvmd.core.CompilerInputs")&&Set.of("compose","write").contains(method))return Category.DIGEST;
        }
        return Category.OTHER;
    }
}
