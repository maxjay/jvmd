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
        // A cold boot creates and writes its layer; reading prior state is the warm boot's job.
        var lookup=Pattern.compile("\\.(contains|committedRoot|open|store|inventory|machine|tree|semanticTree|artifacts?|find)\\(");
        for(var source:sources(BOOT.resolve("cold")).entrySet()){
            var matcher=lookup.matcher(source.getValue());
            assertThat(matcher.find()).as(()->source.getKey()+" calls "+matcher.group()).isFalse();
        }
    }

    @Test void onlyTheColdBootCreateStageCreatesStorage()throws Exception{
        var creators=new TreeSet<String>();
        for(String module:List.of("jvmd-core","jvmd-index","jvmd-index-rocks","jvmd-analyzer","jvmd-boot","jvmd-dist"))
            sources(TestSupport.repo().resolve(module).resolve("src/main/java")).forEach((file,text)->{
                if(text.contains("setCreateIfMissing(true)"))creators.add(Path.of(file).getFileName().toString());
            });
        // RocksMemory.creating is the create stage's option set; BindingFacts is replaced by the LIVE task.
        assertThat(creators).containsExactly("BindingFacts.java","RocksMemory.java");
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
