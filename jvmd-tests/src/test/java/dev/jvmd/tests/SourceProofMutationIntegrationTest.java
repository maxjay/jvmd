package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.*;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class SourceProofMutationIntegrationTest {
    @TempDir Path root;

    @Test void unrelatedExactAndOverloadChangesDoNotReanalyseProofCoveredCallerButRelevantOverloadDoes()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java");
        String first="class A { static int one(){return 1;} static int two(){return 2;} static int foo(Object value){return 3;} static int bar(){return 4;} }";
        String caller="class B { int one(){return A.one();} int foo(){return A.foo(\"x\");} }";
        Files.writeString(a,first);Files.writeString(b,caller);
        var documents=new Documents();documents.open(a,first,1);

        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,a,first)).isEmpty();
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();

            String twoChanged=first.replace("static int two(){return 2;}","static String two(){return \"two\";}");
            mutate(analyzer,documents,a,2,twoChanged);
            long afterTwo=queries(analyzer);
            assertThat(evidence(analyzer)).containsEntry("last_proof_consumers_visited",0L)
                    .containsEntry("last_source_consumers_invalidated",0L)
                    .containsEntry("last_coarse_fallback_files",0L);
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            assertThat(queries(analyzer)).as("A.two must not reanalyse B which depends on A.one/A.foo").isEqualTo(afterTwo);

            String barChanged=twoChanged.replace("static int bar(){return 4;}","static String bar(){return \"bar\";}");
            mutate(analyzer,documents,a,3,barChanged);
            long afterBar=queries(analyzer);
            assertThat(evidence(analyzer)).containsEntry("last_proof_consumers_visited",0L)
                    .containsEntry("last_source_consumers_invalidated",0L)
                    .containsEntry("last_coarse_fallback_files",0L);
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            assertThat(queries(analyzer)).as("A.bar must not reanalyse a caller of A.foo").isEqualTo(afterBar);

            String overload=barChanged.replace(
                    "static int foo(Object value){return 3;}",
                    "static int foo(Object value){return 3;} static int foo(String value){return 5;}");
            mutate(analyzer,documents,a,4,overload);
            var proof=evidence(analyzer);
            assertThat(((Number)proof.get("last_proof_consumers_visited")).longValue()).isPositive();
            assertThat(((Number)proof.get("last_proof_consumers_changed")).longValue()).isPositive();
            assertThat(proof).containsEntry("last_source_consumers_invalidated",1L)
                    .containsEntry("last_coarse_fallback_files",0L);
            long beforeCaller=queries(analyzer);
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            assertThat(queries(analyzer)).as("new A.foo(String) must reconsider B's resolved call").isEqualTo(beforeCaller+1);
        }
    }

    @Test void maintainedCompletionRangeIgnoresDisjointMemberAndChangesForMatchingPrefix()throws Exception{
        Path project=root.resolve("Project.java"),use=root.resolve("Use.java");
        String first="class Project { int getOne(){return 1;} int setOne(){return 2;} }";
        String source="class Use { Object f(Project project){ return project.get; } }";
        Files.writeString(project,first);Files.writeString(use,source);
        var documents=new Documents();documents.open(project,first,1);

        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,project,first)).isEmpty();
            assertThat(completion(analyzer,use,source).path("items").findValuesAsText("name")).contains("getOne");
            long afterWarmCompletion=queries(analyzer);

            String disjoint=insertBeforeLastBrace(first," int setSomething(){return 3;} ");
            mutate(analyzer,documents,project,2,disjoint);
            assertThat(evidence(analyzer)).containsEntry("last_proof_consumers_visited",0L)
                    .containsEntry("last_source_consumers_invalidated",0L)
                    .containsEntry("last_coarse_fallback_files",0L);
            assertThat(completion(analyzer,use,source).path("items").findValuesAsText("name"))
                    .contains("getOne").doesNotContain("setSomething");
            assertThat(queries(analyzer)).as("disjoint set* mutation must keep maintained get* completion zero-javac")
                    .isEqualTo(afterWarmCompletion+1); // only Project mutation attribution

            String relevant=insertBeforeLastBrace(disjoint," int getSomething(){return 4;} ");
            mutate(analyzer,documents,project,3,relevant);
            var proof=evidence(analyzer);
            assertThat(((Number)proof.get("last_proof_consumers_visited")).longValue()).isPositive();
            assertThat(((Number)proof.get("last_proof_consumers_changed")).longValue()).isPositive();
            assertThat(proof).containsEntry("last_source_consumers_invalidated",0L)
                    .containsEntry("last_coarse_fallback_files",0L);
            long beforeCompletion=queries(analyzer);
            assertThat(completion(analyzer,use,source).path("items").findValuesAsText("name"))
                    .contains("getOne","getSomething").doesNotContain("setSomething");
            assertThat(queries(analyzer)).as("matching get* range update remains maintained-state query")
                    .isEqualTo(beforeCompletion);
        }
    }

    private static String insertBeforeLastBrace(String source,String text){
        int end=source.lastIndexOf('}');if(end<0)throw new IllegalArgumentException("missing class brace");
        return source.substring(0,end)+text+source.substring(end);
    }

    private Analyzer analyzer(Documents documents)throws Exception{
        var analyzer=new Analyzer();
        analyzer.configure(new Analyzer.Context("fixture:source-proof:1","25",List.of(),List.of(root),"source-proof",Map.of()),null,256L*1024*1024);
        analyzer.documents(documents);return analyzer;
    }

    @SuppressWarnings("unchecked")
    private static List<CompilerPool.Problem> diagnostics(Analyzer analyzer,Path file,String text)throws Exception{
        var envelope=analyzer.diagnostics(file,text);
        return (List<CompilerPool.Problem>)((Map<String,Object>)envelope.result()).get("diagnostics");
    }

    private static void mutate(Analyzer analyzer,Documents documents,Path file,int version,String text)throws Exception{
        documents.change(file,version,List.of(new Documents.Change(null,text)));analyzer.documents(documents);
        analyzer.changed(file,documents.hash(file));assertThat(diagnostics(analyzer,file,text)).isEmpty();
    }

    private static JsonNode completion(Analyzer analyzer,Path file,String source)throws Exception{
        int cursor=source.indexOf("project.get")+"project.get".length();
        var position=Documents.position(source,cursor);
        var answer=analyzer.completion(file,source,position.line(),position.character(),100,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        return Json.MAPPER.valueToTree(answer.result());
    }

    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}

    @SuppressWarnings("unchecked")
    private static Map<String,Object> evidence(Analyzer analyzer){
        return (Map<String,Object>)analyzer.status().get("source_proof_evidence");
    }
}
