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
    @Test void liveBinaryReferenceNavigatesToMatchingSourceDeclaration() throws Exception {
        var config=TestSupport.config(root,Duration.ofHours(4));
        Path jar=MavenFixtures.artifact(config.m2Repo(),"sample","1","");
        String declaration="package fixture;\npublic class Sample {\n  public static int base(){return 40;}\n}\n";
        IndexFixtures.jar(jar.getParent(),"sample-1",declaration,false);
        Files.writeString(jar.resolveSibling(jar.getFileName()+".sha1"),java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar))));
        Path host=MavenFixtures.project(root.resolve("host"),"<dependencies>"+MavenFixtures.dependency("sample","1")+"</dependencies>");
        Path file=OverlayFixtures.source(host,"Use","class Use { int read(){return fixture.Sample.base();} }");
        String text=Files.readString(file);
        try(var app=new Application(config)) {
            String session=TestSupport.open(app,host);
            var params=Map.of("textDocument",Map.of("uri",file.toUri().toString()),"position",Documents.position(text,text.indexOf("base")));
            com.fasterxml.jackson.databind.JsonNode value=null;
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            do {
                var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of("session",session,"method","textDocument/definition","params",params));
                assertThat(response.has("error")).as(response.toString()).isFalse();
                value=response.path("result").path("result").path("value");
                if(value.hasNonNull("uri"))break;
                Thread.sleep(20);
            } while(System.nanoTime()<deadline);
            assertThat(value.path("uri").asText()).isEqualTo("jar:"+jar.resolveSibling("sample-1-sources.jar").toUri()+"!/fixture/Sample.java");
            assertThat(value.path("range").path("start").path("line").asInt(-1)).isEqualTo(2);
            assertThat(value.path("range").path("start").path("character").asInt(-1)).isEqualTo(20);
            assertThat(value.path("range").path("end").path("character").asInt(-1)).isEqualTo(24);
        }
    }
}
