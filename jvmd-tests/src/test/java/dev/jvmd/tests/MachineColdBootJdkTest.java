package dev.jvmd.tests;

import dev.jvmd.boot.cold.machine.MachineColdBoot;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The production MACHINE cold boot over the configured JDK: every module is a MACHINE leaf. */
@Tag("machine-cold-boot")
class MachineColdBootJdkTest {
    @TempDir Path temp;

    @Test void configuredJdkModulesAreMachineLeaves()throws Exception{
        Path jdk=Path.of(System.getProperty("java.home"));
        var boot=new MachineColdBoot(temp.resolve("generation"),Files.createDirectories(temp.resolve("repository")),jdk,128L*1024*1024);
        try(var storage=boot.run()){
            assertThat(boot.faults()).isEmpty();
            int feature=Runtime.version().feature();
            var base=storage.machine().leafAt("jrt:/java.base");
            assertThat(base).isNotNull();
            assertThat(base.paths().getFirst().gav()).isEqualTo("jdk:java.base:"+feature);
            assertThat(storage.machine().leafAt("jrt:/java.compiler")).isNotNull();
            var string=storage.store().find("java.lang.String",null,false,1,0,Set.of("class")).getFirst();
            assertThat(string.get("scip")).isEqualTo("maven jdk/java.base "+feature+" java/lang/String#");
            assertThat(string.get("doc")).isNotNull();
            assertThat(string.get("source_file").toString()).endsWith("src.zip!/java.base/java/lang/String.java");
        }
    }
}
