package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.CompilerPool;
import dev.jvmd.analyzer.IndexedFileManager;
import dev.jvmd.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompletionPrefixCacheTest {
    @TempDir Path root;
    private Analyzer.Context context(){return new Analyzer.Context("fixture:prefix:1","25",List.of(),List.of(root),"prefix",Map.of());}
    private static String text(String prefix){return "class Use { Object call(Api api){return api."+prefix+"();} }";}
    private static JsonNode complete(Analyzer analyzer,Path file,String text,String prefix)throws Exception{
        var answer=analyzer.completion(file,text,0,text.indexOf("api."+prefix)+4+prefix.length(),100,0);
        assertThat(answer.warnings()).isEmpty();return Json.MAPPER.valueToTree(answer.result());
    }
    @Test void prefixNarrowingReusesDetachedCandidatesAndKeepsTheCurrentEditRange()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} int getPetCount(){return 2;} int other(){return 3;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);int version=1;
            for(String prefix:List.of("g","ge","get","getP","getPe","getPet","getPets")){
                String text=text(prefix);documents.change(file,++version,List.of(new Documents.Change(null,text)));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
                var result=complete(analyzer,file,text,prefix);
                try(var fresh=new Analyzer()){
                    fresh.configure(context(),null,256L*1024*1024);fresh.documents(documents);
                    assertThat(result).isEqualTo(complete(fresh,file,text,prefix));
                }
                assertThat(result.path("range").path("end").path("character").asInt()-result.path("range").path("start").path("character").asInt()).isEqualTo(prefix.length());
            }
            var status=analyzer.status();
            assertThat(status).containsEntry("completion_computations",1L).containsEntry("completion_cache_hits",6L).containsEntry("completion_requests",7L).containsEntry("queries",1L).containsEntry("focus_layout_parses",1L);
            assertThat(status).containsKeys("completion_timing_ms","completion_last_timing_ms","completion_candidates_seen","completion_rows_materialized","completion_doc_lookups");
            @SuppressWarnings("unchecked") var timings=(Map<String,Double>)status.get("completion_timing_ms");
            assertThat(timings).containsKeys("key","source_refresh","focus","compiler_query","editor_total","candidate_discovery","row_materialization","documentation","sort","cache_admission","filter","total");
            assertThat(timings.get("total")).isGreaterThan(0d);
            assertThat((Long)status.get("completion_candidates_seen")).isGreaterThan(0L);
            assertThat((Long)status.get("completion_rows_materialized")).isGreaterThan(0L);
            assertThat((Long)status.get("completion_doc_lookups")).isGreaterThan(0L);
            // Backspacing past the cached prefix must recompute rather than lose candidates.
            String wider=text("");documents.change(file,++version,List.of(new Documents.Change(null,wider)));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,wider,"").path("items").findValuesAsText("name")).contains("other");
            assertThat(analyzer.status().get("completion_computations")).isEqualTo(2L);
        }
    }
    @Test void otherUnsavedAndTimestampPreservingSourceChangesInvalidateTheCache()throws Exception{
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));var documents=new Documents();documents.open(file,text("get"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("int");
            documents.open(api,"class Api { String getPets(){return \"x\";} }",1);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("java.lang.String");
            documents.close(api);var time=Files.getLastModifiedTime(api);Files.writeString(api,"class Api { int getElse(){return 1;} }");Files.setLastModifiedTime(api,time);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getElse").doesNotContain("getPets");
            assertThat(analyzer.status().get("completion_computations")).isEqualTo(3L);
        }
    }
    @Test void timestampPreservingClosedSourceEditsInvalidateWatcherBackedCandidates()throws Exception{
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("int");
            var time=Files.getLastModifiedTime(api);Files.writeString(api,"class Api { String getPets(){return \"x\";} }");Files.setLastModifiedTime(api,time);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("java.lang.String");
            assertThat(analyzer.status()).containsEntry("source_watch_reliable",1L).containsEntry("completion_computations",2L);
        }
    }
    @Test void changedReleaseAndNewSourceNamesCannotReuseOldCandidates()throws Exception{
        String source="class Use { Object call(String api){return api.strip();} }";
        Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("fixture:prefix:1","8",List.of(),List.of(root),"prefix",Map.of()),null,256L*1024*1024);
            assertThat(complete(analyzer,file,source,"strip").path("items").findValuesAsText("name")).doesNotContain("strip");
            analyzer.configure(new Analyzer.Context("fixture:prefix:1","11",List.of(),List.of(root),"prefix",Map.of()),null,256L*1024*1024);
            assertThat(complete(analyzer,file,source,"strip").path("items").findValuesAsText("name")).contains("strip");
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).doesNotContain("getPets");
            Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status().get("completion_computations")).isEqualTo(4L);
        }
    }
    @Test void unresolvedReceiverRetriesSourceDiscoveryWithoutAWatchEvent()throws Exception{
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).doesNotContain("getPets");
            // Suppress delivery while leaving the cached source catalog marked reliable.
            // This deterministically models a create event arriving after the next query.
            var compilerField=Analyzer.class.getDeclaredField("compiler");compilerField.setAccessible(true);
            var managerField=CompilerPool.class.getDeclaredField("manager");managerField.setAccessible(true);
            var manager=managerField.get(compilerField.get(analyzer));
            var watchesField=IndexedFileManager.class.getDeclaredField("sourceWatchDirectories");watchesField.setAccessible(true);
            var watches=(Map<?,?>)watchesField.get(manager);
            watches.keySet().forEach(key->((WatchKey)key).cancel());
            Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status()).containsEntry("completion_computations",2L);
        }
    }

    @Test void nonSourceFilesystemChurnDoesNotInvalidateWatcherBackedCandidates()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("g"),"g").path("items").findValuesAsText("name")).contains("getPets");
            Files.writeString(root.resolve("state.tmp"),"not source");
            documents.change(file,2,List.of(new Documents.Change(null,text("ge"))));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("ge"),"ge").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status()).containsEntry("completion_computations",1L).containsEntry("completion_cache_hits",1L);
        }
    }
    @Test void coarseWorkspaceRootsUseConservativeSourceValidationWithoutWatchingTheWholeWorkspace()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        var context=new Analyzer.Context("fixture:coarse:1","25",List.of(),List.of(root),"coarse",Map.of(),List.of("--release","25"),Set.of(),List.of(),List.of(root),false);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context,null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("g"),"g").path("items").findValuesAsText("name")).contains("getPets");
            documents.change(file,2,List.of(new Documents.Change(null,text("ge"))));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("ge"),"ge").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status()).containsEntry("source_watch_reliable",0L).containsEntry("completion_computations",1L).containsEntry("completion_cache_hits",1L);
        }
    }
    @Test void largeSourceContextsReuseCompletionCandidatesWithoutScanningEverySource()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} int getPetCount(){return 2;} }");
        for(int i=0;i<300;i++)Files.writeString(root.resolve("Helper"+i+".java"),"class Helper"+i+" { int value(){return "+i+";} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("g"),"g").path("items").findValuesAsText("name")).contains("getPets");
            documents.change(file,2,List.of(new Documents.Change(null,text("ge"))));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("ge"),"ge").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status()).containsEntry("source_watch_reliable",1L).containsEntry("completion_computations",1L).containsEntry("completion_cache_hits",1L);
        }
    }
    @Test void detachedHitsStillDetectTimestampPreservingJarReplacementAndDeletion()throws Exception{
        Path jar=IndexFixtures.jar(root.resolve("repository"),"api","package lib; public class Sample { public int getPets(){return 1;} }",true);
        Path sources=Files.createDirectories(root.resolve("sources"));String source="class Use { Object call(lib.Sample api){return api.getPets();} }";
        Path file=Files.writeString(sources.resolve("Use.java"),source);var identities=new FileStateRegistry();
        try(var analyzer=new Analyzer(identities)){
            analyzer.configure(new Analyzer.Context("fixture:prefix:1","25",List.of(jar),List.of(sources),"jar",Map.of()),null,256L*1024*1024);
            assertThat(complete(analyzer,file,source,"get").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(complete(analyzer,file,source,"getP").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status()).containsEntry("completion_computations",1L).containsEntry("completion_cache_hits",1L);
            var time=Files.getLastModifiedTime(jar);
            Path replacement=IndexFixtures.jar(root.resolve("replacement"),"api","package lib; public class Sample { public String getElse(){return \"new\";} }",true);
            Files.copy(replacement,jar,StandardCopyOption.REPLACE_EXISTING);Files.setLastModifiedTime(jar,time);
            assertThat(complete(analyzer,file,source,"get").path("items").findValuesAsText("name")).contains("getElse").doesNotContain("getPets");
            Files.delete(jar);
            var deleted=analyzer.completion(file,source,0,source.indexOf("api.get")+7,100,0);
            assertThat(deleted.warnings()).anyMatch(w->w.startsWith("analyzer_fault:"));
            assertThat(Json.MAPPER.valueToTree(deleted.result()).path("items").isEmpty()).isTrue();
        }
    }
}
