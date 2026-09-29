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

/** A second reader attaches while the first writer keeps an unsaved buffer alive. */
class RetainedSessionAttachTest {
    @TempDir Path temp;

    @Test void attachPreservesUnsavedStateWhileDisposalRecreatesIt() throws Exception {
        Path root=Files.createDirectory(temp.resolve("project"));
        Path file=root.resolve("Value.java");
        String disk="public class Value { public String marker = \"disk\"; }";
        String live="public class Value { public int marker = 42; }";
        Files.writeString(file,disk);
        try(var app=new Application(TestSupport.config(temp,Duration.ofHours(1)))) {
            String first=TestSupport.open(app,root);
            request(app,"document.open",Map.of("session",first,"path",file.toString(),"version",7,"text",live));
            assertThat(hover(app,first,file,live)).contains("int", "marker").doesNotContain("String");
            JsonNode before=request(app,"session.status",Map.of("session",first));
            String second=TestSupport.open(app,root.resolve("."));
            assertThat(second).isEqualTo(first);
            JsonNode after=request(app,"session.status",Map.of("session",second));
            assertThat(after.path("documents")).isEqualTo(before.path("documents"));
            assertThat(hover(app,first,file,live)).contains("int", "marker").doesNotContain("String");
            assertThat(hover(app,second,file,live)).contains("int", "marker").doesNotContain("String");
            assertThat(Files.readString(file)).isEqualTo(disk);
            JsonNode diagnostics=request(app,"lsp.diagnostics",Map.of("session",first,"uri",file.toUri().toString())).path("value");
            assertThat(diagnostics.path("version").asInt()).isEqualTo(7);
            request(app,"document.close",Map.of("session",first,"path",file.toString()));
            String normalReattach=TestSupport.open(app,root);
            assertThat(normalReattach).isEqualTo(first);
            assertThat(hover(app,normalReattach,file,disk)).contains("String", "marker");
            request(app,"session.close",Map.of("session",first));
            String disposed=TestSupport.open(app,root);
            assertThat(disposed).isNotEqualTo(first);
            assertThat(hover(app,disposed,file,disk)).contains("String", "marker");
        }
    }
    private static String hover(Application app,String session,Path file,String text) {
        return request(app,"lsp.request",Map.of("session",session,"method","textDocument/hover",
                "params",Map.of("textDocument",Map.of("uri",file.toUri().toString()),
                        "position",Documents.position(text,text.indexOf("marker")+1)))).path("value").toString();
    }
    private static JsonNode request(Application app,String method,Object params) {
        JsonNode response=TestSupport.request(app.dispatcher(),method,params);
        assertThat(response.has("error")).as(response.toPrettyString()).isFalse();
        return response.path("result").path("result");
    }
}
