package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-9")
class DependencyDefinitionTest {
    @TempDir Path root;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"", "/* base() */\n  "})
    void liveBinaryReferenceNavigatesToMatchingSourceDeclaration(String gap) throws Exception {
        var config=new Config(Path.of(System.getProperty("java.home")),null,root.resolve("repository"),3,
                Duration.ofHours(4),512,true,root.resolve("state"),root.resolve("daemon.sock"));
        Path jar=MavenFixtures.artifact(config.m2Repo(),"sample","1","");
        String declaration="package fixture;\npublic class Sample {\n  public static int "+gap+"base(){return 40;}\n}\n";
        IndexFixtures.jar(jar.getParent(),"sample-1",declaration,false);
        Files.writeString(jar.resolveSibling(jar.getFileName()+".sha1"),java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar))));
        Path host=MavenFixtures.project(root.resolve("host"),"<dependencies>"+MavenFixtures.dependency("sample","1")+"</dependencies>");
        Path file=OverlayFixtures.source(host,"Use","class Use { int read(){return fixture.Sample.base();} }");
        String text=Files.readString(file);
        try(var app=new Application(config)) {
            String session=TestSupport.open(app,host);
            String expectedUri="jar:"+jar.resolveSibling("sample-1-sources.jar").toUri()+"!/fixture/Sample.java";
            com.fasterxml.jackson.databind.JsonNode indexed=null;
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            do {
                indexed=TestSupport.request(app.dispatcher(),"symbol.find",Map.of("session",session,"name_path","base","scope","deps"))
                        .path("result").path("result").path("matches").path(0);
                if(indexed.path("source_file").asText().equals(expectedUri))break;
                Thread.sleep(20);
            } while(System.nanoTime()<deadline);
            assertThat(indexed.path("source_file").asText()).as("Source join must finish before testing live/index metadata merging").isEqualTo(expectedUri);
            var params=Map.of("textDocument",Map.of("uri",file.toUri().toString()),"position",Documents.position(text,text.indexOf("base")));
            var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of("session",session,"method","textDocument/definition","params",params));
            assertThat(response.has("error")).as(response.toString()).isFalse();
            var value=response.path("result").path("result").path("value");
            assertThat(value.path("uri").asText()).isEqualTo(expectedUri);
            int offset=declaration.lastIndexOf("base()");
            assertThat(value.path("range")).isEqualTo(Json.MAPPER.valueToTree(Map.of("start",Documents.position(declaration,offset),"end",Documents.position(declaration,offset+4))));
        }
    }
}
