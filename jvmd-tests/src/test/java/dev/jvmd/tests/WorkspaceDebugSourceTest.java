package dev.jvmd.tests;

import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 6 exit: a line breakpoint in another repository binds and opens that source. */
@Tag("phase-6") @Tag("phase-10")
class WorkspaceDebugSourceTest {
    @TempDir Path root;
    @Test void aBreakpointInTheDependencyRepositoryBinds()throws Exception{
        Path a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b")),classes=Files.createDirectories(root.resolve("classes"));
        Path main=OverlayFixtures.source(a,"Main","public class Main { public static void main(String[] args)throws Exception { System.out.println(\"READY\"); while(System.in.read()!=-1) Library.value(); } }");
        Path library=OverlayFixtures.source(b,"Library","public class Library {\n  public static int value(){\n    int seed=73;\n    return seed;\n  }\n}");
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",classes.toString(),main.toString(),library.toString())).isZero();
        var lookup=new SourceLookup(List.of(a.resolve("src/main/java"),b.resolve("src/main/java")));
        try(var debug=new DebugSession("fixture",new DebugSession.Launch(Path.of(System.getProperty("java.home")),root,List.of(classes),"p.Main",List.of(),true),lookup)){
            var breakpoint=debug.breakpoint("p.Library",library,5);debug.input("x");debug.awaitStop(Duration.ofSeconds(10));
            var frames=dev.jvmd.core.Json.MAPPER.valueToTree(debug.frames(null,0,10).result()).path("frames");assertThat(frames.get(0).path("source_file").asText()).isEqualTo(library.toString());
            assertThat(frames.get(0).path("scip").asText()).contains("p/Library#value().");
            var locals=dev.jvmd.core.Json.MAPPER.valueToTree(debug.locals(null,0,10).result());assertThat(locals.toString()).contains("seed","73");
            assertThat(debug.unbreak(breakpoint.get("breakpoint").toString())).isTrue();debug.resume(null);
        }
    }
}
