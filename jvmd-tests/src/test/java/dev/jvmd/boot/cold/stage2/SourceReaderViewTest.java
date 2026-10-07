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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Source-private metadata is supplied to javac and observed independently of T and existing S/ST. */
@Tag("phase-3")
class SourceReaderViewTest {
    @TempDir Path directory;
    static Stream<Digest> digests() {return Stream.of(Sha256.INSTANCE,new Digests.Sha3());}
    @ParameterizedTest @MethodSource("digests")
    void privateMetadataSelectsTheConsumerWhileBodyAndUnreadMetadataEditsPreserveItsProof(Digest digest) throws Exception {
        for(boolean sibling:List.of(false,true)) {
            var root=directory.resolve("scope"+sibling);
            var annotation=Stage2Support.pack(root.resolve("ann.jar"),Stage2Support.compile(root.resolve("annotation"),
                    Map.of("q/Ann.java","package q; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Ann { int value(); }"),
                    List.of(),List.of()));
            var dep=Stage2Support.Dep.jar("g:ann:1",annotation.toString());
            var model=ProjectModel.parse(sibling
                    ?Stage2Support.model(root,new Stage2Support.Mod("lib","g:lib:1",List.of(dep)),
                        new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.module("g:lib:1","lib"),dep)))
                    :Stage2Support.model(root,new Stage2Support.Mod("app","g:app:1",List.of(dep))));
            String provider=sibling?"lib":"app";
            var app=new SourceUnit("app",0,"app/src/main/java/p/App.java");
            String libPath=provider+"/src/main/java/p/Lib.java",otherPath=provider+"/src/main/java/p/Other.java";
            String lib="package p; public class Lib { @q.Ann(1) private int hidden; public static int call(){return 1;} }";
            String other="package p; public class Other { @q.Ann(1) private int hidden; }";
            var source=new TreeMap<>(Map.of(app.path(),"package p; public class App { int call(){return Lib.call();} }",libPath,lib,otherPath,other));
            var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();var project=Stage2.projectKey(digest,model);
            SourceLeaf original=null;Attribute.Computed accepted=null;Map<String,byte[]> stubs=null;
            BodiesRoot firstBodies=null;
            for(int variant=0;variant<4;variant++) {
                if(variant==1)source.put(libPath,lib.replace("return 1","return 2"));
                if(variant==2)source.put(otherPath,other.replace("@q.Ann(1)","@q.Ann(2)"));
                if(variant==3)source.put(libPath,source.get(libPath).replace("@q.Ann(1)","@q.Ann(2)"));
                Stage2Support.write(root,source);
                var header=new Stage2(digest,tree,Stage2Support.FEATURE,2,root,ClassFacts::of).run(store,model);
                assertThat(header.faults()).isEmpty();
                var providerLeaf=SourceLeaf.decode(store.get(LocalStore.sourceLeafKey(project,provider,0)),digest.width());
                assertThat(providerLeaf.compilerView()).isNotNull();assertThat(providerLeaf.reader()).isNotNull();
                var own=MachineLeaf.decode(store.get(MachineStore.leafKey(providerLeaf.k())),digest.width());
                var currentStubs=new TreeMap<String,byte[]>();
                for(var stub:Stubs.stubs(digest,tree,own,id->store.get(MachineStore.nodeKey(id)),Stubs.Cache.NONE))
                    currentStubs.put(stub.internalName(),stub.bytes());
                if(variant==0) {original=providerLeaf;stubs=currentStubs;}
                else {
                    assertThat(providerLeaf.k()).isEqualTo(original.k());assertThat(providerLeaf.a()).isEqualTo(original.a());
                    assertThat(currentStubs.keySet()).isEqualTo(stubs.keySet());
                    for(var entry:stubs.entrySet())assertThat(currentStubs.get(entry.getKey())).isEqualTo(entry.getValue());
                    if(variant==1)assertThat(providerLeaf).isEqualTo(original);
                    else assertThat(providerLeaf.compilerView()).isNotEqualTo(original.compilerView());
                    var work=new BodyValidation.Work();var plan=new BodyValidation(tree,store,project,"app",0,List.of(),work);
                    if(variant==3) {
                        assertThat(plan.candidates()).contains(app);
                        assertThat(plan.check(app,accepted.indexed().inputs(),null,null).reusable()).isFalse();
                    } else {
                        assertThat(plan.candidates()).doesNotContain(app);assertThat(work.receipts).isZero();
                    }
                }
                var body=new Stage3(digest,tree,Stage2Support.FEATURE,2,root).run(store,model);
                if(firstBodies==null)firstBodies=body.bodies();
                assertThat(body.faults()).isEmpty();
                var computed=body.scopes().get("app/main").files().stream().filter(f->f.path().equals(app.path())).findFirst().orElseThrow().computed();
                assertThat(computed.reusable()).as(computed.faults().toString()).isTrue();
                assertThat(computed.proof().readerReads()).anyMatch(r->r.query().type().equals("p/Lib") && r.query().kind()==ReaderImage.RECIPE);
                assertThat(computed.result().diagnostics()).isEmpty();
                if(accepted!=null) {
                    if(variant==3)assertThat(computed.aci()).isNotEqualTo(accepted.aci());
                    else assertThat(computed.aci()).isEqualTo(accepted.aci());
                    assertThat(computed.result().encode()).isEqualTo(accepted.result().encode());
                }
                var nativeSources=new TreeMap<String,String>();
                source.forEach((path,text)->nativeSources.put(path.substring(path.indexOf("/src/main/java/")+15),text));
                var nativeClasses=Stage2Support.compile(root.resolve("native"+variant),nativeSources,List.of(),List.of(annotation));
                var actualNames=new TreeSet<String>();
                for(var scope:body.scopes().values())for(var file:scope.files()) {
                    assertThat(file.computed().result().diagnostics()).isEmpty();
                    for(var output:file.computed().result().classFiles()) {
                        actualNames.add(output.internalName()+".class");
                        assertThat(store.get(LocalStore.classFileKey(output.contentHash())))
                                .as(output.internalName()).isEqualTo(nativeClasses.get(output.internalName()+".class"));
                    }
                }
                assertThat(actualNames).isEqualTo(nativeClasses.keySet());
                accepted=computed;
            }
            // Current raw SL bindings have moved; historical BROOT must still lead to the first exact view and bytes.
            var historical=SourceLeaf.decode(BodyRecords.read(tree,store,firstBodies.bodiesRoot(),
                    LocalStore.sourceLeafKey(project,provider,0)),digest.width());
            assertThat(historical).isEqualTo(original);
            try(var inputs=new StubDirectories(tree,store,k->MachineLeaf.decode(store.get(MachineStore.leafKey(k)),digest.width()))) {
                var path=inputs.view(historical).path().resolve("p/Lib.class");
                var image=ReaderImage.extract(java.lang.classfile.ClassFile.of().parse(Files.readAllBytes(path)));
                var expected=tree.get(historical.reader(),id->store.get(MachineStore.nodeKey(id)),ReaderImage.owner("p/Lib"));
                assertThat(ReaderImage.seal(tree,store,image).value()).isEqualTo(expected.value());
            }
        }
    }
}
