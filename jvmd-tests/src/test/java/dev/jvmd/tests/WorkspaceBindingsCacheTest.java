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
    @Test void replacedBinaryAndFailedLookupRecoveryAreObserved()throws Exception{
        Path source=Files.createDirectories(root.resolve("source")),binary=Files.createDirectories(root.resolve("binary"));
        Path api=root.resolve("Api.java"),caller=source.resolve("Caller.java");
        String text="class Caller { int call(){return Api.a();} }";Files.writeString(caller,text);
        Files.writeString(api,"public class Api { public static int a(){return 1;} }");compile(api,binary);
        var documents=new Documents();
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(binary),List.of(source),"1",Map.of(source.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            WorkspaceBindings.Loader loader=(file,value)->analyzer.bindings(file,value,null);
            assertThat(cache.get(()->List.of(caller),List.of(binary),documents,"1",16L*1024*1024,loader).diagnostics()).isEmpty();
            Path classFile=binary.resolve("Api.class");var stamp=Files.getLastModifiedTime(classFile);
            Files.writeString(api,"public class Api { public static int b(){return 1;} }");compile(api,binary);Files.setLastModifiedTime(classFile,stamp);
            assertThat(cache.get(()->List.of(caller),List.of(binary),documents,"1",16L*1024*1024,loader).diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
            Files.writeString(api,"public class Api { public static int a(){return 1;} }");compile(api,binary);Files.setLastModifiedTime(classFile,stamp);
            assertThat(cache.get(()->List.of(caller),List.of(binary),documents,"1",16L*1024*1024,loader).diagnostics()).isEmpty();
        }
    }
    private static void compile(Path source,Path output){assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",output.toString(),source.toString())).isZero();}
}
