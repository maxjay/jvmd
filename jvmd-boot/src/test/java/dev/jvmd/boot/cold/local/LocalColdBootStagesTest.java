package dev.jvmd.boot.cold.local;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.boot.cold.machine.MachineColdBoot;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.core.Hashing;
import dev.jvmd.index.layer.local.LocalFile;
import dev.jvmd.resolver.Resolution;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The LOCAL cold boot reads each source file once: javac compiles the text the boot read, from the
 * boot's own source path, and nothing hashes, lists or watches the sources again.
 */
class LocalColdBootStagesTest {
    private static final long BUDGET=32L*1024*1024;
    @TempDir Path temp;

    @Test void declarationsCompileTheTextTheBootReadAndNeverReadOrHashItAgain()throws Exception{
        Path project=Files.createDirectories(temp.resolve("project")).toRealPath();
        Path square=write(project.resolve("lib/src/main/java/lib/Square.java"),"package lib; public class Square { public double area(){ return 4; } }");
        Path main=write(project.resolve("app/src/main/java/app/Main.java"),"package app; import lib.Square; public class Main { public double run(){ return new Square().area(); } }");
        var lib=new Resolution.Module("g:lib:1",project.resolve("lib").toString(),"jar",List.of(project.resolve("lib/src/main/java").toString()),List.of(),
                project.resolve("lib/target/classes").toString(),project.resolve("lib/target/test-classes").toString(),"25",List.of());
        var app=new Resolution.Module("g:app:1",project.resolve("app").toString(),"jar",List.of(project.resolve("app/src/main/java").toString()),List.of(),
                project.resolve("app/target/classes").toString(),project.resolve("app/target/test-classes").toString(),"25",List.of("g:lib:1"));
        var resolution=new Resolution(project.toString(),List.of(app,lib),
                List.of(new Resolution.Node("g:app:1|g:lib:1","g:lib:1","jar","","compile",lib.classes(),null,"",false)),List.of(),
                Map.of("g:lib:1:main",List.of(lib.classes()),"g:app:1:main",List.of(app.classes(),lib.classes())),List.of(),"fixture",true,false);
        // Siblings are not built, so app's compiler reads lib from its sources, as analysis does.
        LocalColdBoot.Contexts contexts=(module,test)->{
            var sources=new ArrayList<Path>();module.sources().forEach(root->sources.add(Path.of(root)));
            if(module.gav().equals("g:app:1"))lib.sources().forEach(root->sources.add(Path.of(root)));
            return new Analyzer.Context(module.gav(),"25",List.of(),sources,"fixture:"+module.gav(),Map.of());
        };
        Files.createDirectories(temp.resolve("repository"));
        try(var storage=new MachineColdBoot(temp.resolve("generation"),temp.resolve("repository"),BUDGET).run();
            var boot=new LocalColdBoot(storage.generation(),project,()->resolution,contexts,storage.machine(),storage,BUDGET)){
            boot.create();boot.moduleGraph();boot.routes();boot.files();
            // Every file the boot read is recorded with the hash of the bytes it read.
            var states=FileStateRegistry.shared();long hashes=hashes(states);
            for(Path file:List.of(square,main))assertThat(states.hash(file)).isEqualTo(Hashing.sha256(Files.readAllBytes(file)));
            assertThat(hashes(states)).isEqualTo(hashes);
            // The source javac needs for app is gone from disk: only the text the boot read can resolve it.
            String squareHash=states.hash(square);Files.delete(square);
            long enumerations=enumerations(states);hashes=hashes(states);
            var watchers=watchers();
            boot.declarations();
            assertThat(hashes(states)).as("no source hashed again").isEqualTo(hashes);
            assertThat(enumerations(states)).as("no source root listed").isEqualTo(enumerations);
            assertThat(watchers()).as("no source watcher").isEqualTo(watchers);
            assertThat(boot.faults()).isEmpty();
            var leaves=new TreeMap<String,LocalFile>();for(var leaf:boot.buildTree().files().files())leaves.put(leaf.path(),leaf);
            assertThat(leaves).containsOnlyKeys("app/src/main/java/app/Main.java","lib/src/main/java/lib/Square.java");
            assertThat(leaves.get("lib/src/main/java/lib/Square.java").content()).isEqualTo(squareHash);
        }
    }

    private static Path write(Path file,String text)throws Exception{Files.createDirectories(file.getParent());return Files.writeString(file,text);}
    private static long hashes(FileStateRegistry states){return ((Number)states.status().get("hashes")).longValue();}
    private static long enumerations(FileStateRegistry states){return ((Number)states.status().get("directory_enumerations")).longValue();}
    private static Set<String> watchers(){
        var names=new TreeSet<String>();
        for(var thread:Thread.getAllStackTraces().keySet())if(thread.getName().startsWith("jvmd-source-state-"))names.add(thread.getName());
        return names;
    }
}
