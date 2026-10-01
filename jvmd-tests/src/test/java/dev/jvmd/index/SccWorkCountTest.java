package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * P3 (corrective pass), permanent work-count bounds for SCC finality. Until B1 every drain ran Tarjan over
 * the whole reachable graph again, so draining after each capture visited O(V^2) vertices; settled
 * components are now not re-entered. Counts are cumulative over the whole run, not per drain. Correctness
 * of finality after changes (merge into a cycle) is checked through the record a merge must produce.
 */
class SccWorkCountTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private Analyzer analyzer()throws Exception{
        Path sources=sources();var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"reactor:"+GAV+":main",
                Map.of(root.resolve("app").toString(),GAV,sources.toString(),GAV),List.of("--release","25"),
                Set.of(),List.of(),List.of(sources),true,"",Processing.NONE),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(new SemanticMemoStore(root.resolve("memo")));
        return analyzer;
    }
    private static Map<?,?> memo(Analyzer analyzer){return (Map<?,?>)analyzer.status().get("attributed_memo");}
    private static long scc(Analyzer analyzer,String key){return ((Number)((Map<?,?>)memo(analyzer).get("scc")).get(key)).longValue();}
    private Path unit(int i,List<Integer> dependencies)throws Exception{
        var body=new StringBuilder("package p;\npublic class U"+i+" { public int v(){ return "+i);
        for(int dependency:dependencies)body.append(" + new U").append(dependency).append("().v()");
        Path file=sources().resolve("p/U"+i+".java");Files.createDirectories(file.getParent());Files.writeString(file,body+"; } }\n");return file;
    }
    record Run(long vertices,long edges,long writes,int units,int dependencyEdges) { }
    /** Units in dependency order, each attributed and drained on its own: the worst case for repeated traversal. */
    private Run run(int units,IntFunction<List<Integer>> dependencies)throws Exception{
        var files=new ArrayList<Path>();int edges=0;
        for(int i=0;i<units;i++){var value=dependencies.apply(i);edges+=value.size();files.add(unit(i,value));}
        try(var analyzer=analyzer()){
            for(Path file:files){analyzer.diagnostics(file,Files.readString(file));analyzer.awaitMemoWrites();}
            return new Run(scc(analyzer,"vertex_visits"),scc(analyzer,"edge_visits"),((Number)memo(analyzer).get("writes")).longValue(),units,edges);
        }
    }
    private static void assertLinear(Run run,String topology){
        assertThat(run.writes()).as(topology+": every unit written").isEqualTo(run.units());
        assertThat(run.vertices()).as(topology+": each vertex entered once over the whole run").isLessThanOrEqualTo(run.units());
        assertThat(run.edges()).as(topology+": each edge examined once over the whole run").isLessThanOrEqualTo(run.dependencyEdges());
    }

    @Test void longChain()throws Exception{assertLinear(run(80,i->i==0?List.of():List.of(i-1)),"chain");}
    @Test void hub()throws Exception{assertLinear(run(80,i->i==0?List.of():List.of(0)),"hub");}
    @Test void layered()throws Exception{
        int width=8;assertLinear(run(64,i->{var result=new ArrayList<Integer>();int layer=i/width;if(layer>0)for(int j=0;j<width;j++)result.add((layer-1)*width+j);return result;}),"layered");
    }
    @Test void randomDag()throws Exception{
        var random=new Random(55);
        assertLinear(run(80,i->{var result=new TreeSet<Integer>();for(int k=0;k<Math.min(i,3);k++)result.add(random.nextInt(i));return List.copyOf(result);}),"random DAG");
    }

    @Test void aCycleIsWrittenAsOneComponent()throws Exception{
        Path a=sources().resolve("p/A.java"),b=sources().resolve("p/B.java");Files.createDirectories(a.getParent());
        Files.writeString(a,"package p; public class A { public int v(){ return 1; } int w(){ return new B().v(); } }");
        Files.writeString(b,"package p; public class B { public int v(){ return 2; } int w(){ return new A().v(); } }");
        try(var analyzer=analyzer()){
            analyzer.diagnostics(a,Files.readString(a));analyzer.awaitMemoWrites();
            analyzer.diagnostics(b,Files.readString(b));analyzer.awaitMemoWrites();
            assertThat(((Number)memo(analyzer).get("writes")).longValue()).isEqualTo(2);
        }
        assertThat(logicalSourceKeys("B")).as("B binds its SCC peer A by content").anyMatch(key->key.endsWith("p/A.java"));
    }

    /** A settled component that later joins a cycle must be re-examined, not reused as final. */
    @Test void aSettledComponentThatJoinsACycleIsReexamined()throws Exception{
        Path a=sources().resolve("p/A.java"),b=sources().resolve("p/B.java");Files.createDirectories(a.getParent());
        Files.writeString(b,"package p; public class B { public int v(){ return 2; } }");
        Files.writeString(a,"package p; public class A { public int v(){ return 1 + new B().v(); } }");
        try(var analyzer=analyzer()){
            analyzer.diagnostics(b,Files.readString(b));analyzer.awaitMemoWrites();
            analyzer.diagnostics(a,Files.readString(a));analyzer.awaitMemoWrites();
            // B now also depends on A: {A, B} becomes one component.
            Files.writeString(b,"package p; public class B { public int v(){ return 2; } int w(){ return new A().v(); } }");
            analyzer.diagnostics(b,Files.readString(b));analyzer.awaitMemoWrites();
            analyzer.diagnostics(a,Files.readString(a));analyzer.awaitMemoWrites();
            assertThat(scc(analyzer,"invalidations")).as("the merge unsettled the earlier components").isPositive();
        }
        assertThat(logicalSourceKeys("B")).as("after the merge B binds A by content").anyMatch(key->key.endsWith("p/A.java"));
        // A body-only edit of A leaves its P_diag unchanged; only a content binding makes B recompile.
        Files.writeString(a,"package p; public class A { public int v(){ return 3 + new B().v(); } }");
        try(var analyzer=analyzer()){
            long before=((Number)analyzer.status().get("queries")).longValue();
            analyzer.diagnostics(b,Files.readString(b));
            assertThat(((Number)analyzer.status().get("queries")).longValue()).as("B is recompiled: its cycle peer changed").isGreaterThan(before);
        }
    }

    /** logical-source: keys in the records of unit {@code name} (its current and earlier variants). */
    private Set<String> logicalSourceKeys(String name)throws Exception{
        var keys=new TreeSet<String>();
        try(var walk=Files.walk(root.resolve("memo"))){
            for(Path file:walk.filter(path->path.toString().endsWith(".memo")).toList()){
                var record=SemanticMemoStore.decode(Files.readAllBytes(file));
                if(!record.key().function().name().equals("attributed-diagnostics"))continue;
                var dependencies=record.certificate().dependencies().dependencies();
                boolean self=dependencies.stream().noneMatch(d->d.key().value().endsWith("p/"+name+".java"));
                if(!self)continue;
                for(var dependency:dependencies)if(dependency.key().value().startsWith("logical-source:"))keys.add(dependency.key().value());
            }
        }
        return keys;
    }

    /**
     * Cold admission in reverse dependency order: every capture waits on a unit not yet attributed, so no
     * drain can write until the last one. Before the back-off, once 256 units were pending every further
     * capture drained the whole unsettled region again (quadratic in V; 43% of a cold 5,000-unit session's
     * allocation). Draining is now amortised: cumulative vertex visits stay within a small multiple of V,
     * and every record is still written.
     */
    @Test void drainsBackOffWhileNothingCanBeWritten()throws Exception{
        int units=600;var files=new ArrayList<Path>();
        for(int i=0;i<units;i++)files.add(unit(i,i==0?List.of():List.of(i-1)));
        try(var analyzer=analyzer()){
            for(int i=units-1;i>=0;i--){Path file=files.get(i);analyzer.diagnostics(file,Files.readString(file));}
            long visits=scc(analyzer,"vertex_visits");
            analyzer.awaitMemoWrites();
            assertThat(((Number)memo(analyzer).get("writes")).longValue()).as("every unit written once its chain is known").isEqualTo(units);
            assertThat(visits).as("cumulative vertex visits before the final drain, V="+units).isLessThanOrEqualTo(4L*units);
        }
    }
}
