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
    private record Run(int actors,double coldMs,double warmMs,long coldQueries,long warmQueries,
                       long peakHeap,long gc,double cpuMs,List<CompilerPool.Problem> diagnostics){}

    @Test void oneTwoAndFourActorsPreserveCorrectnessAndWarmInvariant()throws Exception{
        var files=new ArrayList<Path>();var owner=new HashMap<Path,Integer>();var moduleRoots=new ArrayList<Path>();
        for(int module=0;module<4;module++){
            Path source=Files.createDirectories(root.resolve("module-"+module).resolve("src/main/java"));
            moduleRoots.add(source);
            for(int i=0;i<64;i++){
                Path file=source.resolve("M"+module+"Source"+i+".java");
                String value=i%16==0?"\"bad\"":"1";
                Files.writeString(file,"class M"+module+"Source"+i+" { int value = "+value+"; }\n");
                files.add(file);owner.put(file.toAbsolutePath().normalize(),module);
            }
        }
        var one=run(1,files,owner,moduleRoots),two=run(2,files,owner,moduleRoots),four=run(4,files,owner,moduleRoots);
        assertThat(two.diagnostics()).containsExactlyElementsOf(one.diagnostics());
        assertThat(four.diagnostics()).containsExactlyElementsOf(one.diagnostics());
        for(var result:List.of(one,two,four)){
            assertThat(result.coldQueries()).as(result.actors()+" actors cold module queries").isEqualTo(4);
            assertThat(result.warmQueries()).as(result.actors()+" actors warm additional queries").isZero();
            assertThat(result.peakHeap()).isPositive();assertThat(result.cpuMs()).isGreaterThanOrEqualTo(0d);
        }
        var evidence=new LinkedHashMap<String,Object>();
        for(var result:List.of(one,two,four))evidence.put(Integer.toString(result.actors()),Map.of(
                "wall_cold_ms",result.coldMs(),"wall_warm_ms",result.warmMs(),"cpu_ms",result.cpuMs(),
                "peak_heap_bytes",result.peakHeap(),"gc_collections",result.gc(),
                "cold_javac_queries",result.coldQueries(),"warm_additional_javac_queries",result.warmQueries(),
                "diagnostics",result.diagnostics().size()));
        System.out.println("diagnostics-actors "+Json.MAPPER.writeValueAsString(evidence));
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/diagnostics-actors-perf.json").toFile(),evidence);
    }

    private Run run(int actors,List<Path> files,Map<Path,Integer> owner,List<Path> roots)throws Exception{
        var documents=new Documents();long totalBudget=768L*1024*1024;
        try(var registry=new ModuleAnalyzerRegistry(actors)){
            var engines=new ArrayList<DiagnosticEngine>();
            for(int module=0;module<4;module++){
                Path source=roots.get(module);String gav="fixture:module-"+module+":1";
                var context=new Analyzer.Context(gav,"25",List.of(),List.of(source),"actors-"+actors+"-module-"+module,Map.of(source.toString(),gav));
                engines.add(registry.engine("module-"+module,context,null,totalBudget,documents,root.resolve("state-"+actors)));
            }
            var coordinator=new WorkspaceAnalysisCoordinator(documents,file->engines.get(owner.get(file.toAbsolutePath().normalize())),()->{},_->null,registry.parallelism());
            long beforeQueries=((Number)registry.analyzerStatus(Map.of()).getOrDefault("queries",0L)).longValue();
            long started=System.nanoTime();var cold=RequestScope.call("diag.get",()->coordinator.get(files,true,0,1000,List.of()));double coldMs=(System.nanoTime()-started)/1e6;
            long coldQueries=((Number)registry.analyzerStatus(Map.of()).get("queries")).longValue()-beforeQueries;
            @SuppressWarnings("unchecked") var coldMetrics=(Map<String,Object>)coordinator.status().get("last");
            assertThat(coldMetrics.get("files_reanalysed")).isEqualTo(256);
            assertThat(coldMetrics.get("modules_batch_analysed")).isEqualTo(4);
            assertThat(((Number)coldMetrics.get("actor_parallelism_used")).intValue()).isEqualTo(Math.min(actors,4));

            long warmBefore=((Number)registry.analyzerStatus(Map.of()).get("queries")).longValue();
            started=System.nanoTime();var warm=RequestScope.call("diag.get",()->coordinator.get(files,true,0,1000,List.of()));double warmMs=(System.nanoTime()-started)/1e6;
            long warmQueries=((Number)registry.analyzerStatus(Map.of()).get("queries")).longValue()-warmBefore;
            @SuppressWarnings("unchecked") var warmMetrics=(Map<String,Object>)coordinator.status().get("last");
            assertThat(warmMetrics.get("files_reanalysed")).isEqualTo(0);
            assertThat(problems(warm)).containsExactlyElementsOf(problems(cold));
            @SuppressWarnings("unchecked") var actorsStatus=(Map<String,Object>)registry.status();
            @SuppressWarnings("unchecked") var actorDetail=(Map<String,Object>)actorsStatus.get("actors");
            for(Object value:actorDetail.values()){
                @SuppressWarnings("unchecked") var status=(Map<String,Object>)value;
                assertThat(((Number)status.getOrDefault("faults",0L)).longValue()).isZero();
            }
            return new Run(actors,coldMs,warmMs,coldQueries,warmQueries,
                    ((Number)coldMetrics.get("peak_heap_bytes")).longValue(),((Number)coldMetrics.get("gc_collections")).longValue(),
                    ((Number)coldMetrics.get("analysis_cpu_ms")).doubleValue(),problems(cold));
        }
    }
    @SuppressWarnings("unchecked") private static List<CompilerPool.Problem> problems(Envelope value){
        return (List<CompilerPool.Problem>)((Map<?,?>)value.result()).get("diagnostics");
    }
}
