package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Reproducible SQLite control measurements for the immutable-index redesign. */
@Tag("phase-3")
class IndexRebuildBaselineTest {
    @TempDir Path temp;

    @Test void recordsThreeFreshRunsRestartAndOneArtifactReplacement()throws Exception{
        Path repository=Files.createDirectories(temp.resolve("repository"));
        for(int i=0;i<6;i++){
            Path version=repository.resolve("fixture/sample"+i+"/1");
            String source=IndexFixtures.generic().replace("private String hidden;","private String hidden; public int marker"+i+";");
            IndexFixtures.jar(version,"sample"+i+"-1",source,false);
        }

        var manifest=manifest(repository);
        var runs=new ArrayList<Map<String,Object>>();
        Map<String,Long> expectedCounts=null;
        for(int i=0;i<3;i++){
            Path db=temp.resolve("fresh-"+i).resolve("index.db");
            var measurement=measure(db,repository);
            @SuppressWarnings("unchecked") var counts=(Map<String,Long>)measurement.get("counts");
            if(expectedCounts==null)expectedCounts=counts;else assertThat(counts).isEqualTo(expectedCounts);
            runs.add(measurement);
        }

        Path restartDb=temp.resolve("restart").resolve("index.db");
        measure(restartDb,repository);
        var restart=measure(restartDb,repository);
        @SuppressWarnings("unchecked") var restartStatus=(Map<String,Object>)restart.get("status");
        assertThat(((Number)restartStatus.get("indexed")).longValue()).as("unchanged restart indexed artifacts").isZero();
        assertThat(((Number)restartStatus.get("hashes")).longValue()).as("unchanged release artifacts rehashed").isZero();

        Path changedDir=repository.resolve("fixture/sample0/1");
        IndexFixtures.jar(changedDir,"sample0-1",
                IndexFixtures.generic().replace("private String hidden;","private String hidden; public long added;"),false);
        var update=measure(restartDb,repository);
        @SuppressWarnings("unchecked") var updateStatus=(Map<String,Object>)update.get("status");
        assertThat(((Number)updateStatus.get("indexed")).longValue()).as("one replaced binary should be reindexed").isEqualTo(1L);
        assertThat(((Number)updateStatus.get("hashes")).longValue()).isGreaterThanOrEqualTo(2L);

        var report=new LinkedHashMap<String,Object>();
        report.put("revision",System.getenv().getOrDefault("GITHUB_SHA","local"));
        report.put("jdk",System.getProperty("java.runtime.version"));
        report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
        report.put("filesystem",Files.getFileStore(repository).type());
        report.put("repository",repository.toString());
        report.put("repository_manifest",manifest);
        report.put("fresh_runs",List.copyOf(runs));
        report.put("unchanged_restart",restart);
        report.put("one_artifact_update",update);
        Path output=TestSupport.repo().resolve("jvmd-tests/target/index-rebuild-baseline.json");
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        System.out.println("index-rebuild-baseline "+Json.MAPPER.writeValueAsString(report));
    }

    private static Map<String,Object> measure(Path db,Path repository)throws Exception{
        long started=System.nanoTime();
        Map<String,Object> status;
        List<Double> latencies=new ArrayList<>();
        try(var index=new IndexService(db,repository)){
            index.scan();
            for(int i=0;i<15;i++){
                long queryStarted=System.nanoTime();
                var result=index.find("transform",null,false,100,0);
                latencies.add((System.nanoTime()-queryStarted)/1e6);
                assertThat(result).hasSize(6);
            }
            status=index.status();
        }
        Collections.sort(latencies);
        long finalBytes=storageBytes(db);
        var counts=new LinkedHashMap<String,Long>();
        for(String key:List.of("artifacts","symbols","edges","simple_names"))
            counts.put(key,((Number)status.get(key)).longValue());
        return Map.of(
                "elapsed_ms",(System.nanoTime()-started)/1e6,
                "query_p50_ms",latencies.get(latencies.size()/2),
                "query_p95_ms",latencies.get((int)Math.ceil(latencies.size()*.95)-1),
                "final_storage_bytes",finalBytes,
                "counts",Map.copyOf(counts),
                "status",status);
    }

    private static List<Map<String,Object>> manifest(Path repository)throws Exception{
        var result=new ArrayList<Map<String,Object>>();
        try(var files=Files.walk(repository)){
            for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")).sorted().toList())
                result.add(Map.of("path",repository.relativize(file).toString(),"size",Files.size(file)));
        }
        return List.copyOf(result);
    }

    private static long storageBytes(Path db)throws Exception{
        long bytes=0;
        for(Path file:List.of(db,db.resolveSibling(db.getFileName()+"-wal"),db.resolveSibling(db.getFileName()+"-shm")))
            if(Files.exists(file))bytes+=Files.size(file);
        return bytes;
    }
}
