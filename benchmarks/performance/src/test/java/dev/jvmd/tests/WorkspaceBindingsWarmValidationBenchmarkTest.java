package dev.jvmd.tests;

import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
@Tag("perf")
class WorkspaceBindingsWarmValidationBenchmarkTest {
    @TempDir Path root;

    @Test void measuresUnchangedWorkspaceBindingValidationCost()throws Exception{
        var scenarios=new ArrayList<Map<String,Object>>();
        for(boolean resolved:List.of(false,true))for(int files:List.of(128,512))
            scenarios.add(run(root.resolve((resolved?"maven":"plain")+"-"+files),files,resolved));
        var out=Map.of("feature","workspace-bindings-warm-validation","mode","warm-production-daemon","transport","unix-domain-socket","scenarios",scenarios);
        Path report=TestSupport.repo().resolve("jvmd-tests/target/workspace-bindings-warm-validation-perf.json");
        Files.createDirectories(report.getParent());Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),out);
        System.out.println("workspace-bindings-warm-validation-perf "+Json.MAPPER.writeValueAsString(out));
    }

    private Map<String,Object> run(Path project,int files,boolean resolved)throws Exception{
        if(resolved)MavenFixtures.project(project,"<properties><maven.compiler.release>25</maven.compiler.release></properties>");
        Path src=Files.createDirectories(resolved?project.resolve("src/main/java"):project.resolve("src"));
        Files.writeString(src.resolve("Root.java"),"class Root { static int value(){ return 1; } }\n");
        for(int i=1;i<files;i++)Files.writeString(src.resolve("Other"+i+".java"),
                "class Other"+i+" { int value(){ return "+i+"; } }\n");
        Path daemonRoot=Files.createDirectories(project.resolve("daemon"));
        try(var daemon=new AotDaemon(daemonRoot)){
            Path workspace=resolved?project:src;
            String session=daemon.request("session.open",Map.of("root",workspace.toString())).path("result").path("session").asText();
            var cold=daemon.request("symbol.references",
                    Map.of("session",session,"ref","Root/value()","direction","in","limit",1000));
            assertThat(cold.path("result").isObject()).as(cold.toString()).isTrue();

            var before=status(daemon,session);
            var samples=new double[20];
            for(int i=0;i<samples.length;i++){
                long started=System.nanoTime();
                var response=daemon.request("symbol.references",
                        Map.of("session",session,"ref","Root/value()","direction","in","limit",1000));
                samples[i]=(System.nanoTime()-started)/1_000_000.0;
                assertThat(response.path("result").isObject()).as(response.toString()).isTrue();
            }
            var after=status(daemon,session);Arrays.sort(samples);
            var result=new LinkedHashMap<String,Object>();
            result.put("files",files);
            result.put("resolved",resolved);
            result.put("samples",samples.length);
            result.put("median_ms",percentile(samples,.50));
            result.put("p95_ms",percentile(samples,.95));
            result.put("binding_computations_delta",number(after,"analyzer","binding_computations")-number(before,"analyzer","binding_computations"));
            result.put("javac_queries_delta",number(after,"analyzer","queries")-number(before,"analyzer","queries"));
            result.put("workspace_builds_delta",number(after,"workspace_bindings","builds")-number(before,"workspace_bindings","builds"));
            result.put("workspace_cache_hits_delta",number(after,"workspace_bindings","cache_hits")-number(before,"workspace_bindings","cache_hits"));
            result.put("fast_validation_hits_delta",number(after,"workspace_bindings","fast_validation_hits")-number(before,"workspace_bindings","fast_validation_hits"));
            result.put("full_validations_delta",number(after,"workspace_bindings","full_validations")-number(before,"workspace_bindings","full_validations"));
            return Collections.unmodifiableMap(result);
        }
    }

    private static double percentile(double[] values,double p){
        int index=(int)Math.ceil(p*values.length)-1;return values[Math.max(0,Math.min(values.length-1,index))];
    }
    private static com.fasterxml.jackson.databind.JsonNode status(AotDaemon daemon,String session)throws Exception{
        return daemon.request("session.status",Map.of("session",session)).path("result");
    }
    private static long number(com.fasterxml.jackson.databind.JsonNode node,String section,String field){return node.path(section).path(field).asLong();}
}
