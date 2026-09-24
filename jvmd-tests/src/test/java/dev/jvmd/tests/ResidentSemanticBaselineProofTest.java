package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * TEMPORARY PR proof for the resident-semantic-query cutover.
 * Delete this fixture and its measurement counters before merge.
 */
@Tag("phase-4")
class ResidentSemanticBaselineProofTest {
    @TempDir Path root;

    private static final List<String> COUNTERS=List.of(
            "queries","attribution_invocations",
            "completion_requests","completion_computations","completion_cache_hits",
            "completion_get_all_members_calls","completion_scope_traversals",
            "completion_candidates_seen","completion_rows_materialized",
            "completion_scip_computations","completion_signature_computations",
            "completion_as_member_of_calls","completion_doc_lookups",
            "completion_sort_count","completion_sort_input_size",
            "completion_rows_returned","completion_rows_discarded_after_limit",
            "completion_result_bytes");

    private Analyzer.Context context(){
        return new Analyzer.Context("fixture:resident-proof:1","25",List.of(),List.of(root),"resident-proof",Map.of());
    }

    private static String apiSource(int bodyRevision,boolean added){
        var source=new StringBuilder("package proof; class Api extends BaseApi<String> {\n");
        for(int i=0;i<1000;i++){
            source.append("/** member ").append(i).append(" documentation */ public int member")
                    .append(String.format("%04d",i)).append("(){return ").append(i+bodyRevision).append(";}\n");
        }
        if(added)source.append("/** added API */ public int member0900Added(){return 1;}\n");
        return source.append("}\n").toString();
    }

    private static String baseSource(){
        return """
                package proof;
                interface GenericApi<T> { T memberGeneric(); }
                class BaseApi<T> implements GenericApi<T> {
                    /** inherited generic documentation */
                    public T memberGeneric(){ return null; }
                    /** inherited member documentation */
                    public int memberInherited(){ return 1; }
                }
                """;
    }

    private static String caller(String prefix,int revision){
        return "package proof; class Use { Object call(Api api){ int local="+revision+"; return api."+prefix+"(); } }";
    }

    private static JsonNode complete(Analyzer analyzer,Path file,String text,String prefix)throws Exception{
        int cursor=text.indexOf("api."+prefix)+4+prefix.length();
        var answer=analyzer.completion(file,text,0,cursor,100,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        return Json.MAPPER.valueToTree(answer.result());
    }

    private static Map<String,Long> counters(Analyzer analyzer){
        var status=analyzer.status();var result=new LinkedHashMap<String,Long>();
        for(String key:COUNTERS)result.put(key,((Number)status.getOrDefault(key,0L)).longValue());
        return result;
    }

    private static Map<String,Long> delta(Map<String,Long> before,Map<String,Long> after){
        var result=new LinkedHashMap<String,Long>();
        for(String key:COUNTERS)result.put(key,after.get(key)-before.get(key));
        return result;
    }

    private static long allocatedBytes(){
        var bean=ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean allocated&&allocated.isThreadAllocatedMemorySupported()){
            if(!allocated.isThreadAllocatedMemoryEnabled())allocated.setThreadAllocatedMemoryEnabled(true);
            return allocated.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private static long peakHeapBytes(){
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool->pool.getType()==java.lang.management.MemoryType.HEAP)
                .mapToLong(pool->pool.getPeakUsage().getUsed()).sum();
    }

    private static long linuxMetric(String name){
        Path status=Path.of("/proc/self/status");if(!Files.isRegularFile(status))return -1;
        try{
            for(String line:Files.readAllLines(status))if(line.startsWith(name+":")){
                var parts=line.trim().split("\\s+");
                if(parts.length>=2)return Long.parseLong(parts[1])*1024L;
            }
        }catch(Exception ignored){}
        return -1;
    }

    private static Map<String,Object> measure(String name,Analyzer analyzer,Checked completion)throws Exception{
        var before=counters(analyzer);long allocatedBefore=allocatedBytes(),started=System.nanoTime();
        JsonNode result=completion.run();
        long elapsed=System.nanoTime()-started,allocatedAfter=allocatedBytes();var after=counters(analyzer);
        return new LinkedHashMap<>(Map.of(
                "scenario",name,
                "latency_ns",elapsed,
                "thread_allocation_bytes",allocatedBefore<0||allocatedAfter<0?-1:allocatedAfter-allocatedBefore,
                "response_items",result.path("items").size(),
                "serialized_result_bytes",Json.MAPPER.writeValueAsBytes(result).length,
                "counters",delta(before,after)));
    }

    @FunctionalInterface interface Checked {JsonNode run()throws Exception;}

    @Test void recordsDeterministicCompletionBaseline()throws Exception{
        Path pkg=Files.createDirectories(root.resolve("proof"));
        Path base=Files.writeString(pkg.resolve("BaseApi.java"),baseSource());
        Path api=Files.writeString(pkg.resolve("Api.java"),apiSource(0,false));
        Path other=Files.writeString(pkg.resolve("Other.java"),"package proof; class Other { int value(){return 1;} }");
        String source=caller("member",1);Path use=Files.writeString(pkg.resolve("Use.java"),source);
        var documents=new Documents();documents.open(use,source,1);

        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            var report=new LinkedHashMap<String,Object>();
            report.put("fixture_declared_members",1000);
            report.put("baseline_main","3867271bf083c6c5b31be7b622fe4ef1f8a6e021");
            var scenarios=new ArrayList<Map<String,Object>>();

            scenarios.add(measure("initial_large_member_completion",analyzer,()->complete(analyzer,use,source,"member")));
            scenarios.add(measure("warm_unchanged_repeat",analyzer,()->complete(analyzer,use,source,"member")));

            String narrowed=caller("member0",2);
            documents.change(use,2,List.of(new Documents.Change(null,narrowed)));analyzer.changed(use,documents.hash(use));analyzer.documents(documents);
            scenarios.add(measure("prefix_narrowing_member0",analyzer,()->complete(analyzer,use,narrowed,"member0")));

            String narrower=caller("member09",3);
            documents.change(use,3,List.of(new Documents.Change(null,narrower)));analyzer.changed(use,documents.hash(use));analyzer.documents(documents);
            scenarios.add(measure("prefix_narrowing_member09",analyzer,()->complete(analyzer,use,narrower,"member09")));

            Files.writeString(other,"package proof; class Other { int value(){int x=2; return x;} }");
            scenarios.add(measure("unrelated_body_edit",analyzer,()->complete(analyzer,use,narrower,"member09")));

            Files.writeString(api,apiSource(1,false));
            scenarios.add(measure("receiver_body_only_edit",analyzer,()->complete(analyzer,use,narrower,"member09")));

            Files.writeString(api,apiSource(1,true));
            scenarios.add(measure("relevant_api_addition",analyzer,()->{
                var result=complete(analyzer,use,narrower,"member09");
                assertThat(result.path("items").findValuesAsText("name")).contains("member0900Added");
                return result;
            }));

            String unsaved=caller("member09",99);
            documents.change(use,4,List.of(new Documents.Change(null,unsaved)));analyzer.changed(use,documents.hash(use));analyzer.documents(documents);
            scenarios.add(measure("unsaved_caller_edit",analyzer,()->complete(analyzer,use,unsaved,"member09")));

            report.put("scenarios",scenarios);
            report.put("peak_heap_bytes",peakHeapBytes());
            report.put("peak_rss_bytes",linuxMetric("VmHWM"));
            report.put("rss_bytes",linuxMetric("VmRSS"));
            report.put("final_counters",counters(analyzer));
            System.out.println("RESIDENT_SEMANTIC_BASELINE="+Json.MAPPER.writeValueAsString(report));

            @SuppressWarnings("unchecked")
            var first=(Map<String,Object>)scenarios.getFirst().get("counters");
            assertThat(((Number)first.get("completion_get_all_members_calls")).longValue()).isPositive();
            assertThat(((Number)first.get("completion_candidates_seen")).longValue()).isGreaterThan(1000);
            assertThat(((Number)first.get("completion_doc_lookups")).longValue()).isGreaterThanOrEqualTo(1000);
            assertThat(base).exists(); // keep the hierarchy source explicitly part of the fixture
        }
    }
}
