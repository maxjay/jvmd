package dev.jvmd.tests;

import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class RocksArtifactSinkIntegrationTest {
    @TempDir Path temp;

    @Test void existingSqliteCacheBackfillsANewGenerationWithoutRewritingSqliteSymbols()throws Exception{
        Path repository=temp.resolve("upgrade-repository"),database=temp.resolve("upgrade.db");
        IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        long symbolId;
        try(var index=new IndexService(database,repository)){
            index.scan();symbolId=((Number)index.find("transform",null,false,10,0).getFirst().get("id")).longValue();
        }
        var sink=new RocksArtifactGenerationSink(temp.resolve("upgrade-rocks"),8L*1024*1024);
        try(var index=new IndexService(database,repository,sink)){
            index.scan();assertThat(sink.status()).containsEntry("published",1L).containsEntry("inventory_entries",1);
            assertThat(((Number)index.find("transform",null,false,10,0).getFirst().get("id")).longValue()).isEqualTo(symbolId);
            index.scan();assertThat(sink.status()).containsEntry("published",1L);
        }
    }

    @Test void repositoryScanCanDualPublishWithoutChangingSqliteSemantics()throws Exception{
        Path repository=temp.resolve("repository");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        var sink=new RocksArtifactGenerationSink(temp.resolve("rocks"),8L*1024*1024);
        try(var index=new IndexService(temp.resolve("index.db"),repository,sink)){
            index.indexJar(jar,"fixture:sample:1","jar");
            assertThat(index.find("transform",null,false,10,0)).hasSize(1);
            @SuppressWarnings("unchecked") var status=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)status.get("published")).longValue()).isEqualTo(1L);

            index.indexJar(jar,"fixture:sample:1","jar");
            @SuppressWarnings("unchecked") var repeated=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)repeated.get("published")).longValue()).isEqualTo(1L);
        }
    }

    @Test void repositoryScanMaintainsInventoryWithoutRewritingUnchangedArtifacts()throws Exception{
        Path repository=temp.resolve("scan-repository");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        try(var sink=new RocksArtifactGenerationSink(temp.resolve("scan-rocks"),8L*1024*1024);
            var index=new IndexService(temp.resolve("scan-index.db"),repository,sink)){
            index.scan();
            @SuppressWarnings("unchecked") var first=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)first.get("published")).longValue()).isEqualTo(1L);
            assertThat(((Number)first.get("inventory_entries")).longValue()).isEqualTo(1L);

            index.scan();
            @SuppressWarnings("unchecked") var unchanged=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)unchanged.get("published")).longValue()).isEqualTo(1L);
            assertThat(((Number)unchanged.get("inventory_entries")).longValue()).isEqualTo(1L);

            Files.delete(jar);
            index.scan();
            @SuppressWarnings("unchecked") var deleted=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)deleted.get("inventory_entries")).longValue()).isZero();
        }
    }

    @Test void failedSkeletonScanDoesNotReconcileAwayPriorInventory()throws Exception{
        Path repository=temp.resolve("failed-repository");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        try(var sink=new RocksArtifactGenerationSink(temp.resolve("failed-rocks"),8L*1024*1024);
            var index=new IndexService(temp.resolve("failed-index.db"),repository,sink)){
            index.scan();
            @SuppressWarnings("unchecked") var first=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)first.get("inventory_entries")).longValue()).isEqualTo(1L);

            Files.writeString(jar,"not-a-jar");
            index.scan();
            @SuppressWarnings("unchecked") var failed=(java.util.Map<String,Object>)index.status().get("generation_sink");
            assertThat(((Number)failed.get("inventory_entries")).longValue()).isEqualTo(1L);
            assertThat(((Number)index.status().get("faults")).longValue()).isGreaterThan(0L);
        }
    }

}
