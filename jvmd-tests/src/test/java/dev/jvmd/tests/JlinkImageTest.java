package dev.jvmd.tests;

import java.nio.file.Files;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: assembled jlink image and baked compiler exports. */
@Tag("phase-1")
class JlinkImageTest {
    @Test void imageIncludesCompilerDebugAndSqlModulesAndRunsWithBakedExports() throws Exception {
        var java = TestSupport.repo().resolve("jvmd-dist/target/image/bin/java");
        assertThat(java).as("Run jvmd-dist/assemble.sh after mvn -DskipTests install").isExecutable();
        var process = new ProcessBuilder(java.toString(), "--list-modules").redirectErrorStream(true).start();
        String modules = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).isZero();
        assertThat(modules).contains("jdk.compiler@25", "jdk.jdi@25", "java.sql@25", "jdk.jfr@25", "jdk.zipfs@25");
        String script = Files.readString(TestSupport.repo().resolve("jvmd-dist/assemble.sh"));
        assertThat(script).contains("--add-options=", "com.sun.tools.javac.api", "com.sun.tools.javac.util", "com.sun.tools.javac.code");
    }
}
