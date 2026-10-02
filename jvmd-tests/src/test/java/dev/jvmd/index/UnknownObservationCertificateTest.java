package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.ObservationFaults;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * A failed or unavailable observation is UNKNOWN. It never becomes an identity,
 * so it can neither be written into a certificate nor compare equal to an earlier failure. Failures are
 * injected ({@link ObservationFaults#inject}) rather than made with file permissions, which privileged
 * CI ignores. A negative entry captured while a source root was unreadable must not match again on the
 * next unreadable restart.
 */
class UnknownObservationCertificateTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";

    @AfterEach void clear(){ObservationFaults.clear();}

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private static void write(Path root,String relative,String text)throws Exception{
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,text);
    }
    private Analyzer analyzer()throws Exception{
        Path sources=sources();var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"reactor:"+GAV+":main",
                Map.of(root.resolve("app").toString(),GAV,sources.toString(),GAV),List.of("--release","25"),
                Set.of(),List.of(),List.of(sources),true,"",Processing.NONE),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(new SemanticMemoStore(root.resolve("memo")));
        return analyzer;
    }
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    private static Map<?,?> memo(Analyzer analyzer){return (Map<?,?>)analyzer.status().get("attributed_memo");}
    record Round(Map<String,Boolean> compiled,Map<?,?> memo) { }
    /** One process lifetime over every unit; which units ran javac, and the memo counters. */
    private Round run()throws Exception{
        try(var analyzer=analyzer()){
            var compiled=new TreeMap<String,Boolean>();
            List<Path> files;try(var walk=Files.walk(sources())){files=walk.filter(path->path.toString().endsWith(".java")).sorted().toList();}
            for(Path file:files){
                long before=queries(analyzer);analyzer.diagnostics(file,Files.readString(file));
                compiled.put(sources().relativize(file).toString(),queries(analyzer)>before);
            }
            analyzer.awaitMemoWrites();
            return new Round(compiled,memo(analyzer));
        }
    }
    /** A has a negative entry (Missing is unresolved), a P_diag dependency (Thing) and a package entry. */
    private void project()throws Exception{
        write(sources(),"q/Thing.java","package q; public class Thing { public int size(){ return 1; } }");
        write(sources(),"p/A.java","package p;\nimport q.Thing;\nclass A { int f(){ return new Thing().size(); } Missing m; }\n");
    }
    private Predicate<Path> under(Path path){Path normalized=path.toAbsolutePath().normalize();return candidate->candidate.startsWith(normalized);}
    @SuppressWarnings("unchecked") private static Map<Object,Object> reasons(Round round,String key){return (Map<Object,Object>)round.memo().get(key);}

    @Test void anUnreadableSourceRootAtCaptureWritesNoRecordAndTwoFailuresNeverHit()throws Exception{
        project();
        ObservationFaults.inject(under(root.resolve("app")));
        var first=run();
        assertThat(first.compiled().get("p/A.java")).isTrue();
        assertThat(reasons(first,"refusal_reasons")).as("refused with a reason, not written").containsKey("observation-unavailable");
        // The same failure again: no record exists, so nothing can compare equal to the earlier failure.
        var second=run();
        assertThat(second.compiled().get("p/A.java")).as("two unavailable observations never make a hit").isTrue();
        ObservationFaults.clear();
        var readable=run();
        assertThat(readable.compiled().get("p/A.java")).as("evidence available again: computed normally").isTrue();
        var restart=run();
        assertThat(restart.compiled().get("p/A.java")).as("and then reused").isFalse();
    }

    @Test void anUnreadableSourceRootAtRestartMissesThenHitsOnceReadable()throws Exception{
        project();run();
        ObservationFaults.inject(under(sources()));
        var failing=run();
        assertThat(failing.compiled().get("p/A.java")).as("the negative entry cannot be established: no restore").isTrue();
        assertThat(reasons(failing,"miss_reasons").keySet()).anyMatch(reason->String.valueOf(reason).equals("observation-unavailable"));
        var again=run();
        assertThat(again.compiled().get("p/A.java")).as("a repeated identical failure still misses").isTrue();
        ObservationFaults.clear();
        assertThat(run().compiled().get("p/A.java")).as("readable again: restored").isFalse();
    }

    @Test void anUnreadableDependencyIsUnknownNotUnchanged()throws Exception{
        project();run();
        ObservationFaults.inject(under(sources().resolve("q/Thing.java")));
        var round=run();
        assertThat(round.compiled().get("p/A.java")).as("Thing's current P_diag is unknown, so A's entry cannot match").isTrue();
        ObservationFaults.clear();
        assertThat(run().compiled().get("p/A.java")).isFalse();
    }

    @Test void aCorruptRecordIsAMissAndIsReplaced()throws Exception{
        project();run();
        try(var walk=Files.walk(root.resolve("memo"))){
            for(Path file:walk.filter(path->path.toString().endsWith(".memo")).toList()){
                var record=SemanticMemoStore.decode(Files.readAllBytes(file));
                if(record.key().function().name().equals("attributed-diagnostics")){byte[] bytes=Files.readAllBytes(file);Files.write(file,Arrays.copyOf(bytes,bytes.length/2));}
            }
        }
        var corrupt=run();
        assertThat(corrupt.compiled().get("p/A.java")).isTrue();
        assertThat(run().compiled().get("p/A.java")).as("rewritten by the recomputation").isFalse();
    }

    @Test void onlyAnEstablishedAbsenceIsNegative()throws Exception{
        Path file=Files.writeString(root.resolve("regular.txt"),"x");
        // A missing path and a path through a regular file are absent; an injected failure is unavailable.
        assertThat(ObservationFaults.directory(root.resolve("missing"))).isFalse();
        assertThat(ObservationFaults.regularFile(file.resolve("child"))).isFalse();
        assertThat(ObservationFaults.regularFile(file)).isTrue();
        ObservationFaults.inject(under(file));
        assertThatThrownBy(()->ObservationFaults.regularFile(file)).isInstanceOf(ObservationFaults.Unavailable.class);
    }
}
