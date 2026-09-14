package dev.jvmd.runtime;

import com.sun.jdi.*;
import dev.jvmd.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Implements 4.7: depth/breadth-limited inspection with resumable child and string pages. */
public final class Inspection {
    private record Cursor(String handle,int offset,int depth,int breadth) { }
    private final DebugSession debug;
    public Inspection(DebugSession debug){this.debug=debug;}
    private static String cursor(Cursor value)throws Exception{return "inspect:"+Base64.getUrlEncoder().withoutPadding().encodeToString(Json.MAPPER.writeValueAsBytes(value));}
    private static Cursor cursor(String value)throws Exception{
        try{if(!value.startsWith("inspect:")||value.length()>4096)throw new IllegalArgumentException();return Json.MAPPER.readValue(Base64.getUrlDecoder().decode(value.substring(8)),Cursor.class);}
        catch(Exception e){throw RpcException.invalid("Invalid inspection cursor");}
    }
    public Envelope inspect(String handle,int depth,int breadth,String continuation)throws Exception{
        Cursor request=continuation==null?new Cursor(handle,0,depth,breadth):cursor(continuation);
        if(request.depth()<0||request.depth()>4||request.breadth()<1||request.breadth()>20||request.offset()<0)throw RpcException.invalid("inspect requires depth 0..4 and breadth 1..20");
        var pending=new ArrayList<String>();var seen=new HashSet<Long>();int[] remaining={64};
        var result=walk(debug.handles().get(request.handle()),request.depth(),request.breadth(),request.offset(),seen,remaining,pending);
        var answer=new LinkedHashMap<String,Object>();answer.put("value",result);answer.put("continuations",List.copyOf(pending));answer.put("handle_ttl_seconds",60);
        return new Envelope(2,"live",!pending.isEmpty(),pending.isEmpty()?null:pending.getFirst(),List.of(),answer);
    }
    private Map<String,Object> walk(Value value,int depth,int breadth,int offset,Set<Long> seen,int[] remaining,List<String> pending)throws Exception{
        remaining[0]--;var row=new LinkedHashMap<>(debug.value(value));if(!(value instanceof ObjectReference object))return row;
        String handle=row.get("handle").toString();
        if(value instanceof StringReference string){
            String text=string.value();int from=Math.min(offset,text.length()),to=Math.min(text.length(),from+500);row.put("value",text.substring(from,to));row.put("length",text.length());row.put("offset",from);row.put("truncated",to<text.length());
            if(to<text.length()){String next=cursor(new Cursor(handle,to,depth,breadth));row.put("cursor",next);pending.add(next);}return row;
        }
        if(!seen.add(object.uniqueID())){row.put("cycle",true);return row;}
        if(depth==0){row.put("summary",true);return row;}
        if(remaining[0]<0){String next=cursor(new Cursor(handle,offset,depth,breadth));row.put("cursor",next);pending.add(next);return row;}
        var children=new ArrayList<Map<String,Object>>();int size,to;
        if(object instanceof ArrayReference array){
            size=array.length();int from=Math.min(offset,size);to=Math.min(size,from+breadth);int i=from;
            for(Value item:array.getValues(from,to-from)){if(remaining[0]<=0)break;children.add(Map.of("index",i++,"value",walk(item,depth-1,breadth,0,seen,remaining,pending)));}to=i;
            row.put("elements",children);row.put("length",size);
        }else{
            var fields=object.referenceType().allFields().stream().filter(f->!f.isStatic()).sorted(Comparator.comparing((Field f)->f.declaringType().name()).thenComparing(Field::name)).toList();
            size=fields.size();int from=Math.min(offset,size);to=Math.min(size,from+breadth);var selected=fields.subList(from,to);var values=object.getValues(selected);
            int consumed=from;for(Field field:selected){if(remaining[0]<=0)break;consumed++;var child=new LinkedHashMap<String,Object>();child.put("name",field.name());child.put("scip",debug.sources().symbol(field.declaringType(),null)+field.name()+".");child.put("type",field.typeName());child.put("value",walk(values.get(field),depth-1,breadth,0,seen,remaining,pending));children.add(child);}to=consumed;
            row.put("fields",children);row.put("field_count",size);
        }
        if(to<size){String next=cursor(new Cursor(handle,to,depth,breadth));row.put("cursor",next);pending.add(next);}
        return row;
    }
}
