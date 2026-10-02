package dev.jvmd.tests;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Acceptance checks C1 and M8: the folder a class sits in fixes what it may depend on and call. */
@Tag("phase-1")
class BootStructureTest {
    private static final Path BOOT=TestSupport.repo().resolve("jvmd-boot/src/main/java/dev/jvmd/boot");

    @Test void coldAndWarmBootsNeverImportEachOther()throws Exception{
        assertThat(sources(BOOT.resolve("cold"))).isNotEmpty().allSatisfy((file,text)->assertThat(text).as(file).doesNotContain("dev.jvmd.boot.warm"));
        assertThat(sources(BOOT.resolve("warm"))).allSatisfy((file,text)->assertThat(text).as(file).doesNotContain("dev.jvmd.boot.cold"));
    }

    @Test void layersDoNotKnowHowTheyWereBuilt()throws Exception{
        var layers=new LinkedHashMap<String,String>();
        layers.putAll(sources(TestSupport.repo().resolve("jvmd-index/src/main/java/dev/jvmd/index/layer")));
        layers.putAll(sources(TestSupport.repo().resolve("jvmd-index-rocks/src/main/java/dev/jvmd/index/rocks/layer")));
        assertThat(layers).isNotEmpty().allSatisfy((file,text)->assertThat(text).as(file).doesNotContain("dev.jvmd.boot"));
    }

    @Test void coldBootsCallNoStoreLookup()throws Exception{
        // A cold boot calls only create and write methods on the storage it builds: reading prior state
        // back is the warm boot's job. (A LOCAL cold boot reads the committed MACHINE layer, not a store.)
        var call=Pattern.compile("\\b(store|storage|machineStorage|machineStore|inventory)\\.(\\w+)\\(");
        var writes=Set.of("commit","commitMachine","close","admission","repository");
        var calls=new TreeSet<String>();
        for(var source:sources(BOOT.resolve("cold")).entrySet()){
            var matcher=call.matcher(source.getValue());
            while(matcher.find()){
                calls.add(matcher.group(2));
                assertThat(writes).as(source.getKey()+" calls "+matcher.group()).contains(matcher.group(2));
            }
        }
        assertThat(calls).contains("commit","commitMachine");
    }

    @Test void onlyTheColdBootCreateStageCreatesStorage()throws Exception{
        var creators=new TreeSet<String>();
        for(String module:List.of("jvmd-core","jvmd-index","jvmd-index-rocks","jvmd-analyzer","jvmd-boot","jvmd-dist"))
            sources(TestSupport.repo().resolve(module).resolve("src/main/java")).forEach((file,text)->{
                if(text.contains("setCreateIfMissing(true)"))creators.add(Path.of(file).getFileName().toString());
            });
        // RocksMemory.creating is the MACHINE create stage's option set and RocksLocalStore.create the
        // LOCAL one's; BindingFacts is replaced by the LIVE task.
        assertThat(creators).containsExactly("BindingFacts.java","RocksLocalStore.java","RocksMemory.java");
        assertThat(Files.readString(TestSupport.repo().resolve("jvmd-dist/src/main/java/dev/jvmd/dist/BindingFacts.java"))).contains("TEMPORARY(live-delta)");
        var callers=new TreeSet<String>();
        for(String module:List.of("jvmd-index-rocks","jvmd-boot","jvmd-dist"))
            sources(TestSupport.repo().resolve(module).resolve("src/main/java")).forEach((file,text)->{
                if(text.contains("RocksMemory::creating")||text.contains(".creating("))callers.add(Path.of(file).getFileName().toString());
            });
        assertThat(callers).containsExactly("RocksIndexStorage.java");
        String storage=Files.readString(TestSupport.repo().resolve("jvmd-index-rocks/src/main/java/dev/jvmd/index/rocks/RocksIndexStorage.java"));
        assertThat(storage.split("RocksMemory::creating",-1)).hasSize(2);
        var createCallers=new TreeSet<String>();
        for(String module:List.of("jvmd-boot","jvmd-dist"))
            sources(TestSupport.repo().resolve(module).resolve("src/main/java")).forEach((file,text)->{
                if(text.contains("RocksIndexStorage.create("))createCallers.add(Path.of(file).getFileName().toString());
            });
        assertThat(createCallers).containsExactly("MachineColdBoot.java");
        var localCreators=new TreeSet<String>();
        for(String module:List.of("jvmd-index-rocks","jvmd-boot","jvmd-dist"))
            sources(TestSupport.repo().resolve(module).resolve("src/main/java")).forEach((file,text)->{
                if(text.contains("RocksLocalStore.create("))localCreators.add(Path.of(file).getFileName().toString());
            });
        assertThat(localCreators).containsExactly("LocalColdBoot.java");
    }

    private static Map<String,String> sources(Path root)throws Exception{
        var result=new TreeMap<String,String>();
        if(!Files.isDirectory(root))return result;
        try(var files=Files.walk(root)){
            for(Path file:files.filter(path->path.toString().endsWith(".java")).toList())result.put(file.toString(),Files.readString(file));
        }
        return result;
    }
}
