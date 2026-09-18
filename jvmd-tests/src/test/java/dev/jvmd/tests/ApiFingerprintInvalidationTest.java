package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.Documents;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** API fingerprints keep reverse dependants warm for implementation-only edits. */
@Tag("phase-4")
class ApiFingerprintInvalidationTest {
    @TempDir Path root;

    @Test void bodyOnlyEditDoesNotAttributeDependantsButSignatureEditDoes()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String original="class Api { int value() { return 1; } }";
        String useText="class Use { int use() { return new Api().value(); } }";
        Files.writeString(api,original);Files.writeString(use,useText);
        var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"ctx",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            analyzer.documents(documents);
            assertThat(diagnostics(analyzer,api,original)).isEmpty();
            assertThat(diagnostics(analyzer,use,useText)).isEmpty();
            long warmQueries=number(analyzer,"queries");

            String bodyOnly="class Api { int value() { return 2; } }";
            documents.change(api,2,List.of(new Documents.Change(null,bodyOnly)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,bodyOnly)).isEmpty();
            long afterBody=number(analyzer,"queries");
            assertThat(afterBody).isEqualTo(warmQueries+1);
            assertThat(diagnostics(analyzer,use,useText)).isEmpty();
            assertThat(number(analyzer,"queries")).as("body-only dependant attribution").isEqualTo(afterBody);
            assertThat(number(analyzer,"api_fingerprint_unchanged")).isEqualTo(1L);

            String signature="class Api { String value() { return \"2\"; } }";
            documents.change(api,3,List.of(new Documents.Change(null,signature)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,signature)).isEmpty();
            long afterApi=number(analyzer,"queries");
            assertThat(afterApi).isEqualTo(afterBody+1);
            assertThat(number(analyzer,"api_fingerprint_changes")).isEqualTo(1L);
            assertThat(diagnostics(analyzer,use,useText)).anyMatch(problem->problem.code().startsWith("compiler.err.prob.found.req"));
            assertThat(number(analyzer,"queries")).as("API-change dependant attribution").isEqualTo(afterApi+1);
        }
    }

    @Test void superclassChangeInvalidatesUsers()throws Exception{
        Path first=root.resolve("First.java"),second=root.resolve("Second.java"),api=root.resolve("Api.java"),use=root.resolve("Use.java");
        Files.writeString(first,"class First { int value(){return 1;} }");Files.writeString(second,"class Second { String value(){return \"x\";} }");
        String original="class Api extends First {}",useText="class Use { int n=new Api().value(); }";
        Files.writeString(api,original);Files.writeString(use,useText);var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,api,original)).isEmpty();assertThat(diagnostics(analyzer,use,useText)).isEmpty();long before=number(analyzer,"queries");
            String changed="class Api extends Second {}";documents.change(api,2,List.of(new Documents.Change(null,changed)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,changed)).isEmpty();assertThat(number(analyzer,"api_fingerprint_changes")).isEqualTo(1L);
            assertThat(diagnostics(analyzer,use,useText)).anyMatch(problem->problem.code().startsWith("compiler.err.prob.found.req"));
            assertThat(number(analyzer,"queries")).isEqualTo(before+2);
        }
    }

    @Test void interfaceMethodChangeInvalidatesImplementations()throws Exception{
        Path contract=root.resolve("Contract.java"),impl=root.resolve("Impl.java");
        String original="interface Contract { int value(); }",implementation="class Impl implements Contract { public int value(){return 1;} }";
        Files.writeString(contract,original);Files.writeString(impl,implementation);var documents=new Documents();documents.open(contract,original,1);
        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,contract,original)).isEmpty();assertThat(diagnostics(analyzer,impl,implementation)).isEmpty();
            String changed="interface Contract { String value(); }";documents.change(contract,2,List.of(new Documents.Change(null,changed)));analyzer.documents(documents);analyzer.changed(contract);
            assertThat(diagnostics(analyzer,contract,changed)).isEmpty();
            assertThat(diagnostics(analyzer,impl,implementation)).anyMatch(problem->problem.kind().equals("ERROR"));
        }
    }

    @Test void addedOverloadsInvalidatePreviouslyResolvedCalls()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String original="class Api { static int pick(Object value){return 1;} }",useText="class Use { int n=Api.pick(null); }";
        Files.writeString(api,original);Files.writeString(use,useText);var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,api,original)).isEmpty();assertThat(diagnostics(analyzer,use,useText)).isEmpty();
            String changed="class Api { static int pick(Object value){return 1;} static int pick(String value){return 2;} static int pick(Integer value){return 3;} }";
            documents.change(api,2,List.of(new Documents.Change(null,changed)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,changed)).isEmpty();
            assertThat(diagnostics(analyzer,use,useText)).anyMatch(problem->problem.code().contains("ref.ambiguous"));
        }
    }

    @Test void staticImportTargetChangeInvalidatesTheImporter()throws Exception{
        Path pkg=Files.createDirectories(root.resolve("p")),api=pkg.resolve("Ops.java"),use=pkg.resolve("Use.java");
        String original="package p; public class Ops { public static int value(){return 1;} }";
        String useText="package p; import static p.Ops.value; class Use { int n=value(); }";
        Files.writeString(api,original);Files.writeString(use,useText);var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,api,original)).isEmpty();assertThat(diagnostics(analyzer,use,useText)).isEmpty();
            String changed="package p; public class Ops { public static int answer(){return 1;} }";
            documents.change(api,2,List.of(new Documents.Change(null,changed)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,changed)).isEmpty();
            assertThat(diagnostics(analyzer,use,useText)).anyMatch(problem->problem.code().contains("cant.resolve"));
        }
    }


    @Test void privateImplementationChangeKeepsDependantsWarm()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String original="class Api { private int secret(){return 1;} int value(){return 1;} }";
        String useText="class Use { int n=new Api().value(); }";
        Files.writeString(api,original);Files.writeString(use,useText);var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,api,original)).isEmpty();assertThat(diagnostics(analyzer,use,useText)).isEmpty();long before=number(analyzer,"queries");
            String changed="class Api { private String secret(){return \"changed\";} int value(){return 1;} }";
            documents.change(api,2,List.of(new Documents.Change(null,changed)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,changed)).isEmpty();
            long after=number(analyzer,"queries");assertThat(after).isEqualTo(before+1);
            assertThat(number(analyzer,"api_fingerprint_unchanged")).isEqualTo(1L);
            assertThat(diagnostics(analyzer,use,useText)).isEmpty();
            assertThat(number(analyzer,"queries")).as("private change dependant attribution").isEqualTo(after);
        }
    }

    @Test void packageMoveInvalidatesOldUsersAndReconsidersUnresolvedNewUsers()throws Exception{
        Path p=Files.createDirectories(root.resolve("p")),r=Files.createDirectories(root.resolve("r")),q=Files.createDirectories(root.resolve("q"));
        Path api=p.resolve("Api.java"),moved=r.resolve("Api.java"),oldUse=q.resolve("OldUse.java"),newUse=q.resolve("NewUse.java");
        String original="package p; public class Api {}";
        String oldText="package q; import p.Api; class OldUse { Api value; }";
        String newText="package q; import r.Api; class NewUse { Api value; }";
        Files.writeString(api,original);Files.writeString(oldUse,oldText);Files.writeString(newUse,newText);
        try(var analyzer=analyzer(new Documents())){
            assertThat(diagnostics(analyzer,api,original)).isEmpty();
            assertThat(diagnostics(analyzer,oldUse,oldText)).isEmpty();
            assertThat(diagnostics(analyzer,newUse,newText)).anyMatch(problem->problem.code().contains("cant.resolve"));
            String changed="package r; public class Api {}";
            Files.writeString(moved,changed);Files.delete(api);analyzer.namespaceChanged();
            assertThat(diagnostics(analyzer,moved,changed)).isEmpty();
            assertThat(diagnostics(analyzer,oldUse,oldText)).anyMatch(problem->problem.code().contains("cant.resolve"));
            assertThat(diagnostics(analyzer,newUse,newText)).isEmpty();
        }
    }

    @Test void newSourceReconsidersPreviouslyUnresolvedDiagnostics()throws Exception{
        Path use=root.resolve("Use.java"),added=root.resolve("Missing.java");
        String useText="class Use { Missing value; }";
        Files.writeString(use,useText);var documents=new Documents();
        try(var analyzer=analyzer(documents)){
            assertThat(diagnostics(analyzer,use,useText)).anyMatch(problem->problem.code().contains("cant.resolve"));
            String addedText="class Missing {}";Files.writeString(added,addedText);
            analyzer.changed(added);
            assertThat(diagnostics(analyzer,added,addedText)).isEmpty();
            assertThat(diagnostics(analyzer,use,useText)).isEmpty();
        }
    }

    private Analyzer analyzer(Documents documents)throws Exception{
        var analyzer=new Analyzer();analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"ctx",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);analyzer.documents(documents);return analyzer;
    }

    @SuppressWarnings("unchecked")
    private static List<CompilerPool.Problem> diagnostics(Analyzer analyzer,Path file,String text)throws Exception{
        var envelope=analyzer.diagnostics(file,text);return (List<CompilerPool.Problem>)((Map<String,Object>)envelope.result()).get("diagnostics");
    }
    private static long number(Analyzer analyzer,String key){return ((Number)analyzer.status().get(key)).longValue();}
}
