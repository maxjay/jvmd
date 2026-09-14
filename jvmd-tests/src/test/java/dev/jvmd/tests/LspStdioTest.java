package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 9: editor framing and unsaved hover, definition, diagnostics and rename on strict AOT. */
@Tag("phase-9")
class LspStdioTest {
    @TempDir Path root;
    @Test void nativeEditorRequestsReachTheResidentDaemon()throws Exception{
        Path workspace=Files.createDirectories(root.resolve("workspace")),file=workspace.resolve("Example.java");
        String source="class Example { int value(){return 1;} int use(){return value();} }";Files.writeString(file,source);
        try(var daemon=new AotDaemon(root)){
            Process shim=new ProcessBuilder("node",TestSupport.repo().resolve("shim/src/main.ts").toString(),"--lsp","--root",workspace.toString(),"--socket",root.resolve("semantic.sock").toString()).redirectError(root.resolve("lsp.stderr").toFile()).start();
            var messages=new LinkedBlockingQueue<JsonNode>();var failure=new CompletableFuture<Throwable>();
            Thread reader=Thread.ofVirtual().start(()->{try{byte[] bytes;while((bytes=Framing.read(shim.getInputStream()))!=null){assertThat(bytes.length).isLessThanOrEqualTo(65536);messages.add(Json.MAPPER.readTree(bytes));}}catch(Throwable error){failure.complete(error);}});
            try(var output=shim.getOutputStream()){
                var initialized=request(output,messages,1,"initialize",Map.of("rootUri",workspace.toUri().toString(),"capabilities",Map.of("workspace",Map.of("workspaceEdit",Map.of("documentChanges",true)))));
                assertThat(initialized.path("capabilities").path("textDocumentSync").path("change").asInt()).isEqualTo(2);assertThat(initialized.path("capabilities").path("semanticTokensProvider").path("legend").path("tokenTypes").size()).isEqualTo(11);
                String unsaved=source.replace("return 1;","return missing;");
                notify(output,"textDocument/didOpen",Map.of("textDocument",Map.of("uri",file.toUri().toString(),"languageId","java","version",1,"text",unsaved)));
                JsonNode diagnostics=next(messages);assertThat(diagnostics.path("method").asText()).isEqualTo("textDocument/publishDiagnostics");assertThat(diagnostics.path("params").path("version").asInt()).isEqualTo(1);assertThat(diagnostics.path("params").path("diagnostics").get(0).path("code").asText()).contains("cant.resolve");
                notify(output,"textDocument/didChange",Map.of("textDocument",Map.of("uri",file.toUri().toString(),"version",2),"contentChanges",List.of(Map.of("text",source))));
                var position=Map.of("textDocument",Map.of("uri",file.toUri().toString()),"position",Map.of("line",0,"character",source.lastIndexOf("value")+1));
                var hover=request(output,messages,2,"textDocument/hover",position);assertThat(hover.path("contents").path("value").asText()).contains("value");
                var definition=request(output,messages,3,"textDocument/definition",position);assertThat(definition.path("uri").asText()).isEqualTo(file.toUri().toString());
                var renameParams=new LinkedHashMap<String,Object>(position);renameParams.put("newName","answer");
                var rename=request(output,messages,4,"textDocument/rename",renameParams);assertThat(rename.path("documentChanges").get(0).path("edits").size()).isEqualTo(2);assertThat(rename.path("documentChanges").get(0).path("textDocument").path("version").asInt()).isEqualTo(2);
                assertThat(Files.readString(file)).isEqualTo(source);
                request(output,messages,5,"shutdown",Map.of());notify(output,"exit",Map.of());assertThat(shim.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(shim.exitValue()).as(Files.readString(root.resolve("lsp.stderr"))).isZero();
                assertThat(failure.isDone()).isFalse();
            }finally{shim.destroy();if(!shim.waitFor(5,TimeUnit.SECONDS))shim.destroyForcibly();reader.join(1000);}
        }
    }
    private static void notify(OutputStream output,String method,Map<String,?> params)throws Exception{Framing.write(output,Json.MAPPER.writeValueAsBytes(Map.of("jsonrpc","2.0","method",method,"params",params)));}
    private static JsonNode next(BlockingQueue<JsonNode> messages)throws Exception{var result=messages.poll(30,TimeUnit.SECONDS);assertThat(result).isNotNull();return result;}
    private static JsonNode request(OutputStream output,BlockingQueue<JsonNode> messages,int id,String method,Map<String,?> params)throws Exception{
        Framing.write(output,Json.MAPPER.writeValueAsBytes(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params)));
        JsonNode result;do{result=next(messages);}while(!result.has("id"));assertThat(result.path("id").asInt()).isEqualTo(id);assertThat(result.has("error")).as(result.toString()).isFalse();return result.path("result");
    }
}
