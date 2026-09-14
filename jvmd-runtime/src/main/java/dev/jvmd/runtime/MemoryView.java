package dev.jvmd.runtime;

import com.sun.jdi.*;
import dev.jvmd.core.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Implements 4.7: explicit, bounded heap requests with 60-second snapshot pagination. */
public final class MemoryView {
    private record Snapshot(String kind,List<?> values,long expires,int max) { }
    private final DebugSession debug;
    private final LinkedHashMap<String,Snapshot> snapshots=new LinkedHashMap<>();
    private long sequence;
    public MemoryView(DebugSession debug){this.debug=debug;}
    private void require(){if(!debug.vm().canGetInstanceInfo())throw new RpcException(-32003,"unsupported_capability",Map.of("capability","instance_info"));}
    private static void maximum(int max){if(max<1||max>1000)throw RpcException.invalid("An explicit max from 1 to 1000 is required");}
    private String remember(String kind,List<?> values,int max){
        long now=System.nanoTime();snapshots.entrySet().removeIf(e->now-e.getValue().expires()>=0);while(snapshots.size()>=8)snapshots.remove(snapshots.keySet().iterator().next());
        String key="memory:"+debug.id()+":"+(++sequence);snapshots.put(key,new Snapshot(kind,List.copyOf(values),now+TimeUnit.SECONDS.toNanos(60),max));return key+":0";
    }
    public synchronized Envelope instances(String ref,int max,int limit,String cursor)throws Exception{
        maximum(max);if(cursor!=null)return page(cursor,limit,"instances");require();var type=type(ref);var vm=debug.vm();List<ObjectReference> values;
        vm.suspend();try{values=type.instances(max);}finally{vm.resume();}
        return page(remember("instances",values,max),limit,"instances");
    }
    public synchronized Envelope referrers(String handle,int max,int limit,String cursor)throws Exception{
        maximum(max);if(cursor!=null)return page(cursor,limit,"referrers");require();var vm=debug.vm();List<ObjectReference> values;
        var object=debug.handles().get(handle);vm.suspend();try{values=object.referringObjects(max);}finally{vm.resume();}
        return page(remember("referrers",values,max),limit,"referrers");
    }
    public synchronized Envelope histogram(int limit,String cursor)throws Exception{
        if(cursor!=null)return page(cursor,limit,"histogram");require();var vm=debug.vm();var types=vm.allClasses();var values=new ArrayList<Map<String,Object>>();
        for(int from=0;from<types.size();from+=512){
            var batch=types.subList(from,Math.min(types.size(),from+512));long[] counts=vm.instanceCounts(batch);
            for(int i=0;i<batch.size();i++)if(counts[i]>0){var type=batch.get(i);var row=new LinkedHashMap<String,Object>();row.put("type",type.name());row.put("scip",type instanceof ArrayType?null:debug.sources().symbol(type,null));row.put("instances",counts[i]);values.add(row);}
        }
        values.sort(Comparator.comparingLong((Map<String,Object> row)->((Number)row.get("instances")).longValue()).reversed().thenComparing(row->row.get("type").toString()));
        return page(remember("histogram",values,values.size()),limit,"histogram");
    }
    private ReferenceType type(String ref){
        var types=debug.vm().allClasses().stream().filter(t->t.name().equals(ref)||debug.sources().symbol(t,null).equals(ref)||t.name().endsWith("."+ref)).toList();
        if(types.isEmpty())throw RpcException.invalid("Class is not loaded: "+ref);
        if(types.size()!=1)throw new RpcException(-32002,"ambiguous_ref",Map.of("candidates",types.stream().limit(20).map(t->Map.of("scip",debug.sources().symbol(t,null),"name",t.name(),"loader",Objects.toString(t.classLoader(),"bootstrap"))).toList()));
        return types.getFirst();
    }
    private Envelope page(String cursor,int limit,String kind){
        if(limit<1||limit>100)throw RpcException.invalid("Memory page limit must be 1..100");
        int separator=cursor.lastIndexOf(':');if(separator<0)throw RpcException.invalid("Invalid memory cursor");String key=cursor.substring(0,separator);int offset;try{offset=Integer.parseInt(cursor.substring(separator+1));}catch(NumberFormatException e){throw RpcException.invalid("Invalid memory cursor");}
        var snapshot=snapshots.get(key);if(offset<0||snapshot==null||!snapshot.kind().equals(kind)||System.nanoTime()-snapshot.expires()>=0){snapshots.remove(key);throw RpcException.invalid("Unknown or expired memory cursor");}
        int from=Math.min(offset,snapshot.values().size()),to=Math.min(snapshot.values().size(),from+limit);var result=new ArrayList<Object>();
        for(Object item:snapshot.values().subList(from,to))if(item instanceof ObjectReference object){try{result.add(debug.value(object));}catch(ObjectCollectedException e){result.add(Map.of("kind","collected"));}}else result.add(item);
        boolean more=to<snapshot.values().size();return new Envelope(2,"live",more,more?key+":"+to:null,List.of(),Map.of(kind,result,"requested_max",snapshot.max(),"returned",snapshot.values().size(),"limit_reached",!kind.equals("histogram")&&snapshot.values().size()==snapshot.max()));
    }
}
