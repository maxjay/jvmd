package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import dev.jvmd.core.NamePath;
import org.rocksdb.*;
import java.util.*;
import java.util.function.Predicate;

/** Replaceable local facts: no artifact-wide decoded source list or per-file graph JSON. */
final class SourceOverlay implements AutoCloseable {
    record FileStamp(String file,String hash) {}
    private final RocksDB db;
    private final ReadOptions read=new ReadOptions();
    private long decoded,cacheBytes;
    private final long cacheBudget;
    private record Cached(Map<String,Object> value,long weight) {}
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(64,.75f,true);
    SourceOverlay(RocksDB db){this(db,4L*1024*1024);}
    SourceOverlay(RocksDB db,long budget){this.db=db;this.cacheBudget=budget;}
    private void evict(){cache.clear();cacheBytes=0;}
    private KeyedFacts facts(long artifact){return new KeyedFacts(db,"L/"+String.format(Locale.ROOT,"%016x",artifact)+"/");}
    private static String key(String value){return KeyedFacts.part(value);}
    private static String handle(long id){return String.format(Locale.ROOT,"%016x",id);}
    void replace(WriteBatch batch,long artifact,FileStamp file,List<Map<String,Object>> symbols,List<IndexStore.SourceRelationship> edges)throws Exception {
        evict();var records=new ArrayList<KeyedFacts.Fact>();String owner=key(file.file());
        records.add(new KeyedFacts.Fact("f/"+owner,FactCodec.encode(file),Set.of("files")));
        for(var symbol:symbols){
            var postings=new LinkedHashSet<String>();postings.add("symbols");
            for(String field:List.of("scip","binary_key","name","name_path"))if(symbol.get(field)!=null)postings.add(field+"/"+key(symbol.get(field).toString()));
            for(String field:List.of("name","name_path")){
                String value=Objects.toString(symbol.get(field),"").toLowerCase(Locale.ROOT);
                for(int width=1;width<=Math.min(3,value.length());width++)for(int i=0;i+width<=value.length();i++)postings.add("gram/"+key(value.substring(i,i+width)));
            }
            records.add(new KeyedFacts.Fact("s/"+handle(((Number)symbol.get("id")).longValue()),FactCodec.encode(symbol),postings));
        }
        int i=0;for(var edge:edges)records.add(new KeyedFacts.Fact("e/"+owner+"/"+handle(i++),FactCodec.encode(edge),Set.of("out/"+key(edge.sourceScip()),"in/"+key(edge.targetScip()))));
        facts(artifact).replace(batch,file.file(),records);
    }
    void remove(WriteBatch batch,long artifact,String file)throws Exception {evict();facts(artifact).replace(batch,file,List.of());}
    List<FileStamp> files(long artifact)throws Exception {
        var result=new ArrayList<FileStamp>();facts(artifact).select(read,"files",false,(id,value)->{result.add(FactCodec.decode(value,FileStamp.class));return true;});return result;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> decode(long artifact,String id,byte[] value)throws Exception {
        String key=artifact+"/"+id;var cached=cache.get(key);if(cached!=null)return cached.value();
        decoded++;Map<String,Object> result=Collections.unmodifiableMap(FactCodec.decode(value,Map.class));long weight=128+value.length*4L;
        if(weight<=cacheBudget){cache.put(key,new Cached(result,weight));cacheBytes+=weight;while(cacheBytes>cacheBudget)cacheBytes-=cache.pollFirstEntry().getValue().weight();}return result;
    }
    Map<String,Object> byId(long artifact,long id)throws Exception {byte[] value=facts(artifact).get(read,"s/"+handle(id));return value==null?null:decode(artifact,"s/"+handle(id),value);}
    Map<String,Object> first(long artifact,String field,String value)throws Exception {
        var result=new ArrayList<Map<String,Object>>(1);facts(artifact).select(read,field+"/"+key(value),false,(id,bytes)->{result.add(decode(artifact,id,bytes));return false;});return result.isEmpty()?null:result.getFirst();
    }
    boolean contains(long artifact,String scip)throws Exception {return facts(artifact).contains(read,"scip/"+key(scip));}
    List<Map<String,Object>> select(long artifact,String query,boolean substring,boolean prefix,int limit,long after,Predicate<Map<String,Object>> accepts)throws Exception {
        if(limit<=0)return List.of();String posting;boolean range=false;
        if(prefix){posting="name/"+key(query);range=true;}
        else if(substring){String lower=query.toLowerCase(Locale.ROOT);posting=lower.isEmpty()?"symbols":"gram/"+key(lower.substring(0,Math.min(3,lower.length())));}
        else {
            NamePath parsed=null;try{parsed=NamePath.parse(query);}catch(Exception ignored){}
            if(parsed!=null&&!parsed.identity())posting="name/"+key(parsed.leaf());else posting="scip/"+key(query);
        }
        var result=new TreeMap<Long,Map<String,Object>>();
        KeyedFacts.Visitor visitor=(id,bytes)->{long handle=Long.parseUnsignedLong(id.substring(2),16);if(handle<=after)return true;
            var value=decode(artifact,id,bytes);if(accepts.test(value)){result.put(handle,value);if(result.size()>limit)result.pollLastEntry();}return prefix||result.size()<limit;};
        facts(artifact).select(read,posting,range,visitor);
        if(!substring&&!prefix)facts(artifact).select(read,"binary_key/"+key(query),false,visitor);
        return List.copyOf(result.values());
    }
    List<IndexStore.SourceRelationship> edges(long artifact,String scip,boolean outgoing,Set<String> kinds)throws Exception {
        var result=new LinkedHashSet<IndexStore.SourceRelationship>();facts(artifact).select(read,(outgoing?"out/":"in/")+key(scip),false,(id,value)->{
            var edge=FactCodec.decode(value,IndexStore.SourceRelationship.class);if(kinds.isEmpty()||kinds.contains(edge.kind()))result.add(edge);return true;});return List.copyOf(result);
    }
    long decoded(){return decoded;}
    long cacheBytes(){return cacheBytes;}
    long cacheBudget(){return cacheBudget;}
    @Override public void close(){evict();read.close();}
}
