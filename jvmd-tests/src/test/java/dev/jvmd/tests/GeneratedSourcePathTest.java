package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import dev.jvmd.resolver.MavenResolver;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 5: main and test generated source roots participate in live resolution. */
@Tag("phase-5")
class GeneratedSourcePathTest {
    @TempDir Path root;
    @Test void configuredAndConventionalGeneratedSourcesResolve()throws Exception{
        MavenFixtures.project(root,"<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version><configuration><generatedSourcesDirectory>"+root.resolve("target/generated-sources/custom")+"</generatedSourcesDirectory></configuration></plugin></plugins></build>");
        Path main=Files.createDirectories(root.resolve("target/generated-sources/custom")).resolve("GeneratedMain.java");
        Path test=Files.createDirectories(root.resolve("target/generated-test-sources/test-annotations")).resolve("GeneratedTest.java");
        Path use=Files.createDirectories(root.resolve("src/test/java")).resolve("TestUse.java");
        Files.writeString(main,"class GeneratedMain {}");Files.writeString(test,"class GeneratedTest {}");Files.writeString(use,"class TestUse { GeneratedMain main; GeneratedTest test; }");
        var config=TestSupport.config(root,Duration.ofHours(4));
        try(var resolver=new MavenResolver(config)){var module=resolver.resolve(root).modules().getFirst();assertThat(module.sources()).contains(main.getParent().toString());assertThat(module.testSources()).contains(test.getParent().toString());assertThat(module.processing().enabled()).isFalse();}
        try(var app=new Application(config)){
            String session=TestSupport.open(app,root);var response=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"paths",List.of(use.toString()))).path("result");
            assertThat(response.path("tier").asInt()).isEqualTo(2);assertThat(response.path("result").path("diagnostics").isEmpty()).withFailMessage(response.toPrettyString()).isTrue();
            var status=TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session));assertThat(status.path("result").path("result").path("annotation_processing").path("initialized").asBoolean()).isFalse();
        }
    }
}
