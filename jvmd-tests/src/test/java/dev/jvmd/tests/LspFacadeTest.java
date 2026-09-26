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

/** Implements phase 9: all nine native editor queries share resolved identities and unsaved documents. */
@Tag("phase-9")
class LspFacadeTest {
    @TempDir Path root;
    private static final Map<String,Object> CLIENT=Map.of("textDocument",Map.of("documentSymbol",Map.of("hierarchicalDocumentSymbolSupport",true)),"workspace",Map.of("workspaceEdit",Map.of("documentChanges",true,"resourceOperations",List.of("rename"))));
    private JsonNode call(Application app,String session,String method,Path file,String source,int offset,Map<String,Object> extra){
        var params=new LinkedHashMap<String,Object>(extra);params.put("textDocument",Map.of("uri",file.toUri().toString()));params.put("position",Documents.position(source,offset));
        var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of("session",session,"method",method,"params",params,"client",CLIENT));
        assertThat(response.has("error")).as(response.toString()).isFalse();assertThat(response.path("result").path("warnings").isEmpty()).as(response.toString()).isTrue();
        assertThat(response.path("result").path("truncated").asBoolean()).isFalse();return response.path("result").path("result").path("value");
    }
    @Test void everyFacadeQueryReturnsProtocolDataFromTheCore()throws Exception{
        Path file=root.resolve("Example.java");
        String source="class Example {\n/** Answers with the word length. */\nint value(int count,String word){return count+word.length();}\nint use(){String name=\"x\";return value(1,name);}\n}";
        Files.writeString(file,source);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);int call=source.lastIndexOf("value(");
            var hover=call(app,session,"textDocument/hover",file,source,call+1,Map.of());assertThat(hover.path("contents").path("value").asText()).contains("Answers with the word length","value");
            var definition=call(app,session,"textDocument/definition",file,source,call+1,Map.of());assertThat(definition.path("uri").asText()).isEqualTo(file.toUri().toString());assertThat(definition.path("range").path("start").path("line").asInt()).isEqualTo(2);
            var references=call(app,session,"textDocument/references",file,source,call+1,Map.of("context",Map.of("includeDeclaration",true)));assertThat(references.size()).isEqualTo(2);
            var prepare=call(app,session,"textDocument/prepareRename",file,source,call+1,Map.of());assertThat(prepare.path("placeholder").asText()).isEqualTo("value");
            var rename=call(app,session,"textDocument/rename",file,source,call+1,Map.of("newName","answer"));assertThat(rename.path("documentChanges").get(0).path("edits").size()).isEqualTo(2);assertThat(Files.readString(file)).isEqualTo(source);
            var symbols=call(app,session,"textDocument/documentSymbol",file,source,0,Map.of());assertThat(symbols.size()).isEqualTo(1);assertThat(symbols.get(0).path("children").toString()).contains("value","use");assertThat(symbols.get(0).path("selectionRange").isObject()).isTrue();
            var signature=call(app,session,"textDocument/signatureHelp",file,source,source.lastIndexOf("name);")+2,Map.of());assertThat(signature.path("signatures").size()).isEqualTo(1);assertThat(signature.path("signatures").get(0).path("label").asText()).contains("value","int count","String word");assertThat(signature.path("activeParameter").asInt()).isEqualTo(1);
            var tokens=call(app,session,"textDocument/semanticTokens/full",file,source,0,Map.of());assertThat(tokens.path("data").size()).isGreaterThan(30);assertThat(tokens.path("data").size()%5).isZero();assertThat(tokens.path("resultId").asText()).hasSize(64);
            String unsaved=source.replace("return value(1,name);","return name.len;");
            var opened=TestSupport.request(app.dispatcher(),"document.open",Map.of("session",session,"path",file.toString(),"version",1,"text",unsaved));assertThat(opened.has("error")).isFalse();
            var completion=call(app,session,"textDocument/completion",file,unsaved,unsaved.indexOf("name.len")+8,Map.of());assertThat(completion.path("items").toString()).contains("\"label\":\"length() : int\"");assertThat(completion.path("items").get(0).path("textEdit").path("range").isObject()).isTrue();
            assertThat(Files.readString(file)).isEqualTo(source);
        }
    }
    @Test void completionEnrichesOnlyTheSelectedItemOnResolve()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String apiSource="class Api { /** Greets callers. */ int greet(){return 1;} int grow(){return 2;} }";
        String useSource="class Use { int call(Api api){return api.gre;} }";
        Files.writeString(api,apiSource);Files.writeString(use,useSource);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var completion=call(app,session,"textDocument/completion",use,useSource,useSource.indexOf("api.gre")+7,Map.of());
            JsonNode item=null;for(var candidate:completion.path("items"))if(candidate.path("label").asText().startsWith("greet(")){item=candidate;break;}
            assertThat(item).isNotNull();assertThat(item.has("documentation")).isFalse();assertThat(item.path("detail").asText()).contains("greet");
            String shallowDetail=item.path("detail").asText();
            assertThat(((Map<?,?>)dev.jvmd.lsp.LspFacade.capabilities().get("completionProvider")).get("resolveProvider")).isEqualTo(true);
            var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of("session",session,"method","completionItem/resolve","params",item,"client",CLIENT));
            assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
            var resolved=response.path("result").path("result").path("value");
            assertThat(resolved.path("label").asText()).startsWith("greet(");
            assertThat(resolved.path("detail").asText()).contains("Api","greet").isNotEqualTo(shallowDetail);
            assertThat(resolved.path("documentation").path("value").asText()).contains("Greets callers");
        }
    }

    @Test void fieldResolveUsesMaintainedDeclarationWithoutJavacOrOptionalDocumentation()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String apiSource="class Api { /** Field docs are optional completion enrichment. */ int value; }";
        String useSource="class Use { int call(Api api){return api.val;} }";
        Files.writeString(api,apiSource);Files.writeString(use,useSource);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var completion=call(app,session,"textDocument/completion",use,useSource,useSource.indexOf("api.val")+7,Map.of());
            JsonNode item=null;for(var candidate:completion.path("items"))if(candidate.path("label").asText().startsWith("value")){item=candidate;break;}
            assertThat(item).as(completion.toString()).isNotNull();
            long before=analyzerQueries(app,session);
            var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of(
                    "session",session,"method","completionItem/resolve","params",item,"client",CLIENT));
            assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
            var resolved=response.path("result").path("result").path("value");
            assertThat(resolved.path("detail").asText()).contains("Api","value");
            assertThat(resolved.has("documentation")).isFalse();
            assertThat(analyzerQueries(app,session)).as("exact LIVE field resolve must not enter javac").isEqualTo(before);
        }
    }

    @Test void staleCompletionItemIsRejectedBeforeEnrichment()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String apiSource="class Api { /** Original. */ int greet(){return 1;} }";
        String useSource="class Use { Object call(Api api){return api.gre;} }";
        Files.writeString(api,apiSource);Files.writeString(use,useSource);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var completion=call(app,session,"textDocument/completion",use,useSource,useSource.indexOf("api.gre")+7,Map.of());
            JsonNode item=null;for(var candidate:completion.path("items"))if(candidate.path("label").asText().startsWith("greet(")){item=candidate;break;}
            assertThat(item).as(completion.toString()).isNotNull();
            assertThat(item.path("data").path("resolution_identity").asText()).isNotBlank();

            String changed="class Api { /** Current. */ String greet(){return \"changed\";} }";
            var opened=TestSupport.request(app.dispatcher(),"document.open",Map.of(
                    "session",session,"path",api.toString(),"version",1,"text",changed));
            assertThat(opened.has("error")).as(opened.toString()).isFalse();

            var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of(
                    "session",session,"method","completionItem/resolve","params",item,"client",CLIENT));
            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32801);
            assertThat(response.path("error").path("message").asText()).contains("stale");
        }
    }

    @Test void typeRenameIncludesTheVersionedTextEditAndFileOperation()throws Exception{
        Path file=root.resolve("Original.java");String source="class Original { Original create(){return new Original();} }";Files.writeString(file,source);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);TestSupport.request(app.dispatcher(),"document.open",Map.of("session",session,"path",file.toString(),"version",4,"text",source));
            var rename=call(app,session,"textDocument/rename",file,source,source.lastIndexOf("Original")+2,Map.of("newName","Renamed"));
            assertThat(rename.path("documentChanges").get(0).path("textDocument").path("version").asInt()).isEqualTo(4);assertThat(rename.path("documentChanges").get(1).path("kind").asText()).isEqualTo("rename");
            assertThat(rename.path("documentChanges").get(1).path("newUri").asText()).isEqualTo(root.resolve("Renamed.java").toUri().toString());assertThat(Files.exists(root.resolve("Renamed.java"))).isFalse();
        }
    }
    private static long analyzerQueries(Application app,String session){
        return TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session,"section","analyzer"))
                .path("result").path("result").path("analyzer").path("queries").asLong();
    }

    @Test void aliasedWorkspaceKeepsUnsavedDocumentsAndDiagnosticUris()throws Exception{
        Path actual=Files.createDirectories(root.resolve("workspace")).toRealPath();
        Path alias=Files.createSymbolicLink(root.resolve("linked-workspace"),actual);
        Path file=actual.resolve("Example.java"),clientFile=alias.resolve("Example.java");
        String disk="class Example { int value(){return 1;} }",unsaved=disk.replace("value","answer");
        Files.writeString(file,disk);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,alias);
            var opened=TestSupport.request(app.dispatcher(),"document.open",Map.of("session",session,"path",clientFile.toString(),"version",1,"text",unsaved));
            assertThat(opened.has("error")).as(opened.toString()).isFalse();
            var hover=call(app,session,"textDocument/hover",clientFile,unsaved,unsaved.indexOf("answer")+2,Map.of());
            assertThat(hover.toString()).contains("answer");
            var diagnostic=TestSupport.request(app.dispatcher(),"lsp.diagnostics",Map.of("session",session,"uri",clientFile.toUri().toString()));
            assertThat(diagnostic.has("error")).as(diagnostic.toString()).isFalse();
            var value=diagnostic.path("result").path("result").path("value");
            assertThat(value.path("uri").asText()).isEqualTo(clientFile.toUri().toString());
            assertThat(value.path("version").asInt()).isEqualTo(1);
            assertThat(value.path("diagnostics").isEmpty()).isTrue();
            assertThat(Files.readString(file)).isEqualTo(disk);
        }
    }
}
