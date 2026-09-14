package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 10: real JDI step-into, step-over and step-out preserve stopped-frame state. */
@Tag("phase-10")
class DebuggerSteppingTest {
    @TempDir Path root;
    @Test void stepsIntoOverAndOutOfAMethod()throws Exception{
        String source="""
            public class Probe {
              static int helper(int value) {
                int answer=value+1;
                return answer;
              }
              public static void main(String[] args)throws Exception {
                System.in.read();
                int result=helper(3);
                System.out.println(result);
                System.in.read();
              }
            }
            """;
        Path file=RuntimeFixtures.compile(root,source);
        try(var debug=RuntimeFixtures.start(root,new ObjectHandles("probe"))){
            debug.breakpoint("Probe",file,RuntimeFixtures.line(source,"int result="));debug.input("x");debug.awaitStop(Duration.ofSeconds(10));
            debug.step(null,"step_into");debug.awaitStop(Duration.ofSeconds(10));var into=top(debug);assertThat(into.path("method").asText()).isEqualTo("helper");
            debug.step(null,"step_over");debug.awaitStop(Duration.ofSeconds(10));var over=top(debug);assertThat(over.path("method").asText()).isEqualTo("helper");assertThat(over.path("line").asInt()).isGreaterThan(into.path("line").asInt());
            debug.step(null,"step_out");debug.awaitStop(Duration.ofSeconds(10));assertThat(top(debug).path("method").asText()).isEqualTo("main");
            assertThat(Json.MAPPER.valueToTree(debug.locals(null,0,20).result()).toString()).contains("result","4");
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode top(DebugSession debug)throws Exception{return Json.MAPPER.valueToTree(debug.frames(null,0,1).result()).path("frames").get(0);}
}
