package dev.jvmd.tests;

import dev.jvmd.core.RequestScope;
import dev.jvmd.core.Session;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class RequestScopeTraceTest {
    @TempDir Path root;

    @Test void optInEventsPreserveCausalityAcrossThreadsWithoutMemoLeaks()throws Exception {
        Path recording=run(true);
        var events=RecordingFile.readAllEvents(recording).stream().filter(e->e.getEventType().getName().equals("dev.jvmd.Stage")).toList();
        assertThat(events).isNotEmpty();
        assertThat(events).allMatch(e->e.getString("workflow").equals("workflow-test"));
        var rpc=events.stream().filter(e->e.getString("stage").equals("rpc.execute")).findFirst().orElseThrow();
        var actor=events.stream().filter(e->e.getString("stage").equals("test.actor")).findFirst().orElseThrow();
        assertThat(actor.getLong("parent")).isEqualTo(rpc.getLong("span"));
        assertThat(actor.getLong("request")).isEqualTo(rpc.getLong("request"));
        assertThat(actor.getThread().getJavaThreadId()).isNotEqualTo(rpc.getThread().getJavaThreadId());
        assertThat(actor.getString("counters")).contains("\"operations\":3");
        var queued=events.stream().filter(e->e.getString("stage").equals("session.queue")).findFirst().orElseThrow();
        assertThat(queued.getBoolean("queued")).isTrue();
        assertThat(queued.getLong("threadCpuNanos")).isEqualTo(-1);
        assertThat(queued.getLong("threadAllocatedBytes")).isEqualTo(-1);
        var virtual=events.stream().filter(e->e.getString("stage").equals("test.virtual")).findFirst().orElseThrow();
        assertThat(virtual.getBoolean("virtualThread")).isTrue();
        assertThat(virtual.getLong("threadAllocatedBytes")).isEqualTo(-1);
        assertThat(events).allMatch(e->e.getLong("durationNanos")>=0);
    }

    @Test void disabledInstrumentationEmitsNoEvents()throws Exception {
        assertThat(RecordingFile.readAllEvents(run(false))).noneMatch(e->e.getEventType().getName().equals("dev.jvmd.Stage"));
    }

    private Path run(boolean enabled)throws Exception {
        Path recording=root.resolve(enabled+".jfr"), log=root.resolve(enabled+".log");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-Djvmd.trace="+enabled,"-cp",System.getProperty("java.class.path"),Probe.class.getName(),recording.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
        return recording;
    }

    public static class Probe {
        public static void main(String[] args)throws Exception {
            try(var recording=new Recording();var session=new Session("trace-test",Path.of("."))){
                recording.enable("dev.jvmd.Stage");recording.start();
                RequestScope.traced("test","workflow-test","B",()->{
                    var context=RequestScope.current();
                    session.execute(()->RequestScope.with(context,()->{
                        try(var span=RequestScope.stage("test.actor")){span.count("operations",3);}
                        return null;
                    }));
                    try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
                        workers.submit(()->RequestScope.with(context,()->{
                            try(var span=RequestScope.stage("test.virtual")){return null;}
                        })).get();
                    }
                    return null;
                });
                if(RequestScope.current()!=null)throw new AssertionError("Context leaked");
                recording.stop();recording.dump(Path.of(args[0]));
            }
        }
    }
}
