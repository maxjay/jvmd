package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.mcp.McpTools;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: nested MCP continuations preserve the original mutation and transport budget. */
@Tag("phase-8")
class McpResponseBudgetTest {
    @TempDir Path root;
    @Test void escapedToolResultsStayBoundedAndNeverRepeatAnEdit()throws Exception{
        try(var sessions=new Sessions()){
            String session=sessions.open(root).id();var dispatcher=new Dispatcher(sessions,new Metrics());var calls=new AtomicInteger();
            dispatcher.register("edit.replaceBody",(_,_) -> {calls.incrementAndGet();return Envelope.of(2,"live",Map.of("body","\"\\\n🧪".repeat(12000)));});
            dispatcher.register("mcp.invoke",(s,args)->McpTools.invoke(dispatcher,s.id(),args));
            var arguments=Json.MAPPER.createObjectNode().put("ref","Example/value()").put("body","{}");
            var params=Json.MAPPER.createObjectNode().put("session",session).put("name","replace_body");params.set("arguments",arguments);int pages=0;String cursor;
            do{
                var response=TestSupport.request(dispatcher,"mcp.invoke",params);assertThat(response.has("error")).isFalse();var envelope=response.path("result");
                var content=Map.of("content",List.of(Map.of("type","text","text",Json.MAPPER.writeValueAsString(envelope))),"isError",false);
                assertThat(Json.MAPPER.writeValueAsBytes(content).length).isLessThanOrEqualTo(65536);cursor=envelope.path("cursor").asText("");if(!cursor.isEmpty())arguments.put("cursor",cursor);
                pages++;assertThat(pages).isLessThan(100);
            }while(!cursor.isEmpty()&&!cursor.equals("null"));
            assertThat(pages).isGreaterThan(1);assertThat(calls).hasValue(1);
        }
    }
}
