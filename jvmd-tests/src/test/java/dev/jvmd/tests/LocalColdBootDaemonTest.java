package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Config;
import dev.jvmd.dist.Application;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksLocalStore;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * A daemon opening a project without a committed LOCAL root cold-boots LOCAL: the session's
 * dependencies come from its routes and its sources are attributed by the boot. The next daemon
 * finds the committed root and serves the project on demand.
 */
@Tag("phase-3")
class LocalColdBootDaemonTest {
    @TempDir Path root;

    @Test void aProjectWithoutALocalRootIsColdBootedAndTheNextDaemonFindsItCommitted()throws Exception{
        var base=TestSupport.config(root,Duration.ofHours(4));
        var config=new Config(TestJdk.home(),null,base.m2Repo(),base.mavenMajor(),base.idleTimeout(),base.heapCeilingMb(),false,base.stateDir(),base.socket());
        Path jar=MavenFixtures.artifact(config.m2Repo(),"sample","1","");
        IndexFixtures.jar(jar.getParent(),"sample-1","package fixture;\npublic class Sample {\n  public static int base(){return 40;}\n}\n",false);
        Files.writeString(jar.resolveSibling(jar.getFileName()+".sha1"),HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar))));
        Path project=MavenFixtures.project(root.resolve("project"),"<dependencies>"+MavenFixtures.dependency("sample","1")+"</dependencies>");
        Path use=OverlayFixtures.source(project,"Use","public class Use { int read(){return fixture.Sample.base();} }");
        OverlayFixtures.source(project,"Other","class Other { int twice(){return new Use().read()*2;} }");
        Path local=RocksLocalStore.directory(RocksIndexStorage.generation(config.stateDir().resolve("index-v2")),project);
        try(var app=new Application(config)){
            String session=TestSupport.open(app,project);
            // The dependency is not in the prepared MACHINE: the LOCAL cold boot's routes add it.
            var found=find(app,session,"Sample/base","deps");
            assertThat(found).hasSize(1);
            var hover=TestSupport.complete(app.dispatcher(),"symbol.atPosition",Map.of("session",session,"path",use.toString(),"line",1,"character",50));
            assertThat(hover.has("error")).as(hover.toString()).isFalse();
            var status=awaitCommitted(app,session);
            assertThat(status.path("boot").asText()).isEqualTo("cold");
            assertThat(status.path("built").asInt()).isEqualTo(2);
            assertThat(status.path("faults").asInt()).isZero();
            assertThat(status.path("root").asText()).isNotBlank();
        }
        assertThat(RocksLocalStore.committedRoot(local)).isPresent();
        try(var app=new Application(config)){
            String session=TestSupport.open(app,project);
            assertThat(find(app,session,"Sample/base","deps")).hasSize(1);
            assertThat(localStatus(app,session).path("boot").asText()).isEqualTo("warm");
        }
    }

    private static List<JsonNode> find(Application app,String session,String name,String scope)throws Exception{
        var response=TestSupport.complete(app.dispatcher(),"symbol.find",Map.of("session",session,"name_path",name,"scope",scope));
        assertThat(response.has("error")).as(response.toString()).isFalse();
        var matches=new ArrayList<JsonNode>();response.path("result").path("result").path("matches").forEach(matches::add);return matches;
    }

    private static JsonNode localStatus(Application app,String session)throws Exception{
        return TestSupport.complete(app.dispatcher(),"session.status",Map.of("session",session)).path("result").path("result").path("local");
    }

    private static JsonNode awaitCommitted(Application app,String session)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();JsonNode status;
        do{
            status=localStatus(app,session);
            if(status.path("stage").asText().equals("committed")||status.path("stage").asText().startsWith("failed"))break;
            Thread.sleep(20);
        }while(System.nanoTime()<deadline);
        assertThat(status.path("stage").asText()).as(status.toString()).isEqualTo("committed");
        return status;
    }
}
