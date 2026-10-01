package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict task W5 / A1: a no-change warm restart validates linearly from one shared snapshot. Every
 * stat, hash and directory enumeration of the restarted process goes through one journaled
 * {@link FileStateRegistry}, so its counters are the whole cost.
 */
class WarmRestartGateTest {
    static final int UNITS=Integer.getInteger("jvmd.scenario.units",200);
    private static final String GAV="g:warm:1";
    @TempDir Path root;

    /**
     * {@code files} counts every file the restart validated (the registry's tracked observations: the
     * units plus the JDK platform files); {@code units} is the fixture's source files alone.
     */
    record Restart(long javac,long restores,long stats,long enumerations,long bytesHashed,long nanos,int files,int directories,int units) { }

    private Analyzer analyzer(FileStateRegistry files)throws Exception{
        var analyzer=new Analyzer(files);Path module=root.resolve("app"),sources=root.resolve("app/src/main/java");
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"warm:"+GAV+":main",
                Map.of(module.toString(),GAV,sources.toString(),GAV),List.of("--release","25"),Set.of(),List.of(),List.of(sources),true,""),
                null,512L*1024*1024);
        var documents=new Documents(files);opened.add(documents);analyzer.documents(documents);
        analyzer.memos(new SemanticMemoStore(root.resolve("state/local-memo")));
        return analyzer;
    }
    /** Live source states watch the fixture; close them so no watcher writes state after a session. */
    private final List<Documents> opened=new ArrayList<>();
    @org.junit.jupiter.api.AfterEach void closeDocuments(){opened.forEach(Documents::close);opened.clear();}
    private FileStateRegistry registry(){var files=new FileStateRegistry();files.persistence(root.resolve("state/file-observations-v1.bin"));return files;}
    private static long number(Map<String,Object> status,String key){return ((Number)status.get(key)).longValue();}
    @SuppressWarnings("unchecked")
    private static long memo(Analyzer analyzer,String key){return number((Map<String,Object>)analyzer.status().get("attributed_memo"),key);}

    /** Seed, then one no-change restart; returns the restart's counters. */
    Restart measure(List<SyntheticProjects.Unit> units)throws Exception{
        Path sources=Files.createDirectories(root.resolve("app/src/main/java"));
        var files=List.copyOf(SyntheticProjects.write(sources,units).values());
        int directories;try(var walk=Files.walk(sources)){directories=(int)walk.filter(Files::isDirectory).count();}
        // Observations taken inside the racy timestamp window are never reused; let it pass honestly.
        Thread.sleep(FileStateRegistry.DEFAULT_RACY_WINDOW_NANOS/1_000_000+100);
        var seedFiles=registry();
        try(var seed=analyzer(seedFiles)){
            for(Path file:files)seed.diagnostics(file,Files.readString(file));
            seed.awaitMemoWrites();
        }
        seedFiles.flushObservations();

        var restartFiles=registry();long started=System.nanoTime();
        try(var restarted=analyzer(restartFiles)){
            for(Path file:files)restarted.diagnostics(file,Files.readString(file));
            long nanos=System.nanoTime()-started;
            var status=restartFiles.status();
            return new Restart(((Number)restarted.status().get("queries")).longValue(),memo(restarted,"restores"),
                    number(status,"metadata_checks"),number(status,"directory_enumerations"),number(status,"bytes_hashed"),nanos,
                    (int)number(status,"entries"),directories,files.size());
        }
    }

    @Test void a12CorruptMemoJournalAndInventoryMissThenRebuild()throws Exception{
        Path sources=Files.createDirectories(root.resolve("app/src/main/java"));
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.LAYERED,60,SyntheticProjects.SEED);
        var files=List.copyOf(SyntheticProjects.write(sources,units).values());
        Files.writeString(files.getLast(),Files.readString(files.getLast()).replace("return 1;","return \"wrong\";"));
        var expected=new HashMap<Path,String>();
        var seedFiles=registry();
        try(var seed=analyzer(seedFiles)){
            for(Path file:files)expected.put(file,seed.diagnostics(file,Files.readString(file)).result().toString());
            seed.awaitMemoWrites();
        }
        seedFiles.flushObservations();
        assertThat(expected.get(files.getLast())).contains("compiler.err.prob.found.req");

        // Flip bytes in the middle of every memo record and both journals.
        var damaged=new ArrayList<Path>();
        try(var walk=Files.walk(root.resolve("state"))){walk.filter(Files::isRegularFile).forEach(damaged::add);}
        assertThat(damaged).anyMatch(path->path.toString().endsWith(".memo")).anyMatch(path->path.toString().endsWith(".bin"))
                .anyMatch(path->path.toString().endsWith(".bin.dirs"));
        for(Path file:damaged){byte[] bytes=Files.readAllBytes(file);if(bytes.length>16){bytes[bytes.length/2]^=0x5a;bytes[bytes.length-3]^=0x33;Files.write(file,bytes);}}

        var rebuiltFiles=registry();
        try(var rebuilt=analyzer(rebuiltFiles)){
            for(Path file:files)assertThat(rebuilt.diagnostics(file,Files.readString(file)).result().toString()).as(file.toString()).isEqualTo(expected.get(file));
            assertThat(((Number)rebuilt.status().get("queries")).longValue()).as("corruption is a miss").isPositive();
            rebuilt.awaitMemoWrites();
        }
        rebuiltFiles.flushObservations();
        try(var again=analyzer(registry())){
            for(Path file:files)assertThat(again.diagnostics(file,Files.readString(file)).result().toString()).isEqualTo(expected.get(file));
            assertThat(((Number)again.status().get("queries")).longValue()).as("rebuilt state is reused").isZero();
        }
    }

    @Test void noChangeWarmRestartIsLinearAndEnumeratesNothing()throws Exception{
        var units=SyntheticProjects.generate(SyntheticProjects.Topology.RANDOM_DAG,UNITS,SyntheticProjects.SEED);
        var restart=measure(units);
        System.out.println("W5 warm restart "+restart);
        assertThat(restart.javac()).as("A1 javac").isZero();
        assertThat(restart.restores()).as("A1 restores").isEqualTo(units.size());
        assertThat(restart.bytesHashed()).as("A1 bytes hashed").isZero();
        assertThat(restart.enumerations()).as("A1 directory enumerations").isZero();
        assertThat(restart.files()).as("validated files are the units plus the JDK platform files").isEqualTo(restart.units()+3);
        assertThat(restart.stats()).as("W5 stats <= 2 x files + directories").isLessThanOrEqualTo(2L*restart.files()+restart.directories());
    }
}
