package dev.jvmd.tests;

import dev.jvmd.boot.BootDecision;
import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.boot.cold.machine.MachineInput;
import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.*;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.rocks.RocksIndexStorage;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The MACHINE cold boot on a small repository: its root is a function of its inputs alone. */
@Tag("phase-3")
class MachineColdBootTest {
    private static final long BUDGET=32L*1024*1024;
    @TempDir Path temp;

    private Path repository()throws Exception{
        Path repository=temp.resolve("repository");
        IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        IndexFixtures.jar(repository.resolve("fixture/other/2"),"other-2","Other.java","package other; public class Other { public void run(){} public int size(){return 0;} }",false);
        // Equal content at a second location is one leaf with two paths.
        Path copy=Files.createDirectories(repository.resolve("fixture/copy/1"));
        Files.copy(repository.resolve("fixture/sample/1/sample-1.jar"),copy.resolve("copy-1.jar"));
        return repository;
    }

    private RocksIndexStorage boot(Path generation,Path repository,boolean reverse)throws Exception{
        var boot=new MachineColdBoot(generation,repository,BUDGET);
        var storage=boot.create();
        var inputs=new ArrayList<>(boot.enumerate());
        if(reverse)Collections.reverse(inputs);
        var leaves=new ArrayList<>(boot.buildArtifacts(storage,inputs));
        if(reverse)Collections.reverse(leaves);
        boot.commit(storage,boot.buildTree(leaves));
        return storage;
    }

    @Test void coldBootsInSeparateProcessesGiveEqualRoots()throws Exception{
        // A process fixes the iteration order of hashed collections, so only separate processes show that
        // nothing such an order decides reaches the root.
        Path repository=repository();var roots=new TreeSet<String>();
        var flags=java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(flag->flag.startsWith("--add-exports")||flag.startsWith("--enable-native-access")).toList();
        for(int run=0;run<3;run++){
            var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString()));command.addAll(flags);
            command.addAll(List.of("-cp",System.getProperty("java.class.path"),MachineRootProcess.class.getName(),
                    temp.resolve("process-"+run).toString(),repository.toString()));
            var process=new ProcessBuilder(command).redirectErrorStream(true).start();
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
            roots.add(output.lines().filter(line->line.startsWith("ROOT ")).findFirst().orElseThrow(()->new AssertionError(output)));
        }
        assertThat(roots).hasSize(1);
    }

    @Test void aReopenedGenerationServesItsCommittedRootAndReadsItsTreeWhenNeeded()throws Exception{
        Path repository=repository(),generation=temp.resolve("generation");Root committed;
        try(var storage=new MachineColdBoot(generation,repository,BUDGET).run()){committed=storage.machine().root().orElseThrow();}
        try(var storage=RocksIndexStorage.open(generation,BUDGET)){
            assertThat(storage.machine().root().orElseThrow().identity()).isEqualTo(committed.identity());
            assertThat(storage.status().get("machine_leaves")).isEqualTo(committed.leaves());
            assertThat(storage.machine().tree().root().identity()).isEqualTo(committed.identity());
            assertThat(storage.machine().leafAt(repository.resolve("fixture/sample/1/sample-1.jar").toString())).isNotNull();
        }
    }

    @Test void aRecommitWritesOnlyWhatChangedAndDeletesWhatWent()throws Exception{
        Path repository=repository(),generation=temp.resolve("recommit");
        String copy=repository.resolve("fixture/copy/1/copy-1.jar").toString();MachineTree next;
        try(var storage=new MachineColdBoot(generation,repository,BUDGET).run()){
            var first=storage.machineStore().lastCommit();
            assertThat(first.get("leaves")).isEqualTo(storage.machine().tree().size());
            assertThat(first.get("deleted")).isZero();
            // The shared content loses its second location: one leaf changes and one path goes.
            var previous=storage.machine().tree();var shared=previous.leafAt(copy);
            var leaves=new ArrayList<>(previous.leaves());leaves.remove(shared);
            leaves.add(shared.withPaths(shared.paths().stream().filter(path->!path.location().equals(copy)).toList()));
            next=MachineTree.build(leaves);
            storage.commitMachine(next);
            var recommit=storage.machineStore().lastCommit();
            assertThat(recommit).containsEntry("leaves",1L).containsEntry("paths",0L).containsEntry("deleted",1L);
            // Exactly the nodes of the new tree that the committed tree did not have.
            var stored=new HashSet<dev.jvmd.core.Hash256>();previous.tree().writeNodes((hash,node)->stored.add(hash));
            var changed=new HashSet<dev.jvmd.core.Hash256>();next.tree().writeNodes((hash,node)->{if(!stored.contains(hash))changed.add(hash);});
            assertThat(recommit.get("nodes")).isEqualTo((long)changed.size());
        }
        try(var storage=RocksIndexStorage.open(generation,BUDGET)){
            assertThat(storage.machine().root().orElseThrow().identity()).isEqualTo(next.root().identity());
            assertThat(storage.machineStore().committedTree().root().identity()).isEqualTo(next.root().identity());
            assertThat(storage.machine().leafAt(copy)).isNull();
        }
    }

    @Test void documentationIdentityFollowsDocCommentsNotPositions()throws Exception{
        Path base=temp.resolve("base");Path jar=IndexFixtures.jar(base.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        // The same binary, paired with sources whose declarations moved, or whose doc comment changed.
        Path moved=sourcesVariant("moved",jar,"\n\n\n"+IndexFixtures.generic().replace("public class Sample","\n  public class Sample"));
        Path edited=sourcesVariant("edited",jar,IndexFixtures.generic().replace("Transform the value.","Transform the given value."));
        var identities=new ArrayList<dev.jvmd.core.Hash256>();var ranges=new ArrayList<Object>();
        for(Path repository:List.of(base,moved,edited))
            try(var storage=new MachineColdBoot(temp.resolve("generation-"+repository.getFileName()),repository,BUDGET).run()){
                var leaf=storage.machine().leafAt(repository.resolve("fixture/sample/1/sample-1.jar").toString());
                identities.add(leaf.documentation());
                var transform=storage.repository().documentation(leaf.docsKey(),"fixture.Sample#transform(Ljava/lang/Number;Ljava/lang/CharSequence;)Ljava/util/List;");
                ranges.add(transform.get("name_range"));
            }
        assertThat(identities.get(1)).isEqualTo(identities.get(0));
        assertThat(identities.get(2)).isNotEqualTo(identities.get(0));
        // Positions are still recorded for navigation, from the parser's line map.
        String text=IndexFixtures.generic();int name=text.indexOf(" transform(")+1;
        var start=dev.jvmd.core.Documents.position(text,name);var end=dev.jvmd.core.Documents.position(text,name+"transform".length());
        assertThat(ranges.get(0)).isEqualTo(Map.of("start",Map.of("line",start.line(),"character",start.character()),
                "end",Map.of("line",end.line(),"character",end.character())));
        assertThat(ranges.get(1)).isNotEqualTo(ranges.get(0));
    }

    /** A repository with {@code jar}'s binary and sources made from {@code source}. */
    private Path sourcesVariant(String name,Path jar,String source)throws Exception{
        Path directory=Files.createDirectories(temp.resolve(name).resolve("fixture/sample/1"));
        Files.copy(jar,directory.resolve("sample-1.jar"));
        try(var output=new java.util.jar.JarOutputStream(Files.newOutputStream(directory.resolve("sample-1-sources.jar")))){
            output.putNextEntry(new java.util.jar.JarEntry("fixture/Sample.java"));output.write(source.getBytes(StandardCharsets.UTF_8));output.closeEntry();
        }
        return temp.resolve(name);
    }

    @Test void equalInputsGiveEqualRootsInAnyEnumerationOrder()throws Exception{
        Path repository=repository();
        try(var forward=boot(temp.resolve("forward"),repository,false);var reversed=boot(temp.resolve("reversed"),repository,true)){
            var root=forward.machine().root().orElseThrow();
            assertThat(reversed.machine().root().orElseThrow().identity()).isEqualTo(root.identity());
            assertThat(root.leaves()).isEqualTo(2);
            var sample=forward.machine().leafAt(repository.resolve("fixture/sample/1/sample-1.jar").toString());
            assertThat(sample.paths()).hasSize(2);assertThat(sample.docsKey()).isNotNull();
        }
    }

    @Test void aColdBootLooksUpNothingAndParsesEachContentOnce()throws Exception{
        Path repository=repository();
        try(var storage=new MachineColdBoot(temp.resolve("counted"),repository,BUDGET).run()){
            @SuppressWarnings("unchecked") var published=(Map<String,Object>)storage.repository().status();
            // M2: no publication reuses prior state. M3: three jars hold two contents; each is parsed and published once.
            assertThat(((Number)published.get("reused")).longValue()).isZero();
            assertThat(((Number)published.get("published")).longValue()).isEqualTo(storage.machine().tree().size()).isEqualTo(2);
            assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(storage.generation())).orElseThrow().identity())
                    .isEqualTo(storage.machine().root().orElseThrow().identity());
        }
    }

    /** C2: shuffled inputs, built concurrently in whatever order jobs complete, give one root. */
    @Test void shuffledInputsGiveTheSameRoot()throws Exception{
        Path repository=repository();
        for(int i=0;i<12;i++)IndexFixtures.jar(repository.resolve("fixture/more"+i+"/1"),"more"+i+"-1","M.java","package more"+i+"; public class M { public int v(){return "+i+";} }",i%2==0);
        var roots=new HashSet<dev.jvmd.core.Hash256>();
        for(long seed:List.of(1L,7L,42L)){
            var boot=new MachineColdBoot(temp.resolve("shuffled-"+seed),repository,BUDGET);
            try(var storage=boot.create()){
                var inputs=new ArrayList<>(boot.enumerate());Collections.shuffle(inputs,new Random(seed));
                var leaves=new ArrayList<>(boot.buildArtifacts(storage,inputs));Collections.shuffle(leaves,new Random(seed+1));
                boot.commit(storage,boot.buildTree(leaves));
                roots.add(storage.machine().root().orElseThrow().identity());
            }
        }
        assertThat(roots).hasSize(1);
    }

    @Test void theRootIsWrittenLastAndIsTheOnlyCompletenessSignal()throws Exception{
        Path repository=repository();Path generation=temp.resolve("interrupted");
        var boot=new MachineColdBoot(generation,repository,BUDGET);
        try(var storage=boot.create()){
            var tree=boot.buildTree(boot.buildArtifacts(storage,boot.enumerate()));
            assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation))).isEmpty();
            assertThat(tree.size()).isEqualTo(2);
        }
        // Nothing committed: the next start is a cold boot, which replaces what the interrupted one left.
        assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation))).isEmpty();
        try(var storage=new MachineColdBoot(generation,repository,BUDGET).run()){
            assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation)))
                    .contains(storage.machine().root().orElseThrow());
        }
        // A committed root sends the next start to the warm boot.
        var index=temp.resolve("index");
        var cold=BootDecision.machine(index,repository,BUDGET);
        assertThat(cold.warm()).isFalse();cold.index().close();
        var warm=BootDecision.machine(index,repository,BUDGET);
        assertThat(warm.warm()).isTrue();
        try(var reopened=warm.index()){
            assertThat(reopened.find("transform",null,false,10,0)).isNotEmpty();
        }
    }

    @Test void committedTreesReadBackWithTheIdentitiesTheyWereBuiltWith()throws Exception{
        Path repository=repository();
        try(var storage=boot(temp.resolve("read-back"),repository,false)){
            var root=storage.machine().root().orElseThrow();
            assertThat(storage.machineStore().tree(root).rootHash()).isEqualTo(root.tree());
            Path jar=repository.resolve("fixture/sample/1/sample-1.jar");
            var leaf=storage.machine().leafAt(jar.toString());
            var stored=storage.repository().semanticTree(leaf.cacheKey(),leaf.semanticRoot());
            var facts=ArtifactIndexFormat.from(new BinaryReader().read(jar,false),leaf.key());
            var independent=AlgebraicAccumulator.Value.ZERO;String prefix="member\0fixture.Sample\0";
            for(var symbol:facts.symbols()){
                if(symbol.ownerId()<0||!facts.symbols().get(symbol.ownerId()).key().equals("fixture.Sample"))continue;
                independent=independent.plus(AlgebraicAccumulator.contribution("machine-symbols-v1/range",
                        MachineTree.symbolKey(symbol,facts.symbols().get(symbol.ownerId())).getBytes(StandardCharsets.UTF_8),symbol.resolution().identity()));
            }
            assertThat(independent.cardinality()).isPositive();
            assertThat(stored.range(prefix,prefix+"￿")).isEqualTo(independent);
            assertThat(stored.rootHash()).isEqualTo(MachineTree.semanticTree(facts).rootHash());
        }
    }

    @Test void unreadableInputsContributeNoLeafAndAreReported()throws Exception{
        Path repository=repository();
        Path broken=Files.createDirectories(repository.resolve("fixture/broken/1")).resolve("broken-1.jar");
        Files.writeString(broken,"not a jar");
        Files.writeString(broken.resolveSibling("broken-1.jar.sha1"),"0000");
        var boot=new MachineColdBoot(temp.resolve("faults"),repository,BUDGET);
        try(var storage=boot.run()){
            assertThat(storage.machine().leafAt(broken.toString())).isNull();
            assertThat(boot.faults()).extracting(MachineColdBoot.Fault::location).containsExactly(broken.toString());
        }
    }

    @Test void enumerationPairsSourcesAndSkipsJavadoc()throws Exception{
        Path repository=repository();
        Files.copy(repository.resolve("fixture/other/2/other-2.jar"),repository.resolve("fixture/other/2/other-2-javadoc.jar"));
        var inputs=new MachineColdBoot(temp.resolve("enumerate"),repository,BUDGET).enumerate();
        assertThat(inputs).hasSize(3).allMatch(input->input instanceof MachineInput.Jar);
        assertThat(inputs).extracting(input->input.path().sources()).filteredOn(Objects::nonNull).hasSize(2);
    }
}
