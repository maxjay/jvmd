package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class Stage3Test {
    @TempDir Path dir;
    static Stream<Digest> digests() {return Stream.of(Sha256.INSTANCE,new Digests.Sha3());}
    @AfterAll static void release() {Stage2Support.release();}
    private ProjectModel model() {
        return ProjectModel.parse(Stage2Support.model(dir,
                new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.module("g:dep:1","dep"))).withOptions("-g","-parameters"),
                new Stage2Support.Mod("dep","g:dep:1",List.of()).withOptions("-g","-parameters")));
    }
    private Stage3 driver(Digest digest,ContentTree tree,int workers) {return new Stage3(digest,tree,Stage2Support.FEATURE,workers,dir);}
    private LocalRoot boot(Digest digest,ContentTree tree,InMemoryLocalStore store,ProjectModel model) throws Exception {
        new Stage2(digest,tree,Stage2Support.FEATURE,2,dir,ClassFacts::of).run(store,model);
        return LocalRoot.decode(digest,store.get(LocalStore.localRootKey(Stage2.projectKey(digest,model))));
    }
    private Map<String,byte[]> classes(InMemoryLocalStore store,Stage3.Scope scope) {
        var out=new TreeMap<String,byte[]>();
        for(var file:scope.files())for(var c:file.computed().result().classFiles())out.put(c.internalName()+".class",store.get(LocalStore.classFileKey(c.contentHash())));
        return out;
    }
    private void sameBytes(Map<String,byte[]> actual,Map<String,byte[]> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());actual.forEach((name,bytes)->assertThat(bytes).as(name).isEqualTo(expected.get(name)));
    }

    @ParameterizedTest @MethodSource("digests")
    void nativeClassesRootedProofsAndColdStorageBoundaryHoldAcrossWorkers(Digest digest) throws Exception {
        var dependency=Map.of("q/Base.java","package q; public class Base {public static final int VALUE=7; public int get(){return VALUE;}}");
        var source=Map.of("p/App.java","package p; public class App extends q.Base {public int result(){return get()+q.Base.VALUE;} public static class Nested {}}",
                "p/Other.java","package p; class Other { int read(){return new App().result();}}",
                "p/package-info.java","@Deprecated package p;");
        var inputs=new TreeMap<String,String>();dependency.forEach((name,text)->inputs.put("dep/src/main/java/"+name,text));
        var tests=Map.of("p/Check.java","package p; public class Check {int read(){return new App().result();}}");
        tests.forEach((name,text)->inputs.put("app/src/test/java/"+name,text));
        source.forEach((name,text)->inputs.put("app/src/main/java/"+name,text));Stage2Support.write(dir,inputs);
        var model=model();var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();var local=boot(digest,tree,store,model);
        var expectedDep=Stage2Support.compile(dir.resolve("native-dep"),dependency,List.of("-g","-parameters"),List.of());
        var jar=Stage2Support.pack(dir.resolve("native-dep.jar"),expectedDep);
        var expectedApp=Stage2Support.compile(dir.resolve("native-app"),source,List.of("-g","-parameters"),List.of(jar));
        var appJar=Stage2Support.pack(dir.resolve("native-app.jar"),expectedApp);
        var expectedTest=Stage2Support.compile(dir.resolve("native-test"),tests,List.of("-g","-parameters"),List.of(appJar,jar));
        var copies=List.of(store.copy(),store.copy());Stage3.Result first=null;
        for(int i=0;i<copies.size();i++) {
            var current=copies.get(i);int start=current.events().size();
            var ordered=i==0?model:new ProjectModel(model.root(),model.jdkHome(),model.modules().reversed(),model.bytes());
            var result=driver(digest,tree,i==0?1:4).run(current,ordered);
            assertThat(result.managers().live()).isZero();
            assertThat(result.managers().peak()).isBetween(1,i==0?1:4);
            assertThat(result.faults()).isEmpty();assertThat(result.files()).isEqualTo(5);assertThat(result.bodies().current(local)).isTrue();
            sameBytes(classes(current,result.scopes().get("dep/main")),expectedDep);sameBytes(classes(current,result.scopes().get("app/main")),expectedApp);
            sameBytes(classes(current,result.scopes().get("app/test")),expectedTest);
            var reads=current.events().subList(start,current.events().size());int end=reads.indexOf("putBodiesRoot");
            assertThat(reads.subList(0,end)).doesNotContain("read:C","read:RS","read:CF","read:U","read:OUT","read:X");
            assertThat(reads.get(end-1)).isEqualTo("sync");
            for(var scope:result.scopes().values())for(var file:scope.files()) {
                var proof=current.get(LocalStore.proofKey(result.project(), Stage2Support.source(file.path())));assertThat(proof).isEqualTo(file.computed().proof().encode());
                var indexed=BodyRecords.read(tree,current,result.bodies().bodiesRoot(),LocalStore.proofIndexKey(result.project(),Stage2Support.source(file.path())));
                assertThat(indexed).isEqualTo(file.computed().indexed().encode());
                var decoded=ProofIndex.decode(indexed,digest.width());
                assertThat(decoded.aci()).isEqualTo(file.computed().aci());
                var work=new ProofIndex.Work();
                var unchanged=ProofIndex.Transition.between(tree,decoded.binding(),decoded.binding(),key->{throw new AssertionError("Unchanged resolution read storage");},work);
                assertThat(decoded.advance(unchanged,decoded.inputs(),null,null)).isNotNull();
                assertThat(work.proofNodeReads).isZero();
                assertThat(tree.get(result.bodies().bodiesRoot(),h->current.get(MachineStore.nodeKey(h)),LocalStore.resultKey(file.computed().aci()))).isNotNull();
                assertThat(tree.get(result.bodies().bodiesRoot(),h->current.get(MachineStore.nodeKey(h)),LocalStore.usesKey(file.computed().aci()))).isNull();
            }
            if(first!=null)assertThat(result.bodies()).isEqualTo(first.bodies());first=result;
        }
        var consumers=ReverseIndex.bodyConsumers(digest,copies.getFirst(),new ReverseIndex.Dependency(ReverseIndex.T,"q/Base",Keys.FIELD,"VALUE"));
        assertThat(consumers).contains(new ReverseIndex.Consumer(first.project(), Stage2Support.source("app/src/main/java/p/App.java")));
    }

    @ParameterizedTest @MethodSource("digests")
    void persistedIndexedProofSurvivesAnUnrelatedEditAndRejectsAConsumedConstantEdit(Digest digest) throws Exception {
        var sources=new TreeMap<String,String>();
        sources.put("p/Api.java","package p; public class Api {public static final int VALUE=1; public static final int OTHER=1;}");
        sources.put("p/Use.java","package p; public class Use {public int get(){return Api.VALUE;}}");
        var model=ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("app","g:app:1",List.of())));
        Stage2Support.write(dir.resolve("app/src/main/java"),sources);
        var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();boot(digest,tree,store,model);
        var cold=driver(digest,tree,1).run(store,model);assertThat(cold.faults()).isEmpty();
        var unit=new SourceUnit("app",0,"app/src/main/java/p/Use.java");
        var bytes=BodyRecords.read(tree,store,cold.bodies().bodiesRoot(),LocalStore.proofIndexKey(cold.project(),unit));
        var index=ProofIndex.decode(bytes,digest.width());var originalQueries=index.queries();var originalAci=index.aci();
        var expected=Stage2Support.compile(dir.resolve("native-initial"),sources,List.of(),List.of()).get("p/Use.class");
        for(boolean consumed:List.of(false,true)) {
            sources.put("p/Api.java",sources.get("p/Api.java").replace(consumed?"VALUE=1":"OTHER=1",consumed?"VALUE=2":"OTHER=2"));
            Stage2Support.write(dir.resolve("app/src/main/java"),sources);boot(digest,tree,store,model);
            var own=SourceLeaf.decode(store.get(LocalStore.sourceLeafKey(cold.project(),"app",0)),digest.width());
            var leaf=MachineLeaf.decode(store.get(MachineStore.leafKey(own.k())),digest.width());
            var route=Route.decode(store.get(LocalStore.routeKey(cold.project(),"app",0)),digest.width());
            java.util.function.Function<byte[],byte[]> records=key->{
                assertThat(BodyRecords.tag(key,"C") || BodyRecords.tag(key,"RS") || BodyRecords.tag(key,"CF")).isFalse();
                return store.get(key);
            };
            var binding=ProofIndex.Binding.capture(tree,leaf,route,records);
            var transition=ProofIndex.Transition.between(tree,index.binding(),binding,records,new ProofIndex.Work());
            var next=index.advance(transition,index.inputs(),null,null);
            var nativeBytes=Stage2Support.compile(dir.resolve("native-"+consumed),sources,List.of(),List.of()).get("p/Use.class");
            if(consumed) {
                assertThat(next).isNull();assertThat(nativeBytes).isNotEqualTo(expected);
            } else {
                assertThat(nativeBytes).isEqualTo(expected);assertThat(next).isNotNull();
                assertThat(next.queries()).isEqualTo(originalQueries);assertThat(next.aci()).isEqualTo(originalAci);
                index=ProofIndex.decode(next.encode(),digest.width());
            }
        }
        assertThat(BodyRecords.read(tree,store,cold.bodies().bodiesRoot(),LocalStore.proofIndexKey(cold.project(),unit))).isEqualTo(bytes);
    }

    @ParameterizedTest @MethodSource("digests")
    void concurrentScopesShareOneManagerBudget(Digest digest) throws Exception {
        for(int count:List.of(2,8)) {
            var modules=new Stage2Support.Mod[count];var sources=new TreeMap<String,String>();
            for(int i=0;i<count;i++) {
                modules[i]=new Stage2Support.Mod("m"+i,"g:m"+i+":1",List.of());
                for(int f=0;f<4;f++)sources.put("m"+i+"/src/main/java/p"+i+"/C"+f+".java",
                        "package p"+i+"; public class C"+f+" { public String value(){ return String.valueOf("+f+"); } }");
            }
            Stage2Support.write(dir,sources);var model=ProjectModel.parse(Stage2Support.model(dir,modules));
            var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();boot(digest,tree,store,model);
            for(int workers:List.of(1,4)) {
                var result=driver(digest,tree,workers).run(store.copy(),model);
                assertThat(result.managers().live()).isZero();assertThat(result.managers().peak()).isBetween(1,workers);
                assertThat(result.faults()).isEmpty();assertThat(result.files()).isEqualTo(count*4);
                System.out.println("F14 scopes="+count+" workers="+workers+" managers="+result.managers());
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void rerunRemovesDeletedClassesAndOldConsumersAndKeepsFileErrorsLocal(Digest digest) throws Exception {
        Stage2Support.write(dir,Map.of("dep/src/main/java/q/Base.java","package q; public class Base {public static final int VALUE=7;}",
                "app/src/main/java/p/App.java","package p; public class App {int result(){return q.Base.VALUE;} class Nested {}}"));
        var model=model();var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();boot(digest,tree,store,model);
        var driver=driver(digest,tree,2);var before=driver.run(store,model);var output=dir.resolve("out");
        assertThat(driver.materialise(store,model,"app",0,output).written()).isEqualTo(2);
        Files.delete(dir.resolve("app/src/main/java/p/App.java"));
        Stage2Support.write(dir,Map.of("app/src/main/java/p/Good.java","package p; public class Good {}",
                "app/src/main/java/p/Broken.java","package p; public class Broken {int bad(){return missing;}}"));
        var local=boot(digest,tree,store,model);assertThat(before.bodies().current(local)).isFalse();
        var after=driver.run(store,model);assertThat(after.bodies().current(local)).isTrue();
        var scope=after.scopes().get("app/main");assertThat(scope.files()).hasSize(2);
        assertThat(scope.files().stream().filter(f->f.path().endsWith("Broken.java")).findFirst().orElseThrow().computed().result().attributed()).isFalse();
        assertThat(classes(store,scope)).containsOnlyKeys("p/Good.class");
        assertThat(store.get(LocalStore.bodiesRootHistoryKey(after.project(),1))).isEqualTo(before.bodies().encode());
        assertThat(ReverseIndex.bodyConsumers(digest,store,new ReverseIndex.Dependency(ReverseIndex.T,"q/Base",Keys.FIELD,"VALUE"))).isEmpty();
        var changes=driver.materialise(store,model,"app",0,output);assertThat(changes.written()).isEqualTo(1);assertThat(changes.deleted()).isEqualTo(2);
        assertThat(output.resolve("p/App.class")).doesNotExist();assertThat(output.resolve("p/App$Nested.class")).doesNotExist();
        assertThat(output.resolve("p/Good.class")).exists();
    }

    @ParameterizedTest @MethodSource("digests")
    void aSharedSourceHasIndependentHeaderAndBodyRecordsInEachCompilationScope(Digest digest) throws Exception {
        String source="package p; public class Shared {public static final int HEADER=q.Dep.VALUE; public int value(){return q.Dep.VALUE;}}";
        String path="shared/p/Shared.java";Stage2Support.write(dir,Map.of(path,source));
        var first=Stage2Support.jar(dir,"one.jar",Map.of("q/Dep.java","package q; public class Dep {public static final int VALUE=1;}"),List.of());
        var second=Stage2Support.jar(dir,"two.jar",Map.of("q/Dep.java","package q; public class Dep {public static final int VALUE=2;}"),List.of());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var json=mapper.readTree(Stage2Support.model(dir,
                new Stage2Support.Mod("left","g:left:1",List.of(Stage2Support.Dep.jar("g:one:1",first.toString()))).withOptions("-g","-parameters"),
                new Stage2Support.Mod("right","g:right:1",List.of(Stage2Support.Dep.jar("g:two:1",second.toString()))).withOptions("-g","-parameters")));
        for(var module:json.withArray("modules"))for(var scope:List.of("main","test"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)module.path("scopes").path(scope)).putArray("sourceRoots").add("shared");
        var model=ProjectModel.parse(mapper.writeValueAsBytes(json));var tree=new ContentTree(digest);
        var expectedLeft=Stage2Support.compile(dir.resolve("native-left"),Map.of("p/Shared.java",source),List.of("-g","-parameters"),List.of(first));
        var expectedRight=Stage2Support.compile(dir.resolve("native-right"),Map.of("p/Shared.java",source),List.of("-g","-parameters"),List.of(second));
        Stage3.Result previous=null;
        for(int workers:List.of(1,4)) {
            var store=Stage2Support.jdkOnly(digest).copy();
            new Stage2(digest,tree,Stage2Support.FEATURE,workers,dir,ClassFacts::of).run(store,model);
            assertThat(store.withPrefix("F")).as("one header row per compilation, despite identical physical source").hasSize(4);
            var actual=driver(digest,tree,workers).run(store,model);
            assertThat(actual.files()).isEqualTo(4);
            assertThat(actual.faults()).isEmpty();
            actual.scopes().values().forEach(s -> s.files().forEach(f -> {
                assertThat(f.computed().reusable()).isTrue();
                assertThat(f.computed().proof().readerReads()).anyMatch(read -> read.query().form()==ReverseIndex.M);
            }));
            sameBytes(classes(store,actual.scopes().get("left/main")),expectedLeft);
            sameBytes(classes(store,actual.scopes().get("right/main")),expectedRight);
            sameBytes(classes(store,actual.scopes().get("left/test")),expectedLeft);
            sameBytes(classes(store,actual.scopes().get("right/test")),expectedRight);
            assertThat(store.withPrefix("C")).hasSize(4);
            assertThat(store.withPrefix("RS")).as("reader proofs admit the two distinct route answers").hasSize(2);
            assertThat(store.withPrefix("CF")).hasSize(2);
            var left=FileRow.decode(path,store.get(LocalStore.fileKey(actual.project(),"left",0,path)),digest.width());
            var right=FileRow.decode(path,store.get(LocalStore.fileKey(actual.project(),"right",0,path)),digest.width());
            assertThat(left.kappa()).isEqualTo(right.kappa());assertThat(left.sum()).isNotEqualTo(right.sum());
            var consumed=new ReverseIndex.Dependency(ReverseIndex.T,"q/Dep",Keys.FIELD,"VALUE");
            var consumers=List.of(new ReverseIndex.Consumer(actual.project(),"left",0,path),new ReverseIndex.Consumer(actual.project(),"left",1,path),
                    new ReverseIndex.Consumer(actual.project(),"right",0,path),new ReverseIndex.Consumer(actual.project(),"right",1,path));
            assertThat(ReverseIndex.consumers(digest,store,consumed)).containsExactlyInAnyOrderElementsOf(consumers);
            assertThat(ReverseIndex.bodyConsumers(digest,store,consumed)).containsExactlyInAnyOrderElementsOf(consumers);
            if(previous!=null)assertThat(actual.bodies()).isEqualTo(previous.bodies());previous=actual;
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void aChangedSourceSnapshotCannotReplaceThePreviousBodyGeneration(Digest digest) throws Exception {
        String path="app/src/main/java/p/App.java";
        Stage2Support.write(dir,Map.of(path,"package p; public class App {int value(){return 1;}}"));
        var model=model();var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();boot(digest,tree,store,model);
        var driver=driver(digest,tree,2);var before=driver.run(store,model);long writes=store.recordWriteCount();
        Files.writeString(dir.resolve(path),"package p; public class App {int value(){return 2;}}");
        assertThatThrownBy(()->driver.run(store,model)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Source snapshot differs from F");
        assertThat(store.get(LocalStore.bodiesRootKey(before.project()))).isEqualTo(before.bodies().encode());
        assertThat(store.recordWriteCount()).isEqualTo(writes);assertThat(store.get(LocalStore.bodiesRootHistoryKey(before.project(),1))).isNull();
    }
}
