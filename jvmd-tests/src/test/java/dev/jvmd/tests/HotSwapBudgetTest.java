package dev.jvmd.tests;

import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements R1: the complete production hot-swap request, including compilation, stays under 100 ms. */
@Tag("phase-10") @Tag("perf")
class HotSwapBudgetTest {
    @TempDir Path root;
    @Test void methodBodySwapMeetsTheEndToEndBudgetUnderStrictAot()throws Exception{
        Path workspace=Files.createDirectories(root.resolve("workspace")),file=workspace.resolve("Probe.java");
        String source="public class Probe { static int value(){return %d;} public static void main(String[] args)throws Exception { while(true){value();Thread.sleep(20);} } }";
        Files.writeString(file,source.formatted(0));
        try(var daemon=new AotDaemon(root,Map.of("jdk_home",System.getProperty("java.home")))){
            String session=daemon.request("session.open",Map.of("root",workspace.toString())).path("result").path("session").asText();
            String id=daemon.request("run.start",Map.of("session",session,"target","Probe","debug",true)).path("result").path("run_session").asText();
            var samples=new ArrayList<Double>();var details=new ArrayList<Object>();
            for(int value=1;value<=5;value++){
                Files.writeString(file,source.formatted(value));long started=System.nanoTime();
                var swapped=daemon.request("debug.op",Map.of("session",session,"run_session",id,"op","hotswap","args",Map.of("path",file.toString()))).path("result");
                samples.add((System.nanoTime()-started)/1e6);details.add(swapped);
                assertThat(swapped.path("restart_required").asBoolean()).isFalse();assertThat(swapped.path("redefined").asInt()).isPositive();
            }
            var sorted=samples.stream().sorted().toList();double p95=sorted.get((int)Math.ceil(sorted.size()*.95)-1);
            var evidence=Map.of("request_ms",samples,"p95_ms",p95,"budget_ms",100,"swaps",details);
            System.out.println("hotswap-perf "+Json.MAPPER.writeValueAsString(evidence));
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/hotswap-perf.json").toFile(),evidence);
            assertThat(p95).as("Complete strict-AOT debug.op hotswap latency").isLessThan(100);
        }
    }
}
