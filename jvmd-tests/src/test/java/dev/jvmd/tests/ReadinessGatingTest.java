package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Config;
import dev.jvmd.core.Json;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * READY is session capability. A daemon whose generation has a committed MACHINE root serves from it at
 * once and reconciles it against the repository in the background. A daemon without one (first start, a
 * cold boot interrupted before its root, a new index format) runs the MACHINE cold boot, so READY waits for
 * it: READY is never reported over an uncommitted MACHINE. The daemon's JDK is the tiny test JDK, whose
 * modules are MACHINE leaves like any configured JDK's.
 */
class ReadinessGatingTest {
    @TempDir Path root;
    private Path state;

    @BeforeEach void repository()throws Exception{
        Path artifact=Files.createDirectories(root.resolve("repository/g/a/1"));
        IndexFixtures.jar(artifact,"a","package a; public class Sample { public int getA(){return 1;} }",true);
        state=root.resolve("state");
    }
    private Application daemon(long scanDelaySeconds)throws Exception{
        System.setProperty("jvmd.index.scan.initial_delay_seconds",Long.toString(scanDelaySeconds));
        var config=new Config(TestJdk.home(),null,root.resolve("repository"),3,Duration.ofHours(1),512,state,state.resolve("d.sock"));
        return new Application(config);
    }
    private static JsonNode status(Application app)throws Exception{
        var request=Json.MAPPER.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method","daemon.status");
        request.putObject("params");
        return app.dispatcher().dispatch(request).path("result").path("result");
    }
    private static void restoreDelay(String previous){
        if(previous==null)System.clearProperty("jvmd.index.scan.initial_delay_seconds");
        else System.setProperty("jvmd.index.scan.initial_delay_seconds",previous);
    }

    @Test void aFirstStartIsReadyOnlyAfterTheMachineColdBoot()throws Exception{
        String previous=System.getProperty("jvmd.index.scan.initial_delay_seconds");
        try(var app=daemon(0)){
            app.awaitSessionCapable();
            var status=status(app);
            assertThat(status.path("readiness").path("persisted_index_complete").asBoolean()).as("the MACHINE root is committed").isTrue();
            assertThat(status.path("readiness").path("repository_reconciled").asBoolean()).as("the cold boot enumerated the repository").isTrue();
            assertThat(status.path("index").path("artifacts").asLong()).as("the repository's artifact is indexed at READY").isGreaterThanOrEqualTo(2);
            assertThat(committedRoot()).isPresent();
        }finally{restoreDelay(previous);}
    }

    private java.util.Optional<dev.jvmd.core.tree.Root> committedRoot(){
        return dev.jvmd.index.rocks.layer.RocksMachineStore.committedRoot(dev.jvmd.index.rocks.RocksIndexStorage.machineDirectory(
                dev.jvmd.index.rocks.RocksIndexStorage.generation(state.resolve("index-v2"))));
    }

    @Test void aRestartWithACompletePersistedIndexIsReadyBeforeTheRepositoryScan()throws Exception{
        String previous=System.getProperty("jvmd.index.scan.initial_delay_seconds");
        try{
            try(var first=daemon(0)){first.awaitSessionCapable();}
            long started=System.nanoTime();
            try(var app=daemon(30)){
                app.awaitSessionCapable();
                long readyMillis=(System.nanoTime()-started)/1_000_000;
                var status=status(app);
                assertThat(status.path("readiness").path("persisted_index_complete").asBoolean()).isTrue();
                assertThat(status.path("readiness").path("repository_reconciled").asBoolean())
                        .as("the 30 s-delayed repository scan must not gate readiness").isFalse();
                assertThat(readyMillis).isLessThan(25_000);
                assertThat(status.path("index").path("artifacts").asLong()).as("served from the persisted index").isGreaterThanOrEqualTo(1);
            }
        }finally{restoreDelay(previous);}
    }

    @Test void aColdBootInterruptedBeforeItsRootMakesTheNextStartCold()throws Exception{
        String previous=System.getProperty("jvmd.index.scan.initial_delay_seconds");
        try{
            // Stop after every leaf is built and before the commit: the generation holds data but no root.
            var boot=new dev.jvmd.boot.cold.machine.MachineColdBoot(dev.jvmd.index.rocks.RocksIndexStorage.generation(state.resolve("index-v2")),
                    root.resolve("repository"),TestJdk.home(),8L*1024*1024);
            try(var storage=boot.create()){boot.buildArtifacts(storage,boot.enumerate());}
            assertThat(committedRoot()).isEmpty();
            try(var app=daemon(30)){
                app.awaitSessionCapable();
                var status=status(app);
                assertThat(status.path("readiness").path("repository_scan_requested").asBoolean()).as("a cold boot, not the warm scan").isFalse();
                assertThat(status.path("readiness").path("repository_reconciled").asBoolean()).isTrue();
                assertThat(status.path("index").path("artifacts").asLong()).isGreaterThanOrEqualTo(2);
                assertThat(committedRoot()).isPresent();
            }
        }finally{restoreDelay(previous);}
    }

    private static JsonNode awaitReconciliation(Application app)throws Exception{
        long deadline=System.nanoTime()+60_000_000_000L;JsonNode status;
        do{status=status(app);if(!status.path("readiness").path("repository_reconciliation").asText().equals("pending"))return status;Thread.sleep(50);}
        while(System.nanoTime()<deadline);
        return status;
    }

    /** R2: a cold boot that could not read every artifact is reported incomplete, not called reconciled. */
    @Test void aColdBootWithAFaultedArtifactIsReportedIncompleteNotReconciled()throws Exception{
        String previous=System.getProperty("jvmd.index.scan.initial_delay_seconds");
        Path corrupt=Files.createDirectories(root.resolve("repository/g/broken/1")).resolve("broken-1.jar");
        Files.write(corrupt,new byte[]{'n','o','t',' ','a',' ','j','a','r'});
        try(var app=daemon(0)){
            app.awaitSessionCapable();
            var status=awaitReconciliation(app);
            assertThat(status.path("readiness").path("repository_reconciled").asBoolean()).as("faults leave stale paths unreconciled").isFalse();
            assertThat(status.path("readiness").path("repository_reconciliation").asText()).isEqualTo("incomplete:1");
            assertThat(status.path("index").path("reconciliation").asText()).isEqualTo("incomplete:1");
        }finally{restoreDelay(previous);}
    }

    /**
     * R2: a completed earlier scan is not proof that the repository was unchanged while the daemon was
     * stopped. A warm restart serves from the persisted inventory (session capable) but is not reconciled
     * until its own scan has seen the artifact added offline.
     */
    @Test void anArtifactAddedWhileStoppedIsNotReconciledUntilTheRestartScansIt()throws Exception{
        String previous=System.getProperty("jvmd.index.scan.initial_delay_seconds");
        try{
            long persisted;
            try(var first=daemon(0)){first.awaitSessionCapable();persisted=status(first).path("index").path("artifacts").asLong();}
            Path artifact=Files.createDirectories(root.resolve("repository/g/b/1"));
            IndexFixtures.jar(artifact,"b","package b; public class Sample { public int getB(){return 2;} }",true);
            try(var app=daemon(30)){
                app.awaitSessionCapable();
                var status=status(app);
                assertThat(status.path("readiness").path("persisted_index_complete").asBoolean()).isTrue();
                assertThat(status.path("readiness").path("repository_reconciled").asBoolean()).as("the offline addition is not seen yet").isFalse();
                assertThat(status.path("readiness").path("repository_reconciliation").asText()).isEqualTo("pending");
                assertThat(status.path("index").path("artifacts").asLong()).as("served from the persisted inventory").isEqualTo(persisted);
            }
            try(var app=daemon(0)){
                app.awaitSessionCapable();
                var status=awaitReconciliation(app);
                assertThat(status.path("readiness").path("repository_reconciliation").asText()).isEqualTo("complete");
                assertThat(status(app).path("index").path("artifacts").asLong()).as("the scan indexed the offline addition").isGreaterThan(persisted);
            }
        }finally{restoreDelay(previous);}
    }
}
