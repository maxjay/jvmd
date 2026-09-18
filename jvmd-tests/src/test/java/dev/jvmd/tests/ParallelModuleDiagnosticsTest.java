package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
@Tag("perf")
class ParallelModuleDiagnosticsTest {
    @TempDir Path root;
    private static final long MAX_ACCEPTABLE_HEAP=1024L*1024*1024;
    private record Run(int actors,double coldMs,double warmMs,long coldQueries,long warmQueries,
                       long peakHeap,long gc,double cpuMs,List<CompilerPool.Problem> diagnostics){}

    @Test void oneTwoAndFourActorsPreserveCorrectnessAndWarmInvariant()throws Exception{
        var files=new ArrayList<Path>();
        var owner=new HashMap<Path,Integer>();
        var moduleRoots=new ArrayList<Path>();
        for(int module=0;module<4;module++){
            Path source=Files.createDirectories(root.resolve("module-"+module).resolve("src/main/java"));
            moduleRoots.add(source);
            for(int i=0;i<64;i++){
                Path file=source.resolve("M"+module+"Source"+i+".java");
                String value=i%16==0?"\"bad\"":"1";
                Files.writeString(file,"class M"+module+"Source"+i+" { int value = "+value+"; }\n");
                files.add(file);
                owner.put(file.toAbsolutePath().normalize(),module);
            }
        }

        var one=run(1,files,owner,moduleRoots);
        var two=run(2,files,owner,moduleRoots);
        var four=run(4,files,owner,moduleRoots);

        assertThat(two.diagnostics()).containsExactlyElementsOf(one.diagnostics());
        assertThat(four.diagnostics()).containsExactlyElementsOf(one.diagnostics());
        for(var result:List.of(one,two,four)){
            assertThat(result.coldQueries()).as(result.actors()+" actors cold module queries").isEqualTo(4);
            assertThat(result.warmQueries()).as(result.actors()+" actors warm additional queries").isZero();
            assertThat(result.peakHeap()).as(result.actors()+" actors peak heap").isPositive().isLessThan(MAX_ACCEPTABLE_HEAP);
            assertThat(result.cpuMs()).isGreaterThanOrEqualTo(0d);
        }

        var evidence=new LinkedHashMap<String,Object>();
        for(var result:List.of(one,two,four))evidence.put(Integer.toString(result.actors()),Map.of(
                "wall_cold_ms",result.coldMs(),"wall_warm_ms",result.warmMs(),"cpu_ms",result.cpuMs(),
                "peak_heap_bytes",result.peakHeap(),"gc_collections",result.gc(),
                "cold_javac_queries",result.coldQueries(),"warm_additional_javac_queries",result.warmQueries(),
                "diagnostics",result.diagnostics().size()));
        evidence.put("comparison",Map.of(
                "two_vs_one_wall_ratio",two.coldMs()/one.coldMs(),
                "four_vs_one_wall_ratio",four.coldMs()/one.coldMs(),
                "two_vs_one_heap_ratio",(double)two.peakHeap()/one.peakHeap(),
                "four_vs_one_heap_ratio",(double)four.peakHeap()/one.peakHeap()));
        System.out.println("diagnostics-actors "+Json.MAPPER.writeValueAsString(evidence));
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                TestSupport.repo().resolve("jvmd-tests/target/diagnostics-actors-perf.json").toFile(),evidence);
    }

    @Test void crossModuleBodyAndApiChangesPropagateAcrossActors()throws Exception{
        Path apiRoot=Files.createDirectories(root.resolve("api/src/main/java"));
        Path appRoot=Files.createDirectories(root.resolve("app/src/main/java"));
        Path api=apiRoot.resolve("Api.java");
        Path app=appRoot.resolve("UseApi.java");
        Files.writeString(api,"public class Api { public int value() { return 1; } }\n");
        Files.writeString(app,"class UseApi { int value = new Api().value(); }\n");

        var documents=new Documents();
        try(var registry=new ModuleAnalyzerRegistry(2)){
            long budget=512L*1024*1024;
            var apiContext=new Analyzer.Context("fixture:api:1","25",List.of(),List.of(apiRoot),
                    "cross-api",Map.of(apiRoot.toString(),"fixture:api:1"));
            var appContext=new Analyzer.Context("fixture:app:1","25",List.of(),List.of(appRoot,apiRoot),
                    "cross-app",Map.of(appRoot.toString(),"fixture:app:1",apiRoot.toString(),"fixture:api:1"));
            var apiEngine=registry.engine("api",apiContext,null,budget,documents,root.resolve("cross-state"));
            var appEngine=registry.engine("app",appContext,null,budget,documents,root.resolve("cross-state"));
            var coordinator=new WorkspaceAnalysisCoordinator(documents,
                    file->file.toAbsolutePath().normalize().equals(api.toAbsolutePath().normalize())?apiEngine:appEngine,
                    ()->{},_->null,2);
            var files=List.of(api,app);

            var cold=RequestScope.call("diag.get",()->coordinator.get(files,true,0,100,List.of()));
            assertThat(problems(cold)).isEmpty();

            Files.writeString(api,"public class Api { public int value() { return 2; } }\n");
            var bodyOnly=RequestScope.call("diag.get",()->coordinator.get(files,true,0,100,List.of()));
            assertThat(problems(bodyOnly)).isEmpty();
            @SuppressWarnings("unchecked")
            var bodyMetrics=(Map<String,Object>)coordinator.status().get("last");
            assertThat(((Number)bodyMetrics.get("files_reanalysed")).intValue())
                    .as("body-only edit should not reanalyse the dependant actor").isEqualTo(1);

            Files.writeString(api,"public class Api { public String value() { return \"x\"; } }\n");
            var apiChange=RequestScope.call("diag.get",()->coordinator.get(files,true,0,100,List.of()));
            assertThat(problems(apiChange)).isNotEmpty();
            @SuppressWarnings("unchecked")
            var apiMetrics=(Map<String,Object>)coordinator.status().get("last");
            assertThat(((Number)apiMetrics.get("files_reanalysed")).intValue())
                    .as("API edit should reanalyse the dependant actor").isGreaterThanOrEqualTo(2);

            @SuppressWarnings("unchecked")
            var actors=(Map<String,Object>)registry.status().get("actors");
            assertThat(actors).hasSize(2);
            for(Object value:actors.values()){
                @SuppressWarnings("unchecked") var status=(Map<String,Object>)value;
                assertThat(((Number)status.getOrDefault("faults",0L)).longValue()).isZero();
            }
        }
    }

    private Run run(int actors,List<Path> files,Map<Path,Integer> owner,List<Path> roots)throws Exception{
        var documents=new Documents();
        long totalBudget=768L*1024*1024;
        try(var registry=new ModuleAnalyzerRegistry(actors)){
            var engines=new ArrayList<DiagnosticEngine>();
            for(int module=0;module<4;module++){
                Path source=roots.get(module);
                String gav="fixture:module-"+module+":1";
                var context=new Analyzer.Context(gav,"25",List.of(),List.of(source),
                        "actors-"+actors+"-module-"+module,Map.of(source.toString(),gav));
                engines.add(registry.engine("module-"+module,context,null,totalBudget,documents,root.resolve("state-"+actors)));
            }
            var coordinator=new WorkspaceAnalysisCoordinator(documents,
                    file->engines.get(owner.get(file.toAbsolutePath().normalize())),()->{},_->null,registry.parallelism());
            long beforeQueries=((Number)registry.analyzerStatus(Map.of()).getOrDefault("queries",0L)).longValue();
            long started=System.nanoTime();
            var cold=RequestScope.call("diag.get",()->coordinator.get(files,true,0,1000,List.of()));
            double coldMs=(System.nanoTime()-started)/1e6;
            long coldQueries=((Number)registry.analyzerStatus(Map.of()).get("queries")).longValue()-beforeQueries;
            @SuppressWarnings("unchecked") var coldMetrics=(Map<String,Object>)coordinator.status().get("last");
            assertThat(coldMetrics.get("files_reanalysed")).isEqualTo(256);
            assertThat(coldMetrics.get("modules_batch_analysed")).isEqualTo(4);
            assertThat(((Number)coldMetrics.get("actor_parallelism_used")).intValue()).isEqualTo(Math.min(actors,4));

            long warmBefore=((Number)registry.analyzerStatus(Map.of()).get("queries")).longValue();
            started=System.nanoTime();
            var warm=RequestScope.call("diag.get",()->coordinator.get(files,true,0,1000,List.of()));
            double warmMs=(System.nanoTime()-started)/1e6;
            long warmQueries=((Number)registry.analyzerStatus(Map.of()).get("queries")).longValue()-warmBefore;
            @SuppressWarnings("unchecked") var warmMetrics=(Map<String,Object>)coordinator.status().get("last");
            assertThat(warmMetrics.get("files_reanalysed")).isEqualTo(0);
            assertThat(problems(warm)).containsExactlyElementsOf(problems(cold));
            @SuppressWarnings("unchecked") var actorDetail=(Map<String,Object>)registry.status().get("actors");
            for(Object value:actorDetail.values()){
                @SuppressWarnings("unchecked") var status=(Map<String,Object>)value;
                assertThat(((Number)status.getOrDefault("faults",0L)).longValue()).isZero();
            }
            return new Run(actors,coldMs,warmMs,coldQueries,warmQueries,
                    ((Number)coldMetrics.get("peak_heap_bytes")).longValue(),
                    ((Number)coldMetrics.get("gc_collections")).longValue(),
                    ((Number)coldMetrics.get("analysis_cpu_ms")).doubleValue(),problems(cold));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CompilerPool.Problem> problems(Envelope value){
        return (List<CompilerPool.Problem>)((Map<?,?>)value.result()).get("diagnostics");
    }
}
