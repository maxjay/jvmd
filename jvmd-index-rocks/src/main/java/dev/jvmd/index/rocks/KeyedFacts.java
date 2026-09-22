package dev.jvmd.index.rocks;

import dev.jvmd.index.FactCodec;
import org.rocksdb.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Keyed records and postings, replaced atomically by owning file in the caller's batch. */
public final class KeyedFacts {
    public record Fact(String id,byte[] value,Set<String> postings) {}
    private final RocksDB db;
    private final String namespace;
    public KeyedFacts(RocksDB db,String namespace){this.db=db;this.namespace=namespace;}
    public static String part(String value){return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public void replace(WriteBatch batch,String owner,List<Fact> facts)throws Exception {
        try(var trace=dev.jvmd.core.RequestScope.stage("facts.publish")){
            trace.count("owners_replaced",1);trace.count("records_written",facts.size());
        String group=namespace+"g/"+part(owner);byte[] prior=db.get(bytes(group));
        if(prior!=null)for(Object key:FactCodec.decode(prior,List.class))batch.delete(bytes(key.toString()));
        var keys=new ArrayList<String>();
        for(var fact:facts){
            String key=namespace+"v/"+fact.id();batch.put(bytes(key),fact.value());keys.add(key);
            for(String posting:fact.postings()){key=namespace+"p/"+posting+"/"+fact.id();batch.put(bytes(key),bytes(fact.id()));keys.add(key);}
        }
        if(facts.isEmpty())batch.delete(bytes(group));else batch.put(bytes(group),FactCodec.encode(keys));
    
        }
    }
    public byte[] get(ReadOptions options,String id)throws RocksDBException{return db.get(options,bytes(namespace+"v/"+id));}
    @FunctionalInterface public interface Visitor { boolean visit(String id,byte[] value)throws Exception; }
    @FunctionalInterface public interface KeyVisitor { boolean visit(String id)throws Exception; }
    public void selectKeys(ReadOptions options,String posting,boolean prefix,KeyVisitor visitor)throws Exception {
        try(var trace=dev.jvmd.core.RequestScope.stage("query.lookup")){
        String key=namespace+"p/"+posting+(prefix?"":"/");
        try(var it=db.newIterator(options)){
            for(it.seek(bytes(key));it.isValid()&&new String(it.key(),StandardCharsets.UTF_8).startsWith(key);it.next())
                {trace.count("postings_visited",1);if(!visitor.visit(new String(it.value(),StandardCharsets.UTF_8)))break;}
            it.status();
        }
    
        }
    }
    public void select(ReadOptions options,String posting,boolean prefix,Visitor visitor)throws Exception {
        selectKeys(options,posting,prefix,id->{byte[] value=get(options,id);return value==null||visitor.visit(id,value);});
    }
    public boolean contains(ReadOptions options,String posting)throws Exception {
        boolean[] found={false};selectKeys(options,posting,false,id->{found[0]=true;return false;});return found[0];
    }
}
