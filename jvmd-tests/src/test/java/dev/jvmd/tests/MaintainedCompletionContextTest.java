package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Json;
import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class MaintainedCompletionContextTest {
    @TempDir Path root;

    @Test void simpleIndexedReceiversNeedNoQuerySideJavacAndNoResidentPromotion()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path jar=IndexFixtures.jar(repo,"api","""
                package lib;
                public class Sample {
                    public int getPets(){return 1;}
                    public static int staticValue(){return 2;}
                    public Model getModel(){return new Model();}
                }
                class Model {
                    public String getName(){return "name";}
                }
                """,true);
        Path sources=Files.createDirectories(root.resolve("src/lib"));
        try(var index=new IndexService(root.resolve("index.db"),repo);
            var analyzer=new Analyzer()){
            index.indexJar(jar,"fixture:api:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            analyzer.configure(new Analyzer.Context(
                    "fixture:app:1","25",List.of(jar),List.of(root.resolve("src")),"maintained",Map.of(),
                    List.of("--release","25"),Set.of(),List.of(),List.of(root.resolve("src")),true,"w"),
                    index,256L*1024*1024);

            String parameter="package lib; class Use { Object f(Sample value){ return value.; } }";
            Path file=Files.writeString(sources.resolve("Use.java"),parameter);
            assertZeroJavac(analyzer,file,parameter,"value.","getPets");

            String field="package lib; class Use { Sample field; Object f(){ return field.; } }";
            Files.writeString(file,field);analyzer.changed(file);
            assertZeroJavac(analyzer,file,field,"field.","getPets");

            String statik="package lib; class Use { Object f(){ return Sample.; } }";
            Files.writeString(file,statik);analyzer.changed(file);
            assertZeroJavac(analyzer,file,statik,"Sample.","staticValue");

            String chained="package lib; class Use { Object f(Sample value){ return value.getModel().; } }";
            Files.writeString(file,chained);analyzer.changed(file);
            assertZeroJavac(analyzer,file,chained,"value.getModel().","getName");

            @SuppressWarnings("unchecked")
            var resident=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(((Number)resident.get("semantic_facts")).longValue()).isZero();
            assertThat(index.store().status()).containsEntry("owner_prefix_queries",5L);
        }
    }

    private static void assertZeroJavac(Analyzer analyzer,Path file,String source,String needle,String expected)throws Exception{
        long before=((Number)analyzer.status().get("queries")).longValue();
        int cursor=source.indexOf(needle)+needle.length();
        var position=dev.jvmd.core.Documents.position(source,cursor);
        var answer=analyzer.completion(file,source,position.line(),position.character(),100,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        JsonNode result=Json.MAPPER.valueToTree(answer.result());
        assertThat(result.path("items").findValuesAsText("name")).contains(expected);
        assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(before);
    }
}
