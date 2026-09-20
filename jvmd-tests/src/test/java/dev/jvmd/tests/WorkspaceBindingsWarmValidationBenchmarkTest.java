package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class WorkspaceBindingsWarmValidationBenchmarkTest {
    @TempDir Path root;

    @Test void measuresUnchangedWorkspaceBindingValidationCost()throws Exception{
        var scenarios=new ArrayList<Map<String,Object>>();
        for(int files:List.of(128,512))scenarios.add(run(root.resolve("f"+files),files));
        var out=Map.of("feature","workspace-bindings-warm-validation","scenarios",scenarios);
        Path report=TestSupport.repo().resolve("jvmd-tests/target/workspace-bindings-warm-validation-perf.json");
        Files.createDirectories(report.getParent());Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),out);
        System.out.println("workspace-bindings-warm-validation-perf "+Json.MAPPER.writeValueAsString(out));
    }

    private Map<String,Object> run(Path project,int files)throws Exception{
        Path src=Files.createDirectories(project.resolve("src"));
        Files.writeString(src.resolve("Root.java"),"class Root { static int value(){ return 1; } }\n");
        for(int i=1;i<files;i++)Files.writeString(src.resolve("Other"+i+".java"),
                "class Other"+i+" { int value(){ return "+i+"; } }\n");
        try(var app=new Application(TestSupport.config(project,Duration.ofHours(4)))){
            String session=TestSupport.open(app,src);
            var cold=TestSupport.request(app.dispatcher(),"symbol.references",
                    Map.of("session",session,"ref","Root/value()","direction","in","limit",1000));
            assertThat(cold.has("error")).as(cold.toString()).isFalse();

            var before=status(app,session);
            var samples=new double[20];
            for(int i=0;i<samples.length;i++){
                long started=System.nanoTime();
                var response=TestSupport.request(app.dispatcher(),"symbol.references",
                        Map.of("session",session,"ref","Root/value()","direction","in","limit",1000));
                samples[i]=(System.nanoTime()-started)/1_000_000.0;
                assertThat(response.has("error")).as(response.toString()).isFalse();
            }
            var after=status(app,session);Arrays.sort(samples);
            return Map.of(
                    "files",files,
                    "samples",samples.length,
                    "median_ms",percentile(samples,.50),
                    "p95_ms",percentile(samples,.95),
                    "binding_computations_delta",number(after,"analyzer","binding_computations")-number(before,"analyzer","binding_computations"),
                    "javac_queries_delta",number(after,"analyzer","queries")-number(before,"analyzer","queries"),
                    "workspace_builds_delta",number(after,"workspace_bindings","builds")-number(before,"workspace_bindings","builds"),
                    "workspace_cache_hits_delta",number(after,"workspace_bindings","cache_hits")-number(before,"workspace_bindings","cache_hits"),
                    "fast_validation_hits_delta",number(after,"workspace_bindings","fast_validation_hits")-number(before,"workspace_bindings","fast_validation_hits"),
                    "full_validations_delta",number(after,"workspace_bindings","full_validations")-number(before,"workspace_bindings","full_validations"));
        }
    }

    private static double percentile(double[] values,double p){
        int index=(int)Math.ceil(p*values.length)-1;return values[Math.max(0,Math.min(values.length-1,index))];
    }
    private static com.fasterxml.jackson.databind.JsonNode status(Application app,String session)throws Exception{
        return TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session)).path("result").path("result");
    }
    private static long number(com.fasterxml.jackson.databind.JsonNode node,String section,String field){return node.path(section).path(field).asLong();}
}
