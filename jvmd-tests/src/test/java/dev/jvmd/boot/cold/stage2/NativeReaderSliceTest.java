package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.*;
import dev.jvmd.core.hash.*;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Native metadata -> reader answer -> current X -> CI -> published native bytes and ordered diagnostics. */
@Tag("phase-3")
class NativeReaderSliceTest {
    @TempDir Path directory;
    static Stream<Digest> digests(){return Stream.of(Sha256.INSTANCE,new Digests.Sha3());}
    @AfterAll static void release(){Stage2Support.release();}
    private static final SourceUnit APP=new SourceUnit("app",0,"app/src/main/java/p/App.java");
    private record Snapshot(MachineLeaf own,Route route,LocalRoot local) { }
    private Snapshot boot(Digest digest,ContentTree tree,InMemoryLocalStore store,ProjectModel model) throws Exception {
        var result=new Stage2(digest,tree,Stage2Support.FEATURE,2,directory,ClassFacts::of).run(store,model);
        assertThat(result.faults()).isEmpty();var project=Stage2.projectKey(digest,model);
        return new Snapshot(MachineLeaf.decode(store.get(MachineStore.leafKey(result.leaves().get("app/main"))),digest.width()),
            Route.decode(store.get(LocalStore.routeKey(project,"app",0)),digest.width()),
            LocalRoot.decode(digest,store.get(LocalStore.localRootKey(project))));
    }
    private Attribute.Computed body(Digest digest,ContentTree tree,InMemoryLocalStore store,ProjectModel model,Path jar) throws Exception {
        var result=new Stage3(digest,tree,Stage2Support.FEATURE,1,directory).run(store,model);
        assertThat(result.faults()).isEmpty();
        var computed=result.scopes().get("app/main").files().getFirst().computed();
        assertThat(computed.reusable()).as(computed.faults().toString()).isTrue();
        assertThat(computed.proof().readerReads()).isNotEmpty();
        assertThat(Proof.decode(computed.proof().encode(),digest.width())).isEqualTo(computed.proof());
        var output=Files.createTempDirectory(directory,"oracle-");var messages=new ArrayList<ResultRecord.Diagnostic>();
        DiagnosticListener<JavaFileObject> listener=d->messages.add(new ResultRecord.Diagnostic(switch(d.getKind()){
            case ERROR->0;case WARNING,MANDATORY_WARNING->1;default->2;
        },d.getStartPosition(),d.getEndPosition(),d.getCode(),d.getMessage(Locale.ROOT)));
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=compiler.getStandardFileManager(listener,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
            files.setLocationFromPaths(StandardLocation.CLASS_PATH,List.of(jar));
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH,List.of());
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(output));
            var project=Stage2.projectKey(digest,model);
            var options=Attribute.Options.unprocessed(digest,ModuleRecord.decode(store.get(LocalStore.moduleKey(project,"app"))),Stage2Support.JDK);
            var task=compiler.getTask(null,files,listener,options.javac(),null,files.getJavaFileObjects(directory.resolve(APP.path())));
            task.setLocale(Locale.ROOT);assertThat(task.call()).isTrue();
        }
        assertThat(computed.result().diagnostics()).containsExactlyElementsOf(messages);
        assertThat(computed.result().classFiles()).hasSize(1);
        byte[] expected=Files.readAllBytes(output.resolve("p/App.class"));
        var hash=computed.result().classFiles().getFirst().contentHash();
        assertThat(store.get(LocalStore.classFileKey(hash))).isEqualTo(expected);
        assertThat(tree.get(result.bodies().bodiesRoot(),h->store.get(MachineStore.nodeKey(h)),LocalStore.classFileKey(hash))).isNotNull();
        var project=Stage2.projectKey(digest,model);
        var route=Route.decode(store.get(LocalStore.routeKey(project,"app",0)),digest.width());
        var own=MachineLeaf.decode(store.get(MachineStore.leafKey(SourceLeaf.decode(store.get(LocalStore.sourceLeafKey(project,"app",0)),digest.width()).k())),digest.width());
        var options=Attribute.Options.unprocessed(digest,ModuleRecord.decode(store.get(LocalStore.moduleKey(project,"app"))),Stage2Support.JDK);
        var config=new Pool.Configuration(new Pool.Key(route.routeHash(),own.k()),Files.createTempDirectory(directory,"pool-own-"),List.of(jar),
            options.charset(),options.javac(),List.of("p/App"),List.of(),new Pool.ReaderInputs(tree,route.readerBinding(),store::get));
        var row=FileRow.decode(APP.path(),store.get(LocalStore.fileKey(project,APP)),digest.width());
        try(var pool=new Pool(config,1)) {
            Pool.ReaderStatistics first=null;
            for(int task=0;task<2;task++) {
                var pooled=Attribute.unprocessed(tree,store,own,route,pool,options).run(row,directory.resolve(APP.path()).toUri(),Files.readAllBytes(directory.resolve(APP.path())));
                assertThat(pooled.reusable()).as(pooled.faults().toString()).isTrue();
                assertThat(pooled.aci()).isEqualTo(computed.aci());
                assertThat(pooled.proof().readerReads()).isEqualTo(computed.proof().readerReads());
                assertThat(pooled.result().encode()).isEqualTo(computed.result().encode());
                if(task==0)first=pool.readerStatistics();
                else {
                    var second=pool.readerStatistics();
                    assertThat(second.nodeReads()).as("reader answers belong to the immutable pool binding").isEqualTo(first.nodeReads());
                    if(messages.isEmpty())assertThat(second.physicalReads()).isEqualTo(first.physicalReads());
                    System.out.printf(Locale.ROOT,"READER-POOL %s warnings=%d first=%s second=%s%n",digest.name(),messages.size(),first,second);
                }
            }
            assertThat(pool.statistics().contexts()).isEqualTo(messages.isEmpty()?1:2);
        }
        return computed;
    }
    @ParameterizedTest @MethodSource("digests")
    void metadataOnlyChangeSelectsAndRecompilesWhileUnrelatedChangesPreserveReuse(Digest digest) throws Exception {

        var base=new TreeMap<>(Stage2Support.compile(directory.resolve("lib-build"),Map.of(
            "q/Mode.java","package q; public enum Mode { X,Y }",
            "q/Ann.java","package q; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Ann { Mode value(); }",
            "q/Lib.java","package q; public class Lib { @Ann(Mode.X) private int hidden; public static int call(){return 1;} }",
            "q/Other.java","package q; public class Other {}"),List.of(),List.of()));
        byte[] broken=base.get("q/Lib.class");
        var mode=Stage2Support.compile(directory.resolve("mode-build"),Map.of("q/Mode.java","package q; public enum Mode { Y }"),List.of(),List.of());
        base.put("q/Mode.class",mode.get("q/Mode.class"));
        var jar=Stage2Support.pack(directory.resolve("lib.jar"),base);
        var good=Stage2Support.compile(directory.resolve("good-build"),Map.of("q/Lib.java",
            "package q; public class Lib { @Ann(Mode.Y) private int hidden; public static int call(){return 1;} }"),List.of(),List.of(jar)).get("q/Lib.class");
        base.put("q/Lib.class",good);Stage2Support.pack(jar,base);
        Stage2Support.write(directory,Map.of(APP.path(),"package p; public class App { int value(){return q.Lib.call();} }"));
        var model=ProjectModel.parse(Stage2Support.model(directory,new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.jar("g:lib:1",jar.toString())))));
        var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();var project=Stage2.projectKey(digest,model);
        var a=boot(digest,tree,store,model);var before=body(digest,tree,store,model,jar);
        assertThat(before.result().diagnostics()).isEmpty();

        // Unread metadata is physically different but its own named reader keys have no current consumers.
        var other=Stage2Support.compile(directory.resolve("other-build"),Map.of("q/Other.java",
            "package q; @Ann(Mode.Y) public class Other {}"),List.of(),List.of(jar)).get("q/Other.class");
        base.put("q/Other.class",other);Stage2Support.pack(jar,base);
        var b=boot(digest,tree,store,model);
        assertThat(b.route().routeHash()).isEqualTo(a.route().routeHash());
        var work=new BodyValidation.Work();var unrelated=new BodyValidation(tree,store,project,"app",0,List.of(),work);
        assertThat(unrelated.candidates()).isEmpty();assertThat(work.receipts).isZero();
        assertThat(before.proof().valid(tree,b.own(),b.route(),null,store::get)).isTrue();
        var reused=before.indexed().advance(ProofIndex.Transition.between(tree,before.indexed().binding(),
            ProofIndex.Binding.capture(tree,b.own(),b.route(),store::get),store::get,new ProofIndex.Work()),before.indexed().inputs(),null,null);
        assertThat(reused).isNotNull();assertThat(reused.aci()).isEqualTo(before.aci());

        var substitute=Stage2Support.pack(directory.resolve("equivalent-provider.jar"),base);
        var alternate=ProjectModel.parse(Stage2Support.model(directory,new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.jar("g:substitute:1",substitute.toString())))));
        var equivalent=boot(digest,tree,store,alternate);
        assertThat(equivalent.route().readerBinding()).isEqualTo(b.route().readerBinding());
        assertThat(equivalent.route().routeHash()).isEqualTo(b.route().routeHash());
        assertThat(new BodyValidation(tree,store,project,"app",0,List.of(),new BodyValidation.Work()).candidates()).isEmpty();
        var equalResult=body(digest,tree,store,alternate,substitute);
        assertThat(equalResult.aci()).isEqualTo(before.aci());
        assertThat(equalResult.result().encode()).isEqualTo(before.result().encode());

        base.put("q/Lib.class",broken);Stage2Support.pack(jar,base);
        var c=boot(digest,tree,store,model);
        assertThat(c.route().routeHash()).isEqualTo(a.route().routeHash());assertThat(c.own().k()).isEqualTo(a.own().k());
        var changeWork=new BodyValidation.Work();var changed=new BodyValidation(tree,store,project,"app",0,List.of(),changeWork);
        assertThat(changed.candidates()).containsExactly(APP);
        assertThat(changed.check(APP,before.indexed().inputs(),null,null).reusable()).isFalse();
        assertThat(changeWork.receipts).isOne();assertThat(changeWork.reverse.hits).isOne();
        assertThat(before.proof().valid(tree,c.own(),c.route(),null,store::get)).isFalse();
        var after=body(digest,tree,store,model,jar);
        assertThat(after.aci()).isNotEqualTo(before.aci());
        assertThat(after.result().diagnostics()).extracting(ResultRecord.Diagnostic::code).containsExactly("compiler.warn.unknown.enum.constant");
        // The native enum lookup accepts a private non-enum VAR omitted from T and A.
        byte[] oldMode=base.get("q/Mode.class");var cf=java.lang.classfile.ClassFile.of();
        byte[] privateProvider=cf.transformClass(cf.parse(oldMode),new java.lang.classfile.ClassTransform() {
            @Override public void accept(java.lang.classfile.ClassBuilder out,java.lang.classfile.ClassElement e){out.with(e);}
            @Override public void atEnd(java.lang.classfile.ClassBuilder out){out.withField("X",java.lang.constant.ClassDesc.of("q.Mode"),
                java.lang.classfile.ClassFile.ACC_PRIVATE|java.lang.classfile.ClassFile.ACC_STATIC);}
        });
        assertThat(cf.verify(privateProvider)).isEmpty();
        var oldFacts=ClassFacts.of(digest,oldMode,"q/Mode");var newFacts=ClassFacts.of(digest,privateProvider,"q/Mode");
        assertThat(newFacts.facts().stream().map(Fact::h).toList()).isEqualTo(oldFacts.facts().stream().map(Fact::h).toList());
        base.put("q/Mode.class",privateProvider);Stage2Support.pack(jar,base);
        var d=boot(digest,tree,store,model);
        assertThat(d.route().routeHash()).isEqualTo(c.route().routeHash());
        var namedWork=new BodyValidation.Work();var named=new BodyValidation(tree,store,project,"app",0,List.of(),namedWork);
        assertThat(named.candidates()).containsExactly(APP);
        assertThat(named.check(APP,after.indexed().inputs(),null,null).reusable()).isFalse();
        assertThat(namedWork.reverse.hits).isOne();
        var repaired=body(digest,tree,store,model,jar);
        assertThat(repaired.result().diagnostics()).isEmpty();assertThat(repaired.aci()).isNotEqualTo(after.aci());
        System.out.printf(Locale.ROOT,"READER-SLICE %s metadataChange prefixes=%d hits=%d receipts=%d proofNodes=%d frontierNodes=%d%n",
            digest.name(),changeWork.reverse.prefixes,changeWork.reverse.hits,changeWork.receipts,changeWork.proofs.proofNodeReads,changeWork.proofs.nodeReads);
    }
}
