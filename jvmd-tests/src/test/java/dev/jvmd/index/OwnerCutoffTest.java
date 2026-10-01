package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * The production owner path for diagnostics. A relevant mutation reaches the
 * consumers whose answer can change; an unchanged result stops propagation; restored consumers take part
 * exactly like freshly attributed ones. "Recompiled" is observed as javac work for that read, and every
 * answer is checked against the diagnostics a fresh analyzer computes for the same sources.
 */
class OwnerCutoffTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private Analyzer analyzer(String memo)throws Exception{
        var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources()),"reactor:"+GAV+":main",
                Map.of(root.resolve("app").toString(),GAV,sources().toString(),GAV),List.of("--release","25"),
                Set.of(),List.of(),List.of(sources()),true,"",Processing.NONE),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(new SemanticMemoStore(root.resolve(memo)));
        return analyzer;
    }
    private Path write(String name,String text)throws Exception{Path file=sources().resolve("p/"+name+".java");Files.createDirectories(file.getParent());Files.writeString(file,text);return file;}
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    record Read(boolean compiled,String errors) { }
    private static Read read(Analyzer analyzer,Path file)throws Exception{
        long before=queries(analyzer);var envelope=analyzer.diagnostics(file,Files.readString(file));
        var diagnostics=(List<?>)((Map<?,?>)envelope.result()).get("diagnostics");
        var errors=new TreeSet<String>();for(var problem:diagnostics){var text=problem.toString();if(text.contains("kind=ERROR"))errors.add(text.replaceAll(".*code=([^,]+),.*line=(\\d+).*","$1@$2"));}
        return new Read(queries(analyzer)>before,String.join(";",errors));
    }
    /** The oracle: a fresh analyzer with no memo for the current sources. */
    private String fresh(Path file)throws Exception{
        try(var analyzer=analyzer("oracle-"+System.nanoTime())){
            try(var walk=Files.walk(sources())){for(Path other:walk.filter(p->p.toString().endsWith(".java")).sorted().toList())analyzer.diagnostics(other,Files.readString(other));}
            return read(analyzer,file).errors();
        }
    }

    // A <- B <- C : B calls a.one(); C uses B's API.
    private static final String A1="package p; public class A { public int one(){ return 1; } public int two(){ return 2; } }";
    private static final String B1="package p; public class B { public int f(A a){ return a.one(); } }";
    private static final String C1="package p; public class C { public int g(B b){ return b.f(new A()); } }";
    // For restored consumers: C2 depends on B only (B2's API does not mention A), so C2's certificate holds
    // B's projection and not A's. A consumer that names A directly (C1) binds A's whole P_diag and is
    // rightly reconsidered when A's API changes.
    private static final String B2="package p; public class B { public int f(){ return new A().one(); } }";
    private static final String C2="package p; public class C { public int g(B b){ return b.f(); } }";

    @Test void anUnrelatedMemberChangeLeavesTheConsumerAndItsDownstreamAlone()throws Exception{
        Path a=write("A",A1),b=write("B",B1),c=write("C",C1);
        try(var analyzer=analyzer("memo")){
            read(analyzer,a);read(analyzer,b);read(analyzer,c);
            write("A","package p; public class A { public int one(){ return 1; } public long two(){ return 2L; } }");
            assertThat(read(analyzer,a).compiled()).isTrue();
            var rb=read(analyzer,b);var rc=read(analyzer,c);
            assertThat(rb.errors()).isEqualTo(fresh(b));assertThat(rc.errors()).isEqualTo(fresh(c));
            System.out.println("unrelated: B compiled="+rb.compiled()+" C compiled="+rc.compiled());
            assertThat(rc.compiled()).as("C: B's result did not change").isFalse();
        }
    }

    @Test void aRelevantOverloadChangeReachesTheConsumerAndChangesItsAnswer()throws Exception{
        Path a=write("A",A1),b=write("B",B1),c=write("C",C1);
        try(var analyzer=analyzer("memo")){
            read(analyzer,a);read(analyzer,b);read(analyzer,c);
            write("A","package p; public class A { public int one(int x){ return x; } public int two(){ return 2; } }");
            read(analyzer,a);
            var rb=read(analyzer,b);
            assertThat(rb.compiled()).isTrue();
            assertThat(rb.errors()).as("B now fails to resolve one()").contains("cant.apply").isEqualTo(fresh(b));
            var rc=read(analyzer,c);
            assertThat(rc.errors()).isEqualTo(fresh(c));
            assertThat(rc.compiled()).as("C: B's API (f(A)) is unchanged, so propagation stops at B").isFalse();
        }
    }

    @Test void anUnequalDepthDiamondReachesEveryAffectedConsumerOnce()throws Exception{
        // A <- B <- D and A <- D: D depends on A directly and through B.
        Path a=write("A",A1),b=write("B",B1);
        Path d=write("D","package p; public class D { int h(A a,B b){ return a.two() + b.f(a); } }");
        try(var analyzer=analyzer("memo")){
            read(analyzer,a);read(analyzer,b);read(analyzer,d);
            write("A","package p; public class A { public int one(){ return 1; } }");
            read(analyzer,a);read(analyzer,b);
            var rd=read(analyzer,d);
            assertThat(rd.compiled()).isTrue();assertThat(rd.errors()).contains("cant.resolve").isEqualTo(fresh(d));
            assertThat(read(analyzer,d).compiled()).as("evaluated once; the second read reuses it").isFalse();
        }
    }

    @Test void restoredConsumersTakePartInTheNextMutationLikeFreshOnes()throws Exception{
        Path a=write("A",A1),b=write("B",B2),c=write("C",C2);
        try(var analyzer=analyzer("memo")){read(analyzer,a);read(analyzer,b);read(analyzer,c);analyzer.awaitMemoWrites();}
        try(var analyzer=analyzer("memo")){
            assertThat(read(analyzer,a).compiled()).isFalse();assertThat(read(analyzer,b).compiled()).isFalse();assertThat(read(analyzer,c).compiled()).isFalse();
            // Relevant change after restore: B must not keep its restored clean answer.
            write("A","package p; public class A { public int one(int x){ return x; } public int two(){ return 2; } }");
            read(analyzer,a);
            var rb=read(analyzer,b);
            assertThat(rb.errors()).as("restored B reconsidered").contains("cant.apply").isEqualTo(fresh(b));
            var rc=read(analyzer,c);
            assertThat(rc.errors()).isEqualTo(fresh(c));
            assertThat(rc.compiled()).as("restored C: B's projection is unchanged (early cutoff at B)").isFalse();
        }
    }

    @Test void aRestoredConsumerOfAnUnrelatedChangeStaysRestored()throws Exception{
        Path a=write("A",A1),b=write("B",B2),c=write("C",C2);
        try(var analyzer=analyzer("memo")){read(analyzer,a);read(analyzer,b);read(analyzer,c);analyzer.awaitMemoWrites();}
        write("A","package p; public class A { public int one(){ return 1; } public long two(){ return 2L; } }");
        try(var analyzer=analyzer("memo")){
            assertThat(read(analyzer,a).compiled()).isTrue();
            var rb=read(analyzer,b);var rc=read(analyzer,c);
            System.out.println("restart unrelated: B compiled="+rb.compiled()+" C compiled="+rc.compiled());
            assertThat(rb.errors()).isEqualTo(fresh(b));assertThat(rc.errors()).isEqualTo(fresh(c));
            assertThat(rc.compiled()).as("C restored: its entry for B is unchanged").isFalse();
        }
    }

    /**
     * Deferred continuation: a document-context consumer (B's completion context) cannot be re-evaluated
     * without javac, so propagation defers it. The continuation is owner-side: the deferred consumer's
     * cached document semantics are removed, so the next completion must recompute and cannot serve the
     * pre-change answer.
     */
    @Test void aDeferredCompletionContextIsRecomputedOnTheNextRequest()throws Exception{
        Path a=write("A",A1);
        String text="package p; class B { int f(A a){ return a.; } }";Path b=write("B",text);
        int column=text.indexOf("a.;")+2;
        try(var analyzer=analyzer("memo")){
            read(analyzer,a);
            var before=analyzer.completion(b,text,0,column,200,0).result().toString();
            assertThat(before).contains("one").doesNotContain("three");
            write("A","package p; public class A { public int one(){ return 1; } public int two(){ return 2; } public int three(){ return 3; } }");
            read(analyzer,a);
            var after=analyzer.completion(b,text,0,column,200,0).result().toString();
            assertThat(after).as("the deferred context was recomputed, not served from before the change").contains("three");
        }
    }
}
