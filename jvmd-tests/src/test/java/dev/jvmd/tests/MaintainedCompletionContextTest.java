package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Json;
import dev.jvmd.index.IndexService;
import dev.jvmd.index.IndexStore;
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
                class Base {
                    public int inherited(){return 7;}
                }
                public class Sample extends Base {
                    public int getPets(){return 1;}
                    public static int staticValue(){return 2;}
                    private int hidden(){return 3;}
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
            var sample=index.store().semanticTypesByName("Sample","w",10,IndexStore.SemanticLayer.MACHINE).stream()
                    .filter(value->value.fqn().equals("lib.Sample")).findFirst().orElseThrow();
            var sampleMembers=index.store().semanticMembersByOwner(sample.id(),"get","w",20,null,IndexStore.SemanticLayer.MACHINE);
            assertThat(sampleMembers.symbols()).extracting(IndexStore.IndexedSemanticSymbol::name)
                    .contains("getPets","getModel");
            assertThat(sampleMembers.symbols()).allMatch(value->value.resolution().modifiers().contains("public"));
            analyzer.configure(new Analyzer.Context(
                    "fixture:app:1","25",List.of(jar),List.of(root.resolve("src")),"maintained",Map.of(),
                    List.of("--release","25"),Set.of(),List.of(),List.of(root.resolve("src")),true,"w"),
                    index,256L*1024*1024);

            String parameter="package lib; class Use { Object f(Sample value){ return value.; } }";
            Path file=Files.writeString(sources.resolve("Use.java"),parameter);
            assertZeroJavac(analyzer,file,parameter,"value.","getPets","inherited");

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
        }
    }

    @Test void admittedLocalReceiverCompletesFromResidentFactsWithZeroQuerySideJavac()throws Exception{
        Path sourceRoot=Files.createDirectories(root.resolve("local-src/local"));
        String apiText="package local; class Api { int getLocal(){return 1;} }";
        Path api=Files.writeString(sourceRoot.resolve("Api.java"),apiText);
        String useText="package local; class Use { Object f(Api value){ return value.; } }";
        Path use=Files.writeString(sourceRoot.resolve("Use.java"),useText);

        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context(
                    "fixture:local:1","25",List.of(),List.of(root.resolve("local-src")),"local-maintained",Map.of()),
                    null,256L*1024*1024);

            var admitted=analyzer.bindings(api,apiText,null);
            assertThat(admitted.tier()).isEqualTo(2);
            assertThat(admitted.warnings()).isEmpty();
            @SuppressWarnings("unchecked")
            var resident=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(((Number)resident.get("semantic_facts")).longValue()).isPositive();

            assertZeroJavac(analyzer,use,useText,"value.","getLocal");
        }
    }

    @Test void projectDotResolvesMavenProjectFromMachineRangeWithZeroQuerySideJavac()throws Exception{
        Path repo=Files.createDirectories(root.resolve("maven-repo"));
        Path jar=IndexFixtures.jar(repo,"maven-project","MavenProject.java","""
                package org.apache.maven.project;
                public class MavenProject {
                    public String getArtifactId(){return "artifact";}
                    public String getGroupId(){return "group";}
                }
                """,true);
        Path sources=Files.createDirectories(root.resolve("maven-src/org/apache/maven/project"));
        String source="""
                package org.apache.maven.project;
                class Use {
                    Object inspect(MavenProject project) {
                        return project.;
                    }
                }
                """;
        Path file=Files.writeString(sources.resolve("Use.java"),source);
        try(var index=new IndexService(root.resolve("maven-index.db"),repo);
            var analyzer=new Analyzer()){
            index.indexJar(jar,"org.apache.maven:maven-core:fixture","jar");
            index.loadWorkspace("maven-workspace",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            var project=index.store().semanticTypesByName("MavenProject","maven-workspace",10,IndexStore.SemanticLayer.MACHINE).stream()
                    .filter(value->value.fqn().equals("org.apache.maven.project.MavenProject")).findFirst().orElseThrow();
            var projectMembers=index.store().semanticMembersByOwner(project.id(),"get","maven-workspace",20,null,IndexStore.SemanticLayer.MACHINE);
            assertThat(projectMembers.symbols()).extracting(IndexStore.IndexedSemanticSymbol::name)
                    .contains("getArtifactId","getGroupId");
            assertThat(projectMembers.symbols()).allMatch(value->value.resolution().modifiers().contains("public"));
            analyzer.configure(new Analyzer.Context(
                    "demo:app:1","25",List.of(jar),List.of(root.resolve("maven-src")),"maven-maintained",Map.of(),
                    List.of("--release","25"),Set.of(),List.of(),List.of(root.resolve("maven-src")),true,"maven-workspace"),
                    index,256L*1024*1024);

            long before=((Number)analyzer.status().get("queries")).longValue();
            int cursor=source.indexOf("project.")+"project.".length();
            var position=dev.jvmd.core.Documents.position(source,cursor);
            var answer=analyzer.completion(file,source,position.line(),position.character(),100,0);
            JsonNode result=Json.MAPPER.valueToTree(answer.result());

            assertThat(answer.warnings()).isEmpty();
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(before);
            assertThat(result.path("items").findValuesAsText("name")).contains("getArtifactId","getGroupId");
            @SuppressWarnings("unchecked")
            var resident=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(((Number)resident.get("semantic_facts")).longValue()).isZero();
        }
    }

    private static void assertZeroJavac(Analyzer analyzer,Path file,String source,String needle,String... expected)throws Exception{
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
