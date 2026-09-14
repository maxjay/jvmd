package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Json;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: the TypeScript stdio bridge reaches the actual strict-AOT daemon. */
@Tag("phase-8")
class McpStdioTest {
    @TempDir Path root;
    @Test void findCallersThreeDeepEditAndVerifyWithoutSchemaErrors()throws Exception{
        Path workspace=Files.createDirectories(root.resolve("workspace")),source=workspace.resolve("Example.java"),output=Files.createDirectories(workspace.resolve("classes"));
        Files.writeString(source,"class Example { int value(){return 1;} int first(){return value();} int second(){return first();} int third(){return second();} }");
        Path manifest=Files.createDirectories(workspace.resolve(".jvmd")).resolve("workspace.json");
        Json.MAPPER.writeValue(manifest.toFile(),Map.of("verify_command",List.of(Path.of(System.getProperty("java.home"),"bin/javac").toString(),"-XDrawDiagnostics","-g","-d",output.toString(),source.toString())));
        try(var daemon=new AotDaemon(root)){
            Process shim=new ProcessBuilder("node",TestSupport.repo().resolve("shim/src/main.ts").toString(),"--mcp","--root",workspace.toString(),"--socket",root.resolve("semantic.sock").toString()).redirectError(root.resolve("shim.stderr").toFile()).start();
            try(var out=shim.outputWriter();var in=shim.inputReader();var readers=Executors.newVirtualThreadPerTaskExecutor()){
                var initialized=request(out,in,readers,1,"initialize",Map.of("protocolVersion","2025-11-25"));assertThat(initialized.path("result").path("capabilities").has("tools")).isTrue();
                var listed=request(out,in,readers,2,"tools/list",Map.of());assertThat(listed.path("result").path("tools").size()).isEqualTo(14);
                var found=tool(out,in,readers,3,"find",Map.of("name_path","Example/value()"));assertThat(found.path("result").path("matches").size()).isEqualTo(1);
                var references=tool(out,in,readers,4,"references",Map.of("ref","Example/value()","direction","in","depth",3,"kinds",List.of("calls"),"limit",2));
                var edges=new HashSet<String>();for(var edge:references.path("result").path("edges"))edges.add(edge.toString());
                while(references.path("truncated").asBoolean()){
                    references=tool(out,in,readers,5,"references",Map.of("ref","Example/value()","direction","in","depth",3,"kinds",List.of("calls"),"limit",2,"cursor",references.path("cursor").asText()));
                    for(var edge:references.path("result").path("edges"))edges.add(edge.toString());
                }
                assertThat(edges).hasSize(3);assertThat(edges.toString()).contains("third","value");
                var edited=tool(out,in,readers,6,"replace_body",Map.of("ref","Example/value()","body","{return 42;}"));assertThat(edited.path("result").path("applied").asBoolean()).isTrue();assertThat(edited.path("result").path("diagnostics").isEmpty()).isTrue();
                var verified=tool(out,in,readers,7,"diagnostics",Map.of("verified",true));assertThat(verified.path("source").asText()).isEqualTo("verified");assertThat(verified.path("result").path("exit_code").asInt()).isZero();assertThat(output.resolve("Example.class")).exists();
            }finally{shim.destroy();if(!shim.waitFor(5,TimeUnit.SECONDS))shim.destroyForcibly();}
        }
    }
    private static JsonNode tool(BufferedWriter out,BufferedReader in,ExecutorService readers,int id,String name,Map<String,Object> arguments)throws Exception{
        var response=request(out,in,readers,id,"tools/call",Map.of("name",name,"arguments",arguments));assertThat(response.path("result").path("isError").asBoolean()).as(response.toString()).isFalse();
        assertThat(Json.MAPPER.writeValueAsBytes(response.path("result")).length).isLessThanOrEqualTo(65536);var envelope=Json.MAPPER.readTree(response.path("result").path("content").get(0).path("text").asText());assertThat(envelope.has("tier")).isTrue();return envelope;
    }
    private static JsonNode request(BufferedWriter out,BufferedReader in,ExecutorService readers,int id,String method,Map<String,Object> params)throws Exception{
        out.write(Json.MAPPER.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params)));out.newLine();out.flush();
        String line=readers.submit(in::readLine).get(30,TimeUnit.SECONDS);assertThat(line).isNotNull();var response=Json.MAPPER.readTree(line);assertThat(response.has("error")).as(response.toString()).isFalse();assertThat(response.path("id").asInt()).isEqualTo(id);return response;
    }
}
