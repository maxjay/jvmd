package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.Documents;
import dev.jvmd.dist.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.2 and 4.8: unchanged traversals reuse detached graphs and every changed input invalidates them. */
@Tag("phase-4") @Tag("phase-8")
class WorkspaceBindingsCacheTest {
    @TempDir Path root;
    private static JsonNode request(Application app,String method,Map<String,Object> params)throws Exception{
        var response=TestSupport.complete(app.dispatcher(),method,params);assertThat(response.has("error")).as(response.toString()).isFalse();return response.path("result").path("result");
    }
    private static JsonNode references(Application app,String session)throws Exception{
        return request(app,"symbol.references",Map.of("session",session,"ref","Caller/call()","direction","out"));
    }
    private static long builds(Application app,String session)throws Exception{
        return request(app,"session.status",Map.of("session",session)).path("workspace_bindings").path("builds").asLong();
    }
    @Test void sourceAndEditorChangesCannotReuseAnOldGraph()throws Exception{
        Path api=root.resolve("Api.java"),caller=root.resolve("Caller.java");
        Files.writeString(api,"class Api { static int a(){return 1;} static int b(){return 2;} }");
        String first="class Caller { int call(){return Api.a();} }",second=first.replace("Api.a()","Api.b()");Files.writeString(caller,first);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            assertThat(references(app,session).path("edges").toString()).contains("Api#a().");
            long initial=builds(app,session);references(app,session);assertThat(builds(app,session)).isEqualTo(initial);
            var bound=request(app,"symbol.atPosition",Map.of("session",session,"path",caller.toString(),"line",0,"character",first.indexOf("a()")));
            String identity=bound.path("scip").asText();assertThat(identity).endsWith("Api#a().");
            long queries=request(app,"session.status",Map.of("session",session)).path("analyzer").path("queries").asLong();
            for(int i=0;i<4;i++){
                var described=request(app,"symbol.describe",Map.of("session",session,"ref",identity));assertThat(described.path("file").asText()).isEqualTo(api.toString());
                assertThat(described.path("name_start").asInt(-1)).isGreaterThanOrEqualTo(0);
            }
            assertThat(request(app,"session.status",Map.of("session",session)).path("analyzer").path("queries").asLong()).isEqualTo(queries);
            var stamp=Files.getLastModifiedTime(caller);Files.writeString(caller,second);Files.setLastModifiedTime(caller,stamp);
            assertThat(references(app,session).path("edges").toString()).contains("Api#b().").doesNotContain("Api#a().");assertThat(builds(app,session)).isGreaterThan(initial);
            request(app,"document.open",Map.of("session",session,"path",caller.toString(),"version",1,"text",first));
            assertThat(references(app,session).path("edges").toString()).contains("Api#a().").doesNotContain("Api#b().");
            request(app,"document.close",Map.of("session",session,"path",caller.toString()));
            assertThat(references(app,session).path("edges").toString()).contains("Api#b().");
            Path additional=root.resolve("Additional.java");Files.writeString(additional,"class Additional { int read(){return new Caller().call();} }");
            var incoming=request(app,"symbol.references",Map.of("session",session,"ref","Caller/call()","direction","in"));
            assertThat(incoming.path("edges").toString()).contains("Additional#read().");
            Files.delete(additional);
            assertThat(request(app,"symbol.references",Map.of("session",session,"ref","Caller/call()","direction","in")).path("edges").toString()).doesNotContain("Additional#");
            request(app,"document.open",Map.of("session",session,"path",additional.toString(),"version",1,"text","class Additional { int read(){return new Caller().call();} }"));
            assertThat(request(app,"symbol.references",Map.of("session",session,"ref","Caller/call()","direction","in")).path("edges").toString()).contains("Additional#read().");
            request(app,"document.close",Map.of("session",session,"path",additional.toString()));
            assertThat(request(app,"symbol.references",Map.of("session",session,"ref","Caller/call()","direction","in")).path("edges").toString()).doesNotContain("Additional#");
        }
    }
    @Test void incrementalFragmentsReanalyseOnlyBodyEditsAndApiDependants()throws Exception{
        Path api=root.resolve("Api.java"),user=root.resolve("User.java"),other=root.resolve("Other.java");
        Files.writeString(api,"class Api { static Number value(){return 1;} }");
        Files.writeString(user,"class User { Object read(){return Api.value();} }");
        Files.writeString(other,"class Other { int read(){return 1;} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var initial=request(app,"symbol.references",Map.of("session",session,"ref","Api/value()","direction","in"));
            assertThat(initial.path("edges").toString()).contains("User#read().");
            Files.writeString(other,"class Other { int read(){int x=1; return x;} }");
            request(app,"symbol.references",Map.of("session",session,"ref","Api/value()","direction","in"));
            var body=request(app,"session.status",Map.of("session",session)).path("workspace_bindings");
            assertThat(body.path("last_reanalysed_files").asLong()).isEqualTo(1L);
            assertThat(body.path("incremental_builds").asLong()).isGreaterThanOrEqualTo(1L);
            Files.writeString(api,"class Api { static Integer value(){return 2;} }");
            request(app,"symbol.references",Map.of("session",session,"ref","Api/value()","direction","in"));
            var changed=request(app,"session.status",Map.of("session",session)).path("workspace_bindings");
            assertThat(changed.path("last_reanalysed_files").asLong()).isEqualTo(2L);
            assertThat(changed.path("api_invalidations").asLong()).isGreaterThanOrEqualTo(1L);
        }
    }
    @Test void apiAdditionsReanalysePriorErrorsWithoutOldDependencyEdges()throws Exception{
        Path api=root.resolve("Api.java"),broken=root.resolve("Broken.java");
        Files.writeString(api,"class Api {}");
        Files.writeString(broken,"class Broken { Api.Missing value; }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            request(app,"symbol.references",Map.of("session",session,"ref","Api","direction","in"));
            Files.writeString(api,"class Api { static class Missing {} }");
            var resolved=request(app,"symbol.references",Map.of("session",session,"ref","Api/Missing","direction","in","kinds",List.of("return_type")));
            assertThat(resolved.path("edges").toString()).contains("Broken#value.").contains("Api#Missing");
            var status=request(app,"session.status",Map.of("session",session)).path("workspace_bindings");
            assertThat(status.path("last_reanalysed_files").asLong()).isEqualTo(2L);
        }
    }
    @Test void resolvedWorkspaceAdoptsMerkleStateWithoutWarmSourceRescan()throws Exception{
        Path project=MavenFixtures.project(root.resolve("merkle-project"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties>");
        Path src=Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(src.resolve("Api.java"),"class Api { static int value(){return 1;} }");
        Files.writeString(src.resolve("User.java"),"class User { int read(){return Api.value();} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,project);
            var cold=request(app,"symbol.references",Map.of("session",session,"ref","Api/value()","direction","in"));
            assertThat(cold.path("edges").toString()).contains("User#read().");
            var before=request(app,"session.status",Map.of("session",session)).path("workspace_bindings");
            long full=before.path("full_validations").asLong(),fast=before.path("fast_validation_hits").asLong();
            var warm=request(app,"symbol.references",Map.of("session",session,"ref","Api/value()","direction","in"));
            assertThat(warm.path("edges").toString()).contains("User#read().");
            var after=request(app,"session.status",Map.of("session",session)).path("workspace_bindings");
            assertThat(after.path("full_validations").asLong()).isEqualTo(full);
            assertThat(after.path("fast_validation_hits").asLong()).isEqualTo(fast+1);
            assertThat(after.path("last_reanalysed_files").asLong()).isZero();
        }
    }
    @Test void replacedBinaryAndFailedLookupRecoveryAreObserved()throws Exception{
        Path source=Files.createDirectories(root.resolve("source")),binary=Files.createDirectories(root.resolve("binary"));
        Path api=root.resolve("Api.java"),caller=source.resolve("Caller.java");
        String text="class Caller { int call(){return Api.a();} }";Files.writeString(caller,text);
        Files.writeString(api,"public class Api { public static int a(){return 1;} }");compile(api,binary);
        var documents=new Documents();
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(binary),List.of(source),"1",Map.of(source.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            WorkspaceBindings.Loader loader=(file,value)->analyzer.bindings(file,value,null);
            assertThat(cache.get(()->List.of(caller),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(binary),List.of("--release","25")),documents,16L*1024*1024,loader).diagnostics()).isEmpty();
            Path classFile=binary.resolve("Api.class");var stamp=Files.getLastModifiedTime(classFile);
            Files.writeString(api,"public class Api { public static int b(){return 1;} }");compile(api,binary);Files.setLastModifiedTime(classFile,stamp);
            assertThat(cache.get(()->List.of(caller),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(binary),List.of("--release","25")),documents,16L*1024*1024,loader).diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
            Files.writeString(api,"public class Api { public static int a(){return 1;} }");compile(api,binary);Files.setLastModifiedTime(classFile,stamp);
            assertThat(cache.get(()->List.of(caller),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(binary),List.of("--release","25")),documents,16L*1024*1024,loader).diagnostics()).isEmpty();
        }
    }
    private static void compile(Path source,Path output){assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",output.toString(),source.toString())).isZero();}
}
