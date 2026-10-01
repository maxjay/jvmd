package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.CompilerPool;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Architecture §72–75, §84, §87 and Phase 8: attributed LOCAL memos validated by certificates. */
class AttributedMemoIntegrationTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";
    private static final String A="package p; public class A { public int one(){return 1;} }";
    private static final String B="package p; class B { int use(A a){return a.one();} String bad(){return 1;} }";

    private record Project(Path module,Path sources,Path a,Path b) { }
    private Project project(String name)throws Exception{
        Path module=root.resolve(name).resolve("app"),sources=Files.createDirectories(module.resolve("src/main/java/p")).getParent();
        Path a=sources.resolve("p/A.java"),b=sources.resolve("p/B.java");
        Files.writeString(a,A);Files.writeString(b,B);return new Project(module,sources,a,b);
    }
    private Analyzer analyzer(Project project,SemanticMemoStore memos,List<String> options)throws Exception{
        var analyzer=new Analyzer(new FileStateRegistry());
        var coordinates=Map.of(project.module().toString(),GAV,project.sources().toString(),GAV);
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(project.sources()),"memo:"+GAV+":main",coordinates,
                options,Set.of(),List.of(),List.of(project.sources()),true,""),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));
        analyzer.memos(memos);return analyzer;
    }
    private Analyzer analyzer(Project project,SemanticMemoStore memos)throws Exception{return analyzer(project,memos,List.of("--release","25"));}
    @SuppressWarnings("unchecked")
    private static List<CompilerPool.Problem> diagnostics(Analyzer analyzer,Path file)throws Exception{
        var envelope=analyzer.diagnostics(file,Files.readString(file));
        return (List<CompilerPool.Problem>)((Map<String,Object>)envelope.result()).get("diagnostics");
    }
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> memo(Analyzer analyzer){return (Map<String,Object>)analyzer.status().get("attributed_memo");}
    private static long memo(Analyzer analyzer,String key){return ((Number)memo(analyzer).get(key)).longValue();}
    private static List<String> shape(List<CompilerPool.Problem> problems){
        return problems.stream().map(problem->problem.code()+"@"+problem.line()+":"+problem.character()+":"+problem.message()).toList();
    }

    @Test void restartAndRelocationReuseValidatedAttributedResultsWithoutJavac()throws Exception{
        var memos=new SemanticMemoStore(root.resolve("state/local-memo-v1"));
        var original=project("checkout");
        List<String> expected;
        try(var first=analyzer(original,memos)){
            assertThat(diagnostics(first,original.a())).isEmpty();
            expected=shape(diagnostics(first,original.b()));
            assertThat(expected).anyMatch(problem->problem.startsWith("compiler.err.prob.found.req"));
            first.awaitMemoWrites();
            assertThat(memo(first,"writes")).as(memo(first).toString()).isEqualTo(2);
        }

        // Restart: new analyzer, new file observations, new documents; same LOCAL directory.
        try(var restarted=analyzer(original,new SemanticMemoStore(root.resolve("state/local-memo-v1")))){
            assertThat(shape(diagnostics(restarted,original.b()))).isEqualTo(expected);
            assertThat(queries(restarted)).as("restored without javac").isZero();
            // B's certificate holds A's P_diag: the request restores its dependency cone, A then B.
            assertThat(memo(restarted,"restores")).isEqualTo(2);
            assertThat(restarted.contribution(original.b()).dependencies()).contains(original.a());
        }

        // Another worktree/checkout location with identical logical sources.
        var relocated=project("worktree");
        try(var moved=analyzer(relocated,memos)){
            var problems=diagnostics(moved,relocated.b());
            assertThat(shape(problems)).isEqualTo(expected);
            assertThat(problems).allMatch(problem->problem.file()==null||problem.file().equals(relocated.b().toUri().toString()));
            assertThat(queries(moved)).isZero();
        }
    }

    @Test void changedDependencyApiOrNamespaceInvalidatesTheCertificate()throws Exception{
        var memos=new SemanticMemoStore(root.resolve("memo"));
        var project=project("p");
        try(var first=analyzer(project,memos)){diagnostics(first,project.a());diagnostics(first,project.b());}

        Files.writeString(project.a(),A.replace("one()","two()"));
        try(var changed=analyzer(project,memos)){
            assertThat(diagnostics(changed,project.b())).anyMatch(problem->problem.code().contains("cant.resolve"));
            assertThat(queries(changed)).isPositive();
            assertThat(memo(changed,"restores")).isZero();
            assertThat((String)memo(changed).get("last_miss")).startsWith("stale-dependency");
        }

        Files.writeString(project.a(),A);
        try(var reverted=analyzer(project,memos)){
            diagnostics(reverted,project.b());
            assertThat(memo(reverted,"restores")).as("branch switch back reuses the original variants of A and B").isEqualTo(2);
            assertThat(queries(reverted)).isZero();
        }

        // A new type anywhere in the source roots may change resolution: the namespace leaf changes.
        Files.writeString(project.sources().resolve("p/C.java"),"package p; class C {}");
        try(var added=analyzer(project,memos)){
            diagnostics(added,project.b());
            assertThat(memo(added,"restores")).isZero();
            assertThat((String)memo(added).get("last_miss")).startsWith("stale-dependency:NAMESPACE");
        }
    }

    @Test void ambientCompilerContextIsBoundIntoTheStaticKeyOrRefusedWithItsReason()throws Exception{
        var memos=new SemanticMemoStore(root.resolve("memo"));
        var project=project("processors");
        // W6: -A options are bound into the static key, so a different value never reuses a record.
        try(var analyzer=analyzer(project,memos,List.of("--release","25","-Aflag=1"))){
            diagnostics(analyzer,project.a());diagnostics(analyzer,project.b());analyzer.awaitMemoWrites();
            assertThat(memo(analyzer,"writes")).isEqualTo(2);
        }
        try(var other=analyzer(project,memos,List.of("--release","25","-Aflag=2"))){
            diagnostics(other,project.b());
            assertThat(memo(other,"restores")).isZero();
        }
        // An option whose input has no logical identity stays refused, with its own reason code.
        try(var analyzer=analyzer(project,memos,List.of("--release","25","--patch-module","java.base=/tmp/patch"))){
            diagnostics(analyzer,project.a());analyzer.awaitMemoWrites();
            assertThat(memo(analyzer,"writes")).isZero();
            @SuppressWarnings("unchecked")
            var reasons=(Map<String,Long>)memo(analyzer).get("refusal_reasons");
            assertThat(reasons).containsKey("path-option:--patch-module");
        }
        // Compiler options are part of the static key: a different release never reuses a record.
        try(var first=analyzer(project,memos)){diagnostics(first,project.a());diagnostics(first,project.b());}
        try(var other=analyzer(project,memos,List.of("--release","21"))){
            diagnostics(other,project.b());
            assertThat(memo(other,"restores")).isZero();
            assertThat(queries(other)).isPositive();
        }
    }
}
