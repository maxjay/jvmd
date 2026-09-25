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
        String first="class A { int one(){return 1;} int two(){return 2;} int foo(Object value){return 3;} int bar(){return 4;} }";
        String caller="class B { int one(A a){return a.one();} int foo(A a){return a.foo(\"x\");} }";
        Files.writeString(a,first);Files.writeString(b,caller);
        var documents=new Documents();documents.open(a,first,1);

        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,a,first)).isEmpty();
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();

            String twoChanged=first.replace("int two(){return 2;}","String two(){return \"two\";}");
            mutate(analyzer,documents,a,2,twoChanged);
            long afterTwo=queries(analyzer);
            assertThat(evidence(analyzer)).containsEntry("last_proof_consumers_visited",0L)
                    .containsEntry("last_source_consumers_invalidated",0L)
                    .containsEntry("last_coarse_fallback_files",0L)
                    .containsEntry("last_pre_proof_dependant_invalidations",0L);
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            assertThat(queries(analyzer)).as("A.two must not reanalyse B which depends on A.one/A.foo").isEqualTo(afterTwo);

            String barChanged=twoChanged.replace("int bar(){return 4;}","String bar(){return \"bar\";}");
            mutate(analyzer,documents,a,3,barChanged);
            long afterBar=queries(analyzer);
            assertThat(evidence(analyzer)).containsEntry("last_proof_consumers_visited",0L)
                    .containsEntry("last_source_consumers_invalidated",0L)
                    .containsEntry("last_coarse_fallback_files",0L)
                    .containsEntry("last_pre_proof_dependant_invalidations",0L);
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            assertThat(queries(analyzer)).as("A.bar must not reanalyse a caller of A.foo").isEqualTo(afterBar);

            String overload=barChanged.replace(
                    "int foo(Object value){return 3;}",
                    "int foo(Object value){return 3;} int foo(String value){return 5;}");
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

    @Test void bodyOnlyMutationAttributesOnlyChangedSource()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java");
        String first="class A { int one(){return 1;} }";
        String caller="class B { int f(A a){return a.one();} }";
        Files.writeString(a,first);Files.writeString(b,caller);
        var documents=new Documents();documents.open(a,first,1);

        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,a,first)).isEmpty();
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            long before=queries(analyzer);
            String changed=first.replace("return 1","return 2");
            mutate(analyzer,documents,a,2,changed);
            var proof=evidence(analyzer);
            assertThat(proof).containsEntry("last_pre_proof_dependant_invalidations",0L)
                    .containsEntry("last_leaves_published",0L)
                    .containsEntry("last_source_consumers_invalidated",0L)
                    .containsEntry("last_coarse_fallback_files",0L)
                    .containsEntry("last_pre_proof_dependant_invalidations",0L);
            long afterSource=queries(analyzer);
            assertThat(afterSource).isEqualTo(before+1);
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            assertThat(queries(analyzer)).as("body-only edit must not attribute dependant B").isEqualTo(afterSource);
        }
    }

    @Test void relevantExactInstanceMemberChangeReconsidersDirectConsumer()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java");
        String first="class A { int one(){return 1;} int two(){return 2;} }";
        String caller="class B { int f(A a){return a.one();} }";
        Files.writeString(a,first);Files.writeString(b,caller);
        var documents=new Documents();documents.open(a,first,1);

        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,a,first)).isEmpty();
            assertThat(diagnostics(analyzer,b,caller)).isEmpty();
            String changed=first.replace("int one(){return 1;}","String one(){return \"one\";}");
            mutate(analyzer,documents,a,2,changed);
            var proof=evidence(analyzer);
            assertThat(proof).containsEntry("last_pre_proof_dependant_invalidations",0L)
                    .containsEntry("last_source_consumers_invalidated",1L)
                    .containsEntry("last_coarse_fallback_files",0L);
            assertThat(((Number)proof.get("last_proof_consumers_visited")).longValue()).isPositive();
            assertThat(((Number)proof.get("last_proof_consumers_changed")).longValue()).isPositive();
            long beforeCaller=queries(analyzer);
            assertThat(diagnostics(analyzer,b,caller)).anyMatch(problem->problem.code().startsWith("compiler.err.prob.found.req"));
            assertThat(queries(analyzer)).as("changed A.one exact fact must reconsider B").isEqualTo(beforeCaller+1);
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
                    .containsEntry("last_coarse_fallback_files",0L)
                    .containsEntry("last_pre_proof_dependant_invalidations",0L);
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
                    .containsEntry("last_coarse_fallback_files",0L)
                    .containsEntry("last_pre_proof_dependant_invalidations",0L);
            long beforeCompletion=queries(analyzer);
            assertThat(completion(analyzer,use,source).path("items").findValuesAsText("name"))
                    .contains("getOne","getSomething").doesNotContain("setSomething");
            assertThat(queries(analyzer)).as("matching get* range update remains maintained-state query")
                    .isEqualTo(beforeCompletion);
        }
    }

    @Test void namespaceMutationReconsidersOnlySearchedDomains()throws Exception{
        Path q=Files.createDirectories(root.resolve("q")).resolve("Widget.java");
        Path r=Files.createDirectories(root.resolve("r")).resolve("Types.java");
        Path other=Files.createDirectories(root.resolve("s")).resolve("Types.java");
        Path use=Files.createDirectories(root.resolve("p")).resolve("Use.java");
        String qSource="package q; public class Widget { public int get(){return 1;} protected int hidden(){return 2;} }";
        String rSource="package r; class Other {}";
        String otherSource="package s; class Other {}";
        String useSource="package p; import q.*; import r.*; class Use { Object f(Widget value){ return value.; } }";
        Files.writeString(q,qSource);Files.writeString(r,rSource);Files.writeString(other,otherSource);Files.writeString(use,useSource);
        var documents=new Documents();documents.open(q,qSource,1);documents.open(r,rSource,1);documents.open(other,otherSource,1);

        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,q,qSource)).isEmpty();
            assertThat(diagnostics(analyzer,r,rSource)).isEmpty();
            assertThat(diagnostics(analyzer,other,otherSource)).isEmpty();
            assertThat(completionAt(analyzer,use,useSource,"value.").path("items").findValuesAsText("name")).contains("get");
            long warm=queries(analyzer);

            String unrelated="package s; class Random {}";
            mutate(analyzer,documents,other,2,unrelated);
            assertThat(evidence(analyzer)).containsEntry("last_proof_consumers_visited",0L)
                    .containsEntry("last_coarse_fallback_files",0L);
            long beforeUnrelated=queries(analyzer);
            assertThat(completionAt(analyzer,use,useSource,"value.").path("items").findValuesAsText("name")).contains("get");
            assertThat(queries(analyzer)).as("unrelated package mutation must not re-run name resolution javac")
                    .isEqualTo(beforeUnrelated);
            assertThat(beforeUnrelated).isEqualTo(warm+1);

            String relevant="package r; class Widget {}";
            mutate(analyzer,documents,r,2,relevant);
            var proof=evidence(analyzer);
            assertThat(((Number)proof.get("last_proof_consumers_visited")).longValue()).isPositive();
            assertThat(((Number)proof.get("last_proof_consumers_changed")).longValue()).isPositive();
            assertThat(proof).containsEntry("last_coarse_fallback_files",0L);
            long beforeRelevant=queries(analyzer);
            completionAt(analyzer,use,useSource,"value.");
            assertThat(queries(analyzer)).as("new Widget in a searched wildcard namespace must re-run semantic resolution")
                    .isGreaterThan(beforeRelevant);
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
        return completionAt(analyzer,file,source,"project.get");
    }

    private static JsonNode completionAt(Analyzer analyzer,Path file,String source,String needle)throws Exception{
        int cursor=source.indexOf(needle)+needle.length();
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
