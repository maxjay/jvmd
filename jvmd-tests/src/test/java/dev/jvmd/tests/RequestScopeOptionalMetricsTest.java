package dev.jvmd.tests;

import dev.jvmd.core.RequestScope;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class RequestScopeOptionalMetricsTest {
    @TempDir Path root;

    @Test void missingExtendedManagementModuleDoesNotBreakTracedRequests()throws Exception {
        Path recording=root.resolve("optional.jfr"),log=root.resolve("optional.log");
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "--limit-modules","java.base,java.management,java.logging,java.sql,jdk.jfr",
                "-Djvmd.trace=true","-cp",System.getProperty("java.class.path"),Probe.class.getName(),recording.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(child.waitFor(30,TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).withFailMessage(Files.readString(log)).isZero();
        var events=RecordingFile.readAllEvents(recording).stream().filter(e->e.getEventType().getName().equals("dev.jvmd.Stage")).toList();
        assertThat(events).hasSize(2).allMatch(e->e.getLong("threadAllocatedBytes")==-1);
        assertThat(events).allMatch(e->e.getString("invocation").equals("request-1"));
        assertThat(events).anyMatch(e->e.getString("counters").contains("\"completed_work\":1"));
    }

    public static class Probe {
        public static void main(String[] args)throws Exception {
            if(ModuleLayer.boot().findModule("jdk.management").isPresent())throw new AssertionError("extended module must be absent");
            try(var recording=new Recording()){
                recording.enable("dev.jvmd.Stage");recording.start();
                String value=RequestScope.traced("probe","optional-metrics","request-1","unchanged",()->{
                    try(var span=RequestScope.stage("probe.work")){span.count("completed_work",1);return "ok";}
                });
                if(!value.equals("ok"))throw new AssertionError(value);
                recording.stop();recording.dump(Path.of(args[0]));
            }
        }
    }
}
