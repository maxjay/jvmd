package dev.jvmd.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Implements 4.1 and phase 8: byte-bounded response fragments and replayable, scoped continuations. */
public final class ResponseBudget {
    public static final int MAX_BYTES=64*1024;
    private static final long MAX_STORED_BYTES=64L*1024*1024;
    private record Snapshot(String identity,List<ObjectNode> pages,long expires,long bytes) { }
    private record Candidate(List<String> path,JsonNode node,long weight) { }
    private final LinkedHashMap<String,Snapshot> snapshots=new LinkedHashMap<>(16,.75f,true);
    private long stored;
    private final LongAdder activations=new LongAdder(),continuationPages=new LongAdder(),resumes=new LongAdder();
    private static String suppliedCursor(JsonNode params){String cursor=params.path("cursor").asText("");if(cursor.isEmpty())cursor=params.path("args").path("cursor").asText("");return cursor;}
    private static JsonNode canonical(JsonNode value){
        if(value.isObject()){var result=Json.MAPPER.createObjectNode();var fields=new TreeMap<String,JsonNode>();value.properties().forEach(e->{if(!e.getKey().equals("cursor"))fields.put(e.getKey(),e.getValue());});fields.forEach((key,child)->result.set(key,canonical(child)));return result;}
        if(value.isArray()){var result=Json.MAPPER.createArrayNode();value.forEach(child->result.add(canonical(child)));return result;}return value;
    }
    private static String identity(String method,JsonNode params)throws Exception{return method+":"+Hashing.sha256(Json.MAPPER.writeValueAsBytes(canonical(params.isMissingNode()?Json.MAPPER.createObjectNode():params)));}
    private synchronized void expire(){
        long now=System.nanoTime();var iterator=snapshots.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(now-entry.getValue().expires()>=0){stored-=entry.getValue().bytes();iterator.remove();}}
    }
    public synchronized ObjectNode resume(String method,JsonNode params,JsonNode id)throws Exception{
        String cursor=suppliedCursor(params);if(!cursor.startsWith("budget:"))return null;expire();int split=cursor.lastIndexOf(':');String key=cursor.substring(0,split);int page;
        try{page=Integer.parseInt(cursor.substring(split+1));}catch(NumberFormatException e){throw RpcException.invalid("Invalid response cursor");}
        var snapshot=snapshots.get(key);if(snapshot==null||page<0||page>=snapshot.pages().size()||!snapshot.identity().equals(identity(method,params)))throw RpcException.invalid("Unknown, expired or mismatched response cursor");
        resumes.increment();var response=snapshot.pages().get(page).deepCopy();response.set("id",id==null?Json.MAPPER.nullNode():id);return response;
    }
    private static ObjectNode envelope(ObjectNode response){return (ObjectNode)(response.has("error")?response.path("error").path("data"):response.path("result"));}
    public ObjectNode enforce(ObjectNode response,String method,JsonNode params)throws Exception{
        int maximum=Dispatcher.bounded(params,"_response_bytes",MAX_BYTES,MAX_BYTES);if(maximum<4096)throw RpcException.invalid("_response_bytes must be at least 4096");
        if(Json.MAPPER.writeValueAsBytes(response).length<=maximum)return response;
        activations.increment();int payloadBudget=maximum-2048;
        ObjectNode original=envelope(response);var document=Json.MAPPER.createObjectNode();document.set("payload",original.path("result"));document.set("warnings",original.path("warnings"));
        var pending=new ArrayDeque<JsonNode>();pending.add(document);var fragments=new ArrayList<JsonNode>();long bytes=0;
        while(!pending.isEmpty()){
            JsonNode node=pending.removeFirst();byte[] encoded=Json.MAPPER.writeValueAsBytes(node);
            if(encoded.length<=payloadBudget){fragments.add(node);bytes+=encoded.length;if(bytes>MAX_STORED_BYTES)throw RpcException.invalid("Response exceeds continuation storage; request a smaller limit");continue;}
            Candidate candidate=independentFields(node,List.of(),payloadBudget);
            if(candidate==null)candidate=largest(node,List.of(),null);
            if(candidate==null||candidate.weight()<1024)candidate=objectCandidate(node,List.of());
            if(candidate==null)throw RpcException.invalid("Response contains an unsplittable value");
            List<JsonNode> split=split(node,candidate);pending.addFirst(split.get(1));pending.addFirst(split.get(0));
            if(pending.size()+fragments.size()>2048)throw RpcException.invalid("Response needs too many pages; request a smaller limit");
        }
        String key="budget:"+UUID.randomUUID();var pages=new ArrayList<ObjectNode>();
        for(int i=0;i<fragments.size();i++){
            ObjectNode page=response.deepCopy(),value=envelope(page);var fragment=fragments.get(i);JsonNode payload=fragment.path("payload");
            ObjectNode result;if(payload.isObject())result=(ObjectNode)payload.deepCopy();else{result=Json.MAPPER.createObjectNode();if(!payload.isMissingNode())result.set(payload.isTextual()?"text":"items",payload);}
            if(fragment.has("_jvmd_segments"))result.set("_jvmd_segments",fragment.get("_jvmd_segments"));
            value.set("result",result);value.set("warnings",fragment.path("warnings").isArray()?fragment.path("warnings"):Json.MAPPER.createArrayNode());boolean more=i+1<fragments.size();
            if(more){value.put("truncated",true);value.put("cursor",key+":"+(i+1));}pages.add(page);
        }
        continuationPages.add(pages.size());
        synchronized(this){
            expire();while(stored+bytes>MAX_STORED_BYTES||snapshots.size()>=64){var oldest=snapshots.keySet().iterator().next();stored-=snapshots.remove(oldest).bytes();}
            snapshots.put(key,new Snapshot(identity(method,params),List.copyOf(pages),System.nanoTime()+TimeUnit.SECONDS.toNanos(60),bytes));stored+=bytes;
        }
        return pages.getFirst();
    }
    public Map<String,Object> status(){return Map.of("activations",activations.sum(),"continuation_pages",continuationPages.sum(),"resumes",resumes.sum());}
    private static Candidate independentFields(JsonNode node,List<String> path,int budget)throws Exception{
        if(node.isObject()){
            int large=0;for(var entry:node.properties())if(!entry.getKey().equals("_jvmd_segments")&&Json.MAPPER.writeValueAsBytes(entry.getValue()).length>=Math.max(1024,budget/4))large++;
            // Split independent large fields before values, avoiding their Cartesian duplication.
            if(large>1)return new Candidate(path,node,Json.MAPPER.writeValueAsBytes(node).length);
            for(var entry:node.properties())if(!entry.getKey().equals("_jvmd_segments")){
                var child=new ArrayList<>(path);child.add(entry.getKey());var found=independentFields(entry.getValue(),List.copyOf(child),budget);if(found!=null)return found;
            }
        }else if(node.isArray())for(int i=0;i<node.size();i++){
            var child=new ArrayList<>(path);child.add(Integer.toString(i));var found=independentFields(node.get(i),List.copyOf(child),budget);if(found!=null)return found;
        }
        return null;
    }
    private static Candidate objectCandidate(JsonNode node,List<String> path)throws Exception{
        if(node.isObject()){
            Candidate largest=null;
            for(var entry:node.properties())if(!entry.getKey().equals("_jvmd_segments")&&entry.getValue().isObject()){
                var child=new ArrayList<>(path);child.add(entry.getKey());var candidate=objectCandidate(entry.getValue(),List.copyOf(child));if(candidate!=null&&(largest==null||candidate.weight()>largest.weight()))largest=candidate;
            }
            if(largest!=null)return largest;if(node.size()-(node.has("_jvmd_segments")?1:0)>1)return new Candidate(path,node,Json.MAPPER.writeValueAsBytes(node).length);
        }return null;
    }
    private static Candidate largest(JsonNode node,List<String> path,Candidate best)throws Exception{
        if(node.isArray()&&node.size()>1||node.isTextual()&&node.textValue().length()>1){
            long weight=Json.MAPPER.writeValueAsBytes(node).length;if(best==null||weight>best.weight())best=new Candidate(path,node,weight);
        }
        if(node.isObject())for(var entry:node.properties()){if(entry.getKey().equals("_jvmd_segments"))continue;var child=new ArrayList<>(path);child.add(entry.getKey());best=largest(entry.getValue(),List.copyOf(child),best);}
        else if(node.isArray())for(int i=0;i<node.size();i++){var child=new ArrayList<>(path);child.add(Integer.toString(i));best=largest(node.get(i),List.copyOf(child),best);}
        return best;
    }
    private static String pointer(List<String> path){return path.isEmpty()?"":"/"+String.join("/",path.stream().map(p->p.replace("~","~0").replace("/","~1")).toList());}
    private static List<JsonNode> split(JsonNode original,Candidate candidate){
        JsonNode value=candidate.node();if(value.isObject()){value=value.deepCopy();((ObjectNode)value).remove("_jvmd_segments");}
        int size=value.isTextual()?value.textValue().length():value.size();if(size<2)throw RpcException.invalid("Response contains an unsplittable value");
        int middle=size/2;if(value.isTextual()&&middle>0&&middle<size&&Character.isHighSurrogate(value.textValue().charAt(middle-1))&&Character.isLowSurrogate(value.textValue().charAt(middle)))middle++;
        var halves=new ArrayList<JsonNode>();
        for(int half=0;half<2;half++){
            int begin=half==0?0:middle,end=half==0?middle:size;JsonNode part;
            if(value.isTextual())part=TextNode.valueOf(value.textValue().substring(begin,end));
            else if(value.isArray()){var array=Json.MAPPER.createArrayNode();for(int i=begin;i<end;i++)array.add(value.get(i));part=array;}
            else if(value.isObject()){var object=Json.MAPPER.createObjectNode();int i=0;for(var entry:value.properties()){if(i>=begin&&i<end)object.set(entry.getKey(),entry.getValue());i++;}part=object;}
            else throw RpcException.invalid("Response contains an unsplittable value");
            JsonNode copy=original.deepCopy();
            if(candidate.path().isEmpty()){copy=part;if(copy.isObject()&&original.has("_jvmd_segments"))((ObjectNode)copy).set("_jvmd_segments",original.get("_jvmd_segments").deepCopy());}
            else{
                JsonNode parent=copy;for(int i=0;i<candidate.path().size()-1;i++){String key=candidate.path().get(i);parent=parent.isArray()?parent.get(Integer.parseInt(key)):parent.get(key);}
                String key=candidate.path().getLast();if(parent.isArray())((ArrayNode)parent).set(Integer.parseInt(key),part);else ((ObjectNode)parent).set(key,part);
            }
            ObjectNode root;if(copy.isObject())root=(ObjectNode)copy;else{root=Json.MAPPER.createObjectNode();root.set(value.isTextual()?"text":"items",copy);}
            String path=pointer(candidate.path());ObjectNode segments=root.withObject("_jvmd_segments");JsonNode prior=segments.path(path);int base=prior.path("start").asInt(0),total=prior.path("total").asInt(size);
            segments.set(path,Json.MAPPER.valueToTree(Map.of("start",base+begin,"end",base+end,"total",total,"kind",value.isArray()?"array":value.isTextual()?"string":"object")));halves.add(root);
        }
        return halves;
    }
}
