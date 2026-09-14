package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 5: processors cannot terminate or indefinitely occupy the daemon JVM. */
@Tag("phase-5")
class ExternalAnnotationProcessingTest {
    @TempDir Path root;
    private AnnotationProcessing.Request request(String name,String body)throws Exception{
        Path directory=Files.createDirectories(root.resolve(name)),classes=Files.createDirectories(directory.resolve("processor")),src=Files.createDirectories(directory.resolve("src"));
        Path file=directory.resolve(name+".java");
        Files.writeString(file,"import javax.annotation.processing.*;import javax.lang.model.*;import javax.lang.model.element.*;import java.util.*;@SupportedAnnotationTypes(\"*\") @SupportedSourceVersion(SourceVersion.RELEASE_25) public class "+name+" extends AbstractProcessor { public boolean process(Set<? extends TypeElement> annotations,RoundEnvironment round){"+body+"} }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",classes.toString(),file.toString())).isZero();
        Files.writeString(src.resolve("Input.java"),"class Input {}");
        return new AnnotationProcessing.Request(name,directory,List.of(src),List.of(),List.of(classes),List.of(name),List.of("--release","25"),false);
    }
    @Test void processorExitIsContainedAndOutputComesFromDisk()throws Exception{
        var request=request("ExitProcessor","System.out.println(\"processor-pid=\"+ProcessHandle.current().pid()); System.exit(37); return false;");
        try(var processors=new AnnotationProcessing(TestSupport.config(root,Duration.ofHours(4)))){
            var output=processors.prepare(request,Duration.ofSeconds(10));assertThat(output.exitCode()).isEqualTo(37);assertThat(output.log()).contains("processor-pid=").doesNotContain("processor-pid="+ProcessHandle.current().pid()+"\n");
            assertThat(output.warnings().toString()).contains("annotation_processing_failed");assertThat(processors.prepare(request,Duration.ofSeconds(10)).fingerprint()).isEqualTo(output.fingerprint());assertThat(processors.status().get("runs")).isEqualTo(1L);
        }
    }
    @Test void spinningProcessorIsKilledAtItsDeadline()throws Exception{
        var request=request("SpinProcessor","while(true){Thread.onSpinWait();}");
        try(var processors=new AnnotationProcessing(TestSupport.config(root,Duration.ofHours(4)))){
            long start=System.nanoTime();var output=processors.prepare(request,Duration.ofMillis(700));assertThat(output.timedOut()).isTrue();assertThat((System.nanoTime()-start)/1e6).isLessThan(6000);
            assertThat(output.warnings().toString()).contains("annotation_processing_timeout");
        }
    }
}
