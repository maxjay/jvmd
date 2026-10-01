package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * E3 (corrective pass): {@link FactCodec#encode} writes maps, lists and scalars directly instead of
 * through a Jackson tree. The stored bytes must be identical to the earlier encoder's, for every kind of
 * fact a bindings build stores, so existing stores stay readable and fact identities do not change.
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
                assertThat(FactCodec.encode(value)).as(String.valueOf(value)).isEqualTo(FactCodec.encodeThroughTree(value));
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(30);
    }
}
