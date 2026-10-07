package dev.jvmd.boot.cold.stage3;

import com.sun.source.util.JavacTask;
import dev.jvmd.core.hash.digests.Sha256;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Stage 3's pool is greenfield: its byte oracle is a fresh javac task over the same source/classpath. */
@Tag("phase-3")
class BodyPoolTest {
    @TempDir Path dir;
    private static final List<String> OPTIONS = List.of("-proc:none", "-implicit:none", "-encoding", "UTF-8", "-g", "-parameters");
    private static final String F = "package p; public class F { public static final int C=10; public String value(){ return new G().value(); } static class Nested {} public Object local(){ class Local {} return new Local(); } public Runnable anonymous(){ return new Runnable(){ public void run(){} }; } }";
    private static final String G = "package p; public class G { public String value(){ return String.valueOf(F.C); } }";

    private Pool.Configuration configuration(Path own, List<Path> route, List<String> ownTypes) {
        var digest = Sha256.INSTANCE;
        return new Pool.Configuration(new Pool.Key(digest.hash(new byte[]{1}), digest.hash(new byte[]{3})),
                own, route, StandardCharsets.UTF_8, OPTIONS, ownTypes);
    }

    private static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("memory:///"+name+".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return text; }
        };
    }

    private static int phases(JavacTask task) {
        try {
            int count=0; for(var unit:task.parse()) count++;
            task.analyze(); task.generate(); return count;
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }

    private Path javac(String outputName, Map<String,String> sources, List<Path> classpath) throws Exception {
        return javac(outputName,sources,classpath,OPTIONS);
    }

    private Path javac(String outputName, Map<String,String> sources, List<Path> classpath,List<String> options) throws Exception {
        Path output=Files.createDirectories(dir.resolve(outputName));
        var compiler=ToolProvider.getSystemJavaCompiler();
        var errors=new ArrayList<String>();
        try(var files=compiler.getStandardFileManager(d -> { if(d.getKind()==Diagnostic.Kind.ERROR) errors.add(d.getMessage(Locale.ROOT)); }, Locale.ROOT, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(javax.tools.StandardLocation.CLASS_PATH,classpath);
            files.setLocationFromPaths(javax.tools.StandardLocation.SOURCE_PATH,List.of());
            files.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT,List.of(output));
            var task=compiler.getTask(null,files,null,options,null,sources.entrySet().stream().map(e -> source(e.getKey(),e.getValue())).toList());
            task.setLocale(Locale.ROOT);
            assertThat(task.call()).as(errors.toString()).isTrue();
        }
        return output;
    }

    private Path stubs(Path compiled) throws Exception {
        var digest=Sha256.INSTANCE;
        var tree=new dev.jvmd.core.tree.ContentTree(digest);
        var nodes=new java.util.HashMap<dev.jvmd.core.hash.Identity,byte[]>();
        var sink=new dev.jvmd.core.tree.NodeSink() {
            public void write(dev.jvmd.core.tree.Node node) { nodes.put(node.hash(),node.bytes()); }
            public void flush() { }
        };
        var builder=new dev.jvmd.index.layer.machine.LeafBuilder(tree,sink);
        var facts=new ArrayList<dev.jvmd.index.layer.machine.Fact>();
        for(var entry:bytes(compiled).entrySet()) {
            var parsed=dev.jvmd.index.layer.machine.ClassFacts.of(digest,entry.getValue(),entry.getKey());
            facts.addAll(parsed.facts());builder.edges(parsed.edges());
        }
        facts.sort((a,b) -> java.util.Arrays.compareUnsigned(a.m(),b.m()));facts.forEach(builder::add);builder.seal();
        var leaf=builder.build();
        Path output=Files.createDirectories(dir.resolve(compiled.getFileName()+"-stubs"));
        for(var stub:dev.jvmd.index.layer.machine.Stubs.stubs(digest,tree,leaf,nodes::get,dev.jvmd.index.layer.machine.Stubs.Cache.NONE)) {
            var path=output.resolve(stub.internalName()+".class");Files.createDirectories(path.getParent());Files.write(path,stub.bytes());
        }
        return output;
    }

    private Map<String,byte[]> bytes(Path output) throws Exception {
        var result=new TreeMap<String,byte[]>();
        try(var files=Files.walk(output)) { for(var path:files.filter(p -> p.toString().endsWith(".class")).toList())
            result.put(output.relativize(path).toString().replace('\\','/').replaceFirst("\\.class$",""),Files.readAllBytes(path)); }
        return result;
    }

    private static void sameBytes(Map<String,byte[]> actual,Map<String,byte[]> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        actual.forEach((name,value) -> assertThat(value).as(name).isEqualTo(expected.get(name)));
    }

    @Test void givenSourceOwnClasspathOrderAndFThenGThenFMatchFreshJavac() throws Exception {
        var own=stubs(javac("own",Map.of("p/F",F,"p/G",G),List.of()));
        var shadow=javac("shadow",Map.of("p/F","package p; public class F { public static final int C=999; }"),List.of());
        var expectedF=bytes(javac("freshF",Map.of("p/F",F),List.of(own,shadow)));
        var expectedG=bytes(javac("freshG",Map.of("p/G",G),List.of(own,shadow)));
        try(var pool=new Pool(configuration(own,List.of(shadow),List.of("p/F","p/F$Nested","p/G")),1)) {
            var errors=new ArrayList<String>();
            javax.tools.DiagnosticListener<JavaFileObject> listener=d -> { if(d.getKind()==Diagnostic.Kind.ERROR) errors.add(d.getMessage(Locale.ROOT)); };
            var first=pool.withTask(source("p/F",F),listener,BodyPoolTest::phases);
            var second=pool.withTask(source("p/G",G),listener,BodyPoolTest::phases);
            var third=pool.withTask(source("p/F",F),listener,BodyPoolTest::phases);
            assertThat(errors).isEmpty();
            assertThat(first.value()).isEqualTo(1); assertThat(second.value()).isEqualTo(1); assertThat(third.value()).isEqualTo(1);
            sameBytes(first.classes(),expectedF);sameBytes(second.classes(),expectedG);sameBytes(third.classes(),expectedF);
            var combined=new TreeMap<String,byte[]>();combined.putAll(first.classes());combined.putAll(second.classes());
            sameBytes(combined,bytes(dir.resolve("own")));
            assertThat(first.classes().keySet()).contains("p/F$Nested","p/F$1Local","p/F$1");
            assertThat(pool.statistics()).isEqualTo(new Pool.Statistics(1,1,3));
        }
    }

    @Test void sourceSymbolsAreEvictedAndDependencySymbolsSurvive() throws Exception {
        var own=stubs(javac("own",Map.of("p/F",F,"p/G",G),List.of()));
        var seenOwn=new AtomicReference<Object>(); var seenJdk=new AtomicReference<Object>();
        try(var pool=new Pool(configuration(own,List.of(),List.of("p/F","p/F$Nested","p/G")),1)) {
            for(int i=0;i<3;i++) {
                int run=i;
                pool.withTask(source("p/G",G),null,task -> {
                    try {
                        task.parse();task.analyze();
                        var ownSymbol=task.getElements().getTypeElement("p.F");
                        var dependency=task.getElements().getTypeElement("java.lang.String");
                        if(run>0) { assertThat(ownSymbol).isNotSameAs(seenOwn.get());assertThat(dependency).isSameAs(seenJdk.get()); }
                        seenOwn.set(ownSymbol);seenJdk.set(dependency);
                        task.generate();return null;
                    } catch(IOException e) { throw new UncheckedIOException(e); }
                });
            }
        }
    }

    @Test void sourceFromEarlierTaskCannotReplaceItsStubInLaterTask() throws Exception {
        var own=stubs(javac("own",Map.of("p/F",F,"p/G",G),List.of()));
        var expectedG=bytes(javac("freshG",Map.of("p/G",G),List.of(own)));
        try(var pool=new Pool(configuration(own,List.of(),List.of("p/F","p/F$Nested","p/G")),1)) {
            // Deliberately disagree with the stub to expose a leaked source symbol, even if its API would otherwise mask it.
            pool.withTask(source("p/F",F.replace("C=10","C=20")),null,BodyPoolTest::phases);
            sameBytes(pool.withTask(source("p/G",G),null,BodyPoolTest::phases).classes(),expectedG);
        }
    }

    @Test void ownCleanupCountsOnlyCompletedOrEnteredNames() throws Exception {
        Pool.OwnStatistics baseline=null;
        for(int unrelated:List.of(0,128,1024)) {
            var sources=new TreeMap<String,String>(Map.of("p/F",F,"p/G",G));
            for(int i=0;i<unrelated;i++)sources.put("unrelated/U"+i,"package unrelated; public class U"+i+" {}");
            var compiled=javac("own-"+unrelated,sources,List.of());
            var own=stubs(compiled);var ownTypes=new ArrayList<>(bytes(compiled).keySet());
            try(var pool=new Pool(configuration(own,List.of(),ownTypes),1)) {
                assertThat(pool.ownStatistics()).isEqualTo(new Pool.OwnStatistics(0,0));
                for(int i=0;i<3;i++) {
                    pool.withTask(source("p/F",F),null,BodyPoolTest::phases);
                    pool.withTask(source("p/G",G),null,BodyPoolTest::phases);
                }
                var work=pool.ownStatistics();
                assertThat(work.evictionProbes()).isPositive();assertThat(work.stubLookups()).isBetween(1L,3L);
                if(baseline!=null)assertThat(work).isEqualTo(baseline);
                baseline=work;
                System.out.println("F07 own types="+ownTypes.size()+" cleanup="+work);
            }
        }
    }

    @Test void hierarchyQueryRecordsAClassWhoseInputIsDiscoveredDuringCompletion() throws Exception {
        var dep=stubs(javac("dependency",Map.of("q/Lib","package q; public class Lib { public r.Lazy value; }",
                "r/Lazy","package r; public class Lazy {}"),List.of()));
        var own=Files.createDirectories(dir.resolve("own"));
        try(var pool=new Pool(configuration(own,List.of(dep),List.of()),1)) {
            pool.withTask(source("App","class App {}"),null,task -> {
                try {
                    task.parse();task.analyze();
                    var lib=task.getElements().getTypeElement("q.Lib");
                    var field=lib.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("value")).findFirst().orElseThrow();
                    var type=(javax.lang.model.type.DeclaredType)field.asType();
                    var symbol=type.asElement();
                    var classfile=Class.forName("com.sun.tools.javac.code.Symbol$ClassSymbol").getField("classfile");
                    assertThat(classfile.get(symbol)).isNull();
                    assertThat(Pool.reads(task)).extracting(dev.jvmd.index.layer.local.Proof.Range::type).doesNotContain("r/Lazy");
                    var context=Class.forName("com.sun.tools.javac.api.JavacTaskImpl").getMethod("getContext").invoke(task);
                    var types=Class.forName("com.sun.tools.javac.code.Types");
                    var nativeTypes=types.getMethod("instance",Class.forName("com.sun.tools.javac.util.Context")).invoke(null,context);
                    types.getMethod("supertype",Class.forName("com.sun.tools.javac.code.Type")).invoke(nativeTypes,type);
                    assertThat(classfile.get(symbol)).isNotNull();
                    assertThat(Pool.reads(task)).extracting(dev.jvmd.index.layer.local.Proof.Range::type).contains("r/Lazy");
                    task.generate();return null;
                } catch(ReflectiveOperationException | IOException e) { throw new IllegalStateException(e); }
            });
        }
    }

    @Test void concurrentWorkersMatchSerialBytesAndCloseRejectsNewTasks() throws Exception {
        var own=stubs(javac("own",Map.of("p/F",F,"p/G",G),List.of()));
        var expectedF=bytes(javac("freshF",Map.of("p/F",F),List.of(own)));
        var expectedG=bytes(javac("freshG",Map.of("p/G",G),List.of(own)));
        var pool=new Pool(configuration(own,List.of(),List.of("p/F","p/F$Nested","p/G")),2);
        var entered=new CountDownLatch(2);
        try(pool;var executor=Executors.newFixedThreadPool(2)) {
            java.util.function.Function<JavacTask,Integer> action=task -> {
                entered.countDown();
                try { assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                return phases(task);
            };
            var f=executor.submit(() -> pool.withTask(source("p/F",F),null,action));
            var g=executor.submit(() -> pool.withTask(source("p/G",G),null,action));
            sameBytes(f.get(30,TimeUnit.SECONDS).classes(),expectedF);sameBytes(g.get(30,TimeUnit.SECONDS).classes(),expectedG);
            assertThat(pool.statistics()).isEqualTo(new Pool.Statistics(2,2,2));
        }
        assertThatThrownBy(() -> pool.withTask(source("p/G",G),null,BodyPoolTest::phases)).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test void completedTaskIncludesNativeReadsMadeOnlyDuringGeneration() throws Exception {
        var own=Files.createDirectories(dir.resolve("own"));
        String text="public record R(int value) { public String concat(int x){ return \"x=\"+x; } }";
        try(var pool=new Pool(configuration(own,List.of(),List.of("R")),1)) {
            List<dev.jvmd.index.layer.local.Proof.Range> previous=null;
            for(int run=0;run<2;run++) {
                var result=pool.withTask(source("R",text),null,task -> {
                    try {
                        task.parse();task.analyze();var before=Pool.reads(task);
                        task.generate();return before;
                    } catch(IOException e) { throw new UncheckedIOException(e); }
                });
                assertThat(result.reads()).containsAll(result.value());
                assertThat(result.reads().stream().filter(read -> !result.value().contains(read)).toList()).isNotEmpty();
                assertThat(result.classes()).containsKey("R");
                if(previous!=null) assertThat(result.reads()).isEqualTo(previous);
                previous=result.reads();
            }
        }
    }

    @Test void compilerErrorDoesNotLeakDiagnosticsAndThrownCallbackDiscardsContext() throws Exception {
        var own=stubs(javac("own",Map.of("p/F",F,"p/G",G),List.of()));
        var expected=bytes(javac("freshG",Map.of("p/G",G),List.of(own)));
        try(var pool=new Pool(configuration(own,List.of(),List.of("p/F","p/F$Nested","p/G")),1)) {
            var errors=new ArrayList<String>();
            var failed=pool.withTask(source("p/G","package p; public class G { void f(){ missing(); } }"),d -> errors.add(d.getCode()),BodyPoolTest::phases);
            assertThat(errors).anyMatch(code -> code.startsWith("compiler.err."));assertThat(failed.classes()).isEmpty();
            errors.clear();
            sameBytes(pool.withTask(source("p/G",G),d -> errors.add(d.getCode()),BodyPoolTest::phases).classes(),expected);
            assertThat(errors).isEmpty();assertThat(pool.statistics().contexts()).isEqualTo(1);
            assertThatThrownBy(() -> pool.withTask(source("p/G",G),null,task -> { try { task.parse(); } catch(IOException e) { throw new UncheckedIOException(e); } throw new AssertionError("callback"); }))
                    .isInstanceOf(AssertionError.class).hasMessage("callback");
            sameBytes(pool.withTask(source("p/G",G),null,BodyPoolTest::phases).classes(),expected);
            assertThat(pool.statistics().contexts()).isEqualTo(2);
        }
    }

    @Test void jarRemainsReusableUntilCloseAndAdjacentSourceIsNeverCompiled() throws Exception {
        var dependency=javac("dependency",Map.of("dep/K","package dep; public class K { public static int value(){ return 42; } }"),List.of());
        var jar=dir.resolve("dependency.jar");
        try(var output=new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            for(var entry:bytes(dependency).entrySet()) {
                output.putNextEntry(new java.util.jar.JarEntry(entry.getKey()+".class"));output.write(entry.getValue());output.closeEntry();
            }
        }
        var own=Files.createDirectories(dir.resolve("own"));
        Files.writeString(own.resolve("Hidden.java"),"class Hidden { static final int VALUE=77; }");
        String text="public class Use { public int value(){ return dep.K.value(); } }";
        var expected=bytes(javac("fresh",Map.of("Use",text),List.of(own,jar)));
        try(var pool=new Pool(configuration(own,List.of(jar),List.of()),1)) {
            sameBytes(pool.withTask(source("Use",text),null,BodyPoolTest::phases).classes(),expected);
            sameBytes(pool.withTask(source("Use",text),null,BodyPoolTest::phases).classes(),expected);
            var diagnostics=new ArrayList<String>();
            var missing=pool.withTask(source("Use","class Use { int x=Hidden.VALUE; }"),d -> diagnostics.add(d.getCode()),BodyPoolTest::phases);
            assertThat(missing.classes()).isEmpty();assertThat(diagnostics).anyMatch(code -> code.startsWith("compiler.err."));
        }
        Files.delete(jar); // On Windows an unclosed javac zip prevents this deletion.
        assertThat(jar).doesNotExist();
    }


    @Test void preModuleSourceLevelKeepsTheSameOwnStubRestoration() throws Exception {
        var own=stubs(javac("own",Map.of("p/F",F,"p/G",G),List.of()));
        var options=new ArrayList<>(OPTIONS);options.addAll(List.of("-source","8","-target","8","-Xlint:-options"));
        var base=configuration(own,List.of(),List.of("p/F","p/F$Nested","p/G"));
        var config=new Pool.Configuration(base.key(),base.ownStubs(),base.route(),base.charset(),options,base.ownTypes());
        var expected=bytes(javac("freshG8",Map.of("p/G",G),List.of(own),options));
        try(var pool=new Pool(config,1)) {
            pool.withTask(source("p/F",F),null,BodyPoolTest::phases);
            sameBytes(pool.withTask(source("p/G",G),null,BodyPoolTest::phases).classes(),expected);
            sameBytes(pool.withTask(source("p/G",G),null,BodyPoolTest::phases).classes(),expected);
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
    }

    private record Message(Diagnostic.Kind kind,String code,long position,long start,long end,long line,long column,String text,URI source) {
        static Message of(Diagnostic<? extends JavaFileObject> d) {
            return new Message(d.getKind(),d.getCode(),d.getPosition(),d.getStartPosition(),d.getEndPosition(),d.getLineNumber(),d.getColumnNumber(),
                    d.getMessage(Locale.ROOT),d.getSource()==null ? null : d.getSource().toUri());
        }
    }

    @Test void byteSnapshotsMatchNativeDecodingDiagnosticsAndNeverReopenSource() throws Exception {
        var own=Files.createDirectories(dir.resolve("own"));
        for(var charset:List.of(StandardCharsets.UTF_8,StandardCharsets.ISO_8859_1)) {
            var options=new ArrayList<>(OPTIONS);options.set(options.indexOf("UTF-8"),charset.name());
            var base=configuration(own,List.of(),List.of("Bytes"));
            var config=new Pool.Configuration(base.key(),own,List.of(),charset,options,base.ownTypes());
            try(var pool=new Pool(config,1)) {
                // A malformed task between two successful tasks must not retain its decoder or diagnostics.
                for(int run=0;run<3;run++) {
                    var raw=new ByteArrayOutputStream();
                    raw.write(("public class Bytes { String a=\""+(charset.equals(StandardCharsets.UTF_8)?"😀":"é")+"\"; String b=\"").getBytes(charset));
                    if(run==1 && charset.equals(StandardCharsets.UTF_8)) raw.write(0xff);
                    else raw.write("café".getBytes(charset));
                    raw.write("\"; }".getBytes(charset));
                    byte[] input=raw.toByteArray();
                    var path=dir.resolve(charset.name()+"-"+run).resolve("Bytes.java");Files.createDirectories(path.getParent());Files.write(path,input);
                    var output=Files.createDirectories(path.getParent().resolve("classes"));
                    var expected=new ArrayList<Message>();
                    var compiler=ToolProvider.getSystemJavaCompiler();
                    try(var files=compiler.getStandardFileManager(d -> expected.add(Message.of(d)),Locale.ROOT,charset)) {
                        files.setLocationFromPaths(javax.tools.StandardLocation.CLASS_PATH,List.of(own));
                        files.setLocationFromPaths(javax.tools.StandardLocation.SOURCE_PATH,List.of());
                        files.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT,List.of(output));
                        var task=compiler.getTask(null,files,d -> expected.add(Message.of(d)),options,null,files.getJavaFileObjects(path));
                        task.setLocale(Locale.ROOT);task.call();
                    }
                    Files.delete(path);
                    var actual=new ArrayList<Message>();
                    var result=pool.withTask(path.toUri(),input,d -> actual.add(Message.of(d)),task -> {
                        java.util.Arrays.fill(input,(byte)'!'); // the task must use its private snapshot
                        return phases(task);
                    });
                    assertThat(actual).isEqualTo(expected);
                    // A separately supplied standard file manager can report a decode error through its own Log
                    // while javac still emits a class. Stage 3's decoder shares the task's Log and must emit none.
                    if(expected.stream().anyMatch(d -> d.kind()==Diagnostic.Kind.ERROR)) assertThat(result.classes()).isEmpty();
                    else sameBytes(result.classes(),bytes(output));
                    if(run==1 && charset.equals(StandardCharsets.UTF_8)) assertThat(actual).anyMatch(d -> d.code().equals("compiler.err.illegal.char.for.encoding"));
                    else assertThat(actual).isEmpty();
                }
                assertThat(pool.statistics().contexts()).isEqualTo(1);
            }
        }
    }

}
