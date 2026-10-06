package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
class ProcessorPlanTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }
    static final String PROCESSOR = "fixture.Dynamic";
    static final String INPUT = "package p; @Mark public class Input { static final boolean EMIT = true; public int value(){return new InputFirst().value();} }";
    static final String EMPTY = "package p; @Mark public class Empty { static final boolean EMIT = false; public int value(){return 0;} }";

    private Path processor() throws Exception {
        var sources = Map.of("fixture/Dynamic.java", """
                package fixture;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Mark") public class Dynamic extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion(){return SourceVersion.latestSupported();}
                    public java.util.Set<String> getSupportedOptions(){return java.util.Set.of("mode", "org.gradle.annotation.processing." + processingEnv.getOptions().get("mode"));}
                    public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        for(var annotation:annotations) for(var element:round.getElementsAnnotatedWith(annotation)) {
                            boolean emit=false;
                            for(var member:element.getEnclosedElements()) if(member instanceof VariableElement field && member.getSimpleName().contentEquals("EMIT"))
                                emit=Boolean.TRUE.equals(field.getConstantValue());
                            if(!emit) continue;
                            for(var suffix:java.util.List.of("First","Second")) {
                                String name=element.getSimpleName()+suffix;
                                try(var out=processingEnv.getFiler().createSourceFile("p."+name,element).openWriter()) {
                                    out.write("package p; public class "+name+" { public int value(){return 7;} }");
                                } catch(java.io.IOException failure){throw new RuntimeException(failure);}
                            }
                        }
                        return true;
                    }
                }
                """);
        var entries = new TreeMap<>(Stage2Support.compile(dir.resolve("processor"), sources, List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text(PROCESSOR + "\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text(PROCESSOR + ",dynamic\n"));
        return Stage2Support.pack(dir.resolve("processor.jar"), entries);
    }

    private ProjectModel model(Path processor, boolean reverse) throws Exception {
        var a = new Stage2Support.Mod("isolate", "g:isolate:1", List.of()).withOptions("-Amode=isolating");
        var b = new Stage2Support.Mod("aggregate", "g:aggregate:1", List.of()).withOptions("-Amode=aggregating");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Stage2Support.model(dir, reverse ? b : a, reverse ? a : b));
        for(var module:document.withArray("modules")) {
            var processing=((com.fasterxml.jackson.databind.node.ObjectNode)module).putObject("processing");
            processing.putArray("path").addObject().put("coordinate","g:processor:1").put("location",processor.toString());
        }
        return ProjectModel.parse(json.writeValueAsBytes(document));
    }

    private void sources() throws Exception {
        for(var module:List.of("isolate","aggregate")) Stage2Support.write(dir,Map.of(
                module+"/src/main/java/p/Mark.java","package p; public @interface Mark {}",
                module+"/src/main/java/p/Input.java",INPUT,
                module+"/src/main/java/p/Empty.java",EMPTY));
    }

    record State(ContentTree tree, InMemoryLocalStore store, LocalRoot local, Identity project, Stage2.Result result) {
        ProcessorPlan plan(String module) { return ProcessorPlan.load(tree,local,project,module,0,store::get); }
    }

    private State boot(Digest digest, ProjectModel model, InMemoryLocalStore store, int workers) throws Exception {
        var tree = new ContentTree(digest);
        var result = new Stage2(digest,tree,Stage2Support.FEATURE,workers,dir,ClassFacts::of).run(store,model);
        assertThat(result.faults()).isEmpty();
        var project = Stage2.projectKey(digest,model);
        var local = LocalRoot.decode(digest,store.get(LocalStore.localRootKey(project)));
        assertThat(local.format()).contains(";local=15;");
        return new State(tree,store,local,project,result);
    }

    @ParameterizedTest @MethodSource("digests")
    void anEmptyProcessorSetStillBindsTheActualJarBytes(Digest digest) throws Exception {
        var jar=Stage2Support.pack(dir.resolve("no-services.jar"),Map.of("note.txt",Stage2Support.text("no processors")));
        var hash=digest.hash(digest.hash(Files.readAllBytes(jar)).view());var options=digest.hash(new byte[]{13});
        var scope=new ProcessorRecords.Scope(hash,options,List.of());
        try(var host=ProcessorHost.bodies(List.of(jar),digest,dir.resolve("capture"),StandardCharsets.UTF_8,scope,options)) {
            assertThat(host.names()).isEmpty();assertThat(host.pathHash()).isEqualTo(hash);
        }
        var wrong=new ProcessorRecords.Scope(options,options,List.of());
        assertThatThrownBy(()->ProcessorHost.bodies(List.of(jar),digest,dir.resolve("capture"),StandardCharsets.UTF_8,wrong,options))
                .hasMessage("Processor bytes differ from Stage 2");
        assertThat(dir.resolve("capture")).doesNotExist();
    }

    @ParameterizedTest @MethodSource("digests")
    void dynamicClassificationIsScopedAndPublicationDoesNotDependOnModuleOrderOrWorkers(Digest digest) throws Exception {
        sources(); var path=processor();
        var first=boot(digest,model(path,false),Stage2Support.jdkOnly(digest).copy(),1);
        var isolated=first.plan("isolate");var aggregate=first.plan("aggregate");
        assertThat(isolated.invocation().capability(PROCESSOR).declared()).isEqualTo(ProcessorRecords.ISOLATING);
        assertThat(aggregate.invocation().capability(PROCESSOR).declared()).isEqualTo(ProcessorRecords.AGGREGATING);
        assertThat(isolated.invocation().optionsHash()).isNotEqualTo(aggregate.invocation().optionsHash());
        assertThat(isolated.invocation().processorPathHash()).isEqualTo(aggregate.invocation().processorPathHash());
        var global=ProcessorRecords.Capability.decode(first.store().get(LocalStore.processorKey(isolated.invocation().processorPathHash(),PROCESSOR)));
        assertThat(global.declared()).isEqualTo(ProcessorRecords.NONE);
        assertThat(global.observed()).isEqualTo(ProcessorRecords.GENERATOR);
        assertThat(ProcessorRecords.Scope.decode(isolated.invocation().encode(),digest.width())).isEqualTo(isolated.invocation());
        try(var host=ProcessorHost.bodies(List.of(path),digest,dir.resolve("capture-isolate"),StandardCharsets.UTF_8,
                isolated.invocation(),isolated.invocation().optionsHash())) {assertThat(host.names()).containsExactly(PROCESSOR);}
        try(var host=ProcessorHost.bodies(List.of(path),digest,dir.resolve("capture-aggregate"),StandardCharsets.UTF_8,
                aggregate.invocation(),aggregate.invocation().optionsHash())) {assertThat(host.names()).isEmpty();}
        assertThat(aggregate.generation(PROCESSOR,"").outputs().count()).isEqualTo(2);
        assertThatThrownBy(()->aggregate.generation(PROCESSOR,"aggregate/src/main/java/p/Input.java")).isInstanceOf(IllegalArgumentException.class);
        var reordered=boot(digest,model(path,true),first.store().copy(),4);
        assertThat(reordered.result().root()).isEqualTo(first.result().root());
        assertThat(reordered.plan("isolate").invocation()).isEqualTo(isolated.invocation());
        assertThat(reordered.plan("aggregate").invocation()).isEqualTo(aggregate.invocation());
        assertThat(first.store().readsBeforeRoot()).doesNotContain("PS","PG");
        assertThat(reordered.store().readsBeforeRoot()).doesNotContain("PS","PG");
    }

    @ParameterizedTest @MethodSource("digests")
    void mixedGlobalDeclarationsCannotDisableAScopedGeneratorWithEmptyOutputs(Digest digest) throws Exception {
        sources(); var path = processor(); var model = model(path, false);
        String input = INPUT.replace("EMIT = true", "EMIT = false").replace("return new InputFirst().value();", "return 0;");
        Stage2Support.write(dir, Map.of("isolate/src/main/java/p/Input.java", input));
        var state = boot(digest, model, Stage2Support.jdkOnly(digest).copy(), 2);
        var plan = state.plan("isolate");
        var history = LocalStore.processorKey(plan.invocation().processorPathHash(), PROCESSOR);
        assertThat(ProcessorRecords.Capability.decode(state.store().get(history)).observed()).isEqualTo(ProcessorRecords.GENERATOR);
        assertThat(ProcessorRecords.Capability.decode(state.store().get(history)).declared()).isEqualTo(ProcessorRecords.NONE);
        assertThat(plan.invocation().capability(PROCESSOR).declared()).isEqualTo(ProcessorRecords.ISOLATING);
        assertThat(plan.invocation().capability(PROCESSOR).observed()).isEqualTo(ProcessorRecords.GENERATOR);
        assertThat(plan.generation(PROCESSOR, "isolate/src/main/java/p/Input.java").outputs().count()).isZero();
        assertThat(plan.currentInvocation()).isEqualTo(plan.invocation());
        assertThat(state.tree().get(state.local().local().hash(), id -> state.store().get(MachineStore.nodeKey(id)), history)).isNull();
        var nativeClasses = Stage2Support.compile(dir.resolve("native-isolate"), Map.of("p/Input.java", input, "p/Empty.java", EMPTY,
                        "p/Mark.java", "package p; public @interface Mark {}"),
                List.of("-Amode=isolating", "-proc:full", "--processor-path", path.toString()), List.of());
        var result = new dev.jvmd.boot.cold.stage3.Stage3(digest, state.tree(), Stage2Support.FEATURE, 2, dir).run(state.store(), model);
        assertThat(result.faults()).isEmpty();
        var actual = new TreeMap<String,byte[]>();
        for (var file : result.scopes().get("isolate/main").files()) {
            assertThat(file.computed().reusable()).isTrue();
            for (var output : file.computed().result().classFiles())
                actual.put(output.internalName() + ".class", state.store().get(LocalStore.classFileKey(output.contentHash())));
        }
        assertThat(actual.keySet()).isEqualTo(nativeClasses.keySet());
        actual.forEach((name, bytes) -> assertThat(bytes).as(name).isEqualTo(nativeClasses.get(name)));
        // A new header boot retains its scoped declaration; the mixed global consensus is not a scope plan.
        var repeated = boot(digest, model, state.store(), 2);
        assertThat(repeated.plan("isolate").invocation().capability(PROCESSOR).declared()).isEqualTo(ProcessorRecords.ISOLATING);
    }

    @ParameterizedTest @MethodSource("digests")
    void originalAndEmptyOriginsReachTheirManifestsWithoutScanningOrReadingBlobs(Digest digest) throws Exception {
        sources();var path=processor();var model=model(path,false);
        var state=boot(digest,model,Stage2Support.jdkOnly(digest).copy(),2);var plan=state.plan("isolate");
        var input=plan.generation(PROCESSOR,"isolate/src/main/java/p/Input.java");
        var empty=plan.generation(PROCESSOR,"isolate/src/main/java/p/Empty.java");
        assertThat(input.outputs().count()).isEqualTo(2);assertThat(empty.outputs().count()).isZero();
        assertThat(input.derivation()).isNotEqualTo(empty.derivation());assertThat(plan.matches(empty,List.of())).isTrue();
        var expected=new ArrayList<GeneratedOutputs.Output>();
        var blobReads=new ArrayList<java.util.concurrent.atomic.AtomicInteger>();
        for(var suffix:List.of("First","Second")) {
            String generated=".jvmd/generated/aXNvbGF0ZQ/0/p/Input"+suffix+".java";
            var row=FileRow.decode(generated,state.store().get(LocalStore.fileKey(state.project(), Stage2Support.source(generated))),digest.width());
            assertThat(row.genId()).isEqualTo(input.derivation());
            var bytes=state.store().get(LocalStore.generatedSourceKey(row.kappa()));
            expected.add(new GeneratedOutputs.Output(0,"p/Input"+suffix+".java",bytes));
            blobReads.add(state.store().watchReads(LocalStore.generatedSourceKey(row.kappa())));
        }
        int eventsBefore=state.store().events().size();
        var direct=new ProcessorPlan.Generation(input.derivation(),input.outputs());
        assertThat(plan.matches(direct,expected)).isTrue();
        assertThat(plan.matches(input,List.of(expected.getFirst()))).isFalse();
        assertThat(plan.matches(input,List.of(expected.getFirst(),expected.getFirst()))).isFalse();
        var changed=new ArrayList<>(expected);var original=expected.getFirst();
        changed.set(0,new GeneratedOutputs.Output(0,original.path(),Stage2Support.text("changed")));
        assertThat(plan.matches(input,changed)).isFalse();
        changed.set(0,new GeneratedOutputs.Output(0,"p/Renamed.java",original.bytes()));
        assertThat(plan.matches(input,changed)).isFalse();
        changed.set(0,new GeneratedOutputs.Output(1,original.path(),original.bytes()));
        assertThat(plan.matches(input,changed)).isFalse();
        assertThat(plan.matches(empty,expected)).isFalse();
        assertThat(blobReads).allSatisfy(count -> assertThat(count.get()).isZero());
        assertThat(state.store().events().subList(eventsBefore,state.store().events().size())).noneMatch(e -> e.startsWith("prefix:"));
        var forged=LocalStore.processorGenerationKey(state.project(),"isolate",0,PROCESSOR,"isolate/src/main/java/p/Mark.java");
        state.store().put(forged,input.derivation().bytes());state.store().flush();
        var reads=state.store().watchReads(forged);
        assertThat(plan.generation(PROCESSOR,"isolate/src/main/java/p/Mark.java")).isNull();assertThat(reads.get()).isZero();
        var key=LocalStore.generatedKey(input.derivation());state.store().put(key,DefinerIndex.encodeRoot(empty.outputs()));state.store().flush();
        assertThatThrownBy(()->plan.generation(PROCESSOR,"isolate/src/main/java/p/Input.java"))
                .hasMessage("Processor record differs from the committed LOCAL tree");
    }

    @ParameterizedTest @MethodSource("digests")
    void anEmptyOriginEditMovesOnlyItsInputDerivationAndPreservesBothOutputRoots(Digest digest) throws Exception {
        sources();var path=processor();var model=model(path,false);
        var first=boot(digest,model,Stage2Support.jdkOnly(digest).copy(),2);var before=first.plan("isolate");
        var empty=before.generation(PROCESSOR,"isolate/src/main/java/p/Empty.java");
        var emitting=before.generation(PROCESSOR,"isolate/src/main/java/p/Input.java");
        var aggregate=first.plan("aggregate").generation(PROCESSOR,"");
        Files.writeString(dir.resolve("isolate/src/main/java/p/Empty.java"),EMPTY.replace("return 0","return 1"));
        var second=boot(digest,model,first.store().copy(),2);var after=second.plan("isolate");
        var edited=after.generation(PROCESSOR,"isolate/src/main/java/p/Empty.java");
        assertThat(edited.derivation()).isNotEqualTo(empty.derivation());
        assertThat(edited.outputs()).isEqualTo(empty.outputs());
        assertThat(after.generation(PROCESSOR,"isolate/src/main/java/p/Input.java")).isEqualTo(emitting);
        assertThat(second.plan("aggregate").generation(PROCESSOR,"")).isEqualTo(aggregate);
        assertThat(after.invocation()).isEqualTo(before.invocation());
        assertThat(second.store().writes(LocalStore.generatedKey(emitting.derivation()))).isZero();
        assertThat(second.store().writes(LocalStore.generatedKey(aggregate.derivation()))).isZero();
        // Removing the annotation removes the reference from the new root even though its old raw key still exists.
        Files.writeString(dir.resolve("isolate/src/main/java/p/Empty.java"),EMPTY.replace("@Mark ",""));
        var third=boot(digest,model,second.store().copy(),2);
        var stale=LocalStore.processorGenerationKey(third.project(),"isolate",0,PROCESSOR,"isolate/src/main/java/p/Empty.java");
        assertThat(third.store().get(stale)).isNotNull();
        var read=third.store().watchReads(stale);
        assertThat(third.plan("isolate").generation(PROCESSOR,"isolate/src/main/java/p/Empty.java")).isNull();
        assertThat(read.get()).isZero();
    }

    @ParameterizedTest @MethodSource("digests")
    void bodyCaptureUsesTheCommittedPlanAndMatchesNativeManifestsAndClassBytes(Digest digest) throws Exception {
        sources();var path=processor();var model=model(path,false);
        var state=boot(digest,model,Stage2Support.jdkOnly(digest).copy(),2);var plan=state.plan("isolate");
        var leaf=MachineLeaf.decode(state.store().get(MachineStore.leafKey(state.result().leaves().get("isolate/main"))),digest.width());
        var route=Route.decode(state.store().get(LocalStore.routeKey(state.project(),"isolate",0)),digest.width());
        var own=Files.createDirectories(dir.resolve("stubs"));var names=new ArrayList<String>();
        for(var stub:Stubs.stubs(digest,state.tree(),leaf,id->state.store().get(MachineStore.nodeKey(id)),Stubs.Cache.NONE)) {
            var file=own.resolve(stub.internalName()+".class");Files.createDirectories(file.getParent());Files.write(file,stub.bytes());names.add(stub.internalName());
        }
        var options=List.of("-source",Integer.toString(Stage2Support.FEATURE),"-proc:full","-implicit:none","-encoding","UTF-8","-Amode=isolating");
        var hash=JavacOptions.optionsHash(digest,List.of("-Amode=isolating"),Stage2Support.FEATURE,plan.invocation().processorPathHash(),plan.invocation().names());
        assertThat(hash).isEqualTo(plan.invocation().optionsHash());
        var configuration=new Pool.Configuration(new Pool.Key(route.routeHash(),leaf.k()),own,List.of(),StandardCharsets.UTF_8,options,names);
        var oracle=Stage2Support.compile(dir.resolve("oracle"),Map.of("p/Mark.java","package p; public @interface Mark {}","p/Input.java",INPUT,"p/Empty.java",EMPTY),
                List.of("-proc:full","-encoding","UTF-8","-Amode=isolating","--processor-path",path.toString()),List.of());
        try(var pool=new Pool(configuration,1)) {
            for(var name:List.of("Input","Empty","Input")) {
                String origin="isolate/src/main/java/p/"+name+".java";var source=dir.resolve(origin);var messages=new ArrayList<String>();
                try(var host=ProcessorHost.bodies(List.of(path),digest,dir.resolve("capture"),StandardCharsets.UTF_8,plan.invocation(),hash)) {
                    var result=pool.withTask(source.toUri(),Files.readAllBytes(source),d->messages.add(d.getMessage(java.util.Locale.ROOT)),task->{
                        host.attach(task);
                        try {task.parse();task.analyze();task.generate();return 0;}catch(IOException e){throw new UncheckedIOException(e);}
                    });
                    host.close();assertThat(host.faults()).isEmpty();assertThat(messages).isEmpty();
                    var actual=host.outputs().stream().map(o->new GeneratedOutputs.Output(0,o.name(),o.bytes())).toList();
                    assertThat(plan.matches(plan.generation(PROCESSOR,origin),actual)).isTrue();
                    assertThat(result.classes()).containsOnlyKeys("p/"+name);
                    assertThat(result.classes().get("p/"+name)).isEqualTo(oracle.get("p/"+name+".class"));
                }
            }
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
        assertThat(dir.resolve("capture")).doesNotExist();
    }
}
