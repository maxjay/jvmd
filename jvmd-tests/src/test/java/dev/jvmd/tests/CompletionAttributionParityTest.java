package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompletionAttributionParityTest {
    @TempDir Path root;

    @Test void attributeOnlyCompletionMatchesFullFlowAcrossFlowSensitiveConstructs()throws Exception{
        Files.writeString(root.resolve("Api.java"),"""
                class Api {
                    /** Returns the visible pets. */
                    int getPets(){return 1;}
                    /** Returns the display name. */
                    String getName(){return "";}
                }
                """);
        assertParity("basic","class Use { Object call(Api api){ return api."+EditorQueries.MARKER+"(); } }","get");
        assertParity("pattern","class Use { int call(Object value){ if(value instanceof String s && s."+EditorQueries.MARKER+"()) return 1; return 0; } }","sta");
        assertParity("definite-assignment","class Use { Object call(boolean flag){ String s; if(flag) s=\"\"; return s."+EditorQueries.MARKER+"(); } }","sub");
        assertParity("switch-pattern","class Use { Object call(Object value){ return switch(value){ case String s -> s."+EditorQueries.MARKER+"(); default -> \"\"; }; } }","sub");
    }

    @Test void recordsIsolatedAttributeOnlyVersusFlowCost()throws Exception{
        String source="class BenchUse { Object call(Api api){ return api."+EditorQueries.MARKER+"(); } }";
        Path file=root.resolve("BenchUse.java");Files.writeString(file,source);
        CompilerPool.Query<List<Map<String,Object>>> completion=(task,units,tier)->
                EditorQueries.completion(task,units,new SymbolIdentity(task,"fixture:completion:1","25",ignored->null,List.of(root)),"get");
        var attrTimes=new ArrayList<Double>();var flowTimes=new ArrayList<Double>();
        try(var attr=new CompilerPool();var flow=new CompilerPool()){
            attr.configure("attr-perf","25",List.of(),List.of(root),null,256L*1024*1024);
            flow.configure("flow-perf","25",List.of(),List.of(root),null,256L*1024*1024);
            for(int i=0;i<5;i++){
                assertThat(run(attr,true,file,source,completion)).isEqualTo(run(flow,false,file,source,completion));
            }
            for(int i=0;i<20;i++){
                if((i&1)==0){
                    attrTimes.add(time(attr,true,file,source,completion));
                    flowTimes.add(time(flow,false,file,source,completion));
                }else{
                    flowTimes.add(time(flow,false,file,source,completion));
                    attrTimes.add(time(attr,true,file,source,completion));
                }
            }
            var result=new LinkedHashMap<String,Object>();
            result.put("samples",20);result.put("attr_ms",attrTimes);result.put("flow_ms",flowTimes);
            result.put("attr_median_ms",median(attrTimes));result.put("flow_median_ms",median(flowTimes));
            result.put("attr_status",attr.status());result.put("flow_status",flow.status());
            Path output=TestSupport.repo().resolve("jvmd-tests/target/completion-attribution-perf.json");Files.createDirectories(output.getParent());
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result);
            System.out.println("completion-attribution-perf "+Json.MAPPER.writeValueAsString(result));
        }
    }

    private static List<Map<String,Object>> run(CompilerPool pool,boolean attributeOnly,Path file,String source,CompilerPool.Query<List<Map<String,Object>>> completion)throws Exception{
        pool.sourcesChanged();
        var outcome=attributeOnly?pool.attributedQuery(file,source,completion):pool.query(file,source,2,completion);
        assertThat(outcome.warnings()).isEmpty();assertThat(outcome.tier()).isEqualTo(2);return outcome.result();
    }
    private static double time(CompilerPool pool,boolean attributeOnly,Path file,String source,CompilerPool.Query<List<Map<String,Object>>> completion)throws Exception{
        pool.sourcesChanged();long started=System.nanoTime();
        var outcome=attributeOnly?pool.attributedQuery(file,source,completion):pool.query(file,source,2,completion);
        double elapsed=(System.nanoTime()-started)/1e6;
        assertThat(outcome.warnings()).isEmpty();assertThat(outcome.tier()).isEqualTo(2);return elapsed;
    }
    private static double median(List<Double> values){var sorted=values.stream().sorted().toList();return (sorted.get(9)+sorted.get(10))/2.0;}

    private void assertParity(String name,String source,String prefix)throws Exception{
        Path file=root.resolve("Use-"+name+".java");Files.writeString(file,source);
        CompilerPool.Query<List<Map<String,Object>>> completion=(task,units,tier)->
                EditorQueries.completion(task,units,new SymbolIdentity(task,"fixture:completion:1","25",ignored->null,List.of(root)),prefix);
        CompilerPool.Outcome<List<Map<String,Object>>> attributed,flow;
        try(var pool=new CompilerPool()){
            pool.configure("attr-"+name,"25",List.of(),List.of(root),null,256L*1024*1024);
            attributed=pool.attributedQuery(file,source,completion);
            assertThat(pool.status()).containsEntry("attribute_only_queries",1L);
        }
        try(var pool=new CompilerPool()){
            pool.configure("flow-"+name,"25",List.of(),List.of(root),null,256L*1024*1024);
            flow=pool.query(file,source,2,completion);
            assertThat(pool.status()).containsEntry("attribute_only_queries",0L);
        }
        assertThat(attributed.warnings()).as(name+" ATTR warnings").isEmpty();
        assertThat(flow.warnings()).as(name+" FLOW warnings").isEmpty();
        assertThat(attributed.tier()).as(name+" ATTR tier").isEqualTo(2);
        assertThat(flow.tier()).as(name+" FLOW tier").isEqualTo(2);
        assertThat(attributed.result()).as(name+" completion rows").isEqualTo(flow.result());
    }
}
