package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Documents;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class ProjectResolutionHotPathTest {
    @TempDir Path root;

    @Test void ordinaryEditorQueriesConsumeMaintainedResolutionWithoutResolverEntry()throws Exception{
        Path project=MavenFixtures.project(root.resolve("project"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties>");
        Path sourceRoot=Files.createDirectories(project.resolve("src/main/java/p"));
        Path api=Files.writeString(sourceRoot.resolve("Api.java"),
                "package p; class Api { int value(){return 1;} }");
        String useText="package p; class Use { int complete(Api api){ return api.val; } int read(Api api){ return api.value(); } }";
        Path use=Files.writeString(sourceRoot.resolve("Use.java"),useText);

        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,project);
            long before=resolveWorkspaceCalls(app);

            int completionOffset=useText.indexOf("api.val")+"api.val".length();
            int valueOffset=useText.lastIndexOf("value")+2;
            for(int i=0;i<3;i++){
                request(app,session,"textDocument/completion",use,useText,completionOffset,Map.of("context",Map.of("triggerKind",1)));
                request(app,session,"textDocument/hover",use,useText,valueOffset,Map.of());
                request(app,session,"textDocument/definition",use,useText,valueOffset,Map.of());
                request(app,session,"textDocument/references",use,useText,valueOffset,
                        Map.of("context",Map.of("includeDeclaration",true)));
            }
            assertThat(resolveWorkspaceCalls(app))
                    .as("unchanged editor semantic requests must not enter resolveWorkspace")
                    .isEqualTo(before);

            String changed="package p; class Api { int value(){return 2;} }";
            var opened=TestSupport.request(app.dispatcher(),"document.open",Map.of(
                    "session",session,"path",api.toString(),"version",1,"text",changed));
            assertThat(opened.has("error")).as(opened.toString()).isFalse();
            request(app,session,"textDocument/completion",use,useText,completionOffset,Map.of("context",Map.of("triggerKind",1)));
            assertThat(resolveWorkspaceCalls(app))
                    .as("ordinary Java source mutation belongs to semantic state, not Maven resolution")
                    .isEqualTo(before);
        }
    }

    private static JsonNode request(Application app,String session,String method,Path file,String source,int offset,
                                    Map<String,Object> extra){
        var params=new LinkedHashMap<String,Object>(extra);
        params.put("textDocument",Map.of("uri",file.toUri().toString()));
        params.put("position",Documents.position(source,offset));
        var response=TestSupport.request(app.dispatcher(),"lsp.request",Map.of(
                "session",session,"method",method,"params",params,"client",Map.of()));
        assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
        return response.path("result").path("result").path("value");
    }

    private static long resolveWorkspaceCalls(Application app){
        var status=TestSupport.request(app.dispatcher(),"daemon.status",Map.of());
        assertThat(status.has("error")).as(status.toPrettyString()).isFalse();
        return status.path("result").path("result").path("resolver").path("resolve_workspace_calls").asLong();
    }
}
