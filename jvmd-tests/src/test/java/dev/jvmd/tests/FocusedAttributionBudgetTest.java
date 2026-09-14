package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.analyzer.SourceText;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4 exit: distinct focused members of a 2k-line file meet the 50 ms budget. */
@Tag("phase-4") @Tag("perf")
class FocusedAttributionBudgetTest {
    @TempDir Path temp;
    @Test void uncachedMemberQueriesMeetTheBudgetWithStrictAot()throws Exception{
        StringBuilder source=new StringBuilder("class Large {\n");var offsets=new ArrayList<Integer>();
        for(int method=0;method<100;method++){source.append("int method").append(method).append("(int input) {\n");for(int line=0;line<17;line++)source.append("input += ").append(line).append(";\n");source.append("return ");offsets.add(source.length());source.append("input;\n}\n");}source.append("}\n");
        Path file=temp.resolve("Large.java");Files.writeString(file,source.toString());var text=new SourceText(source.toString());
        try(var daemon=new AotDaemon(temp)){
            long before=System.nanoTime();String session=daemon.request("session.open",Map.of("root",temp.toString())).path("result").path("session").asText();double open=(System.nanoTime()-before)/1e6;
            double[] times=new double[50];
            for(int i=0;i<70;i++){var position=text.position(offsets.get(i));before=System.nanoTime();var response=daemon.request("symbol.atPosition",Map.of("session",session,"path",file.toString(),"line",position.line(),"character",position.character()));double elapsed=(System.nanoTime()-before)/1e6;
                assertThat(response.path("tier").asInt()).isEqualTo(2);assertThat(response.path("warnings").isEmpty()).isTrue();assertThat(response.path("result").path("name").asText()).isEqualTo("input");if(i>=20)times[i-20]=elapsed;
            }
            var status=daemon.request("session.status",Map.of("session",session)).path("result").path("analyzer");
            assertThat(status.path("queries").asLong()).isEqualTo(70);assertThat(status.path("focus_layout_parses").asLong()).isEqualTo(1);
            System.out.println("phase-4-analyzer "+status);
            Arrays.sort(times);var measured=Map.of("session_open_ms",open,"focused_p50_ms",times[25],"focused_p95_ms",times[47],"focused_max_ms",times[49]);
            System.out.println("phase-4-perf "+Json.MAPPER.writeValueAsString(measured));Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/phase-4-perf.json").toFile(),measured);
            assertThat(open).as("session open ms").isLessThan(200);assertThat(times[47]).as("uncached focused attribution p95 ms").isLessThan(50);
        }
    }
}
