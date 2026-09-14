package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 10: launch, source compilation, break/eval, body swap, restart and JFR via the daemon. */
@Tag("phase-10")
class RuntimeProtocolTest {
    @TempDir Path root;
    @Test void launchesSwapsBodiesAndRestartsForSchemaChanges()throws Exception{
        Path workspace=Files.createDirectories(root.resolve("workspace"));String source="""
            public class Probe {
              static int count;
              static int tick(){return ++count;}
              public static void main(String[] args)throws Exception {
                while(true){tick();Thread.sleep(20);}
              }
            }
            """;
        Path file=workspace.resolve("Probe.java");Files.writeString(file,source);
        var config=new Config(Path.of(System.getProperty("java.home")),null,root.resolve("repository"),3,Duration.ofHours(4),512,false,root.resolve("state"),root.resolve("socket"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,workspace);var run=call(app,"run.start",Map.of("session",session,"target","Probe","debug",true));String id=run.path("run_session").asText();long pid=run.path("pid").asLong();
            assertThat(id).isNotBlank();assertThat(run.path("port").asInt()).isPositive();
            op(app,session,id,"break",Map.of("class","Probe","path",file.toString(),"line",3));awaitStop(app,session,id);
            assertThat(op(app,session,id,"frames",Map.of()).path("frames").get(0).path("source_file").asText()).isEqualTo(file.toString());
            Files.writeString(file,source.replace("return ++count;","return 42;"));
            var swap=op(app,session,id,"hotswap",Map.of("paths",List.of(file.toString())));assertThat(swap.path("restart_required").asBoolean()).isFalse();assertThat(swap.path("redefined").asInt()).isPositive();
            op(app,session,id,"continue",Map.of());awaitStop(app,session,id);
            assertThat(op(app,session,id,"eval",Map.of("expression","Probe.tick()")).path("value").path("value").asText()).isEqualTo("42");
            String added=source.replace("return ++count;","return 42;").replace("static int count;","static int count; static int added(){return 99;}");Files.writeString(file,added);
            var unsupported=op(app,session,id,"hotswap",Map.of("path",file.toString()));assertThat(unsupported.path("restart_required").asBoolean()).isTrue();assertThat(unsupported.path("reason").asText()).isNotBlank();
            var restarted=op(app,session,id,"restart",Map.of());String next=restarted.path("run_session").asText();assertThat(next).isNotEqualTo(id);assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            op(app,session,next,"break",Map.of("class","Probe","path",file.toString(),"line",3));awaitStop(app,session,next);
            assertThat(op(app,session,next,"eval",Map.of("expression","Probe.added()")).path("value").path("value").asText()).isEqualTo("99");
            op(app,session,next,"histogram",Map.of("jfr",Map.of("action","start","name","allocation","duration_seconds",60)));
            var recording=op(app,session,next,"histogram",Map.of("jfr",Map.of("action","dump","name","allocation","path","target/allocation.jfr")));assertThat(Files.size(Path.of(recording.path("file").asText()))).isGreaterThan(1000);
            op(app,session,next,"stop",Map.of());assertThat(call(app,"session.status",Map.of("session",session)).path("runs").isEmpty()).isTrue();
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode call(Application app,String method,Map<String,?> params){
        var response=TestSupport.request(app.dispatcher(),method,params);assertThat(response.has("error")).as(response.toPrettyString()).isFalse();assertThat(response.path("result").path("warnings").toString()).doesNotContain("analyzer_fault");return response.path("result").path("result");
    }
    private static com.fasterxml.jackson.databind.JsonNode op(Application app,String session,String run,String op,Map<String,?> args){return call(app,"debug.op",Map.of("session",session,"run_session",run,"op",op,"args",args));}
    private static void awaitStop(Application app,String session,String id)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline){for(var run:call(app,"session.status",Map.of("session",session)).path("runs"))if(run.path("run_session").asText().equals(id)&&!run.path("stopped_threads").isEmpty())return;Thread.sleep(10);}throw new AssertionError("Debugger did not stop");
    }
}
