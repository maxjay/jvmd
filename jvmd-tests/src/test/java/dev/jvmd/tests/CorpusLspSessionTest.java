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

/** Implements phase 9 exit: diagnostics, hover, definition and rename on the pinned PetClinic corpus. */
@Tag("phase-9") @Tag("corpus")
class CorpusLspSessionTest {
    @TempDir Path temp;
    @Test void corpusEditorQueriesFollowUnsavedBuffersAndReturnApplicableRenameEdits()throws Exception{
        Path root=TestSupport.repo().resolve("jvmd-tests/corpus/petclinic"),file=root.resolve("src/main/java/org/springframework/samples/petclinic/owner/Owner.java");String source=Files.readString(file);
        var config=new Config(Path.of(System.getProperty("java.home")),null,Path.of(System.getProperty("user.home"),".m2/repository"),3,Duration.ofHours(4),1024,false,temp,temp.resolve("daemon.sock"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,root);
            var described=request(app,"symbol.describe",Map.of("session",session,"ref","Owner/getPets()","doc_depth",0));int start=described.path("body_start").asInt(),end=described.path("body_end").asInt();assertThat(start).isPositive();assertThat(end).isGreaterThan(start);
            String unsaved=source.substring(0,start)+"{return unknownEditorPet;}"+source.substring(end);
            request(app,"document.open",Map.of("session",session,"path",file.toString(),"version",1,"text",unsaved));
            var problems=request(app,"lsp.diagnostics",Map.of("session",session,"uri",file.toUri().toString())).path("value");assertThat(problems.path("diagnostics").toString()).contains("cant.resolve");assertThat(problems.path("version").asInt()).isEqualTo(1);
            request(app,"document.change",Map.of("session",session,"path",file.toString(),"version",2,"changes",List.of(Map.of("text",source))));
            var clean=request(app,"lsp.diagnostics",Map.of("session",session,"uri",file.toUri().toString())).path("value");assertThat(clean.path("diagnostics").isEmpty()).as(clean.toPrettyString()).isTrue();
            var params=new LinkedHashMap<String,Object>();params.put("textDocument",Map.of("uri",file.toUri().toString()));params.put("position",Documents.position(source,source.indexOf("getPets(")+1));
            var client=Map.of("workspace",Map.of("workspaceEdit",Map.of("documentChanges",true)));
            var hover=request(app,"lsp.request",Map.of("session",session,"method","textDocument/hover","params",params,"client",client)).path("value");assertThat(hover.path("contents").path("value").asText()).contains("getPets");
            var definition=request(app,"lsp.request",Map.of("session",session,"method","textDocument/definition","params",params,"client",client)).path("value");assertThat(definition.path("uri").asText()).isEqualTo(file.toUri().toString());
            params.put("newName","getPetsForEditor");
            var rename=request(app,"lsp.request",Map.of("session",session,"method","textDocument/rename","params",params,"client",client)).path("value");assertThat(rename.path("documentChanges").size()).isGreaterThan(1);assertThat(rename.toString()).contains("getPetsForEditor");assertThat(Files.readString(file)).isEqualTo(source);
            request(app,"document.close",Map.of("session",session,"path",file.toString()));
            var verified=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));assertThat(verified.has("error")).as(verified.toPrettyString()).isFalse();assertThat(verified.path("result").path("source").asText()).isEqualTo("verified");
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/editor-session.json").toFile(),Map.of("corpus","petclinic","diagnostics",clean,"definition",definition,"rename_files",rename.path("documentChanges").size(),"verified",verified.path("result")));
        }
    }
    private static JsonNode request(Application app,String method,Map<String,Object> params)throws Exception{
        var response=TestSupport.request(app.dispatcher(),method,params);assertThat(response.has("error")).as(response.toPrettyString()).isFalse();var envelope=response.path("result");
        assertThat(envelope.path("warnings").toString()).doesNotContain("analyzer_fault");assertThat(envelope.path("truncated").asBoolean()).as(response.toPrettyString()).isFalse();assertThat(Json.MAPPER.writeValueAsBytes(response).length).isLessThanOrEqualTo(65536);return envelope.path("result");
    }
}
