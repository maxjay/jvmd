package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Attribute;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class BodyAttributeTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    record State(ContentTree tree,InMemoryLocalStore store,Identity project,MachineLeaf own,Route route,
                 Attribute.Options options,Pool.Configuration configuration,Map<String,FileRow> rows,Map<String,Path> files) {
        Attribute attribute(Pool pool) { return Attribute.unprocessed(tree,store,own,route,pool,options); }
        Map<String,byte[]> classes(Attribute.Computed result) {
            var out=new TreeMap<String,byte[]>();
            result.result().classFiles().forEach(c -> out.put(c.internalName(),store.get(LocalStore.classFileKey(c.contentHash()))));
            return out;
        }
    }

    private State boot(Digest digest,Map<String,String> sources,List<String> options) throws Exception {
        var bytes=new TreeMap<String,byte[]>();sources.forEach((path,text) -> bytes.put(path,text.getBytes(JavacOptions.charset(options))));
        return bootBytes(digest,bytes,options);
    }

    private State bootBytes(Digest digest,Map<String,byte[]> sources,List<String> options) throws Exception {
        var files=new TreeMap<String,Path>();
        for(var entry:sources.entrySet()) {
            String path="app/src/main/java/"+entry.getKey();var file=dir.resolve(path);
            Files.createDirectories(file.getParent());Files.write(file,entry.getValue());files.put(entry.getKey(),file);
        }
        var model=ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("app","g:app:1",List.of()).withOptions(options.toArray(String[]::new))));
        var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();
        var result=new Stage2(digest,tree,Stage2Support.FEATURE,2,dir,ClassFacts::of).run(store,model);
        var project=Stage2.projectKey(digest,model);
        var own=MachineLeaf.decode(store.get(MachineStore.leafKey(result.leaves().get("app/main"))),digest.width());
        var route=Route.decode(store.get(LocalStore.routeKey(project,"app",0)),digest.width());
        var module=ModuleRecord.decode(store.get(LocalStore.moduleKey(project,"app")));
        var policy=Attribute.Options.unprocessed(digest,module,Stage2Support.JDK);
        var stubs=Files.createDirectories(dir.resolve("stubs"));var names=new ArrayList<String>();
        for(var stub:Stubs.stubs(digest,tree,own,id -> store.get(MachineStore.nodeKey(id)),Stubs.Cache.NONE)) {
            var path=stubs.resolve(stub.internalName()+".class");Files.createDirectories(path.getParent());Files.write(path,stub.bytes());names.add(stub.internalName());
        }
        var configuration=new Pool.Configuration(new Pool.Key(route.routeHash(),own.k()),stubs,List.of(),policy.charset(),policy.javac(),names);
        var rows=new TreeMap<String,FileRow>();
        for(var name:files.keySet()) { String path="app/src/main/java/"+name;rows.put(name,FileRow.decode(path,store.get(LocalStore.fileKey(project, Stage2Support.source(path))),digest.width())); }
        return new State(tree,store,project,own,route,policy,configuration,rows,files);
    }

    private Attribute.Computed run(State state,Attribute attribute,String name) throws Exception {
        var file=state.files().get(name);return attribute.run(state.rows().get(name),file.toUri(),Files.readAllBytes(file));
    }

    record Oracle(Map<String,byte[]> classes,List<ResultRecord.Diagnostic> messages) { }
    private Oracle oracle(State state) throws Exception {
        var output=Files.createDirectories(dir.resolve("oracle"));var messages=new ArrayList<ResultRecord.Diagnostic>();
        var compiler=ToolProvider.getSystemJavaCompiler();
        javax.tools.DiagnosticListener<javax.tools.JavaFileObject> listener=d -> messages.add(new ResultRecord.Diagnostic(switch(d.getKind()) {
            case ERROR -> 0;case WARNING,MANDATORY_WARNING -> 1;default -> 2;
        },d.getStartPosition(),d.getEndPosition(),d.getCode(),d.getMessage(Locale.ROOT)));
        try(var files=compiler.getStandardFileManager(listener,Locale.ROOT,state.options().charset())) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH,List.of());
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH,List.of());
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(output));
            var task=compiler.getTask(null,files,listener,state.options().javac(),null,canonicalNames(files.getJavaFileObjectsFromPaths(state.files().values())));
            task.setLocale(Locale.ROOT);task.call();
        }
        var classes=new TreeMap<String,byte[]>();
        try(var paths=Files.walk(output)) {
            for(var path:paths.filter(p -> p.toString().endsWith(".class")).toList())
                classes.put(output.relativize(path).toString().replace('\\','/').replaceFirst("\\.class$",""),Files.readAllBytes(path));
        }
        return new Oracle(classes,List.copyOf(messages));
    }

    private static void sameBytes(Map<String,byte[]> actual,Map<String,byte[]> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());actual.forEach((name,bytes) -> assertThat(bytes).as(name).isEqualTo(expected.get(name)));
    }

    @ParameterizedTest @MethodSource("digests")
    void perFileResultsMatchWholeModuleBytesAndRepeatWithoutRecordWrites(Digest digest) throws Exception {
        var state=boot(digest,Map.of("p/App.java","""
                package p;
                public class App extends Other {
                    public String value(int x){ class Local { String get(){ return "v="+(C+x); } } return new Local().get(); }
                    public Runnable work(){ return new Runnable(){ public void run(){} }; }
                    public record Pair(String key,int value) {}
                }
                class Auxiliary {}
                ""","p/Other.java","package p; public class Other { public static final int C=7; }"),List.of("-g","-parameters"));
        var expected=oracle(state);assertThat(expected.messages()).isEmpty();
        var beforeLocal=state.store().get(LocalStore.localRootKey(state.project()));
        try(var pool=new Pool(state.configuration(),1)) {
            var attribute=state.attribute(pool);var actual=new TreeMap<String,byte[]>();
            var first=run(state,attribute,"p/App.java");actual.putAll(state.classes(first));
            var other=run(state,attribute,"p/Other.java");actual.putAll(state.classes(other));
            assertThat(first.result().attributed()).isTrue();assertThat(other.result().attributed()).isTrue();sameBytes(actual,expected.classes());
            assertThat(first.proof().valid(state.tree(),state.own(),state.route(),null,state.store()::get)).isTrue();
            assertThat(ResultRecord.decode(state.store().get(LocalStore.resultKey(first.aci())),digest.width())).isEqualTo(first.result());
            assertThat(UsesRecord.decode(state.store().get(LocalStore.usesKey(first.aci())))).isEqualTo(first.uses());
            long writes=state.store().recordWriteCount();
            var again=attribute.run(state.rows().get("p/App.java"),dir.resolve("elsewhere/App.java").toUri(),Files.readAllBytes(state.files().get("p/App.java")));
            assertThat(again).isEqualTo(first);assertThat(state.store().recordWriteCount()).isEqualTo(writes);
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
        assertThat(state.store().get(LocalStore.localRootKey(state.project()))).isEqualTo(beforeLocal);
        assertThat(state.store().get(LocalStore.bodiesRootKey(state.project()))).isNull();
        assertThat(state.store().get(LocalStore.proofKey(state.project(), Stage2Support.source(state.rows().get("p/App.java").path())))).isNull();
    }

    @ParameterizedTest @MethodSource("digests")
    void diagnosticNotesShareAcrossLocationsUnderTheSameACI(Digest digest) throws Exception {
        var state=boot(digest,Map.of("p/App.java","package p; public class App { int x(){return new java.util.Date().getYear();} }"),List.of());
        try(var pool=new Pool(state.configuration(),1)) {
            var attribute=state.attribute(pool);var first=run(state,attribute,"p/App.java");
            assertThat(first.result().diagnostics()).anyMatch(d->d.code().equals("compiler.note.deprecated.filename"));
            assertThat(first.result().diagnostics()).isEqualTo(oracle(state).messages());
            var relocated=attribute.run(state.rows().get("p/App.java"),dir.resolve("another/project/App.java").toUri(),Files.readAllBytes(state.files().get("p/App.java")));
            assertThat(relocated.aci()).isEqualTo(first.aci());
            assertThat(relocated.result()).isEqualTo(first.result());
            assertThat(first.result().diagnostics().getFirst().message()).startsWith("App.java ");
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void bodyErrorsAndWarningsKeepNativeOrderTextAndUtf16Positions(Digest digest) throws Exception {
        String source="package p; public class App { String emoji=\"ðŸ˜€\"; int f(){ Old.call(); return missing; } }";
        var state=boot(digest,Map.of("p/App.java",source,"p/Old.java","package p; public class Old { @Deprecated(forRemoval=true) public static void call(){} }"),List.of("-Xlint:all"));
        var expected=oracle(state);assertThat(expected.messages()).anyMatch(d -> d.kind()==0).anyMatch(d -> d.kind()==1);
        try(var pool=new Pool(state.configuration(),1)) {
            var attribute=state.attribute(pool);var result=run(state,attribute,"p/App.java");
            assertThat(result.result().attributed()).isFalse();assertThat(result.result().classFiles()).isEmpty();
            assertThat(result.result().diagnostics()).isEqualTo(expected.messages());
            assertThat(result.result().diagnostics()).anyMatch(d -> d.kind()==0 && d.start()==source.indexOf("missing"));
            var clean=run(state,attribute,"p/Old.java");assertThat(clean.result().attributed()).isTrue();assertThat(clean.result().diagnostics()).isEmpty();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void stringConstantsFromOwnStubsProduceExactNativeClassBytes(Digest digest) throws Exception {
        String literal = "\\uD800|\\uDC00|\\uD83D\\uDE00|\\0|\\n|cafÃ©";
        var state = boot(digest, Map.of("p/Constants.java", "package p; public class Constants { public static final String VALUE=\""+literal+"\"; }",
                "p/Label.java", "package p; public @interface Label { String value() default \""+literal+"\"; }",
                "p/App.java", "package p; @Label(Constants.VALUE) public class App { public String value(){return Constants.VALUE;} public static final String COPIED=Constants.VALUE; }"),
                List.of("-g", "-parameters"));
        var expected = oracle(state); assertThat(expected.messages()).isEmpty();
        try (var pool = new Pool(state.configuration(), 1)) {
            var attribute = state.attribute(pool); var actual = new TreeMap<String,byte[]>();
            for (var name : state.files().keySet()) {
                var result = run(state, attribute, name);
                assertThat(result.result().attributed()).isTrue();
                actual.putAll(state.classes(result));
            }
            sameBytes(actual, expected.classes());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void rejectsStaleSourceWrongNameAndMismatchedPoolBeforePublishing(Digest digest) throws Exception {
        var state=boot(digest,Map.of("p/App.java","package p; public class App {}"),List.of());
        var file=state.files().get("p/App.java");var row=state.rows().get("p/App.java");var bytes=Files.readAllBytes(file);
        try(var pool=new Pool(state.configuration(),1)) {
            var attribute=state.attribute(pool);long writes=state.store().recordWriteCount();
            assertThatThrownBy(() -> attribute.run(row,file.toUri(),"class Changed {}".getBytes())).hasMessageContaining("Source snapshot differs");
            assertThatThrownBy(() -> attribute.run(row,dir.resolve("Other.java").toUri(),bytes)).hasMessageContaining("basename differs");
            assertThat(state.store().recordWriteCount()).isEqualTo(writes);assertThat(pool.statistics().tasks()).isZero();
        }
        var base=state.configuration();var changed=new ArrayList<>(base.options());changed.add("-g:none");
        try(var pool=new Pool(new Pool.Configuration(base.key(),base.ownStubs(),base.route(),base.charset(),changed,base.ownTypes()),1)) {
            assertThatThrownBy(() -> state.attribute(pool)).hasMessageContaining("hashed attribution options");
        }
        try(var pool=new Pool(new Pool.Configuration(new Pool.Key(digest.hash(new byte[]{1}),base.key().own()),base.ownStubs(),base.route(),base.charset(),base.options(),base.ownTypes()),1)) {
            assertThatThrownBy(() -> state.attribute(pool)).hasMessageContaining("current route and own leaf");
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void parseFailureStillHasAnErrorResultAndProof(Digest digest) throws Exception {
        var state=boot(digest,Map.of("p/App.java","package p; public class App { void f( { }"),List.of());
        var expected=oracle(state);
        try(var pool=new Pool(state.configuration(),1)) {
            var result=run(state,state.attribute(pool),"p/App.java");
            assertThat(result.result().attributed()).isFalse();assertThat(result.result().classFiles()).isEmpty();
            assertThat(result.result().diagnostics()).isEqualTo(expected.messages());
            assertThat(result.proof().valid(state.tree(),state.own(),state.route(),null,state.store()::get)).isTrue();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void charsetAndDebugOptionsAreBoundToActualBytesAndResultIdentity(Digest digest) throws Exception {
        var identities=new java.util.HashSet<Identity>();
        var output=new ArrayList<byte[]>();
        for(var options:List.of(List.of("-g:none","-encoding","UTF-8"),List.of("-g","-parameters","-encoding","UTF-8"),
                List.of("-g","-parameters","-encoding","ISO-8859-1"))) {
            var state=boot(digest,Map.of("p/App.java","package p; public class App { public String value(int x){ return \"cafÃ©\"+x; } }"),options);
            var expected=oracle(state);
            try(var pool=new Pool(state.configuration(),1)) {
                var result=run(state,state.attribute(pool),"p/App.java");
                assertThat(result.result().attributed()).isTrue();assertThat(result.result().diagnostics()).isEqualTo(expected.messages());
                sameBytes(state.classes(result),expected.classes());identities.add(result.aci());output.add(state.classes(result).get("p/App"));
            }
        }
        assertThat(identities).hasSize(3);
        assertThat(output.get(0)).isNotEqualTo(output.get(1));
        assertThat(output.get(1)).isEqualTo(output.get(2)); // equal characters, explicitly different source bytes and encoding inputs
    }

    @ParameterizedTest @MethodSource("digests")
    void concurrentEqualAttributionsShareEveryContentRecord(Digest digest) throws Exception {
        var state=boot(digest,Map.of("p/App.java","package p; public class App { public String f(int x){ return \"x=\"+x; } }"),List.of("-g"));
        var file=state.files().get("p/App.java");var source=Files.readAllBytes(file);
        long writes=state.store().recordWriteCount();
        try(var pool=new Pool(state.configuration(),2);var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);var attribute=state.attribute(pool);
            java.util.concurrent.Callable<Attribute.Computed> job=() -> { start.await();return attribute.run(state.rows().get("p/App.java"),file.toUri(),source); };
            var a=executor.submit(job);var b=executor.submit(job);start.countDown();
            var first=a.get(30,java.util.concurrent.TimeUnit.SECONDS);var second=b.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(second).isEqualTo(first);
            assertThat(state.store().recordWriteCount()-writes).isEqualTo(first.result().classFiles().size()+2);
            assertThat(pool.statistics()).isEqualTo(new Pool.Statistics(2,2,2));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void unprocessedEntryPointCannotSilentlyOmitProcessors(Digest digest) throws Exception {
        var state=boot(digest,Map.of("p/App.java","package p; public class App {}"),List.of());
        var processing=new ProjectModel.Processing(List.of(new ProjectModel.Dependency("g:processor:1","processor.jar",null)),List.of("p.Processor"),List.of(),List.of());
        var module=new ModuleRecord("g:app:1",Stage2Support.FEATURE,false,List.of(),List.of(),List.of(),processing);
        assertThatThrownBy(() -> Attribute.Options.unprocessed(digest,module,Stage2Support.JDK)).hasMessageContaining("Processor-bearing module");
        var row=state.rows().get("p/App.java");var id=digest.hash(new byte[]{1});
        var contextual=new FileRow(row.path(),row.kappa(),row.size(),row.mtimeNanos(),row.sum(),row.typeKeys(),row.faults(),row.headerProof(),row.ownR(),row.absences(),
                false,null,null,new ProcessorRecords.Context(id,id,List.of()));
        try(var pool=new Pool(state.configuration(),1)) {
            var attribute=state.attribute(pool);var file=state.files().get("p/App.java");var bytes=Files.readAllBytes(file);long writes=state.store().recordWriteCount();
            assertThatThrownBy(() -> attribute.run(contextual,file.toUri(),bytes)).hasMessageContaining("Processor-bearing file");
            assertThat(pool.statistics().tasks()).isZero();assertThat(state.store().recordWriteCount()).isEqualTo(writes);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void malformedSnapshotPersistsItsNativeEncodingErrorAndNoClassFiles(Digest digest) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        bytes.writeBytes("package p; public class App { String emoji=\"ðŸ˜€\"; String x=\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(0xff);bytes.writeBytes("\"; }".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var state=bootBytes(digest,Map.of("p/App.java",bytes.toByteArray()),List.of("-encoding","UTF-8"));
        var expected=oracle(state);
        try(var pool=new Pool(state.configuration(),1)) {
            var result=run(state,state.attribute(pool),"p/App.java");
            assertThat(result.result().attributed()).isFalse();assertThat(result.result().classFiles()).isEmpty();
            assertThat(result.result().diagnostics()).isEqualTo(expected.messages()).anyMatch(d -> d.code().equals("compiler.err.illegal.char.for.encoding"));
            assertThat(ResultRecord.decode(state.store().get(LocalStore.resultKey(result.aci())),digest.width())).isEqualTo(result.result());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void defaultLocaleCannotMovePersistedDiagnosticsOrAci(Digest digest) throws Exception {
        var state=boot(digest,Map.of("p/App.java","package p; public class App { int f(){ return missing; } }"),List.of());
        var expected=oracle(state);var previous=Locale.getDefault();
        try(var pool=new Pool(state.configuration(),1)) {
            var attribute=state.attribute(pool);Locale.setDefault(Locale.FRANCE);
            var first=run(state,attribute,"p/App.java");assertThat(first.result().diagnostics()).isEqualTo(expected.messages());
            Locale.setDefault(Locale.JAPAN);long writes=state.store().recordWriteCount();
            assertThat(run(state,attribute,"p/App.java")).isEqualTo(first);
            assertThat(state.store().recordWriteCount()).isEqualTo(writes);
        } finally { Locale.setDefault(previous); }
    }
    /** Native javac oracle with the same explicit diagnostic filename policy; no production formatter is used. */
    private static List<javax.tools.JavaFileObject> canonicalNames(Iterable<? extends javax.tools.JavaFileObject> inputs) {
        var result=new ArrayList<javax.tools.JavaFileObject>();
        for(var input:inputs)result.add(new javax.tools.ForwardingJavaFileObject<javax.tools.JavaFileObject>(input) {
            @Override public String getName() { return Path.of(toUri()).getFileName().toString(); }
        });
        return result;
    }

}
