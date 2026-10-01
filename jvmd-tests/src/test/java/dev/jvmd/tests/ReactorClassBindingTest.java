package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict task A8 on a reactor: another module's class directory is bound class by class
 * ({@code reactor-class:} P_diag, {@code class-package:} sets, negative resolutions), not by the
 * directory's content. A body-only rebuild of the library restores every dependant; a signature
 * change reached only through a supertype, a class appearing where a name was unresolved, and a
 * class making a star-imported name ambiguous each recompile exactly the units that hold them.
 */
class ReactorClassBindingTest {
    @TempDir Path root;
    private static final String APP="g:app:1",LIB="g:lib:1";
    private Path library,librarySources,sources;
    private SemanticMemoStore memos;
    private List<String> units=List.of("p/A.java","p/B.java","q/C.java","q/D.java");

    @BeforeEach void reactor()throws Exception{
        librarySources=Files.createDirectories(root.resolve("lib/src/main/java"));library=Files.createDirectories(root.resolve("lib/target/classes"));
        write(librarySources,"lib/Base.java","package lib; public class Base { public int inherited(){ return 1; } }");
        write(librarySources,"lib/Lib.java","package lib; public class Lib extends Base { public static int value(){ return 2; } }");
        buildLibrary();
        sources=Files.createDirectories(root.resolve("app/src/main/java"));
        // A: reaches Base.inherited only through Lib's supertype.
        write(sources,"p/A.java","package p; class A { int f(lib.Lib l){ return l.inherited(); } }");
        // B: names a library class that does not exist (a negative resolution).
        write(sources,"p/B.java","package p; class B { Object g(){ return new lib.Missing(); } }");
        // C: star-imports lib and java.util and uses List, which only java.util has.
        write(sources,"q/C.java","package q; import java.util.*; import lib.*; class C { List<String> h(){ return new ArrayList<>(); } }");
        // D: does not touch the library.
        write(sources,"q/D.java","package q; class D { int k(){ return 3; } }");
        memos=new SemanticMemoStore(root.resolve("memo"));
    }
    private static Path write(Path root,String relative,String text)throws Exception{
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,text);return file;
    }
    /** A Maven rebuild of the library module: its class directory is rewritten from its sources. */
    private void buildLibrary()throws Exception{
        try(var old=Files.walk(library)){for(Path file:old.filter(Files::isRegularFile).toList())Files.delete(file);}
        List<Path> files;try(var walk=Files.walk(librarySources)){files=walk.filter(path->path.toString().endsWith(".java")).toList();}
        var compiler=ToolProvider.getSystemJavaCompiler();var out=new java.io.StringWriter();
        boolean ok=compiler.getTask(out,null,null,List.of("--release","25","-d",library.toString()),null,
                compiler.getStandardFileManager(null,null,null).getJavaFileObjectsFromPaths(files)).call();
        assertThat(ok).as(out.toString()).isTrue();
    }
    private Analyzer analyzer()throws Exception{
        var coordinates=new LinkedHashMap<String,String>();
        coordinates.put(root.resolve("app").toString(),APP);coordinates.put(sources.toString(),APP);coordinates.put(library.toString(),LIB);
        var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(APP,"25",List.of(library),List.of(sources),"reactor:"+APP+":main",coordinates,List.of("--release","25"),
                Set.of(),List.of(),List.of(sources),true,"",Processing.NONE),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(memos);
        return analyzer;
    }
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    record Round(Set<String> compiled,Map<String,String> diagnostics) { }
    private Round diagnose()throws Exception{
        var compiled=new TreeSet<String>();var diagnostics=new TreeMap<String,String>();
        try(var analyzer=analyzer()){
            for(String unit:units){
                long before=queries(analyzer);Path file=sources.resolve(unit);
                var envelope=analyzer.diagnostics(file,Files.readString(file));diagnostics.put(unit,envelope.result()+" warnings="+envelope.warnings());
                if(queries(analyzer)>before)compiled.add(unit);
            }
            analyzer.awaitMemoWrites();
            @SuppressWarnings("unchecked") var memo=(Map<String,Object>)analyzer.status().get("attributed_memo");
            assertThat((Map<?,?>)memo.get("refusal_reasons")).as(memo.toString()).isEmpty();
        }
        return new Round(compiled,diagnostics);
    }

    /**
     * A name left unresolved under static imports is bound through the statically imported classes:
     * a member added to a source class of this module or to a library class recompiles the unit.
     */
    @Test void unresolvedNamesUnderStaticImportsAreBound()throws Exception{
        write(sources,"q/Consts.java","package q; public class Consts { public static final int ONE=1; }");
        write(sources,"q/E.java","package q; import static q.Consts.*; class E { int m(){ return MORE; } }");
        write(sources,"q/F.java","package q; import static lib.Lib.*; class F { int k(){ return EXTRA; } }");
        units=List.of("q/Consts.java","q/E.java","q/F.java","q/D.java");
        var cold=diagnose();
        assertThat(cold.diagnostics().get("q/E.java")).contains("cant.resolve");assertThat(cold.diagnostics().get("q/F.java")).contains("cant.resolve");
        assertThat(diagnose().compiled()).as("both records were written: no-change restart").isEmpty();

        write(sources,"q/Consts.java","package q; public class Consts { public static final int ONE=1; public static final int MORE=2; }");
        var source=diagnose();
        assertThat(source.compiled()).as("a static member added to a statically imported source class").contains("q/E.java").doesNotContain("q/F.java","q/D.java");
        assertThat(source.diagnostics().get("q/E.java")).doesNotContain("cant.resolve");

        write(librarySources,"lib/Lib.java","package lib; public class Lib extends Base { public static int value(){ return 2; } public static final int EXTRA=3; }");
        buildLibrary();
        var library=diagnose();
        assertThat(library.compiled()).as("a static member added to a statically imported library class").contains("q/F.java").doesNotContain("q/D.java");
        assertThat(library.diagnostics().get("q/F.java")).doesNotContain("cant.resolve");
    }

    @Test void libraryClassesAreBoundClassByClass()throws Exception{
        var cold=diagnose();
        assertThat(cold.diagnostics().get("p/B.java")).contains("cant.resolve");
        assertThat(diagnose().compiled()).as("no-change restart").isEmpty();

        write(librarySources,"lib/Base.java","package lib; public class Base { public int inherited(){ int body=7; return body; } }");
        buildLibrary();
        assertThat(diagnose().compiled()).as("A8: a body-only library rebuild restores every dependant").isEmpty();

        write(librarySources,"lib/Base.java","package lib; public class Base { public long inherited(){ return 1L; } }");
        buildLibrary();
        var signature=diagnose();
        assertThat(signature.compiled()).as("a supertype's signature change recompiles its users only").containsExactly("p/A.java");
        assertThat(signature.diagnostics().get("p/A.java")).contains("possible lossy conversion");
        var unchanged=diagnose();
        assertThat(unchanged.compiled()).as("no-change restart after the signature change").isEmpty();
        assertThat(unchanged.diagnostics()).as("restored diagnostics and warnings equal the computed ones").isEqualTo(signature.diagnostics());

        write(librarySources,"lib/Missing.java","package lib; public class Missing { }");
        buildLibrary();
        var appeared=diagnose();
        assertThat(appeared.compiled()).as("a class appearing where a name was unresolved").contains("p/B.java").doesNotContain("q/D.java");
        assertThat(appeared.diagnostics().get("p/B.java")).doesNotContain("cant.resolve");

        write(librarySources,"lib/List.java","package lib; public class List { }");
        buildLibrary();
        var ambiguous=diagnose();
        assertThat(ambiguous.compiled()).as("a class added to a star-imported package").contains("q/C.java").doesNotContain("q/D.java");
        assertThat(ambiguous.diagnostics().get("q/C.java")).contains("ambiguous");
    }
}
