package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
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
            assertThat(analyzer.status()).containsEntry("completion_computations",1L).containsEntry("completion_cache_hits",6L).containsEntry("queries",1L).containsEntry("focus_layout_parses",1L);
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
}
