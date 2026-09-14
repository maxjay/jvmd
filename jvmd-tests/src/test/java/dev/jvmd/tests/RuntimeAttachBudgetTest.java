package dev.jvmd.tests;

import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 10 exit: production daemon attaches within 500 ms under strict AOT. */
@Tag("phase-10") @Tag("perf")
class RuntimeAttachBudgetTest {
    @TempDir Path root;
    @Test void productionAttachMeetsTheBudget()throws Exception{
        Path workspace=Files.createDirectories(root.resolve("workspace"));Files.writeString(workspace.resolve("Probe.java"),"public class Probe { public static void main(String[] args)throws Exception { while(true) Thread.sleep(20); } }");
        try(var daemon=new AotDaemon(root,Map.of("jdk_home",System.getProperty("java.home")))){
            String session=daemon.request("session.open",Map.of("root",workspace.toString())).path("result").path("session").asText();
            var run=daemon.request("run.start",Map.of("session",session,"target","Probe","debug",true)).path("result");double attach=run.path("attach_ms").asDouble();assertThat(run.path("port").asInt()).isPositive();assertThat(attach).isGreaterThan(0).isLessThan(500);
            var metrics=Map.of("attach_ms",attach);System.out.println("phase-10-perf "+Json.MAPPER.writeValueAsString(metrics));Json.MAPPER.writeValue(TestSupport.repo().resolve("jvmd-tests/target/phase-10-perf.json").toFile(),metrics);
            daemon.request("debug.op",Map.of("session",session,"run_session",run.path("run_session").asText(),"op","stop","args",Map.of()));
        }
    }
}
