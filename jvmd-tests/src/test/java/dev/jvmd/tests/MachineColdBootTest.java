package dev.jvmd.tests;

import dev.jvmd.boot.BootDecision;
import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.boot.cold.machine.MachineInput;
import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.tree.KeyedTree;
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
        var boot=new MachineColdBoot(generation,repository,temp.resolve("no-jdk"),BUDGET);
        var storage=boot.create();
        var inputs=new ArrayList<>(boot.enumerate());
        if(reverse)Collections.reverse(inputs);
        var leaves=new ArrayList<>(boot.buildArtifacts(storage,inputs));
        if(reverse)Collections.reverse(leaves);
        boot.commit(storage,boot.buildTree(leaves));
        return storage;
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
        try(var storage=new MachineColdBoot(temp.resolve("counted"),repository,temp.resolve("no-jdk"),BUDGET).run()){
            @SuppressWarnings("unchecked") var published=(Map<String,Object>)storage.repository().status();
            // M2: no publication reuses prior state. M3: three jars hold two contents; each is parsed and published once.
            assertThat(((Number)published.get("reused")).longValue()).isZero();
            assertThat(((Number)published.get("published")).longValue()).isEqualTo(storage.machine().tree().size()).isEqualTo(2);
            assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(storage.generation())).orElseThrow().identity())
                    .isEqualTo(storage.machine().root().orElseThrow().identity());
        }
    }

    @Test void theRootIsWrittenLastAndIsTheOnlyCompletenessSignal()throws Exception{
        Path repository=repository();Path generation=temp.resolve("interrupted");
        var boot=new MachineColdBoot(generation,repository,temp.resolve("no-jdk"),BUDGET);
        try(var storage=boot.create()){
            var tree=boot.buildTree(boot.buildArtifacts(storage,boot.enumerate()));
            assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation))).isEmpty();
            assertThat(tree.size()).isEqualTo(2);
        }
        // Nothing committed: the next start is a cold boot, which replaces what the interrupted one left.
        assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation))).isEmpty();
        try(var storage=new MachineColdBoot(generation,repository,temp.resolve("no-jdk"),BUDGET).run()){
            assertThat(RocksMachineStore.committedRoot(RocksIndexStorage.machineDirectory(generation)))
                    .contains(storage.machine().root().orElseThrow());
        }
        // A committed root sends the next start to the warm boot.
        var index=temp.resolve("index");
        var cold=BootDecision.machine(index,repository,temp.resolve("no-jdk"),BUDGET);
        assertThat(cold.warm()).isFalse();cold.index().close();
        var warm=BootDecision.machine(index,repository,temp.resolve("no-jdk"),BUDGET);
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
        var boot=new MachineColdBoot(temp.resolve("faults"),repository,temp.resolve("no-jdk"),BUDGET);
        try(var storage=boot.run()){
            assertThat(storage.machine().leafAt(broken.toString())).isNull();
            assertThat(boot.faults()).extracting(MachineColdBoot.Fault::location).containsExactly(broken.toString());
        }
    }

    @Test void enumerationPairsSourcesAndSkipsJavadoc()throws Exception{
        Path repository=repository();
        Files.copy(repository.resolve("fixture/other/2/other-2.jar"),repository.resolve("fixture/other/2/other-2-javadoc.jar"));
        var inputs=new MachineColdBoot(temp.resolve("enumerate"),repository,temp.resolve("no-jdk"),BUDGET).enumerate();
        assertThat(inputs).hasSize(3).allMatch(input->input instanceof MachineInput.Jar);
        assertThat(inputs).extracting(input->input.path().sources()).filteredOn(Objects::nonNull).hasSize(2);
    }
}
