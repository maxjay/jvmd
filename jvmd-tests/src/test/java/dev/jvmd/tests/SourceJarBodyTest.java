package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 7: dependency find returns the descriptor-joined source body and doc summary. */
@Tag("phase-7")
class SourceJarBodyTest {
    @TempDir Path root;
    @Test void findRoundTripsTheExactBodyFromASourcesJar()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repository")),workspace=Files.createDirectories(root.resolve("workspace")),state=Files.createDirectories(root.resolve("state"));
        Path jar=IndexFixtures.jar(repo,"sample",IndexFixtures.generic(),false);
        try(var index=new IndexService(state.resolve("index.db"),repo)){index.indexJar(jar,"fixture:sample:1","jar");index.indexSources(repo.resolve("sample-sources.jar"));}
        var config=new Config(Path.of(System.getProperty("java.home")),null,repo,3,Duration.ofHours(4),512,false,state,root.resolve("daemon.sock"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,workspace);
            var response=TestSupport.request(app.dispatcher(),"symbol.find",Map.of("session",session,"name_path","transform","scope","deps","include_body",true)).path("result");
            assertThat(response.path("result").path("matches").size()).isEqualTo(1);var result=response.path("result").path("matches").get(0);
            assertThat(result.path("body").asText()).isEqualTo("{ return null; }");assertThat(result.path("source_file").asText()).contains("sample-sources.jar!/fixture/Sample.java");assertThat(result.path("doc").asText()).isEqualTo("Transform the value.");
        }
    }
}
