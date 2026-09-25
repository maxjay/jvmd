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
class ClasspathLifecycleIntegrationTest {
    @TempDir Path root;

    @Test void realConfigureTransitionUsesClasspathSearchFixedPoints()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path a=IndexFixtures.jar(repo,"a","package a; public class Sample { public int getA(){return 1;} }",true);
        Path b=IndexFixtures.jar(repo,"b","package b; public class Sample { public int getB(){return 2;} }",true);
        Path c=IndexFixtures.jar(repo,"c","package c; public class Sample { public int getC(){return 3;} }",true);
        Path sources=Files.createDirectories(root.resolve("src"));
        Path file=sources.resolve("Use.java");

        try(var index=new IndexService(root.resolve("index.db"),repo);
            var analyzer=new Analyzer()){
            for(var artifact:List.of(
                    Map.entry(a,"fixture:a:1"),
                    Map.entry(b,"fixture:b:1"),
                    Map.entry(c,"fixture:c:1")))
                index.indexJar(artifact.getKey(),artifact.getValue(),"jar");
            load(index,a,b,c);

            String aSource="class Use { a.Sample choose(a.Sample value){return value;} Object f(a.Sample value){return choose(value).get;} }";
            Files.writeString(file,aSource);var documents=new Documents();documents.open(file,aSource,1);
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g1");
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            assertThat(currentQueries(analyzer)).isPositive();

            // C changes structurally, but the A-winning search examined only slot 0.
            c=IndexFixtures.jar(repo,"c","package c; public class Sample { public int getC(){return 3;} public int extra(){return 4;} }",true);
            index.indexJar(c,"fixture:c:1","jar");load(index,a,b,c);
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g2");
            assertThat(currentQueries(analyzer)).isZero();
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            assertThat(currentQueries(analyzer)).as("later C change must not re-run javac for A-winning context").isZero();

            // Establish a C-winning context. The first request on its fresh compiler is the one bounded fallback.
            String cSource="class Use { c.Sample choose(c.Sample value){return value;} Object f(c.Sample value){return choose(value).get;} }";
            Files.writeString(file,cSource);documents.change(file,2,List.of(new Documents.Change(null,cSource)));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertNames(analyzer,file,cSource,"choose(value).get","getC");
            long cQueries=currentQueries(analyzer);
            assertThat(cQueries).isPositive();

            // C changes again, but its declaration-resolution identity is equal. The search proof is
            // reconsidered by the structural diff and reaches a fixed point before the ProofDag.
            c=IndexFixtures.jar(repo,"c","package c; public class Sample { public int getC(){return 5;} public int unrelated(){return 6;} }",true);
            index.indexJar(c,"fixture:c:1","jar");load(index,a,b,c);
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g3");
            assertThat(currentQueries(analyzer)).isZero();
            assertNames(analyzer,file,cSource,"choose(value).get","getC");
            assertThat(currentQueries(analyzer)).as("equal C search proof must stop before semantic re-attribution").isZero();

            // Changing the winning type declaration changes the CLASSPATH_SEARCH leaf. That changed
            // leaf flows through the production ProofDag and invalidates only this proof-backed context.
            c=IndexFixtures.jar(repo,"c","package c; public final class Sample { public int getC(){return 7;} }",true);
            index.indexJar(c,"fixture:c:1","jar");load(index,a,b,c);
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g4");
            assertThat(currentQueries(analyzer)).isZero();
            assertNames(analyzer,file,cSource,"choose(value).get","getC");
            assertThat(currentQueries(analyzer)).as("changed C winner must force one bounded context re-attribution").isEqualTo(1);
        }
    }

    private static void load(IndexService index,Path a,Path b,Path c)throws Exception{
        index.loadWorkspace("w",List.of(
                new IndexService.WorkspaceArtifact(a.toString(),"compile"),
                new IndexService.WorkspaceArtifact(b.toString(),"compile"),
                new IndexService.WorkspaceArtifact(c.toString(),"compile")),List.of());
    }

    private static void configure(Analyzer analyzer,IndexService index,Documents documents,Path sources,List<Path> classpath,String generation)throws Exception{
        analyzer.configure(new Analyzer.Context(
                "fixture:app:1","25",classpath,List.of(sources),generation,Map.of(),
                List.of("--release","25"),Set.of(),List.of(),List.of(sources),true,"w"),
                index,256L*1024*1024);
        analyzer.documents(documents);
    }

    private static void assertNames(Analyzer analyzer,Path file,String source,String needle,String expected)throws Exception{
        int cursor=source.indexOf(needle)+needle.length();
        var position=Documents.position(source,cursor);
        var answer=analyzer.completion(file,source,position.line(),position.character(),100,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        JsonNode result=Json.MAPPER.valueToTree(answer.result());
        assertThat(result.path("items").findValuesAsText("name")).contains(expected);
    }

    private static long currentQueries(Analyzer analyzer){
        return ((Number)analyzer.status().get("queries")).longValue();
    }
}
