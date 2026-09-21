package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Json;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class WorkspaceBindingsIncrementalBenchmarkTest {
    @TempDir Path root;

    @Test void measuresReferencesAndRenameAcrossBodyAndApiEdits()throws Exception{
        var scenarios=new ArrayList<Map<String,Object>>();
        scenarios.add(runScenario(root.resolve("refs"),"references"));
        scenarios.add(runScenario(root.resolve("rename"),"rename"));
        var out=Map.of("feature","incremental-workspace-bindings","files",128,"scenarios",scenarios);
        Path report=TestSupport.repo().resolve("jvmd-tests/target/workspace-bindings-incremental-perf.json");
        Files.createDirectories(report.getParent());Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),out);
        System.out.println("workspace-bindings-incremental-perf "+Json.MAPPER.writeValueAsString(out));
    }

    private Map<String,Object> runScenario(Path project,String operation)throws Exception{
        Path src=Files.createDirectories(project.resolve("src"));
        Files.writeString(src.resolve("Root.java"),rootSource("Number","1"));
        for(int i=0;i<127;i++)Files.writeString(src.resolve("User"+i+".java"),
                i<16?"class User"+i+" { Object read(){ return Root.value(); } }\n":
                        "class User"+i+" { Object read(){ return "+i+"; } }\n");
        try(var app=new Application(TestSupport.config(project,Duration.ofHours(4)))){
            String session=TestSupport.open(app,src);
            var samples=new ArrayList<Map<String,Object>>();
            samples.add(measure(app,session,operation,"cold"));
            samples.add(measure(app,session,operation,"warm"));

            Path leaf=src.resolve("User0.java");
            Files.writeString(leaf,"class User0 { Object read(){ int x=1; return Root.value(); } }\n");
            samples.add(measure(app,session,operation,"body_edit"));

            Files.writeString(src.resolve("Root.java"),rootSource("Integer","2"));
            samples.add(measure(app,session,operation,"api_edit"));
            return Map.of("operation",operation,"samples",samples);
        }
    }

    private Map<String,Object> measure(Application app,String session,String operation,String phase)throws Exception{
        var before=status(app,session);long started=System.nanoTime();
        JsonNode response=operation.equals("references")
                ?TestSupport.request(app.dispatcher(),"symbol.references",Map.of("session",session,"ref","Root/value()","direction","in","limit",1000))
                :TestSupport.request(app.dispatcher(),"edit.rename",Map.of("session",session,"ref","Root/value()","new_name","renamedValue","dry_run",true));
        double ms=(System.nanoTime()-started)/1_000_000.0;
        assertThat(response.has("error")).as(response.toString()).isFalse();
        var after=status(app,session);
        var row=new LinkedHashMap<String,Object>();
        row.put("phase",phase);row.put("request_ms",ms);
        row.put("binding_computations_delta",number(after,"analyzer","binding_computations")-number(before,"analyzer","binding_computations"));
        row.put("javac_queries_delta",number(after,"analyzer","queries")-number(before,"analyzer","queries"));
        row.put("workspace_builds_delta",number(after,"workspace_bindings","builds")-number(before,"workspace_bindings","builds"));
        row.put("workspace_cache_hits_delta",number(after,"workspace_bindings","cache_hits")-number(before,"workspace_bindings","cache_hits"));
        row.put("cached_files",number(after,"workspace_bindings","cached_files"));
        return row;
    }

    private JsonNode status(Application app,String session)throws Exception{
        return TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session)).path("result").path("result");
    }
    private static long number(JsonNode node,String section,String field){return node.path(section).path(field).asLong();}
    private static String rootSource(String type,String value){return "class Root { static "+type+" value(){ return "+value+"; } }\n";}
}
