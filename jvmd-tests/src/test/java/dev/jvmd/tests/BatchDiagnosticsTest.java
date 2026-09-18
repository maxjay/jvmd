package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.WorkspaceAnalysisCoordinator;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class BatchDiagnosticsTest {
    @TempDir Path root;
    @Test void hundredFilesUseOneTaskThenZeroAndMatchIndividualDiagnostics()throws Exception{
        var files=new LinkedHashMap<Path,String>();
        for(int i=0;i<100;i++){
            Path file=root.resolve("Source"+i+".java");String text="class Source"+i+" { int n="+(i%10==0?"\"bad\"":"1")+"; }";
            Files.writeString(file,text);files.put(file,text);
        }
        var documents=new Documents();
        try(var batch=analyzer();var individual=analyzer()){
            batch.documents(documents);individual.documents(documents);
            var coordinator=new WorkspaceAnalysisCoordinator(documents,file->batch);
            long started=System.nanoTime();var cold=RequestScope.call("diag.get",()->coordinator.get(List.copyOf(files.keySet()),true,0,1000,List.of()));double coldMs=(System.nanoTime()-started)/1e6;
            assertThat(batch.status()).containsEntry("queries",1L).containsEntry("batch_queries",1L).containsEntry("diagnostic_files_analysed",100L);
            var expected=new ArrayList<CompilerPool.Problem>();for(var entry:files.entrySet())expected.addAll(problems(individual.diagnostics(entry.getKey(),entry.getValue())));
            assertThat(problems(cold)).containsExactlyInAnyOrderElementsOf(expected);
            started=System.nanoTime();var warm=RequestScope.call("diag.get",()->coordinator.get(List.copyOf(files.keySet()),true,0,1000,List.of()));double warmMs=(System.nanoTime()-started)/1e6;
            assertThat(problems(warm)).isEqualTo(problems(cold));assertThat(batch.status()).containsEntry("queries",1L);
            assertThat(((Map<?,?>)coordinator.status().get("last")).get("files_reanalysed")).isEqualTo(0);
            System.out.println("diagnostics-batch "+Json.MAPPER.writeValueAsString(Map.of("files",100,"cold_ms",coldMs,"warm_ms",warmMs,"cold_queries",1,"warm_queries",0)));
        }
    }
    @Test void bodyEditRefreshesChangedPrerequisiteBeforeAlphabeticallyEarlierDependant()throws Exception{
        Path api=root.resolve("ZApi.java"),use=root.resolve("AUse.java");String original="class ZApi { int value(){return 1;} }";
        Files.writeString(api,original);Files.writeString(use,"class AUse { int n=new ZApi().value(); }");
        var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=analyzer()){
            analyzer.documents(documents);var coordinator=new WorkspaceAnalysisCoordinator(documents,file->analyzer);
            RequestScope.call("diag.get",()->coordinator.get(List.of(use,api),true,0,100,List.of()));
            String changed=original.replace("return 1","return 2");documents.change(api,2,List.of(new Documents.Change(null,changed)));analyzer.documents(documents);
            var updated=RequestScope.call("diag.get",()->coordinator.get(List.of(use,api),true,0,100,List.of()));
            assertThat(problems(updated)).isEmpty();assertThat(analyzer.status()).containsEntry("queries",3L);
            assertThat(((Map<?,?>)coordinator.status().get("last")).get("files_reanalysed")).isEqualTo(1);
        }
    }
    @Test void documentChangeAtABatchBoundarySupersedesTheWholeResponse()throws Exception{
        var files=new ArrayList<Path>();for(int i=0;i<20;i++){Path file=root.resolve("Supersede"+i+".java");Files.writeString(file,"class Supersede"+i+" {}");files.add(file);}
        var documents=new Documents();String first=Files.readString(files.getFirst());documents.open(files.getFirst(),first,1);
        try(var analyzer=analyzer()){
            analyzer.documents(documents);var changed=new java.util.concurrent.atomic.AtomicBoolean();
            var coordinator=new WorkspaceAnalysisCoordinator(documents,file->analyzer,()->{
                if(changed.compareAndSet(false,true))documents.change(files.getFirst(),2,List.of(new Documents.Change(null,"class Supersede0 { int changed; }")));
            });
            var result=RequestScope.call("diag.get",()->coordinator.get(files,true,0,1000,List.of()));
            assertThat(result.warnings().toString()).contains("diagnostics_superseded");
            assertThat(problems(result)).isEmpty();assertThat(changed).isTrue();
        }
    }

    @Test void adaptiveSelectorUsesIncrementalForSparseChangesAndBatchForDenseInvalidation()throws Exception{
        for(int dependants:List.of(1,10,50,100))assertStrategy(dependants);
    }
    private void assertStrategy(int dependants)throws Exception{
        Path project=Files.createDirectories(root.resolve("adaptive-"+dependants)),api=project.resolve("Api.java");
        String original="class Api { int value(){return 1;} }";Files.writeString(api,original);
        var files=new ArrayList<Path>();files.add(api);
        for(int i=0;i<100;i++){
            Path file=project.resolve("Use"+i+".java");
            String text=i<dependants?"class Use"+i+" { int n=new Api().value(); }":"class Use"+i+" { int n="+i+"; }";
            Files.writeString(file,text);files.add(file);
        }
        var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=analyzer(project)){
            analyzer.documents(documents);var coordinator=new WorkspaceAnalysisCoordinator(documents,file->analyzer);
            RequestScope.call("diag.get",()->coordinator.get(files,true,0,1000,List.of()));
            long before=((Number)analyzer.status().get("queries")).longValue();
            documents.change(api,2,List.of(new Documents.Change(null,"class Api { String value(){return \"changed\";} }")));analyzer.documents(documents);
            RequestScope.call("diag.get",()->coordinator.get(files,true,0,1000,List.of()));
            @SuppressWarnings("unchecked") var metrics=(Map<String,Object>)coordinator.status().get("last");
            assertThat(metrics.get("files_reanalysed")).isEqualTo(dependants+1);
            if(dependants<16){
                assertThat(metrics).containsEntry("modules_batch_analysed",0).containsEntry("modules_incrementally_analysed",1);
                assertThat(((Number)analyzer.status().get("queries")).longValue()-before).isEqualTo(dependants+1);
            }else{
                assertThat(metrics).containsEntry("modules_batch_analysed",1);
                assertThat(((Number)analyzer.status().get("queries")).longValue()-before).isEqualTo(2);
            }
        }
    }

    private Analyzer analyzer()throws Exception{return analyzer(root);}
    private Analyzer analyzer(Path sourceRoot)throws Exception{var analyzer=new Analyzer();analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(sourceRoot),"context:"+sourceRoot,Map.of(sourceRoot.toString(),"test:app:1")),null,256L*1024*1024);return analyzer;}
    @SuppressWarnings("unchecked") private static List<CompilerPool.Problem> problems(Envelope value){return (List<CompilerPool.Problem>)((Map<?,?>)value.result()).get("diagnostics");}
}
