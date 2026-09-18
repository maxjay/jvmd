package dev.jvmd.prototype;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StoragePrototypeTest {
    @TempDir Path temp;

    @Test void compareBulkStoresOnIdenticalFacts()throws Exception{
        int symbols=Integer.getInteger("jvmd.prototype.symbols",100_000);
        int relationships=Integer.getInteger("jvmd.prototype.relationships",200_000);
        var entries=facts(symbols,relationships);
        var results=new ArrayList<Map<String,Object>>();
        List<String> expectedName=null,expectedReverse=null;

        for(String backend:List.of("immutable","rocks")){
            var repetitions=new ArrayList<Map<String,Object>>();
            for(int repetition=0;repetition<3;repetition++){
                Path root=temp.resolve(backend+"-"+repetition);
                long rssBefore=rssBytes(),writesBefore=writeBytes(),started=System.nanoTime();
                try(PrototypeStore store=backend.equals("immutable")
                        ?new ImmutableSegmentStore(root.resolve("index.seg"))
                        :new RocksSstStore(root.resolve("db"))){
                    store.publish(entries);
                    double publishMs=(System.nanoTime()-started)/1e6;
                    byte[] exact=store.get(bytes("K|fixture.Type#method123()V"));
                    assertThat(exact).isNotNull();
                    var names=keys(store.prefix(bytes("N|method12"),1000));
                    var reverse=keys(store.prefix(bytes("R|dep.Type12|"),1000));
                    if(expectedName==null){expectedName=names;expectedReverse=reverse;}
                    else{assertThat(names).isEqualTo(expectedName);assertThat(reverse).isEqualTo(expectedReverse);}

                    var latencies=new ArrayList<Double>();
                    for(int i=0;i<40;i++){
                        long q=System.nanoTime();
                        store.get(bytes("K|fixture.Type#method123()V"));
                        store.prefix(bytes("N|method12"),1000);
                        store.prefix(bytes("R|dep.Type12|"),1000);
                        latencies.add((System.nanoTime()-q)/1e6);
                    }
                    Collections.sort(latencies);
                    repetitions.add(Map.of(
                            "publish_ms",publishMs,
                            "storage_bytes",store.storageBytes(),
                            "write_bytes",Math.max(0,writeBytes()-writesBefore),
                            "rss_delta_bytes",Math.max(0,rssBytes()-rssBefore),
                            "query_p50_ms",latencies.get(latencies.size()/2),
                            "query_p95_ms",latencies.get((int)Math.ceil(latencies.size()*.95)-1)));
                }
            }
            results.add(Map.of("backend",backend,"runs",List.copyOf(repetitions)));
        }

        assertThat(expectedName).isNotEmpty();assertThat(expectedReverse).isNotEmpty();
        var report=new LinkedHashMap<String,Object>();
        report.put("jdk",System.getProperty("java.runtime.version"));
        report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        report.put("symbols",symbols);report.put("relationships",relationships);report.put("entries",entries.size());
        report.put("results",List.copyOf(results));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/index-prototype-results.json"),json(report));
        System.out.println("index-storage-prototypes "+json(report));
    }

    private static List<PrototypeStore.Entry> facts(int symbols,int relationships){
        var entries=new ArrayList<PrototypeStore.Entry>(symbols*4+relationships*2);
        for(int i=0;i<symbols;i++){
            String id=hex8(i),method="method"+i,key="fixture.Type#"+method+"()V";
            byte[] value=key.getBytes(StandardCharsets.UTF_8);
            entries.add(new PrototypeStore.Entry("I|"+id,value));
            entries.add(new PrototypeStore.Entry("K|"+key,ByteBuffer.allocate(4).putInt(i).array()));
            entries.add(new PrototypeStore.Entry("N|"+method+"|"+id,new byte[0]));
            entries.add(new PrototypeStore.Entry("F|fixture.Type|"+id,new byte[0]));
        }
        for(int i=0;i<relationships;i++){
            int source=i%symbols;String id=hex8(source),target="dep.Type"+((i/symbols)*1000+(i%1000)),kind=(i&1)==0?"calls":"return_type";
            entries.add(new PrototypeStore.Entry("O|"+id+"|"+kind+"|"+target,new byte[0]));
            entries.add(new PrototypeStore.Entry("R|"+target+"|"+kind+"|"+id,new byte[0]));
        }
        entries.sort((a,b)->ImmutableSegmentStore.compare(a.key(),b.key()));
        return List.copyOf(entries);
    }

    private static String hex8(int value){String raw=Integer.toHexString(value);return "0".repeat(8-raw.length())+raw;}
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
    private static long writeBytes(){
        try{
            for(String line:Files.readAllLines(Path.of("/proc/self/io")))if(line.startsWith("write_bytes:"))
                return Long.parseLong(line.substring(line.indexOf(':')+1).trim());
        }catch(Exception ignored){}
        return 0;
    }
    private static String json(Object value){
        if(value instanceof Map<?,?> map){
            var parts=new ArrayList<String>();for(var e:map.entrySet())parts.add(quote(String.valueOf(e.getKey()))+":"+json(e.getValue()));
            return "{"+String.join(",",parts)+"}";
        }
        if(value instanceof Collection<?> values)return "["+String.join(",",values.stream().map(StoragePrototypeTest::json).toList())+"]";
        if(value instanceof String s)return quote(s);
        return String.valueOf(value);
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
