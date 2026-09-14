package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 10: inspection bounds, heap caps, static/instance evaluation and resume epochs. */
@Tag("phase-10")
class RuntimeInspectionTest {
    @TempDir Path root;
    @Test void inspectsPagesEvaluatesExpressionsAndFindsRetainingObjects()throws Exception{
        String source="""
            public class Probe {
              static Holder retained;
              static class Holder { Box target; Holder(Box value){target=value;} }
              static class Box { int seed=7; int[] numbers={1,7,9}; Box self=this;
                int add(int n){return seed+n;} String pick(String s){return "string";} String pick(Object o){return "object";}
              }
              public static void main(String[] args)throws Exception {
                System.out.println("READY "+Math.abs(-1));
                System.in.read();
                Box box=new Box(); retained=new Holder(box); int seed=5; int[] array={1,7,9};
                System.out.println("STOP "+seed);
                System.in.read();
              }
            }
            """;
        Path file=RuntimeFixtures.compile(root,source);
        try(var debug=RuntimeFixtures.start(root,new ObjectHandles("probe"))){
            debug.breakpoint("Probe",file,RuntimeFixtures.line(source,"System.out.println(\"STOP"));debug.input("x");debug.awaitStop(Duration.ofSeconds(10));
            var frames=Json.MAPPER.valueToTree(debug.frames(null,0,10).result()).path("frames");String frame=frames.get(0).path("frame").asText();
            assertThat(frames.get(0).path("scip").asText()).startsWith("maven fixture/probe 1 ");
            var locals=Json.MAPPER.valueToTree(debug.locals(frame,0,20).result()).path("locals");String handle="";for(var local:locals)if(local.path("name").asText().equals("box"))handle=local.path("value").path("handle").asText();assertThat(handle).startsWith("obj:probe:");
            var inspected=debug.inspect(handle,4,1,null);assertThat(inspected.truncated()).isTrue();assertThat(inspected.cursor()).isNotBlank();
            var next=debug.inspect(handle,4,1,inspected.cursor());assertThat(Json.MAPPER.valueToTree(next.result()).path("value").path("fields").size()).isLessThanOrEqualTo(1);
            assertThatThrownBy(()->debug.inspect("bad",5,20,null)).hasMessageContaining("depth");
            assertThat(Json.MAPPER.valueToTree(debug.eval("array[1] + box.seed * 2",frame).result()).path("value").path("value").asText()).isEqualTo("21");
            assertThat(Json.MAPPER.valueToTree(debug.eval("(int)(3.75 + seed)",frame).result()).path("value").path("value").asText()).isEqualTo("8");
            assertThat(Json.MAPPER.valueToTree(debug.eval("box.add(3)",frame).result()).path("value").path("value").asText()).isEqualTo("10");
            assertThatThrownBy(()->debug.locals(frame,0,10)).hasMessageContaining("stale");
            assertThat(Json.MAPPER.valueToTree(debug.eval("box.pick(null)",null).result()).path("value").path("value").asText()).isEqualTo("string");
            assertThat(Json.MAPPER.valueToTree(debug.eval("Math.max(seed, 3)",null).result()).path("value").path("value").asText()).isEqualTo("5");
            assertThat(Json.MAPPER.valueToTree(debug.eval("false && (1 / 0 > 0)",null).result()).path("value").path("value").asText()).isEqualTo("false");
            var instances=Json.MAPPER.valueToTree(debug.memory().instances("Probe$Box",2,1,null).result()).path("instances");assertThat(instances.size()).isEqualTo(1);
            var referrers=Json.MAPPER.valueToTree(debug.memory().referrers(handle,20,20,null).result()).path("referrers");assertThat(referrers.toString()).contains("Probe$Holder");
            assertThatThrownBy(()->debug.memory().instances("Probe$Box",0,10,null)).hasMessageContaining("explicit max");
            var histogram=debug.memory().histogram(2,null);assertThat(histogram.truncated()).isTrue();assertThat(histogram.cursor()).isNotBlank();assertThat(Json.MAPPER.valueToTree(debug.memory().histogram(2,histogram.cursor()).result()).path("histogram").size()).isEqualTo(2);
        }
    }
}
