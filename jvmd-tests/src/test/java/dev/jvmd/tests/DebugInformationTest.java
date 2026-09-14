package dev.jvmd.tests;

import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 10: line tables alone do not satisfy the local-variable precondition. */
@Tag("phase-10")
class DebugInformationTest {
    @TempDir Path root;
    @Test void refusesEvaluationWhenTheClassWasCompiledWithoutVariableTables()throws Exception{
        Path source=root.resolve("Probe.java"),classes=Files.createDirectories(root.resolve("classes"));
        String text="public class Probe {\n public static void main(String[] args)throws Exception {System.in.read();\n int number=7;\n System.out.println(number);\n System.in.read();\n }\n}";
        Files.writeString(source,text);assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-g:lines,source","-d",classes.toString(),source.toString())).isZero();
        try(var debug=RuntimeFixtures.start(root,new ObjectHandles("probe"))){
            debug.breakpoint("Probe",source,4);debug.input("x");debug.awaitStop(Duration.ofSeconds(10));
            assertThat(debug.status()).containsEntry("debug","no_local_variables");assertThatThrownBy(()->debug.eval("3+1",null)).hasMessageContaining("unsupported_capability");
        }
    }
}
