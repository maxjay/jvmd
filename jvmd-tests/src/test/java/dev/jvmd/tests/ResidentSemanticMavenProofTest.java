package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * TEMPORARY PR proof against the pinned Apache Maven fixture.
 * It intentionally records current behavior even when completion correctness is incomplete.
 */
@Tag("phase-4")
@EnabledIfEnvironmentVariable(named="JVMD_RESIDENT_PROOF_MAVEN_ROOT",matches=".+")
class ResidentSemanticMavenProofTest {
    @TempDir Path state;

    private static final List<String> COUNTERS=List.of(
            "queries","attribution_invocations",
            "completion_requests","completion_computations","completion_cache_hits",
            "completion_get_all_members_calls","completion_scope_traversals",
            "completion_candidates_seen","completion_rows_materialized",
            "completion_scip_computations","completion_signature_computations",
            "completion_as_member_of_calls","completion_doc_lookups",
            "completion_sort_count","completion_sort_input_size",
            "completion_rows_returned","completion_rows_discarded_after_limit",
            "completion_result_bytes","resident_semantic_fact_mutations","resident_semantic_tree_range_entries_read");

    private Config config(){
        return new Config(Path.of(System.getProperty("java.home")),null,
                Path.of(System.getProperty("user.home"),".m2","repository"),3,
                Duration.ofHours(4),1024,false,state,state.resolve("daemon.sock"));
    }

    private static JsonNode request(Application app,String method,Map<String,?> params)throws Exception{
        var response=TestSupport.complete(app.dispatcher(),method,params);
        assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
        return response;
    }

    private static Map<String,Long> analyzerCounters(Application app,String session)throws Exception{
        var status=TestSupport.complete(app.dispatcher(),"session.status",Map.of("session",session))
                .path("result").path("result").path("analyzer");
        var resident=status.path("resident_semantic_state");var result=new LinkedHashMap<String,Long>();
        for(String key:COUNTERS){
            if(key.equals("resident_semantic_fact_mutations"))result.put(key,resident.path("semantic_fact_mutations").asLong());
            else if(key.equals("resident_semantic_tree_range_entries_read"))result.put(key,resident.path("semantic_tree_range_entries_read").asLong());
            else result.put(key,status.path(key).asLong());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Long> budgetCounters(Application app){
        var status=(Map<String,Object>)app.dispatcher().status().get("response_budget");
        return Map.of(
                "activations",((Number)status.get("activations")).longValue(),
                "continuation_pages",((Number)status.get("continuation_pages")).longValue(),
                "resumes",((Number)status.get("resumes")).longValue());
    }

    private static Map<String,Long> delta(Map<String,Long> before,Map<String,Long> after){
        var result=new LinkedHashMap<String,Long>();
        after.forEach((key,value)->result.put(key,value-before.getOrDefault(key,0L)));return result;
    }

    private static long rssBytes(){
        Path status=Path.of("/proc/self/status");if(!Files.isRegularFile(status))return -1;
        try{for(String line:Files.readAllLines(status))if(line.startsWith("VmRSS:")){
            var parts=line.trim().split("\\s+");return Long.parseLong(parts[1])*1024L;
        }}catch(Exception ignored){}
        return -1;
    }

    private static long allocatedBytes(){
        var bean=ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean allocated&&allocated.isThreadAllocatedMemorySupported()){
            if(!allocated.isThreadAllocatedMemoryEnabled())allocated.setThreadAllocatedMemoryEnabled(true);
            return allocated.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private static Map<String,Object> measure(String scenario,Application app,String session,Checked action)throws Exception{
        var before=analyzerCounters(app,session);var budgetBefore=budgetCounters(app);
        long allocationBefore=allocatedBytes(),heapBefore=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),rssBefore=rssBytes(),started=System.nanoTime();
        JsonNode response=action.run();
        long elapsed=System.nanoTime()-started,rssAfter=rssBytes(),heapAfter=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),allocationAfter=allocatedBytes();
        var after=analyzerCounters(app,session);var budgetAfter=budgetCounters(app);
        var envelope=response.path("result");var result=envelope.path("result");
        var report=new LinkedHashMap<String,Object>();
        report.put("scenario",scenario);report.put("latency_ns",elapsed);
        report.put("thread_allocation_bytes",allocationBefore<0||allocationAfter<0?-1:allocationAfter-allocationBefore);
        report.put("heap_before_bytes",heapBefore);report.put("heap_after_bytes",heapAfter);
        report.put("rss_before_bytes",rssBefore);report.put("rss_after_bytes",rssAfter);
        report.put("serialized_response_bytes",Json.MAPPER.writeValueAsBytes(response).length);
        report.put("items",result.path("items").size());
        var warnings=envelope.path("warnings");report.put("warning_count",warnings.size());
        report.put("warnings_sample",java.util.stream.StreamSupport.stream(warnings.spliterator(),false).limit(3).map(JsonNode::asText).toList());
        report.put("counters",delta(before,after));report.put("response_budget",delta(budgetBefore,budgetAfter));
        return report;
    }

    @FunctionalInterface interface Checked {JsonNode run()throws Exception;}

    private static String insertMethod(String source,String body){
        int end=source.lastIndexOf('}');if(end<0)throw new IllegalArgumentException("No class closing brace");
        return source.substring(0,end)+"\n"+body+"\n"+source.substring(end);
    }

    private static String callerProbe(String source,String prefix){
        return insertMethod(source,"    private Object benchmarkCompletion(MavenProject project) { return project."+prefix+"; }");
    }

    private static int completionOffset(String text,String prefix){
        String needle="return project."+prefix;int at=text.lastIndexOf(needle);
        if(at<0)throw new IllegalArgumentException("Completion probe not found");
        return at+needle.length();
    }

    private static JsonNode complete(Application app,String session,Path caller,String text,String prefix)throws Exception{
        int offset=completionOffset(text,prefix);var pos=Documents.position(text,offset);
        return request(app,"symbol.completion",Map.of(
                "session",session,"path",caller.toString(),
                "line",pos.line(),"character",pos.character(),"limit",1000));
    }

    private static JsonNode lspComplete(Application app,String session,Path caller,String text,String prefix)throws Exception{
        int offset=completionOffset(text,prefix);var pos=Documents.position(text,offset);
        var nativeParams=Map.of(
                "textDocument",Map.of("uri",caller.toUri().toString()),
                "position",Map.of("line",pos.line(),"character",pos.character()),
                "context",Map.of("triggerKind",2,"triggerCharacter","."));
        return request(app,"lsp.request",Map.of(
                "session",session,"method","textDocument/completion","params",nativeParams,"client",Map.of()));
    }

    @Test void recordsApacheMavenCompletionBaseline()throws Exception{
        Path root=Path.of(System.getenv("JVMD_RESIDENT_PROOF_MAVEN_ROOT")).toAbsolutePath().normalize();
        Path receiver=root.resolve("impl/maven-core/src/main/java/org/apache/maven/project/MavenProject.java");
        Path caller=root.resolve("impl/maven-core/src/main/java/org/apache/maven/project/DefaultMavenProjectHelper.java");
        assertThat(receiver).exists();assertThat(caller).exists();

        try(var app=new Application(config())){
            String session=TestSupport.open(app,root);
            String callerOriginal=Files.readString(caller),receiverOriginal=Files.readString(receiver);
            String broad=callerProbe(callerOriginal,"");
            request(app,"document.open",Map.of("session",session,"path",caller.toString(),"version",1,"text",broad));

            var scenarios=new ArrayList<Map<String,Object>>();
            scenarios.add(measure("maven_project_first",app,session,()->complete(app,session,caller,broad,"")));
            scenarios.add(measure("maven_project_warm_repeat",app,session,()->complete(app,session,caller,broad,"")));

            String narrowed=callerProbe(callerOriginal,"get");
            request(app,"document.change",Map.of("session",session,"path",caller.toString(),"version",2,
                    "changes",List.of(Map.of("text",narrowed))));
            scenarios.add(measure("maven_project_prefix_get",app,session,()->complete(app,session,caller,narrowed,"get")));

            var budgetBeforeLsp=budgetCounters(app);long lspAllocatedBefore=allocatedBytes(),lspStarted=System.nanoTime();
            var lspResponse=lspComplete(app,session,caller,narrowed,"get");
            long lspElapsed=System.nanoTime()-lspStarted,lspAllocatedAfter=allocatedBytes();var budgetAfterLsp=budgetCounters(app);
            long lspActivations=budgetAfterLsp.get("activations")-budgetBeforeLsp.get("activations");
            assertThat(lspActivations).as("initial LSP completion must not require ResponseBudget paging").isZero();
            var lspProof=new LinkedHashMap<String,Object>();
            lspProof.put("latency_ns",lspElapsed);
            lspProof.put("thread_allocation_bytes",lspAllocatedBefore<0||lspAllocatedAfter<0?-1:lspAllocatedAfter-lspAllocatedBefore);
            lspProof.put("serialized_response_bytes",Json.MAPPER.writeValueAsBytes(lspResponse).length);
            lspProof.put("items",lspResponse.path("result").path("result").path("value").path("items").size());
            lspProof.put("response_budget",delta(budgetBeforeLsp,budgetAfterLsp));

            String editedReceiver=insertMethod(receiverOriginal,"    public void benchmarkAddedMethod() {}");
            request(app,"document.open",Map.of("session",session,"path",receiver.toString(),"version",1,"text",editedReceiver));
            scenarios.add(measure("maven_project_unsaved_api_addition",app,session,()->complete(app,session,caller,narrowed,"get")));

            var report=new LinkedHashMap<String,Object>();
            report.put("fixture",root.toString());report.put("receiver",receiver.toString());report.put("caller",caller.toString());
            report.put("baseline_main","3867271bf083c6c5b31be7b622fe4ef1f8a6e021");
            report.put("scenarios",scenarios);
            report.put("lsp_initial_completion",lspProof);
            report.put("final_analyzer_counters",analyzerCounters(app,session));
            report.put("final_response_budget",budgetCounters(app));
            System.out.println("RESIDENT_SEMANTIC_MAVEN_BASELINE="+Json.MAPPER.writeValueAsString(report));
        }
    }
}
