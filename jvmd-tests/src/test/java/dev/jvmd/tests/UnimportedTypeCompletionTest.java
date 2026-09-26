package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.dist.Application;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-9")
class UnimportedTypeCompletionTest {
    @TempDir Path root;

    @Test void lspSuggestsIndexedDependencyTypeWithAutoImportAndKeepsMemberCompletionLocal()throws Exception{
        var config=TestSupport.config(root,Duration.ofHours(4));
        installDependency(config.m2Repo());
        Path project=MavenFixtures.project(root.resolve("project"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties><dependencies>"+
                        MavenFixtures.dependency("library","1")+"</dependencies>");
        Path sourceRoot=Files.createDirectories(project.resolve("src/main/java/app"));
        Path file=sourceRoot.resolve("Use.java");
        String source="package app; class Use { Samp value; }";Files.writeString(file,source);

        try(var app=new Application(config)){
            String session=TestSupport.open(app,project);
            TestSupport.request(app.dispatcher(),"document.open",Map.of("session",session,"path",file.toString(),"version",1,"text",source));

            var completion=lspCompletion(app,session,file,source,source.indexOf("Samp")+4);
            JsonNode item=null;for(var candidate:completion.path("items"))if(candidate.path("label").asText().equals("Sample")){item=candidate;break;}
            assertThat(item).as(completion.toString()).isNotNull();
            assertThat(item.path("detail").asText()).isEqualTo("lib.Sample");
            assertThat(item.path("textEdit").path("newText").asText()).isEqualTo("Sample");
            assertThat(item.path("data").path("semantic_origin").asText()).isEqualTo("machine");
            long beforeResolve=analyzerQueries(app,session);
            var resolved=TestSupport.request(app.dispatcher(),"lsp.request",Map.of(
                    "session",session,"method","completionItem/resolve","params",item,"client",Map.of()));
            assertThat(resolved.has("error")).as(resolved.toPrettyString()).isFalse();
            assertThat(analyzerQueries(app,session)).as("indexed type resolve must stay off javac").isEqualTo(beforeResolve);
            assertThat(item.path("additionalTextEdits").size()).isEqualTo(1);
            assertThat(item.path("additionalTextEdits").get(0).path("newText").asText()).contains("import lib.Sample;");
            assertThat(item.path("additionalTextEdits").get(0).path("range").path("start").path("character").asInt())
                    .isEqualTo("package app;".length());

            String member="package app; class Use { Object read(String value){ return value.Samp; } }";
            TestSupport.request(app.dispatcher(),"document.change",Map.of("session",session,"path",file.toString(),"version",2,"changes",List.of(Map.of("text",member))));
            long before=indexQueries(app,session);
            var memberCompletion=TestSupport.request(app.dispatcher(),"symbol.completion",Map.of(
                    "session",session,"path",file.toString(),"line",0,"character",member.indexOf("Samp")+4,"limit",100));
            long after=indexQueries(app,session);
            assertThat(memberCompletion.path("result").path("result").path("items").findValuesAsText("name")).doesNotContain("Sample");
            assertThat(after-before).isZero();
        }
    }

    @Test void dependencyCompletionResolveDoesNotScanWorkspaceSource()throws Exception{
        var config=TestSupport.config(root,Duration.ofHours(4));
        installDependency(config.m2Repo());
        Path project=MavenFixtures.project(root.resolve("resolve-project"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties><dependencies>"+
                        MavenFixtures.dependency("library","1")+"</dependencies>");
        Path sourceRoot=Files.createDirectories(project.resolve("src/main/java/app"));
        Path file=sourceRoot.resolve("Use.java");
        String source="package app; import lib.Sample; class Use { Object read(Sample value){ return value.lab; } }";
        Files.writeString(file,source);

        try(var app=new Application(config)){
            String session=TestSupport.open(app,project);
            TestSupport.request(app.dispatcher(),"document.open",Map.of(
                    "session",session,"path",file.toString(),"version",1,"text",source));
            var completion=lspCompletion(app,session,file,source,source.indexOf("value.lab")+"value.lab".length());
            JsonNode item=null;
            for(var candidate:completion.path("items"))if(candidate.path("label").asText().startsWith("label(")){item=candidate;break;}
            assertThat(item).as(completion.toString()).isNotNull();
            assertThat(item.path("data").path("scip").asText()).startsWith("maven fixture/library 1 ");

            long before=analyzerQueries(app,session);
            var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of(
                    "session",session,"method","completionItem/resolve","params",item,"client",Map.of()));
            assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
            assertThat(response.path("result").path("result").path("value").path("label").asText()).startsWith("label(");
            assertThat(analyzerQueries(app,session))
                    .as("dependency resolve must use the indexed SCIP directly rather than scanning workspace source")
                    .isEqualTo(before);
        }
    }

    @Test void fullLiveUnqualifiedPageDoesNotQueryIndex()throws Exception{
        var config=TestSupport.config(root,Duration.ofHours(4));
        Path project=MavenFixtures.project(root.resolve("broad-project"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties>");
        Path sourceRoot=Files.createDirectories(project.resolve("src/main/java/app"));
        Path file=sourceRoot.resolve("Use.java");
        var code=new StringBuilder("package app; class Use { ");
        for(int i=0;i<20;i++)code.append("int alpha").append(String.format("%02d",i)).append("; ");
        code.append("Object read(){ return al; } }");
        String source=code.toString();Files.writeString(file,source);

        try(var app=new Application(config)){
            String session=TestSupport.open(app,project);
            TestSupport.request(app.dispatcher(),"document.open",Map.of("session",session,"path",file.toString(),"version",1,"text",source));
            long beforeQueries=indexQueries(app,session);
            int offset=source.indexOf("return al")+"return al".length();
            var position=dev.jvmd.core.Documents.position(source,offset);
            var response=TestSupport.request(app.dispatcher(),"symbol.completion",Map.of(
                    "session",session,"path",file.toString(),"line",position.line(),"character",position.character(),"limit",10));
            assertThat(response.has("error")).as(response.toString()).isFalse();
            assertThat(response.path("result").path("result").path("items").size()).isEqualTo(10);
            assertThat(indexQueries(app,session)-beforeQueries).isZero();
        }
    }

    private static JsonNode lspCompletion(Application app,String session,Path file,String source,int offset){
        var params=Map.of("textDocument",Map.of("uri",file.toUri().toString()),"position",dev.jvmd.core.Documents.position(source,offset));
        var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of("session",session,"method","textDocument/completion","params",params,"client",Map.of()));
        assertThat(response.has("error")).as(response.toString()).isFalse();
        return response.path("result").path("result").path("value");
    }

    private static long analyzerQueries(Application app,String session){
        return TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session,"section","analyzer"))
                .path("result").path("result").path("analyzer").path("queries").asLong();
    }

    private static long indexQueries(Application app,String session){
        return TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session))
                .path("result").path("result").path("index").path("timings").path("query_calls").asLong();
    }

    private void installDependency(Path repository)throws Exception{
        Path build=Files.createDirectories(root.resolve("dependency-build"));
        Path compiled=IndexFixtures.jar(build,"library","""
                package lib;
                /** Indexed dependency type used by completion. */
                public class Sample { public String label(){ return "sample"; } }
                """,true);
        Path directory=Files.createDirectories(repository.resolve("fixture/library/1"));
        Path jar=directory.resolve("library-1.jar"),pom=directory.resolve("library-1.pom");
        Files.copy(compiled,jar,StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(pom,MavenFixtures.pom("fixture","library","1",""));
        for(Path path:List.of(jar,pom)){
            byte[] digest=java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path));
            Files.writeString(path.resolveSibling(path.getFileName()+".sha1"),java.util.HexFormat.of().formatHex(digest),StandardCharsets.US_ASCII);
        }
    }
}
