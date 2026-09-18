package dev.jvmd.prototype;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StoragePrototypeTest {
    @TempDir Path temp;

    @Test void identicalFactsProduceEquivalentRequiredLookupsAndMeasurements()throws Exception{
        int symbols=Integer.getInteger("jvmd.prototype.symbols",20_000);
        int relationships=Integer.getInteger("jvmd.prototype.relationships",50_000);
        var data=synthetic(symbols,relationships);var entries=entries(data);
        var results=new ArrayList<Map<String,Object>>();List<String> expectedNames=null,expectedReverse=null;

        for(PrototypeStore store:List.of(new ImmutableSegmentStore(temp.resolve("immutable/index.seg")),new RocksSstStore(temp.resolve("rocks")))){
            try(store){
                long rssBefore=rssBytes(),started=System.nanoTime();store.publish(entries);double publishMs=(System.nanoTime()-started)/1e6;
                var names=keys(store.prefix(bytes("N\0method12"),1000));
                var reverse=keys(store.prefix(bytes("R\0dep.Type12\0"),1000));
                assertThat(ArtifactIndexFormat.decode(store.get(bytes("A")))).isEqualTo(data);
                if(expectedNames==null){expectedNames=names;expectedReverse=reverse;}else{assertThat(names).isEqualTo(expectedNames);assertThat(reverse).isEqualTo(expectedReverse);}
                var query=new ArrayList<Double>();for(int i=0;i<30;i++){long q=System.nanoTime();store.prefix(bytes("N\0method12"),1000);store.prefix(bytes("R\0dep.Type12\0"),1000);query.add((System.nanoTime()-q)/1e6);}
                Collections.sort(query);
                results.add(Map.of("backend",store.name(),"publish_ms",publishMs,"storage_bytes",store.storageBytes(),
                        "query_p50_ms",query.get(query.size()/2),"query_p95_ms",query.get((int)Math.ceil(query.size()*.95)-1),
                        "rss_delta_bytes",Math.max(0,rssBytes()-rssBefore),"entries",entries.size()));
            }
        }
        assertThat(expectedNames).isNotEmpty();assertThat(expectedReverse).isNotEmpty();
        var report=Map.of("symbols",symbols,"relationships",relationships,"entries",entries.size(),"results",results);
        Path output=Path.of("target/index-prototype-results.json");Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        System.out.println("index-storage-prototypes "+Json.MAPPER.writeValueAsString(report));
    }

    private static ArtifactIndexFormat.ArtifactData synthetic(int count,int relationships){
        String hash="a".repeat(64);var key=new ArtifactIndexFormat.Key(hash,ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var symbols=new ArrayList<ArtifactIndexFormat.SymbolRecord>(count);
        for(int i=0;i<count;i++)symbols.add(new ArtifactIndexFormat.SymbolRecord(i,-1,"fixture.Type#method"+i+"()V","fixture.Type",
                "method"+i,"method","void method"+i+"()","()V",1,"fixture/Type.class",List.of(),"{}"));
        var edges=new ArrayList<ArtifactIndexFormat.Relationship>(relationships);
        for(int i=0;i<relationships;i++)edges.add(new ArtifactIndexFormat.Relationship(i%count,"dep.Type"+i,"call"));
        return new ArtifactIndexFormat.ArtifactData(key,List.copyOf(symbols),List.copyOf(edges));
    }

    private static List<PrototypeStore.Entry> entries(ArtifactIndexFormat.ArtifactData data)throws Exception{
        var entries=new ArrayList<PrototypeStore.Entry>();entries.add(new PrototypeStore.Entry("A",ArtifactIndexFormat.encode(data)));
        for(var symbol:data.symbols()){
            byte[] id=ByteBuffer.allocate(4).putInt(symbol.id()).array();
            entries.add(new PrototypeStore.Entry("I\0"+String.format("%08x",symbol.id()),symbol.key().getBytes(StandardCharsets.UTF_8)));
            entries.add(new PrototypeStore.Entry("K\0"+symbol.key(),id));
            entries.add(new PrototypeStore.Entry("N\0"+symbol.name()+"\0"+String.format("%08x",symbol.id()),new byte[0]));
            entries.add(new PrototypeStore.Entry("F\0"+symbol.fqn()+"\0"+String.format("%08x",symbol.id()),new byte[0]));
        }
        for(var edge:data.relationships()){
            String id=String.format("%08x",edge.sourceId());
            entries.add(new PrototypeStore.Entry("O\0"+id+"\0"+edge.kind()+"\0"+edge.target(),new byte[0]));
            entries.add(new PrototypeStore.Entry("R\0"+edge.target()+"\0"+edge.kind()+"\0"+id,new byte[0]));
        }
        entries.sort((a,b)->ImmutableSegmentStore.compare(a.key(),b.key()));return List.copyOf(entries);
    }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static List<String> keys(List<PrototypeStore.Entry> values){return values.stream().map(e->new String(e.key(),StandardCharsets.UTF_8)).toList();}
    private static long rssBytes(){
        try{
            for(String line:Files.readAllLines(Path.of("/proc/self/status")))if(line.startsWith("VmRSS:")){
                String[] parts=line.trim().split("\\s+");return Long.parseLong(parts[1])*1024;
            }
        }catch(Exception ignored){}
        return 0;
    }
}
