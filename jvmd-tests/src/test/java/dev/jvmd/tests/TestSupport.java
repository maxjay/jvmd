package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Implements 12.4: isolated machine state and real dispatcher requests for checkpoint tests. */
public final class TestSupport {
    private TestSupport() { }
    public static Path repo() { return Path.of(System.getProperty("basedir")).getParent(); }
    public static Config config(Path temp, Duration idle) {
        return new Config(Path.of(System.getProperty("java.home")), null, temp.resolve("repository"), 3,
                idle, 512, false, temp.resolve("state"), temp.resolve("daemon.sock"));
    }
    public static JsonNode request(Dispatcher dispatcher, String method, Object params) {
        var request = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        request.set("params", Json.MAPPER.valueToTree(params));
        return dispatcher.dispatch(request);
    }
    /** Reassembles transport fragments while leaving the method's native page cursor intact. */
    public static JsonNode complete(Dispatcher dispatcher,String method,Object params) throws Exception {
        ObjectNode arguments=Json.MAPPER.valueToTree(params),merged=Json.MAPPER.createObjectNode();
        var strings=new HashMap<String,TreeMap<Integer,String>>();
        JsonNode response,envelope;long bytes=0;int pages=0;
        do {
            response=request(dispatcher,method,arguments);
            envelope=response.has("error")?response.path("error").path("data"):response.path("result");
            bytes+=Json.MAPPER.writeValueAsBytes(response).length;
            if(bytes>64L*1024*1024||++pages>2048)throw new AssertionError("Continuation exceeded the documented budget");
            var fragment=Json.MAPPER.createObjectNode();fragment.set("payload",envelope.path("result"));fragment.set("warnings",envelope.path("warnings"));
            merge(merged,fragment,"","",envelope.path("result").path("_jvmd_segments"),strings);
            String cursor=envelope.path("cursor").asText();
            if(!envelope.path("truncated").asBoolean()||!cursor.startsWith("budget:"))break;
            arguments.put("cursor",cursor);
        } while(true);
        for(var entry:strings.entrySet()) {
            int split=entry.getKey().lastIndexOf('/');JsonNode parent=merged.at(entry.getKey().substring(0,split));
            String key=entry.getKey().substring(split+1).replace("~1","/").replace("~0","~");
            var value=TextNode.valueOf(String.join("",entry.getValue().values()));
            if(parent.isArray())((ArrayNode)parent).set(Integer.parseInt(key),value);else ((ObjectNode)parent).set(key,value);
        }
        ObjectNode result=response.deepCopy();ObjectNode body=(ObjectNode)(result.has("error")?result.path("error").path("data"):result.path("result"));
        body.set("result",merged.path("payload"));body.set("warnings",merged.path("warnings"));return result;
    }
    private static JsonNode merge(JsonNode target,JsonNode source,String local,String absolute,JsonNode segments,Map<String,TreeMap<Integer,String>> strings) {
        var segment=segments.path(local);
        if(source.isTextual()&&segment.path("kind").asText().equals("string")) {
            strings.computeIfAbsent(absolute,k->new TreeMap<>()).put(segment.path("start").asInt(),source.asText());return source;
        }
        if(source.isArray()) {
            ArrayNode result=target!=null&&target.isArray()?(ArrayNode)target:Json.MAPPER.createArrayNode();
            int offset=segment.path("kind").asText().equals("array")?segment.path("start").asInt():0;
            if(offset<0||offset+source.size()>8*1024*1024)throw new AssertionError("Invalid continuation offset");
            while(result.size()<offset+source.size())result.addNull();
            for(int i=0;i<source.size();i++)result.set(offset+i,merge(result.get(offset+i),source.get(i),local+"/"+i,absolute+"/"+(offset+i),segments,strings));return result;
        }
        if(source.isObject()) {
            ObjectNode result=target!=null&&target.isObject()?(ObjectNode)target:Json.MAPPER.createObjectNode();
            for(var entry:source.properties())if(!entry.getKey().equals("_jvmd_segments")) {
                String key=entry.getKey().replace("~","~0").replace("/","~1");
                result.set(entry.getKey(),merge(result.get(entry.getKey()),entry.getValue(),local+"/"+key,absolute+"/"+key,segments,strings));
            }return result;
        }
        return source;
    }
    public static String open(Application app, Path root) {
        return request(app.dispatcher(), "session.open", java.util.Map.of("root", root.toString())).path("result").path("result").path("session").asText();
    }
}
