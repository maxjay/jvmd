package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.7 and 5: arbitrary target execution cannot block the session forever. */
@Tag("phase-10") @Tag("phase-11")
class EvaluationDeadlineTest {
    @TempDir Path root;
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
    void terminatesAnUnboundedInvocationAndLeavesTheSessionRestartable(int tier)throws Exception {
        String source="""
            public class Probe {
              public static int forever(){while(true) Thread.onSpinWait();}
              public static void main(String[] args)throws Exception {
                System.out.println("READY"); System.in.read();
                int seed=7;
                System.out.println("STOP "+seed);
                System.in.read();
              }
            }
            """;
        Path file=RuntimeFixtures.compile(root,source);
        var launch=new DebugSession.Launch(Path.of(System.getProperty("java.home")),root,List.of(root.resolve("classes")),"Probe",List.of(),true);
        var lookup=new SourceLookup(List.of(root));var request=new RunManager.Request(launch,lookup,launch.javaHome(),List.of(root),List.of(),root.resolve("classes"),List.of(new RunManager.Target(root,List.of(root),List.of(root.resolve("classes")),List.of(),root.resolve("classes"))));
        try(var debug=new DebugSession("deadline",launch,lookup)) {
            debug.breakpoint("Probe",file,RuntimeFixtures.line(source,"System.out.println(\"STOP"));debug.input("x");debug.awaitStop(Duration.ofSeconds(10));
            long started=System.nanoTime();
            assertThatThrownBy(()->{if(tier==1)debug.eval("Probe.forever()",null);else new CompiledEvaluation(debug,request).evaluate("Probe.forever()",null);})
                .isInstanceOf(RpcException.class).hasMessageContaining("evaluation_timeout");
            assertThat((System.nanoTime()-started)/1e9).isLessThan(10);
            assertThat(ProcessHandle.of(debug.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(debug.status().get("stopped_threads")).isEqualTo(List.of());
            assertThatThrownBy(()->debug.frames(null,0,10)).hasMessageContaining("disconnected");
        }
        try(var restarted=new DebugSession("restarted",launch,lookup)) {
            RuntimeFixtures.output(restarted,"READY");assertThat(restarted.status().get("alive")).isEqualTo(true);
        }
    }
}
