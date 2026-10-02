package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Annotation processing, reactor class outputs and
 * path options enter the static key as logical, restart-stable values; units Lombok rewrites are
 * bound through their binary P_diag; {@code lombok.config} files are per-unit certificate entries.
 */
class ProcessorBindingTest {
    @TempDir Path root;
    private static final String GAV="g:processing:1",LIBRARY="g:library:1";
    /** The context warning Application attaches to every Lombok context; it is context state, not a query failure. */
    private static final String FIDELITY="lombok_reduced_fidelity: generated member bodies and positions are unavailable";

    record Checkout(Path module,Path sources,Path classes,Path library) { }

    /** A module with sources plus Lombok's class output and a reactor dependency's class output, as the external run leaves them. */
    private Checkout checkout(String name)throws Exception{
        Path module=root.resolve(name).resolve("app"),sources=Files.createDirectories(module.resolve("src/main/java"));
        write(sources,"p/T.java","package p; @lombok.Getter public class T { private int x; }");
        write(sources,"p/X.java","package p; class X { int f(T t){ return t.getX() + lib.Lib.value(); } }");
        write(sources,"q/Y.java","package q; class Y { int g(){ return 1; } }");
        Path library=Files.createDirectories(root.resolve(name).resolve("library/target/classes"));
        Path librarySource=Files.createDirectories(root.resolve(name).resolve("library-src"));
        compile(List.of(write(librarySource,"lib/Lib.java","package lib; public class Lib { public static int value(){ return 2; } }")),library,List.of());
        Path classes=Files.createDirectories(root.resolve(name).resolve("state/apt/classes"));
        compile(List.of(sources.resolve("p/T.java")),classes,List.of("-proc:full","-processorpath",lombok().toString()));
        return new Checkout(module,sources,classes,library);
    }
    private static Path write(Path root,String relative,String text)throws Exception{
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,text);return file;
    }
    private static void compile(List<Path> files,Path output,List<String> extra)throws Exception{
        var options=new ArrayList<>(List.of("--release","25","-classpath",lombok().toString(),"-d",output.toString()));options.addAll(extra);
        var compiler=ToolProvider.getSystemJavaCompiler();var out=new java.io.StringWriter();
        boolean ok=compiler.getTask(out,null,null,options,null,compiler.getStandardFileManager(null,null,null).getJavaFileObjectsFromPaths(files)).call();
        assertThat(ok).as(out.toString()).isTrue();
    }
    private static Path lombok()throws Exception{return ProcessorAllowlistTest.jar("org.projectlombok:lombok:"+AnnotationFixtures.LOMBOK);}

    private Analyzer analyzer(Checkout checkout,SemanticMemoStore memos,Processing processing)throws Exception{
        var coordinates=new LinkedHashMap<String,String>();
        coordinates.put(checkout.module().toString(),GAV);coordinates.put(checkout.sources().toString(),GAV);
        coordinates.put(checkout.classes().toString(),GAV);coordinates.put("role:"+checkout.classes(),"processor-classes-main");
        coordinates.put(checkout.library().toString(),LIBRARY);coordinates.put(lombok().toString(),"org.projectlombok:lombok:"+AnnotationFixtures.LOMBOK);
        var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(checkout.classes(),checkout.library(),lombok()),List.of(checkout.sources()),
                "processing:"+GAV+":main",coordinates,List.of("--release","25"),Set.of(checkout.sources().resolve("p/T.java")),List.of(FIDELITY),
                List.of(checkout.sources()),true,"",processing),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(memos);
        return analyzer;
    }
    private static Processing lombokProcessing()throws Exception{return new Processing(true,List.of(lombok()),List.of(),"full");}
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    @SuppressWarnings("unchecked")
    private static Map<String,Object> memo(Analyzer analyzer){return (Map<String,Object>)analyzer.status().get("attributed_memo");}
    private static long memo(Analyzer analyzer,String key){return ((Number)memo(analyzer).get(key)).longValue();}
    private static Set<String> diagnose(Analyzer analyzer,Checkout checkout)throws Exception{
        var compiled=new TreeSet<String>();
        for(String unit:List.of("p/T.java","p/X.java","q/Y.java")){
            long before=queries(analyzer);Path file=checkout.sources().resolve(unit);
            var envelope=analyzer.diagnostics(file,Files.readString(file));
            if(unit.equals("p/X.java"))assertThat(envelope.result().toString()).as("Lombok members are visible through the class output").doesNotContain("cant.resolve");
            assertThat(envelope.warnings()).as("restored or computed, the context warning is reported").contains(FIDELITY);
            if(queries(analyzer)>before)compiled.add(unit);
        }
        return compiled;
    }

    @Test void allowlistedLombokContextIsMemoisedAndRestoredThroughBinaryProjections()throws Exception{
        var checkout=checkout("checkout");var memos=new SemanticMemoStore(root.resolve("memo"));
        try(var first=analyzer(checkout,memos,lombokProcessing())){
            diagnose(first,checkout);first.awaitMemoWrites();
            assertThat((Map<?,?>)memo(first).get("refusal_reasons")).as("A9: allowlisted processors are never refused").isEmpty();
            assertThat(memo(first,"writes")).as(memo(first).toString()).isEqualTo(3);
        }
        try(var restarted=analyzer(checkout,memos,lombokProcessing())){
            assertThat(diagnose(restarted,checkout)).as("A9: no-change restart of a Lombok context").isEmpty();
            assertThat(memo(restarted,"restores")).isEqualTo(3);
        }
    }

    @Test void movedCheckoutKeepsReactorAndProcessorIdentities()throws Exception{
        var memos=new SemanticMemoStore(root.resolve("memo"));
        var original=checkout("checkout");
        try(var first=analyzer(original,memos,lombokProcessing())){diagnose(first,original);first.awaitMemoWrites();}
        var moved=checkout("elsewhere/worktree");
        try(var relocated=analyzer(moved,memos,lombokProcessing())){
            assertThat(diagnose(relocated,moved)).as("reactor:<gav>|classes and processor slots are location-free").isEmpty();
        }
        // Another module's class output is bound class by class: an unrelated class leaves every record valid...
        Path librarySource=root.resolve("elsewhere/worktree/library-src");
        compile(List.of(write(librarySource,"lib/Extra.java","package lib; public class Extra { }")),moved.library(),List.of());
        try(var unrelated=analyzer(moved,memos,lombokProcessing())){
            assertThat(diagnose(unrelated,moved)).as("a class no unit completed or star-imports").isEmpty();
        }
        // ...and a signature change of a class a unit completed recompiles that unit.
        compile(List.of(write(librarySource,"lib/Lib.java","package lib; public class Lib { public static long value(){ return 2L; } }")),moved.library(),List.of());
        try(var changed=analyzer(moved,memos,lombokProcessing())){
            assertThat(diagnose(changed,moved)).as("a changed reactor class P_diag").containsExactly("p/X.java");
        }
    }

    @Test void lombokConfigEditRecompilesExactlyTheUnitsHoldingIt()throws Exception{
        var checkout=checkout("checkout");var memos=new SemanticMemoStore(root.resolve("memo"));
        try(var first=analyzer(checkout,memos,lombokProcessing())){diagnose(first,checkout);first.awaitMemoWrites();}
        Files.writeString(checkout.sources().resolve("p/lombok.config"),"config.stopBubbling = true\n");
        try(var restarted=analyzer(checkout,memos,lombokProcessing())){
            assertThat(diagnose(restarted,checkout)).as("A10: units under p/ hold p/lombok.config").containsExactly("p/T.java","p/X.java");
        }
    }

    /**
     * {@code lombok.config} is read by the processor inside javac's transaction but
     * is not in its live input state. An edit after the processor read it and before the capture must not
     * be bound to the result computed with the old configuration.
     */
    @Test void aLombokConfigEditedDuringCaptureIsNotBoundToTheEarlierResult()throws Exception{
        var checkout=checkout("checkout");var memos=new SemanticMemoStore(root.resolve("memo"));
        Path x=checkout.sources().resolve("p/X.java").toAbsolutePath().normalize();
        dev.jvmd.analyzer.ObservationFaults.captureBarrier(file->{
            if(!file.toAbsolutePath().normalize().equals(x))return;
            try{Files.writeString(checkout.sources().resolve("p/lombok.config"),"config.stopBubbling = true\n");}
            catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        });
        try(var analyzer=analyzer(checkout,memos,lombokProcessing())){
            diagnose(analyzer,checkout);analyzer.awaitMemoWrites();
            assertThat(((Map<?,?>)memo(analyzer).get("refusal_reasons")).get("processor-resource-superseded")).as(memo(analyzer).toString()).isEqualTo(1L);
        }finally{dev.jvmd.analyzer.ObservationFaults.clear();}
        try(var restarted=analyzer(checkout,memos,lombokProcessing())){
            assertThat(diagnose(restarted,checkout)).as("X had no record; T's and Y's records hold").contains("p/X.java").doesNotContain("q/Y.java");
        }
    }

    @Test void processorOutsideTheAllowlistIsRefusedWithItsClassName()throws Exception{
        var checkout=checkout("checkout");var memos=new SemanticMemoStore(root.resolve("memo"));
        var custom=new Processing(true,List.of(lombok()),List.of("com.example.CustomProcessor"),"only");
        try(var analyzer=analyzer(checkout,memos,custom)){
            diagnose(analyzer,checkout);analyzer.awaitMemoWrites();
            assertThat(memo(analyzer,"writes")).isZero();
            @SuppressWarnings("unchecked") var reasons=(Map<String,Long>)memo(analyzer).get("refusal_reasons");
            assertThat(reasons).containsKey("processor-not-allowlisted:com.example.CustomProcessor");
        }
    }
}
