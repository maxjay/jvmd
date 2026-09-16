package dev.jvmd.tests;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Request-level diagnostics preparation must not repeatedly invoke Maven resolution per source file. */
@Tag("phase-2")
class DiagnosticRequestContextTest {
    @TempDir Path temp;

    @Test void workspaceResolutionIsMemoizedWithinOneDiagnosticRpc()throws Exception{
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
            long calls=before.path("resolve_calls").asLong(),hits=before.path("request_cache_hits").asLong();
            daemon.request("diag.get",Map.of("session",session,"limit",1000));
            var after=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            assertThat(after.path("resolve_calls").asLong()-calls).as("Maven resolves per diag.get").isEqualTo(1L);
            assertThat(after.path("request_cache_hits").asLong()-hits).as("per-file refreshes served from request snapshot").isGreaterThanOrEqualTo(3L);
        }
    }
}
