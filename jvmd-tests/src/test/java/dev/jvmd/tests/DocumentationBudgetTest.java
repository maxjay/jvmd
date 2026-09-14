package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 7 exit: StringUtils signature closure with JDK documentation under strict AOT. */
@Tag("phase-7") @Tag("perf")
class DocumentationBudgetTest {
    @TempDir Path root;
    @Test void springStringClosureMeetsTheFiftyMillisecondBudget()throws Exception{
        Path repository=Path.of(System.getProperty("user.home"),".m2/repository"),spring=repository.resolve("org/springframework/spring-core/7.0.8/spring-core-7.0.8.jar");
        assertThat(spring).exists();Path state=Files.createDirectories(root.resolve("state")),workspace=Files.createDirectories(root.resolve("workspace"));
        try(var index=new IndexService(state.resolve("index.db"),repository)){
            index.indexJar(spring,"org.springframework:spring-core:7.0.8","jar");index.indexSources(spring.resolveSibling("spring-core-7.0.8-sources.jar"));index.linkEdges();
        }
        try(var daemon=new AotDaemon(root,Map.of("jdk_home",System.getProperty("java.home")))){
            String session=daemon.request("session.open",Map.of("root",workspace.toString())).path("result").path("session").asText();double[] times=new double[50];
            for(int i=0;i<70;i++){
                long before=System.nanoTime();var response=daemon.request("symbol.describe",Map.of("session",session,"ref","StringUtils/hasText(String)","doc_depth",3,"detail","summary"));double elapsed=(System.nanoTime()-before)/1e6;if(i>=20)times[i-20]=elapsed;
                assertThat(response.path("tier").asInt()).isEqualTo(2);var result=response.path("result");assertThat(result.path("name").asText()).isEqualTo("hasText");assertThat(result.path("doc").asText()).isNotBlank();assertThat(result.path("closure").size()).isGreaterThan(0);
                boolean found=false;for(var member:result.path("closure")){assertThat(member.path("doc").asText()).as(member.path("name_path").asText()).isNotBlank();if(member.path("name_path").asText().equals("java.lang.String"))found=true;}assertThat(found).isTrue();
            }
            var page=daemon.request("symbol.describe",Map.of("session",session,"ref","StringUtils/hasText(String)","doc_depth",3,"limit",1));assertThat(page.path("truncated").asBoolean()).isTrue();assertThat(page.path("cursor").asText()).isNotBlank();
            Arrays.sort(times);var measured=Map.of("doc_depth",3,"p50_ms",times[25],"p95_ms",times[47],"max_ms",times[49]);System.out.println("phase-7-perf "+Json.MAPPER.writeValueAsString(measured));Json.MAPPER.writeValue(TestSupport.repo().resolve("jvmd-tests/target/phase-7-perf.json").toFile(),measured);assertThat(times[47]).isLessThan(50);
        }
    }
}
