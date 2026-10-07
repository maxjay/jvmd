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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Actual cold capture -> current X -> selected CI -> changed units/OUT -> independent javac. */
@Tag("phase-3")
class BodyValidationNativeTest {
    @TempDir Path directory;
    static Stream<Digest> digests() {return Stream.of(Sha256.INSTANCE,new Digests.Sha3());}
    @AfterAll static void release() {Stage2Support.release();}
    static SourceUnit unit(String name) {return new SourceUnit("app",0,"app/src/main/java/p/"+name+".java");}

    @ParameterizedTest @MethodSource("digests")
    void unrelatedConstantConsumedConstantAndDirectBodyEditsMatchNativeWithoutScanningReceipts(Digest digest) throws Exception {
        var sources=new TreeMap<String,String>();
        sources.put("p/Api.java","package p; public class Api { public static final int VALUE=1, OTHER=1; }");
        sources.put("p/Use.java","package p; public class Use { public int value(){return Api.VALUE;} }");
        sources.put("p/Idle.java","package p; public class Idle { public int value(){return 7;} }");
        Stage2Support.write(directory.resolve("app/src/main/java"),sources);
        var model=ProjectModel.parse(Stage2Support.model(directory,new Stage2Support.Mod("app","g:app:1",List.of())));
        var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();
        var headers=new Stage2(digest,tree,Stage2Support.FEATURE,2,directory,ClassFacts::of);
        assertThat(headers.run(store,model).faults()).isEmpty();
        var cold=new Stage3(digest,tree,Stage2Support.FEATURE,2,directory).run(store,model);assertThat(cold.faults()).isEmpty();
        var project=cold.project();var idleKey=LocalStore.proofIndexKey(project,unit("Idle"));
        var idle=BodyRecords.read(tree,store,cold.bodies().bodiesRoot(),idleKey);
        var useKey=LocalStore.proofIndexKey(project,unit("Use"));
        var original=BodyRecords.read(tree,store,cold.bodies().bodiesRoot(),useKey);
        for(int edit=0;edit<3;edit++) {
            String direct=edit==2?"Use":"Api";
            if(edit==0)sources.put("p/Api.java",sources.get("p/Api.java").replace("OTHER=1","OTHER=2"));
            else if(edit==1)sources.put("p/Api.java",sources.get("p/Api.java").replace("VALUE=1","VALUE=2"));
            else sources.put("p/Use.java",sources.get("p/Use.java").replace("Api.VALUE","Api.VALUE+1"));
            Stage2Support.write(directory.resolve("app/src/main/java"),sources);
            assertThat(headers.run(store,model).faults()).isEmpty();
            var work=new BodyValidation.Work();var plan=new BodyValidation(tree,store,project,"app",0,List.of(unit(direct)),work);
            assertThat(plan.candidates()).containsExactlyInAnyOrderElementsOf(edit==1?List.of(unit("Api"),unit("Use")):List.of(unit(direct)));
            var current=LocalRoot.decode(digest,store.get(LocalStore.localRootKey(project)));
            var generation=BodyGeneration.begin(tree,store,project,current);
            var own=SourceLeaf.decode(generation.local(LocalStore.sourceLeafKey(project,"app",0)),digest.width());
            var leaf=MachineLeaf.decode(generation.get(MachineStore.leafKey(own.k())),digest.width());
            var route=Route.decode(generation.local(LocalStore.routeKey(project,"app",0)),digest.width());
            var module=ModuleRecord.decode(generation.local(LocalStore.moduleKey(project,"app")));
            var options=Attribute.Options.unprocessed(digest,module,Stage2Support.JDK);
            var selected=new TreeMap<SourceUnit,List<Entry>>();var before=new ArrayList<ResultRecord>();var after=new ArrayList<ResultRecord>();
            try(var stubs=new StubDirectories(tree,generation,k->MachineLeaf.decode(generation.get(MachineStore.leafKey(k)),digest.width()))) {
                var ownDirectory=stubs.get(own.k());
                try(var pool=new Pool(new Pool.Configuration(new Pool.Key(route.routeHash(),own.k()),ownDirectory.path(),List.of(),options.charset(),options.javac(),ownDirectory.types()),1)) {
                    var attribute=Attribute.unprocessed(tree,generation,leaf,route,pool,options);
                    for(var unit:plan.candidates()) {
                        var row=FileRow.decode(unit.path(),generation.local(LocalStore.fileKey(project,unit)),digest.width());
                        var inputs=new ProofIndex.Inputs(Path.of(unit.path()).getFileName().toString(),row.kappa(),options.hash());
                        var checked=plan.check(unit,inputs,null,null);assertThat(checked.reusable()).isFalse();
                        before.add(ResultRecord.decode(generation.get(LocalStore.resultKey(checked.previous().aci())),digest.width()));
                        var path=directory.resolve(unit.path());var computed=attribute.run(row,path.toUri(),Files.readAllBytes(path));
                        assertThat(computed.reusable()).as(computed.faults().toString()).isTrue();assertThat(computed.result().diagnostics()).isEmpty();
                        after.add(computed.result());var entries=new ArrayList<Entry>();
                        entries.add(generation.record(LocalStore.proofKey(project,unit),computed.proof().encode()));
                        entries.add(generation.record(LocalStore.proofIndexKey(project,unit),computed.indexed().encode()));
                        entries.add(generation.record(LocalStore.resultKey(computed.aci()),computed.result().encode()));
                        entries.add(generation.record(LocalStore.usesKey(computed.aci()),computed.uses().encode()));
                        for(var query:ReverseIndex.dependencies(computed.proof()))entries.add(generation.record(ReverseIndex.bodyKey(query,project,unit),Entry.NONE));
                        for(var file:computed.result().classFiles())entries.add(generation.reference(LocalStore.classFileKey(file.contentHash())));
                        selected.put(unit,entries);
                    }
                    assertThat(pool.statistics().tasks()).isEqualTo(plan.candidates().size());
                }
            }
            var outputKey=LocalStore.outputKey(project,"app",0);var prior=DefinerIndex.decodeRoot(generation.get(outputKey),digest.width());
            var output=Output.apply(tree,generation,prior,before,after);
            selected.put(new SourceUnit("app",0,""),List.of(generation.record(outputKey,DefinerIndex.encodeRoot(output))));
            plan.checkCurrent();var bodies=generation.commitUnitsDelta(selected,Set.of());
            assertThat(work.receipts).isEqualTo(plan.candidates().size());
            assertThat(BodyRecords.read(tree,store,bodies.bodiesRoot(),idleKey)).isEqualTo(idle);
            if(edit==0)assertThat(BodyRecords.read(tree,store,bodies.bodiesRoot(),useKey)).isEqualTo(original);
            var nativeClasses=Stage2Support.compile(directory.resolve("oracle-"+edit),sources,List.of(),List.of());
            var actual=new TreeMap<String,byte[]>();tree.forEach(output.hash(),h->store.get(MachineStore.nodeKey(h)),e->
                    actual.put(new Codec.Reader(e.key()).zstr()+".class",store.get(LocalStore.classFileKey(Identity.of(e.value())))));
            assertThat(actual.keySet()).isEqualTo(nativeClasses.keySet());actual.forEach((name,bytes)->assertThat(bytes).as(name).isEqualTo(nativeClasses.get(name)));
        }
        assertThat(BodyRecords.read(tree,store,cold.bodies().bodiesRoot(),useKey)).isEqualTo(original);
    }
}
