package dev.jvmd.tests;

import dev.jvmd.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: Unicode byte budgets, complete list pages, scoped replay and paged errors. */
@Tag("phase-8")
class ResponseBudgetTest {
    @TempDir Path root;
    @Test void continuationsDoNotRepeatMutationsAndKeepTheNativeCursor()throws Exception{
        try(var sessions=new Sessions()){
            var first=sessions.open(root);var second=sessions.open(Files.createDirectories(root.resolve("other")));var dispatcher=new Dispatcher(sessions,new Metrics());var calls=new AtomicInteger();
            var items=new ArrayList<Map<String,Object>>();for(int i=0;i<300;i++)items.add(Map.of("id",i,"value","🙂".repeat(300)));
            dispatcher.register("edit.large",(session,args)->{calls.incrementAndGet();return new Envelope(2,"live",true,"native-next",List.of(),Map.of("items",items));});
            var response=request(dispatcher,"edit.large",Map.of("session",first.id()));var ids=new ArrayList<Integer>();String next;
            do{
                var envelope=response.path("result");assertThat(Json.MAPPER.writeValueAsBytes(envelope.path("result")).length).isLessThanOrEqualTo(ResponseBudget.MAX_BYTES);
                envelope.path("result").path("items").forEach(item->ids.add(item.path("id").asInt()));next=envelope.path("cursor").asText();if(!next.startsWith("budget:"))break;
                var wrong=request(dispatcher,"edit.large",Map.of("session",second.id(),"cursor",next));assertThat(wrong.path("error").path("code").asInt()).isEqualTo(-32602);assertThat(wrong.has("result")).isFalse();
                response=request(dispatcher,"edit.large",Map.of("cursor",next,"session",first.id()));
            }while(true);
            assertThat(ids).containsExactlyElementsOf(java.util.stream.IntStream.range(0,300).boxed().toList());assertThat(calls.get()).isEqualTo(1);assertThat(next).isEqualTo("native-next");
        }
    }
    @Test void longUnicodeTextAndWarningsHaveResumableOffsets()throws Exception{
        try(var sessions=new Sessions()){
            var session=sessions.open(root);var dispatcher=new Dispatcher(sessions,new Metrics());String body="🙂abc".repeat(30000);
            dispatcher.register("probe.text",(s,p)->Envelope.of(2,"live",Map.of("body",body)));
            var chunks=new TreeMap<Integer,String>();String cursor=null;int pages=0;
            do{
                var args=new LinkedHashMap<String,Object>();args.put("session",session.id());if(cursor!=null)args.put("cursor",cursor);var response=request(dispatcher,"probe.text",args).path("result");var result=response.path("result");
                assertThat(Json.MAPPER.writeValueAsBytes(result).length).isLessThanOrEqualTo(ResponseBudget.MAX_BYTES);var segment=result.path("_jvmd_segments").path("/payload/body");chunks.put(segment.path("start").asInt(),result.path("body").asText());cursor=response.path("truncated").asBoolean()?response.path("cursor").asText():null;assertThat(++pages).isLessThan(100);
            }while(cursor!=null);
            assertThat(String.join("",chunks.values())).isEqualTo(body);
            dispatcher.register("probe.warning",(s,p)->new Envelope(1,"live",false,null,List.of("warning "+"界".repeat(30000)),Map.of("ok",true)));
            var warning=request(dispatcher,"probe.warning",Map.of("session",session.id())).path("result");assertThat(warning.path("truncated").asBoolean()).isTrue();assertThat(warning.path("cursor").asText()).startsWith("budget:");
            assertThat(Json.MAPPER.writeValueAsBytes(warning).length).isLessThan(ResponseBudget.MAX_BYTES+1024);
        }
    }
    @Test void verificationErrorsStayErrorsAcrossPagesAndRunOnlyOnce()throws Exception{
        try(var sessions=new Sessions()){
            var session=sessions.open(root);var dispatcher=new Dispatcher(sessions,new Metrics());var calls=new AtomicInteger();
            dispatcher.register("verify.large",(s,p)->{calls.incrementAndGet();throw new RpcException(-32004,"verify_failed",Map.of("output","line\n".repeat(40000)));});
            String cursor=null;int pages=0;
            do{
                var args=new LinkedHashMap<String,Object>();args.put("session",session.id());if(cursor!=null)args.put("cursor",cursor);var response=request(dispatcher,"verify.large",args);assertThat(response.has("result")).isFalse();assertThat(response.path("error").path("code").asInt()).isEqualTo(-32004);
                var envelope=response.path("error").path("data");assertThat(Json.MAPPER.writeValueAsBytes(envelope.path("result")).length).isLessThanOrEqualTo(ResponseBudget.MAX_BYTES);cursor=envelope.path("truncated").asBoolean()?envelope.path("cursor").asText():null;assertThat(++pages).isLessThan(30);
            }while(cursor!=null);
            assertThat(calls.get()).isEqualTo(1);
        }
    }
    private static JsonNode request(Dispatcher dispatcher,String method,Map<String,?> args){var request=Json.MAPPER.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method",method);request.set("params",Json.MAPPER.valueToTree(args));return dispatcher.dispatch(request);}
}
