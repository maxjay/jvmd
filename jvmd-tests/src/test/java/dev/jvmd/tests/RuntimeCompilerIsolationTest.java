package dev.jvmd.tests;

import dev.jvmd.core.RpcException;
import dev.jvmd.runtime.RuntimeCompiler;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.7 and 8: faster compilation preserves SDK APIs and keeps processors outside the daemon. */
@Tag("phase-10")
class RuntimeCompilerIsolationTest {
    @TempDir Path root;
    @Test void compileClasspathNeverLeaksDaemonClassesAndHonorsReleaseApis()throws Exception{
        Path source=root.resolve("Probe.java");Files.writeString(source,"class Probe { dev.jvmd.core.Config leaked; }");
        assertThatThrownBy(()->compile(source,List.of(),List.of())).isInstanceOf(RpcException.class).satisfies(error->assertThat(((RpcException)error).data().toString()).contains("doesnt.exist"));
        Files.writeString(source,"class Probe { java.beans.BeanInfo metadata; java.util.List<String> values; }");
        var result=compile(source,List.of(),List.of("--release","17"));assertThat(result.mode()).isEqualTo("in_process");
        byte[] bytes=result.classes().get("Probe.class");assertThat(((bytes[6]&255)<<8)|(bytes[7]&255)).isEqualTo(61);
    }
    @Test void discoveredProcessorsAreDisabledAndExplicitProcessorsRunInAChildJvm()throws Exception{
        Path processorSource=root.resolve("Marker.java"),classes=Files.createDirectories(root.resolve("processor-classes")),jar=root.resolve("processor.jar"),marker=root.resolve("processor-pid");
        Files.writeString(processorSource,"""
            import java.util.*;
            import javax.annotation.processing.*;
            import javax.lang.model.*;
            import javax.lang.model.element.*;
            public class Marker extends AbstractProcessor {
              private boolean done;
              public Set<String> getSupportedAnnotationTypes(){return Set.of("*");}
              public Set<String> getSupportedOptions(){return Set.of("marker");}
              public SourceVersion getSupportedSourceVersion(){return SourceVersion.latestSupported();}
              public boolean process(Set<? extends TypeElement> annotations,RoundEnvironment round){
                if(!done){done=true;try{java.nio.file.Files.writeString(java.nio.file.Path.of(processingEnv.getOptions().get("marker")),Long.toString(ProcessHandle.current().pid()));}catch(Exception error){throw new RuntimeException(error);}}
                return false;
              }
            }
            """);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",classes.toString(),processorSource.toString())).isZero();
        try(var output=new JarOutputStream(Files.newOutputStream(jar))){
            output.putNextEntry(new JarEntry("Marker.class"));Files.copy(classes.resolve("Marker.class"),output);output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor"));output.write("Marker\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));output.closeEntry();
        }
        Path source=root.resolve("Probe.java");Files.writeString(source,"class Probe {}");
        assertThat(compile(source,List.of(jar),List.of("-Amarker="+marker)).mode()).isEqualTo("in_process");assertThat(marker).doesNotExist();
        assertThat(compile(source,List.of(jar),List.of("-proc:full","-processor","Marker","-Amarker="+marker)).mode()).isEqualTo("external");
        assertThat(Long.parseLong(Files.readString(marker))).isNotEqualTo(ProcessHandle.current().pid());
    }
    private RuntimeCompiler.Compilation compile(Path source,List<Path> classpath,List<String> options)throws Exception{
        return RuntimeCompiler.compile(Path.of(System.getProperty("java.home")),root,List.of(source),classpath,List.of(),options,Duration.ofSeconds(30));
    }
}
