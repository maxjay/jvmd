package dev.jvmd.index;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link FactCodec#encode} writes maps, lists and scalars directly instead of through a Jackson tree. The
 * stored bytes must be identical to the tree encoding ({@link #encodeThroughTree}, the original encoder kept
 * here as the oracle) for every kind of fact a bindings build stores, so existing stores stay readable and
 * fact identities do not change.
 */
class FactCodecEquivalenceTest {
    @TempDir Path root;

    @Test void directEncodingIsByteIdenticalToTheTreeEncodingForBindingsFacts()throws Exception{
        String text="""
                package p;
                import java.util.*;
                /** Docs with "quotes", unicode é and a {@code tag}. */
                public class Sample<T extends Comparable<T>> implements Runnable {
                    private final Map<String,List<T>> values=new HashMap<>();
                    static final double RATE=1.5; static final long BIG=1L<<40;
                    public void run(){ for(var e:values.entrySet()){ int n=e.getValue().size(); System.out.println(n+RATE); } }
                    @Deprecated <U> U convert(T input,java.util.function.Function<T,U> f){ return f.apply(input); }
                    record Pair(String left,int right) { }
                    enum Kind { A, B }
                }
                """;
        Path file=Files.createDirectories(root.resolve("p")).resolve("Sample.java");Files.writeString(file,text);
        int compared=0;
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            var graph=analyzer.bindings(file,text,null).result();
            var values=new ArrayList<Object>();values.addAll(graph.symbols().values());values.addAll(graph.edges());values.addAll(graph.occurrences());
            values.add(new ArrayList<>(List.of("a","b","a")));values.add(Map.of());values.add(List.of());
            var withNull=new LinkedHashMap<String,Object>();withNull.put("k",null);withNull.put("n",3);withNull.put("f",2.5f);withNull.put("path",root);values.add(withNull);
            for(Object value:values){
                byte[] direct=FactCodec.encode(value);
                assertThat(direct).as(String.valueOf(value)).isEqualTo(encodeThroughTree(value));
                assertThatCode(()->FactCodec.decode(direct,Object.class)).as("decodes: "+value).doesNotThrowAnyException();
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(30);
    }

    /** The original encoder: every value through its Jackson tree, with a record-local string table. */
    static byte[] encodeThroughTree(Object value)throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){out.writeInt(0x4a564601);write(out,Json.MAPPER.valueToTree(value),new HashMap<>());}
        return bytes.toByteArray();
    }
    private static void write(DataOutputStream out,JsonNode value,Map<String,Integer> strings)throws IOException {
        if(value.isNull()){out.writeByte(0);return;}
        if(value.isTextual()){out.writeByte(1);string(out,value.textValue(),strings);return;}
        if(value.isBoolean()){out.writeByte(value.booleanValue()?2:3);return;}
        if(value.isIntegralNumber()){out.writeByte(4);out.writeLong(value.longValue());return;}
        if(value.isFloatingPointNumber()){out.writeByte(5);out.writeDouble(value.doubleValue());return;}
        out.writeByte(value.isArray()?6:7);out.writeInt(value.size());
        if(value.isArray())for(var child:value)write(out,child,strings);
        else for(var entry:value.properties()){string(out,entry.getKey(),strings);write(out,entry.getValue(),strings);}
    }
    private static void string(DataOutputStream out,String value,Map<String,Integer> strings)throws IOException {
        Integer id=strings.get(value);if(id!=null){out.writeInt(id);return;}
        strings.put(value,strings.size());byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(-bytes.length-1);out.write(bytes);
    }
}
