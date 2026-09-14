package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8 exit: an agent edits the pinned PetClinic corpus through the frozen tool contract. */
@Tag("phase-8") @Tag("corpus")
class CorpusAgentSessionTest {
    @TempDir Path temp;
    @Test void callersThreeDeepBodyReplacementAndVerifiedBuildCompleteOnPetclinic()throws Exception{
        Path root=TestSupport.repo().resolve("jvmd-tests/corpus/petclinic"),source=root.resolve("src/main/java/org/springframework/samples/petclinic/owner/Owner.java");String before=Files.readString(source);
        var config=new Config(Path.of(System.getProperty("java.home")),null,Path.of(System.getProperty("user.home"),".m2/repository"),3,Duration.ofHours(4),1024,false,temp,temp.resolve("daemon.sock"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,root);var found=tool(app,session,"find",Map.of("name_path","Owner/getPets()","scope","workspace"));
            assertThat(found.path("result").path("matches").size()).isEqualTo(1);
            var params=new LinkedHashMap<String,Object>();params.put("ref","Owner/getPets()");params.put("direction","in");params.put("depth",3);params.put("kinds",List.of("calls"));params.put("limit",3);
            var edges=new HashSet<String>();int pages=0;boolean more;
            do{
                var references=tool(app,session,"references",params);for(var edge:references.path("result").path("edges"))edges.add(edge.toString());more=references.path("truncated").asBoolean();
                if(more)params.put("cursor",references.path("cursor").asText());assertThat(++pages).isLessThan(100);
            }while(more);
            assertThat(edges.size()).isGreaterThan(3);assertThat(edges.toString()).contains("getPets","addPet");
            var edited=tool(app,session,"replace_body",Map.of("ref","Owner/getPets()","body","{\n\t\treturn java.util.Objects.requireNonNull(this.pets);\n\t}"));
            assertThat(edited.path("result").path("applied").asBoolean()).isTrue();assertThat(edited.path("result").path("diagnostics").isEmpty()).as(edited.toString()).isTrue();assertThat(Files.readString(source)).contains("Objects.requireNonNull(this.pets)");
            var verified=tool(app,session,"diagnostics",Map.of("verified",true));assertThat(verified.path("source").asText()).isEqualTo("verified");assertThat(verified.path("result").path("diagnostics").isEmpty()).isTrue();
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/agent-session.json").toFile(),Map.of("corpus","petclinic","caller_edges",edges.size(),"pages",pages,"verified",verified));
        }finally{Files.writeString(source,before);}
    }
    private static JsonNode tool(Application app,String session,String name,Map<String,Object> arguments)throws Exception{
        var response=TestSupport.request(app.dispatcher(),"mcp.invoke",Map.of("session",session,"name",name,"arguments",arguments));
        assertThat(response.has("error")).as(response.toPrettyString()).isFalse();var envelope=response.path("result");assertThat(envelope.path("warnings").toString()).doesNotContain("analyzer_fault");
        var payload=Map.of("content",List.of(Map.of("type","text","text",Json.MAPPER.writeValueAsString(envelope))),"isError",false);
        assertThat(Json.MAPPER.writeValueAsBytes(payload).length).isLessThanOrEqualTo(65536);return envelope;
    }
}
