package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class DiagnosticIdentityTest {
    @TempDir Path root;

    @Test void warmRequestUsesStampsAndDetectsPreservedModificationTime()throws Exception{
        Path file=root.resolve("A.java");Files.writeString(file,"class A { int n=1; }");
        var documents=new Documents();
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,64L*1024*1024);analyzer.documents(documents);
            analyzer.diagnostics(file,documents);long hashes=((Number)documents.fileStates().status().get("hashes")).longValue();
            analyzer.diagnostics(file,documents);
            if(Files.getFileStore(file).supportsFileAttributeView("unix"))
                assertThat(documents.fileStates().status()).containsEntry("hashes",hashes);
            assertThat(analyzer.status()).containsEntry("queries",1L);
            var stamp=Files.getLastModifiedTime(file);Files.writeString(file,"class A { int n=X; }");Files.setLastModifiedTime(file,stamp);
            assertThat(problems(analyzer.diagnostics(file,documents))).isNotEmpty();
            assertThat(analyzer.status()).containsEntry("queries",2L);
        }
    }

    @Test void constantsAndTransitiveDependenciesInvalidateWithoutTouchingUnrelatedFiles()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java"),c=root.resolve("C.java"),other=root.resolve("Other.java");
        String original="class A { static final int N=1; int value(){return 1;} }";
        Files.writeString(a,original);Files.writeString(b,"class B extends A {}");
        Files.writeString(c,"class C { int n=new B().value(); int f(int x){return switch(x){case A.N->1; case 2->2; default->0;};} }");
        Files.writeString(other,"class Other {}");var documents=new Documents();documents.open(a,original,1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,128L*1024*1024);analyzer.documents(documents);
            for(Path file:List.of(a,b,c,other))assertThat(problems(analyzer.diagnostics(file,documents))).isEmpty();
            String changed=original.replace("N=1","N=2");documents.change(a,2,List.of(new Documents.Change(null,changed)));analyzer.documents(documents);analyzer.changed(a);
            analyzer.diagnostics(a,documents);analyzer.diagnostics(b,documents);
            assertThat(problems(analyzer.diagnostics(c,documents))).anyMatch(p->p.code().contains("duplicate.case"));
            long queries=((Number)analyzer.status().get("queries")).longValue();analyzer.diagnostics(other,documents);
            assertThat(analyzer.status()).containsEntry("queries",queries).containsEntry("api_fingerprint_changes",1L);
        }
    }

    @Test void compilerReleaseChangeCannotReuseAnOldDiagnosticState()throws Exception{
        Path file=root.resolve("Modern.java");String text="record Modern(int value) {}";Files.writeString(file,text);var documents=new Documents();
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","11",List.of(),List.of(root),"same-module",Map.of(root.toString(),"test:app:1")),null,64L*1024*1024);analyzer.documents(documents);
            assertThat(problems(analyzer.diagnostics(file,documents))).isNotEmpty();assertThat(analyzer.status()).containsEntry("queries",1L);
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"same-module",Map.of(root.toString(),"test:app:1")),null,64L*1024*1024);analyzer.documents(documents);
            assertThat(problems(analyzer.diagnostics(file,documents))).isEmpty();assertThat(analyzer.status()).containsEntry("queries",2L);
        }
    }

    @Test void diagnosticMemoryHasAnEnforcedBudget(){
        var store=new DiagnosticStore();store.budget(1024);
        for(int i=0;i<100;i++)store.put(root.resolve("A"+i+".java"),"hash","context"+i,"cp",Envelope.of(2,"live",Map.of("diagnostics",List.of())));
        assertThat(((Number)store.status().get("bytes")).longValue()).isLessThanOrEqualTo(1024);
        assertThat(((Number)store.status().get("evictions")).longValue()).isPositive();
    }
    private Analyzer.Context context(){return new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"context",Map.of(root.toString(),"test:app:1"));}
    @SuppressWarnings("unchecked") private static List<CompilerPool.Problem> problems(Envelope value){return (List<CompilerPool.Problem>)((Map<?,?>)value.result()).get("diagnostics");}
}
