package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.boot.BootDecision;
import dev.jvmd.boot.cold.local.LocalColdBoot;
import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksLocalStore;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import dev.jvmd.resolver.Resolution;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The LOCAL cold boot of a two-module project over a committed MACHINE: section 5.3, checks L1 to L6. */
@Tag("phase-3")
class LocalColdBootTest {
    private static final long BUDGET=32L*1024*1024;
    @TempDir Path temp;
    private Path project,repository,jar,lib,app,appTest;

    @BeforeEach void project()throws Exception{
        repository=temp.resolve("repository");
        jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        project=Files.createDirectories(temp.resolve("project")).toRealPath();
        lib=source("lib/src/main/java/lib/Shape.java","package lib; public interface Shape { double area(); default String label(){ return \"shape\"; } }");
        source("lib/src/main/java/lib/Square.java","package lib; public class Square implements Shape { private final double side; public Square(double side){ this.side=side; } public double area(){ return side*side; } }");
        app=source("app/src/main/java/app/Main.java","package app; import lib.Square; public class Main { public double run(){ return new Square(2).area(); } public fixture.Sample<String> sample(){ return null; } }");
        source("app/src/main/java/app/Helper.java","package app; final class Helper { static int twice(int value){ return value*2; } }");
        appTest=source("app/src/test/java/app/MainTest.java","package app; class MainTest { double check(){ return new Main().run()+Helper.twice(1); } }");
    }

    private Path source(String path,String text)throws Exception{
        Path file=project.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);return file;
    }

    private Resolution resolution(Path... extraJars){
        String libClasses=project.resolve("lib/target/classes").toString(),appClasses=project.resolve("app/target/classes").toString(),
                appTestClasses=project.resolve("app/target/test-classes").toString();
        var libModule=new Resolution.Module("g:lib:1",project.resolve("lib").toString(),"jar",List.of(project.resolve("lib/src/main/java").toString()),List.of(),
                libClasses,project.resolve("lib/target/test-classes").toString(),"25",List.of());
        var appModule=new Resolution.Module("g:app:1",project.resolve("app").toString(),"jar",List.of(project.resolve("app/src/main/java").toString()),
                List.of(project.resolve("app/src/test/java").toString()),appClasses,appTestClasses,"25",List.of("g:lib:1"));
        var nodes=new ArrayList<Resolution.Node>(List.of(
                new Resolution.Node("g:app:1|g:lib:1","g:lib:1","jar","","compile",libClasses,null,"",false),
                new Resolution.Node("g:app:1|fixture:sample:1","fixture:sample:1","jar","","compile",jar.toString(),null,"",false)));
        var appMain=new ArrayList<>(List.of(appClasses,libClasses,jar.toString()));
        for(Path extra:extraJars){
            nodes.add(new Resolution.Node("g:app:1|extra:"+extra.getFileName(),"extra:"+extra.getFileName()+":1","jar","","compile",extra.toString(),null,"",false));
            appMain.add(extra.toString());
        }
        var appTestPath=new ArrayList<>(List.of(appTestClasses));appTestPath.addAll(appMain);
        return new Resolution(project.toString(),List.of(appModule,libModule),nodes,List.of(),
                Map.of("g:lib:1:main",List.of(libClasses),"g:app:1:main",appMain,"g:app:1:test",appTestPath),List.of(),"fixture",true,false);
    }

    /**
     * The analyzer's compiler context for a module scope of {@link #resolution}: the fixture's sibling
     * classes are not built, so javac reads siblings from their sources, as analysis does.
     */
    private Analyzer.Context context(Resolution.Module module,boolean test){
        var graph=resolution();String scope=test?"test":"main";
        var classpath=new ArrayList<Path>();graph.classpaths().get(module.gav()+":"+scope).forEach(location->classpath.add(Path.of(location)));
        var sources=new ArrayList<Path>();module.sources().forEach(root->sources.add(Path.of(root)));if(test)module.testSources().forEach(root->sources.add(Path.of(root)));
        var coordinates=new LinkedHashMap<String,String>();
        for(var other:graph.modules()){
            for(String root:other.sources())coordinates.put(root,other.gav());for(String root:other.testSources())coordinates.put(root,other.gav());
            if(module.dependencies().contains(other.gav())){other.sources().forEach(root->sources.add(Path.of(root)));classpath.add(Path.of(other.classes()));}
        }
        coordinates.put(jar.toString(),"fixture:sample:1");
        return new Analyzer.Context(module.gav(),"25",classpath,sources,"fixture:"+module.gav()+":"+scope,coordinates,List.of("--release","25"),
                Set.of(),List.of(),List.copyOf(sources),true,"");
    }

    private RocksIndexStorage machine(Path generation)throws Exception{
        return new MachineColdBoot(generation,repository,BUDGET).run();
    }

    private LocalColdBoot local(RocksIndexStorage storage,Resolution resolution)throws Exception{
        return new LocalColdBoot(storage.generation(),project,()->resolution,this::context,storage.machine(),storage,BUDGET);
    }

    private Path localDirectory(RocksIndexStorage storage)throws Exception{return RocksLocalStore.directory(storage.generation(),project);}

    @Test void twoColdBootsOfTheSameProjectGiveEqualRootsInAnyCompletionOrder()throws Exception{
        try(var first=machine(temp.resolve("first"));var second=machine(temp.resolve("second"))){
            var forward=local(first,resolution());
            Root root=forward.run();
            // The second boot builds the units in a different order: the test unit first, then the app.
            var reordered=local(second,resolution());reordered.start();
            reordered.require(appTest);reordered.require(app);
            Root again=reordered.committed().get(60,TimeUnit.SECONDS);
            assertThat(again.identity()).isEqualTo(root.identity());
            assertThat(root.leaves()).isEqualTo(5);
            assertThat(RocksLocalStore.committedRoot(localDirectory(first)).orElseThrow().identity()).isEqualTo(root.identity());
        }
    }

    @Test void routesReferToMachineLeavesOrSiblingsAndLocalHoldsNoArtifactDeclarations()throws Exception{
        try(var storage=machine(temp.resolve("generation"))){
            var boot=local(storage,resolution());boot.run();
            var tree=boot.layer().orElseThrow().tree().orElseThrow();
            assertThat(tree.routes()).containsOnlyKeys("g:lib:1:main","g:app:1:main","g:app:1:test");
            var appRoute=tree.routes().get("g:app:1:main");
            assertThat(appRoute.modules()).containsExactly("g:lib:1");
            assertThat(appRoute.machineKeys()).containsExactly(storage.machine().leafAt(jar.toString()).cacheKey());
            for(var route:tree.routes().values())for(String key:route.machineKeys())assertThat(storage.machine().leaf(key)).isNotNull();
            assertThat(tree.modules()).containsEntry("g:app:1",List.of("g:lib:1")).containsEntry("g:lib:1",List.of());
            try(var store=openStore(storage)){
                for(var entry:store.semantic().entries())
                    assertThat(Objects.toString(entry.getValue().sourceFile(),"")).as(entry.getKey()).startsWith(project.toString());
            }
            assertThat(tree.semantic().entries()).anyMatch(entry->"lib.Square".equals(entry.getValue().fqn()));
        }
    }

    @Test void aRequestReturnsAfterItsUnitIsBuilt()throws Exception{
        try(var storage=machine(temp.resolve("generation"))){
            var boot=local(storage,resolution());boot.start();
            boot.require(appTest);
            assertThat(boot.layer().orElseThrow().pending(appTest)).isFalse();
            assertThat(boot.layer().orElseThrow().view().type("app.MainTest")).isNotNull();
            boot.committed().get(60,TimeUnit.SECONDS);
            assertThat(boot.layer().orElseThrow().pendingCount()).isZero();
            assertThat(boot.faults()).isEmpty();
        }
    }

    @Test void aFinishedBootLeavesNoSourceWatcherBehind()throws Exception{
        var before=sourceWatchers();
        try(var storage=machine(temp.resolve("generation"))){local(storage,resolution()).run();}
        // A watcher outliving the boot would keep writing the daemon's state after it closed.
        var after=sourceWatchers();after.removeAll(before);
        assertThat(after).isEmpty();
    }

    private static Set<Thread> sourceWatchers(){
        var result=new HashSet<Thread>();
        for(var thread:Thread.getAllStackTraces().keySet())if(thread.getName().startsWith("jvmd-source-state-"))result.add(thread);
        return result;
    }

    @Test void anOwnerWhoseUnitIsNotBuiltIsUnknownNeverAnsweredByALowerLayer()throws Exception{
        LocalTree tree;
        try(var storage=machine(temp.resolve("generation"))){
            var boot=local(storage,resolution());boot.run();tree=boot.layer().orElseThrow().tree().orElseThrow();
        }
        var facts=new HashMap<String,Map<String,SemanticFact>>();
        for(var entry:tree.semantic().entries())facts.computeIfAbsent(entry.getValue().sourceFile(),_->new HashMap<>()).put(entry.getValue().id(),entry.getValue());
        Path shape=lib,square=project.resolve("lib/src/main/java/lib/Square.java");
        var layer=new LocalLayer(Map.of(shape,"lib.Shape",square,"lib.Square"),tree.routes());
        var leaf=tree.files().file("lib/src/main/java/lib/Shape.java");
        layer.admit(shape,leaf,new SemanticSnapshot("source:"+shape,shape.toString(),leaf.content(),facts.get(shape.toString()),Map.of(),leaf.api(),leaf.namespace(),"",Set.of()));
        // An installed copy of lib.Square in a lower layer must not answer while LOCAL's Square is unbuilt.
        var installed=new ResidentSemanticState();
        var squareFacts=facts.get(square.toString());
        installed.admit(new SemanticSnapshot("type:installed",null,"installed",squareFacts,Map.of(),"","","",Set.of()));
        var squareType=squareFacts.values().stream().filter(fact->"lib.Square".equals(fact.fqn())).findFirst().orElseThrow();
        var view=SemanticReadViews.precedence(SemanticReadViews.resident(new ResidentSemanticState()),layer.view(),
                SemanticReadViews.resident(installed,SemanticReadView.Origin.MACHINE),
                binaryName->{try{return layer.view().type(binaryName)==null&&layer.ownsBinary(binaryName);}catch(Exception e){return true;}});
        assertThat(layer.ownsBinary("lib.Square")).isTrue();
        assertThat(view.type("lib.Square")).isNull();
        assertThat(view.type("lib.Shape")).isNotNull();
        assertThat(layer.view().completeness(view.type("lib.Shape").id())).isEqualTo(SemanticCompleteness.COMPLETE);
        assertThat(layer.view().completeness(squareType.id())).isEqualTo(SemanticCompleteness.UNKNOWN);
        layer.admit(square,tree.files().file("lib/src/main/java/lib/Square.java"),new SemanticSnapshot("source:"+square,square.toString(),"square",squareFacts,Map.of(),"","","",Set.of()));
        assertThat(view.type("lib.Square").origin()).isEqualTo(SemanticReadView.Origin.LOCAL);
        assertThat(view.completeness(squareType.id())).isEqualTo(SemanticCompleteness.COMPLETE);
    }

    @Test void aBootStoppedBeforeItsRootLeavesTheProjectCold()throws Exception{
        try(var storage=machine(temp.resolve("generation"))){
            var machine=new BootDecision.Machine(storage,null,false);
            var failing=new LocalColdBoot(storage.generation(),project,()->{throw new IllegalStateException("resolution failed");},this::context,storage.machine(),storage,BUDGET);
            assertThatThrownBy(failing::run).hasMessageContaining("resolution failed");
            assertThat(Files.isDirectory(localDirectory(storage))).isTrue();
            assertThat(RocksLocalStore.committedRoot(localDirectory(storage))).isEmpty();
            var decided=BootDecision.local(machine,project,()->resolution(),this::context,BUDGET);
            assertThat(decided.warm()).isFalse();
            var root=decided.cold().run();
            assertThat(RocksLocalStore.committedRoot(localDirectory(storage)).orElseThrow().identity()).isEqualTo(root.identity());
            assertThat(BootDecision.local(machine,project,()->resolution(),this::context,BUDGET).warm()).isTrue();
        }
    }

    @Test void aRouteEntryMissingFromMachineIsBuiltAndTheMachineRootRecommitted()throws Exception{
        Path outside=IndexFixtures.jar(temp.resolve("downloaded"),"other-2","Other.java","package other; public class Other { public void run(){} }",false);
        try(var storage=machine(temp.resolve("generation"))){
            var before=storage.machine().root().orElseThrow();
            assertThat(storage.machine().leafAt(outside.toString())).isNull();
            var boot=local(storage,resolution(outside));boot.run();
            var leaf=storage.machine().leafAt(outside.toString());
            assertThat(leaf).isNotNull();
            assertThat(boot.layer().orElseThrow().tree().orElseThrow().routes().get("g:app:1:main").machineKeys()).contains(leaf.cacheKey());
            var committed=RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(storage.generation())).orElseThrow();
            assertThat(committed.identity()).isNotEqualTo(before.identity()).isEqualTo(storage.machine().root().orElseThrow().identity());
            assertThat(committed.leaves()).isEqualTo(before.leaves()+1);
        }
    }

    @Test void addingARouteArtifactDuringARepositoryScanKeepsTheScansInventory()throws Exception{
        Path outside=IndexFixtures.jar(temp.resolve("downloaded"),"other-2","Other.java","package other; public class Other { public void run(){} }",false);
        try(var storage=machine(temp.resolve("generation"))){
            // A repository scan of the generation is in progress and has observed the sample jar.
            long scan=storage.beginScan();
            var sample=storage.machine().leafAt(jar.toString());
            storage.observe(scan,new IndexStore.ArtifactInput(new ArtifactContext("fixture:sample:1","jar",jar.toString()),sample.key(),
                    Files.size(jar),Files.getLastModifiedTime(jar).toMillis()));
            local(storage,resolution(outside)).run();
            assertThat(storage.completeScan(scan)).isEmpty();
        }
    }

    @Test void eachModuleScopeCompilesWithTheContextAnalysisUses()throws Exception{
        // A context with an annotation processor's generated sources: the boot compiles with it as given.
        Path generated=Files.createDirectories(temp.resolve("generated/gen"));
        Files.writeString(generated.resolve("Made.java"),"package gen; public class Made { public int size(){ return 1; } }");
        source("app/src/main/java/app/Factory.java","package app; public class Factory { public gen.Made made(){ return new gen.Made(); } }");
        var asked=new ArrayList<String>();
        LocalColdBoot.Contexts contexts=(module,test)->{
            asked.add(module.gav()+":"+test);var context=context(module,test);
            if(!module.gav().equals("g:app:1")||test)return context;
            var sources=new ArrayList<>(context.sources());sources.add(temp.resolve("generated"));
            return new Analyzer.Context(context.gav(),context.release(),context.classpath(),sources,context.generation()+":generated",context.coordinates(),
                    context.compilerOptions(),context.binarySources(),context.warnings(),context.navigationSources(),true,"");
        };
        try(var storage=machine(temp.resolve("generation"))){
            var boot=new LocalColdBoot(storage.generation(),project,()->resolution(),contexts,storage.machine(),storage,BUDGET);boot.run();
            assertThat(boot.faults()).isEmpty();
            assertThat(asked).containsExactlyInAnyOrder("g:app:1:false","g:app:1:true","g:lib:1:false");
            var made=boot.layer().orElseThrow().tree().orElseThrow().semantic().entries().stream().map(Map.Entry::getValue)
                    .filter(fact->fact.name().equals("made")).findFirst().orElseThrow();
            assertThat(made.type().toString()).contains("gen.Made").doesNotContain("Unknown");
        }
    }

    @Test void identitiesReadFromTheCommittedTreeEqualIndependentlyComputedOnes()throws Exception{
        try(var storage=machine(temp.resolve("generation"))){
            var boot=local(storage,resolution());boot.run();
            try(var store=openStore(storage)){
                var byFile=new HashMap<String,List<SemanticFact>>();
                for(var entry:store.semantic().entries())byFile.computeIfAbsent(entry.getValue().sourceFile(),_->new ArrayList<>()).add(entry.getValue());
                var files=store.files().entries();
                assertThat(files).hasSize(5);
                for(var entry:files){
                    var leaf=entry.getValue();var own=byFile.get(project.resolve(leaf.path()).toString());
                    var independent=LocalSemanticTree.file(own);
                    assertThat(leaf.semanticRoot()).as(leaf.path()).isEqualTo(independent.rootHash());
                    assertThat(leaf.facts()).isEqualTo(own.size());
                    var sum=dev.jvmd.core.AlgebraicAccumulator.Value.ZERO;
                    for(var fact:own)sum=sum.plus(dev.jvmd.core.AlgebraicAccumulator.contribution(ResidentSemanticState.FACTS.domain()+"/range",
                            fact.orderedKey().getBytes(java.nio.charset.StandardCharsets.UTF_8),fact.resolutionIdentity()));
                    assertThat(leaf.resolution()).isEqualTo(sum.identity("local-file-resolution-v1"));
                }
                var rebuilt=LocalFileTree.build(files.stream().map(Map.Entry::getValue).toList());
                assertThat(rebuilt.tree().rootHash()).isEqualTo(store.files().rootHash());
            }
        }
    }

    /** The committed LOCAL store, opened read-write after its boot closed it. */
    private RocksLocalStore openStore(RocksIndexStorage storage)throws Exception{
        return RocksLocalStore.open(localDirectory(storage));
    }
}
