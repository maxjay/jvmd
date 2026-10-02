package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.ObservationFaults;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * A record pairs a result only with the evidence of the inputs javac read. The
 * capture is paused after attribution ({@link ObservationFaults#captureBarrier}); another thread then
 * mutates the inputs. The outcome must be a refusal ({@code inputs-superseded}), never A's result with
 * B's evidence. A -> B -> A is included: equal content afterwards does not prove javac read it.
 */
class CaptureFrontierRaceTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";

    @AfterEach void clear(){ObservationFaults.clear();}

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private static void write(Path root,String relative,String text)throws Exception{
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,text);
    }
    private Analyzer analyzer(Documents documents)throws Exception{
        Path sources=sources();var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"reactor:"+GAV+":main",
                Map.of(root.resolve("app").toString(),GAV,sources.toString(),GAV),List.of("--release","25"),
                Set.of(),List.of(),List.of(sources),true,"",Processing.NONE),null,256L*1024*1024);
        analyzer.documents(documents);analyzer.memos(new SemanticMemoStore(root.resolve("memo")));
        return analyzer;
    }
    private static Map<?,?> memo(Analyzer analyzer){return (Map<?,?>)analyzer.status().get("attributed_memo");}
    private static final String THING="package q; public class Thing { public int size(){ return 1; } }";
    private static final String THING_B="package q; public class Thing { public long size(){ return 1; } }";
    private static final String A="package p;\nimport q.Thing;\nclass A { int f(){ return new Thing().size(); } }\n";

    @FunctionalInterface interface Mutation { void apply(Documents documents,Path sources)throws Exception; }

    /** Attribute Thing, then A with its capture paused while {@code mutation} runs on another thread. */
    private Map<?,?> race(Mutation mutation)throws Exception{
        write(sources(),"q/Thing.java",THING);write(sources(),"p/A.java",A);
        var documents=new Documents(new FileStateRegistry());
        Path thing=sources().resolve("q/Thing.java"),a=sources().resolve("p/A.java");
        documents.open(thing,THING,1);
        // Every analyzer call stays on one owner thread (the compiler is thread-confined); the
        // mutation runs on the test thread as a concurrent editor event.
        var owner=Executors.newSingleThreadExecutor();
        try{
            var analyzer=owner.submit(()->analyzer(documents)).get();
            try{
                owner.submit(()->{analyzer.diagnostics(thing,THING);return null;}).get();
                var paused=new CountDownLatch(1);var resume=new CountDownLatch(1);
                ObservationFaults.captureBarrier(file->{
                    if(!file.toAbsolutePath().normalize().equals(a))return;
                    paused.countDown();
                    try{if(!resume.await(30,TimeUnit.SECONDS))throw new IllegalStateException("not resumed");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                });
                var request=owner.submit(()->{analyzer.diagnostics(a,A);return null;});
                assertThat(paused.await(30,TimeUnit.SECONDS)).as("capture reached the barrier").isTrue();
                mutation.apply(documents,sources());
                resume.countDown();
                request.get(60,TimeUnit.SECONDS);
                ObservationFaults.clear();
                return owner.submit(()->{analyzer.awaitMemoWrites();return memo(analyzer);}).get();
            }finally{owner.submit(()->{analyzer.close();return null;}).get();}
        }finally{owner.shutdownNow();}
    }
    private static long refusals(Map<?,?> memo,String reason){
        var value=((Map<?,?>)memo.get("refusal_reasons")).get(reason);return value==null?0:((Number)value).longValue();
    }

    @Test void aDependencyEditedDuringCaptureRefusesTheRecord()throws Exception{
        var memo=race((documents,sources)->documents.change(sources.resolve("q/Thing.java"),2,List.of(new Documents.Change(null,THING_B))));
        assertThat(refusals(memo,"inputs-superseded")).isEqualTo(1);
    }

    @Test void aDependencyEditedAndRevertedDuringCaptureStillRefuses()throws Exception{
        var memo=race((documents,sources)->{
            Path thing=sources.resolve("q/Thing.java");
            documents.change(thing,2,List.of(new Documents.Change(null,THING_B)));
            documents.change(thing,3,List.of(new Documents.Change(null,THING)));
        });
        assertThat(refusals(memo,"inputs-superseded")).as("A -> B -> A: the input epoch moved").isEqualTo(1);
    }

    @Test void aMembershipChangeDuringCaptureRefuses()throws Exception{
        // A new unsaved buffer in A's own package is a membership change of the source state.
        var memo=race((documents,sources)->documents.open(sources.resolve("p/Thing.java"),"package p; public class Thing { }",1));
        assertThat(refusals(memo,"inputs-superseded")).isEqualTo(1);
    }

    @Test void withoutAMutationTheCaptureIsWritten()throws Exception{
        var memo=race((documents,sources)->{});
        assertThat(refusals(memo,"inputs-superseded")).isZero();
        assertThat(((Number)memo.get("writes")).longValue()).as("Thing and A").isEqualTo(2);
    }
}
