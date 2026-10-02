package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict task W3: the attributed certificate holds P_diag of every completed dependency outside the
 * unit's SCC, content of SCC peers, the S0 type set of the own and star-imported packages only, and
 * negative resolutions. There is no coarse or global entry.
 */
class PreciseCertificateTest {
    @TempDir Path root;
    private static final String GAV="g:certificate:1";

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private Path write(String relative,String text)throws Exception{
        Path file=sources().resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,text);return file;
    }
    private Analyzer analyzer(SemanticMemoStore memos)throws Exception{
        var analyzer=new Analyzer(new FileStateRegistry());Path module=root.resolve("app"),sources=sources();
        var coordinates=Map.of(module.toString(),GAV,sources.toString(),GAV);
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"certificate:"+GAV+":main",coordinates,
                List.of("--release","25"),Set.of(),List.of(),List.of(sources),true,""),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(memos);return analyzer;
    }
    private SemanticMemoStore memos(){return new SemanticMemoStore(root.resolve("state/local-memo"));}
    private static void diagnose(Analyzer analyzer,Collection<Path> files)throws Exception{
        for(Path file:files)analyzer.diagnostics(file,Files.readString(file));
    }
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> memo(Analyzer analyzer){return (Map<String,Object>)analyzer.status().get("attributed_memo");}
    private static long memo(Analyzer analyzer,String key){return ((Number)memo(analyzer).get(key)).longValue();}
    private void seed(Collection<Path> files)throws Exception{
        try(var first=analyzer(memos())){diagnose(first,files);first.awaitMemoWrites();
            assertThat(memo(first,"writes")).as(memo(first).toString()).isEqualTo(files.size());}
    }

    @Test void bodyEditInsideAThreeCycleInvalidatesTheCycleAndBodyEditOutsideInvalidatesNone()throws Exception{
        Path o=write("p/O.java","package p; public class O { public static int f(){ return 1; } }");
        Path x=write("p/X.java","package p; public class X { public static int x(){ return Y.y() + O.f(); } }");
        Path y=write("p/Y.java","package p; public class Y { public static int y(){ return Z.z(); } }");
        Path z=write("p/Z.java","package p; public class Z { public static int z(){ return X.x() > 0 ? 1 : 0; } }");
        var all=List.of(o,x,y,z);seed(all);

        Files.writeString(x,"package p; public class X { public static int x(){ return Y.y() + O.f() + 1; } }");
        try(var restarted=analyzer(memos())){
            diagnose(restarted,all);restarted.awaitMemoWrites();
            assertThat(queries(restarted)).as("body edit to a cycle member recompiles the whole cycle").isEqualTo(3);
            assertThat(memo(restarted,"restores")).as("only the unit outside the cycle is restored").isEqualTo(1);
        }

        Files.writeString(o,"package p; public class O { public static int f(){ return 2; } }");
        try(var restarted=analyzer(memos())){
            diagnose(restarted,all);
            assertThat(queries(restarted)).as("body edit outside the cycle recompiles only that unit").isEqualTo(1);
            assertThat(memo(restarted,"restores")).as("the cycle restores against O's equal P_diag").isEqualTo(3);
        }
    }

    @Test void privateMemberOfADependencyIsPartOfTheCertificate()throws Exception{
        Path a=write("p/A.java","package p; public class A { private int hidden(){ return 1; } public int open(){ return 2; } }");
        Path b=write("p/B.java","package p; class B { int use(A a){ return a.open(); } }");
        seed(List.of(a,b));
        Files.writeString(a,"package p; public class A { public int open(){ return 2; } }");
        try(var restarted=analyzer(memos())){
            diagnose(restarted,List.of(b));
            assertThat(memo(restarted,"restores")).as("removing a private member changes P_diag(A)").isZero();
            assertThat((String)memo(restarted).get("last_miss")).startsWith("stale-dependency:RESOLUTION_PATH");
        }
    }

    @Test void namespaceEntriesAreScopedToTheOwnAndStarImportedPackages()throws Exception{
        Path a=write("p/A.java","package p; import q.*; public class A { Object f(){ return null; } }");
        Path q=write("q/Q.java","package q; public class Q { }");
        seed(List.of(a,q));

        write("r/Unrelated.java","package r; public class Unrelated { }");
        try(var restarted=analyzer(memos())){
            diagnose(restarted,List.of(a));
            assertThat(memo(restarted,"restores")).as("a new type in an unconsulted package is not a dependency").isEqualTo(1);
            assertThat(queries(restarted)).isZero();
        }

        write("q/Added.java","package q; public class Added { }");
        try(var restarted=analyzer(memos())){
            diagnose(restarted,List.of(a));
            assertThat(memo(restarted,"restores")).as("a new type in a star-imported package is").isZero();
            assertThat((String)memo(restarted).get("last_miss")).startsWith("stale-dependency:NAMESPACE");
        }
    }

    @Test void negativeResolutionOfASingleTypeImportIsPartOfTheCertificate()throws Exception{
        Path a=write("p/A.java","package p; import m.Missing; class A { Missing value; }");
        seed(List.of(a));
        write("z/Other.java","package z; public class Other { }");
        try(var restarted=analyzer(memos())){
            diagnose(restarted,List.of(a));
            assertThat(memo(restarted,"restores")).isEqualTo(1);
        }
        write("m/Missing.java","package m; public class Missing { }");
        try(var restarted=analyzer(memos())){
            var problems=restarted.diagnostics(a,Files.readString(a));
            assertThat(memo(restarted,"restores")).as("the established absence of m.Missing no longer holds").isZero();
            assertThat((String)memo(restarted).get("last_miss")).startsWith("stale-dependency:NEGATIVE_RESOLUTION");
            assertThat(problems.result().toString()).doesNotContain("cant.resolve");
        }
    }

    @Test void dependencyProjectionFromADependantsTaskEqualsTheUnitsOwnProjection()throws Exception{
        var units=DiagnosticProjectionSufficiencyTest.baseline();var files=new ArrayList<Path>();
        for(var entry:units.entrySet())files.add(write(entry.getKey(),entry.getValue()));
        try(var analyzer=analyzer(memos())){
            var own=new HashMap<Path,dev.jvmd.core.Hash256>();
            for(Path file:files)own.put(file.toAbsolutePath().normalize(),analyzer.bindings(file,Files.readString(file),null).result().diagnosticProjection());
            int compared=0;
            for(Path file:files){
                var snapshot=analyzer.bindings(file,Files.readString(file),null).result();
                for(var entry:snapshot.dependencyProjections().entrySet()){
                    assertThat(entry.getValue()).as("P_diag(%s) seen from %s",entry.getKey(),file).isEqualTo(own.get(entry.getKey()));compared++;
                }
                assertThat(snapshot.dependencyProjections().keySet()).as("every completed dependency of %s has a projection",file)
                        .containsAll(snapshot.dependencies().stream().map(path->path.toAbsolutePath().normalize())
                                .filter(path->!path.equals(file.toAbsolutePath().normalize())).toList());
            }
            assertThat(compared).isGreaterThan(50);
        }
    }
}
