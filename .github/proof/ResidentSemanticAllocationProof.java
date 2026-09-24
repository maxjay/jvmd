package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ResidentSemanticAllocationProof {
    private static final int MEMBERS=1000;
    private static final List<String> SCENARIOS=List.of(
            "cold_first_completion","warm_unchanged_completion","prefix_narrowing",
            "body_only_receiver_edit","documentation_only_edit","api_addition","api_removal","api_rename",
            "a_b_a","multi_module_a_b_a","unsaved_source_open_close",
            "deep_inheritance","wide_inheritance","inaccessible_beyond_limit",
            "overridden_beyond_limit","unqualified_type_merge");

    @FunctionalInterface interface Action { Map<String,Object> run() throws Exception; }
    private record Scenario(AutoCloseable resource,Action action) implements AutoCloseable {
        @Override public void close() throws Exception { resource.close(); }
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("usage: <measure|profile> <scenario> <output>");
        String mode=args[0],scenario=args[1];Path output=Path.of(args[2]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        var result=new LinkedHashMap<String,Object>();
        result.put("scenario",scenario);result.put("mode",mode);result.put("subject_sha",System.getProperty("jvmd.proof.subject",""));
        try{
            if(!SCENARIOS.contains(scenario))throw new IllegalArgumentException("Unknown scenario "+scenario);
            if(mode.equals("measure"))result.putAll(measure(scenario));
            else if(mode.equals("profile"))result.putAll(profile(scenario,output.resolveSibling(output.getFileName().toString().replace(".json",".jfr"))));
            else throw new IllegalArgumentException("Unknown mode "+mode);
        }catch(Throwable failure){
            result.put("error",failure.getClass().getName()+": "+Objects.toString(failure.getMessage(),""));
            var trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));result.put("stack",trace.toString());
        }
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result);
    }

    private static Map<String,Object> measure(String name)throws Exception{
        try(var scenario=scenario(name)){
            settleGc();long heapBefore=heapUsed(),rssBefore=rssBytes(),threadBefore=threadAllocated(),allBefore=allThreadsAllocated();
            var sampler=new PeakSampler();sampler.start();long started=System.nanoTime();
            Map<String,Object> outcome=scenario.action().run();
            long elapsed=System.nanoTime()-started,threadAfter=threadAllocated(),allAfter=allThreadsAllocated();
            sampler.close();settleGc();long retained=heapUsed(),rssAfter=rssBytes();
            var result=new LinkedHashMap<String,Object>();
            result.put("latency_ms",elapsed/1_000_000.0);
            result.put("thread_allocated_bytes",Math.max(0,threadAfter-threadBefore));
            result.put("all_threads_allocated_bytes",Math.max(0,allAfter-allBefore));
            result.put("heap_before_bytes",heapBefore);
            result.put("retained_heap_after_bytes",retained);
            result.put("retained_heap_delta_bytes",retained-heapBefore);
            result.put("peak_heap_bytes",sampler.peakHeap);
            result.put("rss_before_bytes",rssBefore);result.put("rss_after_bytes",rssAfter);result.put("peak_rss_bytes",sampler.peakRss);
            result.put("outcome",outcome);return result;
        }
    }

    private static Map<String,Object> profile(String name,Path jfr)throws Exception{
        try(var scenario=scenario(name);var recording=new Recording()){
            recording.setName("resident-semantic-"+name);
            recording.enable("jdk.ObjectAllocationSample").with("throttle","1000/s").withStackTrace();
            settleGc();recording.start();long started=System.nanoTime();
            Map<String,Object> outcome=scenario.action().run();
            long elapsed=System.nanoTime()-started;recording.stop();recording.dump(jfr);
            var weights=new HashMap<String,Long>();long samples=0,totalWeight=0;
            try(var events=new RecordingFile(jfr)){
                while(events.hasMoreEvents()){
                    RecordedEvent event=events.readEvent();
                    if(!event.getEventType().getName().equals("jdk.ObjectAllocationSample"))continue;
                    RecordedClass type=event.getClass("objectClass");long weight=event.getLong("weight");
                    String key=type==null?"<unknown>":type.getName();
                    weights.merge(key,weight,Long::sum);samples++;totalWeight+=weight;
                }
            }
            var top=weights.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(25)
                    .map(e->Map.<String,Object>of("class",e.getKey(),"sampled_weight_bytes",e.getValue())).toList();
            return Map.of("profile_latency_ms",elapsed/1_000_000.0,"allocation_samples",samples,
                    "sampled_weight_bytes",totalWeight,"top_allocations",top,"jfr",jfr.getFileName().toString(),"outcome",outcome);
        }
    }

    private static Scenario scenario(String name)throws Exception{
        return switch(name){
            case "cold_first_completion" -> completionScenario(false,"member",null);
            case "warm_unchanged_completion" -> completionScenario(true,"member",null);
            case "prefix_narrowing" -> prefixNarrowing();
            case "body_only_receiver_edit" -> mutationScenario("body");
            case "documentation_only_edit" -> mutationScenario("doc");
            case "api_addition" -> mutationScenario("add");
            case "api_removal" -> mutationScenario("remove");
            case "api_rename" -> mutationScenario("rename");
            case "a_b_a" -> abaScenario();
            case "multi_module_a_b_a" -> multiModuleScenario();
            case "unsaved_source_open_close" -> unsavedScenario();
            case "deep_inheritance" -> hierarchyScenario(true);
            case "wide_inheritance" -> hierarchyScenario(false);
            case "inaccessible_beyond_limit" -> inaccessibleScenario();
            case "overridden_beyond_limit" -> overriddenScenario();
            case "unqualified_type_merge" -> unqualifiedTypeMergeScenario();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static Scenario completionScenario(boolean warm,String prefix,String variant)throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-completion-"),file=root.resolve("Use.java");
        String text=fixture(MEMBERS,variant);Files.writeString(file,text);
        var analyzer=new Analyzer();analyzer.configure(context(root,"proof"),null,512L*1024*1024);
        String needle="api."+prefix;if(warm)complete(analyzer,file,text,needle,100);
        return new Scenario(analyzer,()->completionOutcome(analyzer,file,text,needle,100));
    }

    private static Scenario prefixNarrowing()throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-prefix-"),file=root.resolve("Use.java");
        String broad=fixture(MEMBERS,null),narrow=broad.replace("api.member;","api.member09;");
        Files.writeString(file,broad);var analyzer=new Analyzer();analyzer.configure(context(root,"proof"),null,512L*1024*1024);
        complete(analyzer,file,broad,"api.member",100);
        return new Scenario(analyzer,()->completionOutcome(analyzer,file,narrow,"api.member09",100));
    }

    private static Scenario mutationScenario(String kind)throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-mutation-"),file=root.resolve("Use.java");
        String before=fixture(MEMBERS,null),after=fixture(MEMBERS,kind);Files.writeString(file,before);
        var analyzer=new Analyzer();analyzer.configure(context(root,"proof"),null,512L*1024*1024);
        complete(analyzer,file,before,"api.member05",100);
        @SuppressWarnings("unchecked") var prior=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
        long mutations=((Number)prior.get("semantic_fact_mutations")).longValue();
        return new Scenario(analyzer,()->{
            Files.writeString(file,after);analyzer.changed(file);
            var result=completionOutcome(analyzer,file,after,kind.equals("rename")?"api.member05":"api.member05",100);
            @SuppressWarnings("unchecked") var state=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            result.put("fact_mutations_delta",((Number)state.get("semantic_fact_mutations")).longValue()-mutations);
            result.put("semantic_facts",state.get("semantic_facts"));result.put("semantic_root",state.get("semantic_root"));
            return result;
        });
    }

    private static Scenario abaScenario()throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-aba-"),file=root.resolve("Use.java");
        String a=fixture(MEMBERS,null),b=fixture(MEMBERS,"api_change");Files.writeString(file,a);
        var analyzer=new Analyzer();analyzer.configure(context(root,"proof"),null,512L*1024*1024);
        complete(analyzer,file,a,"api.member05",100);
        @SuppressWarnings("unchecked") var first=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
        String rootA=first.get("semantic_root").toString();long epochA=((Number)first.get("semantic_epoch")).longValue();
        return new Scenario(analyzer,()->{
            Files.writeString(file,b);analyzer.changed(file);complete(analyzer,file,b,"api.member05",100);
            Files.writeString(file,a);analyzer.changed(file);complete(analyzer,file,a,"api.member05",100);
            @SuppressWarnings("unchecked") var last=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            return new LinkedHashMap<>(Map.of("root_restored",rootA.equals(last.get("semantic_root")),
                    "epoch_advanced",((Number)last.get("semantic_epoch")).longValue()>epochA,
                    "semantic_facts",last.get("semantic_facts"),"semantic_root",last.get("semantic_root")));
        });
    }

    private static Scenario multiModuleScenario()throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-modules-"),a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b"));
        Path fa=a.resolve("UseA.java"),fb=b.resolve("UseB.java");
        String ta="class ApiA { int alpha(){return 1;} } class UseA { Object f(ApiA api){ return api.al; } }";
        String tb="class ApiB { int beta(){return 2;} } class UseB { Object f(ApiB api){ return api.be; } }";
        Files.writeString(fa,ta);Files.writeString(fb,tb);
        var analyzer=new Analyzer();analyzer.configure(context(a,"module-a"),null,512L*1024*1024);
        complete(analyzer,fa,ta,"api.al",100);
        @SuppressWarnings("unchecked") var initial=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
        String rootA=initial.get("semantic_root").toString();long factsA=((Number)initial.get("semantic_facts")).longValue();
        return new Scenario(analyzer,()->{
            analyzer.configure(context(b,"module-b"),null,512L*1024*1024);complete(analyzer,fb,tb,"api.be",100);
            analyzer.configure(context(a,"module-a"),null,512L*1024*1024);
            @SuppressWarnings("unchecked") var restored=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            boolean rootPreserved=rootA.equals(restored.get("semantic_root"));
            boolean factsPreserved=((Number)restored.get("semantic_facts")).longValue()==factsA;
            complete(analyzer,fa,ta,"api.al",100);
            return new LinkedHashMap<>(Map.of("root_preserved_before_query",rootPreserved,"facts_preserved_before_query",factsPreserved,
                    "facts_before",factsA,"facts_restored",restored.get("semantic_facts")));
        });
    }

    private static Scenario unsavedScenario()throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-unsaved-"),use=root.resolve("Use.java"),created=root.resolve("Created.java");
        Files.writeString(use,"class Stable { int stable(){return 1;} } class Use { int f(){ return Created.answer(); } }");
        var app=new Application(TestSupport.config(root,Duration.ofHours(4)));String session=TestSupport.open(app,root);
        diagnostics(app,session,use);long beforeFacts=residentFacts(app,session);
        return new Scenario(app,()->{
            request(app,session,"document.open",Map.of("path",created.toString(),"version",1,"text","class Created { static int answer(){return 42;} }"));
            long afterOpen=residentFacts(app,session);int openErrors=diagnostics(app,session,use);
            request(app,session,"document.close",Map.of("path",created.toString()));
            long afterClose=residentFacts(app,session);int closeErrors=diagnostics(app,session,use);
            return new LinkedHashMap<>(Map.of("resident_facts_before",beforeFacts,"resident_facts_after_open",afterOpen,
                    "resident_facts_after_close",afterClose,"open_errors",openErrors,"close_errors",closeErrors,
                    "unrelated_state_survived_open",afterOpen>=beforeFacts));
        });
    }

    private static Scenario hierarchyScenario(boolean deep)throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-hierarchy-"),file=root.resolve("Use.java");String text=deep?deepSource():wideSource();
        Files.writeString(file,text);var analyzer=new Analyzer();analyzer.configure(context(root,"proof"),null,512L*1024*1024);
        String needle=deep?"value.deep":"value.wide";
        return new Scenario(analyzer,()->completionOutcome(analyzer,file,text,needle,100));
    }

    private static Scenario inaccessibleScenario()throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-inaccessible-"),file=root.resolve("Use.java");var s=new StringBuilder("class Api {\n");
        for(int i=0;i<180;i++)s.append("private int a").append(String.format("%03d",i)).append("(){return ").append(i).append(";}\n");
        for(int i=0;i<140;i++)s.append("public int az").append(String.format("%03d",i)).append("(){return ").append(i).append(";}\n");
        s.append("} class Use { Object f(Api api){ return api.a; } }");String text=s.toString();Files.writeString(file,text);
        var analyzer=new Analyzer();analyzer.configure(context(root,"proof"),null,512L*1024*1024);
        return new Scenario(analyzer,()->{
            var out=completionOutcome(analyzer,file,text,"api.a",100);out.put("correct_accepted_count",Objects.equals(out.get("items"),100));return out;
        });
    }

    private static Scenario overriddenScenario()throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-overridden-"),file=root.resolve("Use.java");var s=new StringBuilder("class Base {\n");
        for(int i=0;i<160;i++)s.append("int a").append(String.format("%03d",i)).append("(){return ").append(i).append(";}\n");
        for(int i=0;i<140;i++)s.append("static int az").append(String.format("%03d",i)).append("(){return ").append(i).append(";}\n");
        s.append("}\nclass Sub extends Base {\n");
        for(int i=0;i<160;i++)s.append("@Override int a").append(String.format("%03d",i)).append("(){return ").append(i+1).append(";}\n");
        s.append("}\nclass Use { Object f(){ return Sub.a; } }");String text=s.toString();Files.writeString(file,text);
        var analyzer=new Analyzer();analyzer.configure(context(root,"proof"),null,512L*1024*1024);
        return new Scenario(analyzer,()->{
            var out=completionOutcome(analyzer,file,text,"Sub.a",100);out.put("correct_accepted_count",Objects.equals(out.get("items"),100));return out;
        });
    }

    private static Scenario unqualifiedTypeMergeScenario()throws Exception{
        Path root=Files.createTempDirectory("jvmd-proof-type-merge-");
        var config=TestSupport.config(root,Duration.ofHours(4));installDependency(root,config.m2Repo());
        Path project=MavenFixtures.project(root.resolve("project"),"<properties><maven.compiler.release>25</maven.compiler.release></properties><dependencies>"+
                MavenFixtures.dependency("library","1")+"</dependencies>");
        Path dir=Files.createDirectories(project.resolve("src/main/java/app")),file=dir.resolve("Use.java");
        String source="package app; class Use { Samp value; }";Files.writeString(file,source);
        var app=new Application(config);String session=TestSupport.open(app,project);
        request(app,session,"document.open",Map.of("path",file.toString(),"version",1,"text",source));
        return new Scenario(app,()->{
            int offset=source.indexOf("Samp")+4;var pos=Documents.position(source,offset);
            JsonNode response=request(app,session,"symbol.completion",Map.of("path",file.toString(),"line",pos.line(),"character",pos.character(),"limit",100));
            JsonNode items=response.path("result").path("result").path("items");
            boolean sample=false;for(var item:items)if(item.path("name").asText().equals("Sample"))sample=true;
            return new LinkedHashMap<>(Map.of("items",items.size(),"contains_indexed_sample",sample));
        });
    }

    private static Analyzer.Context context(Path root,String generation){
        return new Analyzer.Context("proof:"+generation+":1","25",List.of(),List.of(root),generation,Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> completionOutcome(Analyzer analyzer,Path file,String text,String needle,int limit)throws Exception{
        var envelope=complete(analyzer,file,text,needle,limit);var result=(Map<String,Object>)envelope.result();
        var items=(List<Map<String,Object>>)result.getOrDefault("items",List.of());
        var out=new LinkedHashMap<String,Object>();out.put("items",items.size());out.put("truncated",envelope.truncated());
        var state=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
        for(String key:List.of("semantic_facts","semantic_tree_entries","semantic_fact_mutations","semantic_tree_range_entries_read","semantic_stale_units","semantic_root"))
            if(state.containsKey(key))out.put(key,state.get(key));
        return out;
    }

    private static Envelope complete(Analyzer analyzer,Path file,String text,String needle,int limit)throws Exception{
        int cursor=text.indexOf(needle)+needle.length();var p=Documents.position(text,cursor);
        return analyzer.completion(file,text,p.line(),p.character(),limit,0);
    }

    private static String fixture(int count,String variant){
        var s=new StringBuilder("class Api {\n");
        for(int i=0;i<count;i++){
            if("remove".equals(variant)&&i==500)continue;
            String name=("rename".equals(variant)&&i==500)?"renamed0500":"member"+String.format("%04d",i);
            String doc=("doc".equals(variant)&&i==500)?"changed documentation "+i:"documentation "+i;
            String type=("api_change".equals(variant)&&i==500)?"long":"int";
            String body=("body".equals(variant)&&i==500)?"999999":Integer.toString(i);
            s.append("/** ").append(doc).append(" */ public ").append(type).append(" ").append(name).append("(){return ").append(body);
            if(type.equals("long"))s.append("L");s.append(";}\n");
        }
        if("add".equals(variant))s.append("/** added */ public int member0500x(){return 7;}\n");
        s.append("}\nclass Use { Object f(Api api){ return api.");
        s.append(variant==null?"member":("rename".equals(variant)?"member05":"member05")).append("; } }");
        return s.toString();
    }

    private static String deepSource(){
        var s=new StringBuilder("class D0 { public int deep000(){return 0;} }\n");
        for(int i=1;i<=50;i++)s.append("class D").append(i).append(" extends D").append(i-1).append(" { public int deep")
                .append(String.format("%03d",i)).append("(){return ").append(i).append(";} }\n");
        return s.append("class Use { Object f(D50 value){ return value.deep; } }").toString();
    }

    private static String wideSource(){
        var s=new StringBuilder();
        for(int i=0;i<50;i++){s.append("interface I").append(i).append(" {");
            for(int j=0;j<4;j++)s.append(" int wide").append(String.format("%02d%02d",i,j)).append("();");
            s.append(" }\n");}
        s.append("abstract class Wide implements ");for(int i=0;i<50;i++){if(i>0)s.append(",");s.append("I").append(i);}s.append(" {}\n");
        return s.append("class Use { Object f(Wide value){ return value.wide; } }").toString();
    }

    private static JsonNode request(Application app,String session,String method,Map<String,Object> values){
        var params=new LinkedHashMap<>(values);params.put("session",session);return TestSupport.request(app.dispatcher(),method,params);
    }

    private static int diagnostics(Application app,String session,Path file){
        JsonNode response=request(app,session,"diag.get",Map.of("paths",List.of(file.toString())));
        return response.path("result").path("result").path("diagnostics").size();
    }

    private static long residentFacts(Application app,String session){
        return request(app,session,"session.status",Map.of()).path("result").path("result").path("analyzer")
                .path("resident_semantic_state").path("semantic_facts").asLong();
    }

    private static void installDependency(Path root,Path repository)throws Exception{
        Path build=Files.createDirectories(root.resolve("dependency-build"));
        Path compiled=IndexFixtures.jar(build,"library","package lib; public class Sample { public String label(){ return \"sample\"; } }",true);
        Path directory=Files.createDirectories(repository.resolve("fixture/library/1"));
        Path jar=directory.resolve("library-1.jar"),pom=directory.resolve("library-1.pom");
        Files.copy(compiled,jar,StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(pom,MavenFixtures.pom("fixture","library","1",""));
        for(Path path:List.of(jar,pom)){
            byte[] digest=java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path));
            Files.writeString(path.resolveSibling(path.getFileName()+".sha1"),java.util.HexFormat.of().formatHex(digest),StandardCharsets.US_ASCII);
        }
    }

    private static long heapUsed(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    private static void settleGc()throws InterruptedException{
        for(int i=0;i<3;i++){System.gc();Thread.sleep(80);}
    }
    private static com.sun.management.ThreadMXBean threadBean(){
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(bean.isThreadAllocatedMemorySupported()&&!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }
    private static long threadAllocated(){long value=threadBean().getThreadAllocatedBytes(Thread.currentThread().threadId());return Math.max(0,value);}
    private static long allThreadsAllocated(){
        var bean=threadBean();long total=0;for(long id:bean.getAllThreadIds()){long value=bean.getThreadAllocatedBytes(id);if(value>0)total+=value;}return total;
    }
    private static long rssBytes(){
        try{
            for(String line:Files.readAllLines(Path.of("/proc/self/status")))if(line.startsWith("VmRSS:")){
                String[] parts=line.trim().split("\\s+");return Long.parseLong(parts[1])*1024L;
            }
        }catch(Exception ignored){}return -1;
    }

    private static final class PeakSampler implements AutoCloseable {
        final AtomicBoolean running=new AtomicBoolean(true);volatile long peakHeap=heapUsed(),peakRss=rssBytes();Thread thread;
        void start(){
            thread=new Thread(()->{while(running.get()){peakHeap=Math.max(peakHeap,heapUsed());peakRss=Math.max(peakRss,rssBytes());try{Thread.sleep(1);}catch(InterruptedException ignored){}}},"proof-peak-sampler");
            thread.setDaemon(true);thread.start();
        }
        @Override public void close()throws InterruptedException{running.set(false);thread.join();peakHeap=Math.max(peakHeap,heapUsed());peakRss=Math.max(peakRss,rssBytes());}
    }
}
