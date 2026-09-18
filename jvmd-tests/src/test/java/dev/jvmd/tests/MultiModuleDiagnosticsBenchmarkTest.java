package dev.jvmd.tests;

import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Real Maven reactor benchmark for incremental workspace diagnostics. */
@Tag("perf")
class MultiModuleDiagnosticsBenchmarkTest {
    @TempDir Path root;

    @Test void multiModuleReactorBatchesColdAnalysisAndDoesZeroWarmSemanticWork()throws Exception{
        Path reactor=MavenFixtures.project(root,
                "<packaging>pom</packaging><modules><module>core</module><module>app</module></modules>"
                +"<properties><maven.compiler.release>25</maven.compiler.release></properties>");
        Path core=MavenFixtures.project(reactor.resolve("core"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties>");
        Path app=MavenFixtures.project(reactor.resolve("app"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties>"
                +"<dependencies>"+MavenFixtures.dependency("core","1")+"</dependencies>");

        Path coreSources=Files.createDirectories(core.resolve("src/main/java/fixture/core"));
        Path appSources=Files.createDirectories(app.resolve("src/main/java/fixture/app"));
        for(int i=0;i<64;i++){
            Files.writeString(coreSources.resolve("Core"+i+".java"),
                    "package fixture.core; public class Core"+i+" { public int value(){ return "+i+"; } }\n");
            String text=i==0
                    ?"package fixture.app; import fixture.core.Core0; class App0 { int value(){ return new Core0().value(); } }\n"
                    :"package fixture.app; class App"+i+" { int value(){ return "+i+"; } }\n";
            Files.writeString(appSources.resolve("App"+i+".java"),text);
        }

        try(var daemon=new AotDaemon(root)){
            var opened=daemon.request("session.open",Map.of("root",reactor.toString())).path("result");
            String session=opened.path("session").asText();
            assertThat(opened.path("modules").asInt()).isGreaterThanOrEqualTo(3);
            assertThat(opened.path("classpath_entries").asInt()).isGreaterThan(0);

            var resolverBefore=daemon.request("daemon.status",Map.of()).path("resolver");
            long resolvesBefore=resolverBefore.path("resolve_calls").asLong();
            var analyzerBefore=daemon.request("session.status",Map.of("session",session)).path("result").path("analyzer");
            long queriesBefore=analyzerBefore.path("queries").asLong();

            long started=System.nanoTime();
            var cold=daemon.request("diag.get",Map.of("session",session,"limit",1000));
            double coldMs=(System.nanoTime()-started)/1e6;
            assertThat(cold.path("result").path("diagnostics").isEmpty()).withFailMessage(cold.toPrettyString()).isTrue();

            var coldStatus=daemon.request("session.status",Map.of("session",session)).path("result");
            long coldQueries=coldStatus.path("analyzer").path("queries").asLong()-queriesBefore;
            assertThat(coldQueries).as("one batched javac task per source-bearing module").isBetween(1L,2L);
            assertThat(coldStatus.path("diagnostics").path("last").path("files_reanalysed").asInt()).isEqualTo(128);
            assertThat(coldStatus.path("diagnostics").path("last").path("modules_batch_analysed").asInt()).isEqualTo(2);
            assertThat(coldStatus.path("analyzer").path("module_compilers").size()).isGreaterThanOrEqualTo(2);

            long warmQueriesBefore=coldStatus.path("analyzer").path("queries").asLong();
            started=System.nanoTime();
            var warm=daemon.request("diag.get",Map.of("session",session,"limit",1000));
            double warmMs=(System.nanoTime()-started)/1e6;
            assertThat(warm.path("result").path("diagnostics").isEmpty()).withFailMessage(warm.toPrettyString()).isTrue();

            var warmStatus=daemon.request("session.status",Map.of("session",session)).path("result");
            assertThat(warmStatus.path("analyzer").path("queries").asLong()).isEqualTo(warmQueriesBefore);
            assertThat(warmStatus.path("diagnostics").path("last").path("files_reanalysed").asInt()).isZero();
            assertThat(warmStatus.path("diagnostics").path("last").path("files_valid").asInt()).isEqualTo(128);

            var resolverAfter=daemon.request("daemon.status",Map.of()).path("resolver");
            assertThat(resolverAfter.path("resolve_calls").asLong()-resolvesBefore)
                    .as("one Maven workspace validation per diag.get")
                    .isEqualTo(2L);

            System.out.println("diagnostics-multimodule "+Json.MAPPER.writeValueAsString(Map.of(
                    "files",128,
                    "source_modules",2,
                    "cold_ms",coldMs,
                    "warm_ms",warmMs,
                    "cold_javac_queries",coldQueries,
                    "warm_additional_javac_queries",0)));
        }
    }
}
