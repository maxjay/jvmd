package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4: live and verified errors carry the same javac codes and locations. */
@Tag("phase-4")
class LiveVerifiedDiagnosticsTest {
    @TempDir Path root;
    @Test void compilerCodesSurviveTheRealMavenVerificationProcess()throws Exception{
        Files.writeString(root.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>diagnostics</artifactId><version>1</version>
                <properties><maven.compiler.release>25</maven.compiler.release></properties>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin></plugins></build></project>
                """);
        Path file=Files.createDirectories(root.resolve("src/main/java")).resolve("Example.java");
        Files.writeString(file,"class Example { int value() { return missing; } }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);assertThat(session).isNotBlank();
            var live=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"paths",List.of(file.toString()))).path("result");
            assertThat(live.path("tier").asInt()).isEqualTo(2);assertThat(live.path("warnings").isEmpty()).isTrue();
            var expected=live.path("result").path("diagnostics").get(0);assertThat(expected.path("code").asText()).startsWith("compiler.err.cant.resolve");
            var verified=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));
            assertThat(verified.path("error").path("code").asInt()).withFailMessage(verified.toPrettyString()).isEqualTo(-32004);
            var actual=verified.path("error").path("data").path("result").path("diagnostics");
            assertThat(actual.size()).withFailMessage(verified.toPrettyString()).isEqualTo(1);
            for(String field:List.of("code","file","line","character","kind"))assertThat(actual.get(0).path(field)).as(field).isEqualTo(expected.path(field));
            assertThat(actual.get(0).path("source").asText()).isEqualTo("verified");
            Files.writeString(file,"class Example { int value() { return 42; } }");
            var clean=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));
            assertThat(clean.has("error")).withFailMessage(clean.toPrettyString()).isFalse();
            assertThat(clean.path("result").path("source").asText()).isEqualTo("verified");assertThat(clean.path("result").path("result").path("diagnostics").isEmpty()).isTrue();
            assertThat(TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session)).path("result").path("result").path("diagnostics").isEmpty()).isTrue();
        }
    }
    @Test void commandTimeoutStopsItsProcessTree()throws Exception{
        var manifest=Json.MAPPER.createObjectNode();manifest.putArray("verify_command").add("sh").add("-c").add("sleep 30");
        var result=new Verifier(TestSupport.config(root,Duration.ofHours(4))).verify(root,manifest,Duration.ofMillis(100));
        assertThat(result.timedOut()).isTrue();assertThat(result.exitCode()).isEqualTo(-1);assertThat(result.elapsedMillis()).isLessThan(5000);assertThat(result.warnings()).anyMatch(w->w.startsWith("verify_timeout:"));
        assertThat(Verifier.split("mvn -q '-Dname=two words' test-compile")).containsExactly("mvn","-q","-Dname=two words","test-compile");
    }
}
