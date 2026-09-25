package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class DocumentContextProofTest {
    @TempDir Path root;

    @Test void focusedDocumentProofReusesAcrossUnrelatedEditsAndRejectsRelevantLexicalEdits()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path jar=IndexFixtures.jar(repo,"api","""
                package lib;
                public class Sample {
                    public int getPets(){return 1;}
                    public String getName(){return "sample";}
                }
                """,true);
        Path sourceRoot=Files.createDirectories(root.resolve("src/lib"));
        Path file=sourceRoot.resolve("Use.java");
        String original="""
                package lib;
                class Use {
                    Sample choose(Sample value){ return value; }
                    Object target(Sample value){ return choose(value).; }
                    int unrelated(){ return 1; }
                }
                """;
        Files.writeString(file,original);

        try(var index=new IndexService(root.resolve("index.db"),repo);
            var analyzer=new Analyzer()){
            index.indexJar(jar,"fixture:api:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            analyzer.configure(new Analyzer.Context(
                    "fixture:app:1","25",List.of(jar),List.of(root.resolve("src")),"proof-context",Map.of(),
                    List.of("--release","25"),Set.of(),List.of(),List.of(root.resolve("src")),true,"w"),
                    index,256L*1024*1024);

            long before=queries(analyzer);
            assertCompletion(analyzer,file,original,"choose(value).","getPets");
            long afterFirst=queries(analyzer);
            assertThat(afterFirst).isGreaterThan(before);

            assertCompletion(analyzer,file,original,"choose(value).","getPets");
            assertThat(queries(analyzer)).isEqualTo(afterFirst);

            String unrelated=original.replace("return 1;","return 2;");
            Files.writeString(file,unrelated);analyzer.changed(file);
            assertCompletion(analyzer,file,unrelated,"choose(value).","getPets");
            assertThat(queries(analyzer)).as("unrelated member edit must preserve the focused document proof").isEqualTo(afterFirst);

            String relevant=unrelated.replace("Object target(Sample value){","Object target(Sample value){ int marker = 1;");
            Files.writeString(file,relevant);analyzer.changed(file);
            assertCompletion(analyzer,file,relevant,"choose(value).","getPets");
            assertThat(queries(analyzer)).as("lexical edit in the focused member must invalidate the document proof")
                    .isGreaterThan(afterFirst);
        }
    }

    private static void assertCompletion(Analyzer analyzer,Path file,String source,String needle,String expected)throws Exception{
        int cursor=source.indexOf(needle)+needle.length();
        var position=Documents.position(source,cursor);
        var answer=analyzer.completion(file,source,position.line(),position.character(),100,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        JsonNode result=Json.MAPPER.valueToTree(answer.result());
        assertThat(result.path("items").findValuesAsText("name")).contains(expected);
    }

    private static long queries(Analyzer analyzer){
        return ((Number)analyzer.status().get("queries")).longValue();
    }
}
