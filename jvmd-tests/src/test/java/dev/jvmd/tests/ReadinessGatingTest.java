package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Config;
import dev.jvmd.core.Json;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Architecture §95 and Phase 9: readiness is session capability, not whole-repository reconciliation. */
class ReadinessGatingTest {
    @TempDir Path root;

    private static JsonNode status(Application app)throws Exception{
        var request=Json.MAPPER.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method","daemon.status");
        request.putObject("params");
        return app.dispatcher().dispatch(request).path("result").path("result");
    }

    @Test void daemonBecomesSessionCapableBeforeTheRepositoryScanCompletes()throws Exception{
        Path repository=Files.createDirectories(root.resolve("repository/g/a/1"));
        IndexFixtures.jar(repository,"a","package a; public class Sample { public int getA(){return 1;} }",true);
        Path state=root.resolve("state");
        String previous=System.getProperty("jvmd.index.scan.initial_delay_seconds");
        System.setProperty("jvmd.index.scan.initial_delay_seconds","30");
        try{
            var config=new Config(Path.of(System.getProperty("java.home")),null,root.resolve("repository"),3,Duration.ofHours(1),512,true,state,state.resolve("d.sock"));
            long started=System.nanoTime();
            try(var app=new Application(config)){
                app.awaitSessionCapable();
                long readyMillis=(System.nanoTime()-started)/1_000_000;
                var readiness=status(app).path("readiness");
                assertThat(readiness.path("session_capable").asBoolean()).isTrue();
                assertThat(readiness.path("repository_scan_requested").asBoolean()).isTrue();
                assertThat(readiness.path("repository_reconciled").asBoolean())
                        .as("the 30s-delayed repository scan must not gate readiness").isFalse();
                assertThat(readyMillis).isLessThan(25_000);
            }
        }finally{
            if(previous==null)System.clearProperty("jvmd.index.scan.initial_delay_seconds");
            else System.setProperty("jvmd.index.scan.initial_delay_seconds",previous);
        }
    }
}
