package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4 exit: real clean corpus builds agree with tier-two live diagnostics. */
@Tag("phase-4") @Tag("corpus")
class LiveVerifiedAgreementTest {
    @TempDir Path temp;
    @Test void petclinicAndTheUserReactorAgree()throws Exception{
        var evidence=new ArrayList<Map<String,Object>>();
        for(Path root:List.of(TestSupport.repo().resolve("jvmd-tests/corpus/petclinic"),TestSupport.repo())){
            assertThat(Files.isRegularFile(root.resolve("pom.xml"))).as(root.toString()).isTrue();
            Path state=Files.createDirectories(temp.resolve(root.getFileName().toString()));
            var config=new Config(Path.of(System.getProperty("java.home")),null,Path.of(System.getProperty("user.home"),".m2/repository"),3,Duration.ofHours(4),1024,false,state,state.resolve("daemon.sock"));
            try(var app=new Application(config)){
                String session=TestSupport.open(app,root);assertThat(session).isNotBlank();
                var live=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();String cursor="0";long started=System.nanoTime();
                do{
                    var response=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"cursor",cursor,"limit",1000)).path("result");
                    assertThat(response.path("tier").asInt()).withFailMessage(response.toPrettyString()).isEqualTo(2);
                    assertThat(response.path("warnings").isEmpty()).withFailMessage(response.toPrettyString()).isTrue();
                    response.path("result").path("diagnostics").forEach(live::add);
                    if(!response.path("truncated").asBoolean())break;cursor=response.path("cursor").asText();
                }while(true);
                var verified=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));
                var report=Map.<String,Object>of("root",root.toString(),"live",live,"verified",verified,"elapsed_ms",(System.nanoTime()-started)/1e6);evidence.add(report);
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/agreement.json").toFile(),evidence);
                assertThat(verified.has("error")).withFailMessage(verified.toPrettyString()).isFalse();
                var actual=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();verified.path("result").path("result").path("diagnostics").forEach(actual::add);
                assertThat(live).withFailMessage(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report)).isEmpty();
                assertThat(actual).withFailMessage(verified.toPrettyString()).isEmpty();
                System.out.println("corpus-agreement "+root.getFileName()+" live=0 verified=0");
            }
        }
    }
}
