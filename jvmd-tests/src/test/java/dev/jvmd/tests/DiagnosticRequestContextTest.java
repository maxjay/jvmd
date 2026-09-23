package dev.jvmd.tests;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Unchanged diagnostics must reuse the resident project model without entering Maven. */
@Tag("phase-2")
class DiagnosticRequestContextTest {
    @TempDir Path temp;

    @Test void workspaceResolutionStaysResidentAcrossUnchangedDiagnosticRpcs()throws Exception{
        Files.writeString(temp.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>diag-context</artifactId><version>1</version>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                </project>
                """);
        Path sources=Files.createDirectories(temp.resolve("src/main/java/fixture"));
        for(int i=0;i<4;i++)Files.writeString(sources.resolve("Source"+i+".java"),"package fixture; class Source"+i+" { int value(){ return "+i+"; } }\n");
        try(var daemon=new AotDaemon(temp)){
            String session=daemon.request("session.open",Map.of("root",temp.toString())).path("result").path("session").asText();
            var before=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            long calls=before.path("resolve_calls").asLong(),hits=before.path("project_model_fast_hits").asLong();
            daemon.request("diag.get",Map.of("session",session,"limit",1000));
            var after=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            assertThat(after.path("resolve_calls").asLong()-calls).as("unchanged diagnostics must not enter Maven resolution").isZero();
            assertThat(after.path("project_model_fast_hits").asLong()).isGreaterThan(hits);
            var status=daemon.request("session.status",Map.of("session",session)).path("result");
            assertThat(status.path("analysis_contexts").path("context_constructions").asLong()).isEqualTo(1L);

            Files.writeString(temp.resolve("pom.xml"),Files.readString(temp.resolve("pom.xml")).replace("</project>","<!-- model change -->\n</project>"));
            daemon.request("diag.get",Map.of("session",session,"limit",1000));
            var changed=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            assertThat(changed.path("resolve_calls").asLong()).isEqualTo(calls+1);
            assertThat(changed.path("project_model_invalidations").asLong()).isPositive();
            long settledCalls=changed.path("resolve_calls").asLong(),settledHits=changed.path("project_model_fast_hits").asLong();
            daemon.request("diag.get",Map.of("session",session,"limit",1000));
            var settled=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            assertThat(settled.path("resolve_calls").asLong()).isEqualTo(settledCalls);
            assertThat(settled.path("project_model_fast_hits").asLong()).isGreaterThan(settledHits);
        }
    }
}
