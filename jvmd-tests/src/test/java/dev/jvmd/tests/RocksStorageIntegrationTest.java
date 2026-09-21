package dev.jvmd.tests;

import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class RocksStorageIntegrationTest {
    @TempDir Path temp;

    @Test void unchangedRestartDoesNotLinkAndDeletedJarDisappearsFromTheStore()throws Exception{
        Path repository=temp.resolve("incremental-repository"),rocks=temp.resolve("incremental-rocks");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        try(var index=new IndexService(new RocksIndexStorage(rocks,8L*1024*1024),repository)){
            index.scan();assertThat(index.store().status()).containsEntry("link_passes",0L);
            index.scan();assertThat(index.store().status()).containsEntry("link_passes",0L);
        }
        try(var index=new IndexService(new RocksIndexStorage(rocks,8L*1024*1024),repository)){
            index.scan();assertThat(index.store().status()).containsEntry("link_passes",0L);
            assertThat(index.find("transform",null,false,10,0)).hasSize(1);
            Files.delete(jar);Files.deleteIfExists(jar.resolveSibling("sample-1-sources.jar"));
            index.scan();assertThat(index.find("transform",null,false,10,0)).isEmpty();
            assertThat(index.store().status()).containsEntry("link_passes",0L);
        }
    }

    @Test void legacySqliteCacheIsUntouchedWhileRocksRebuildsFromArtifacts()throws Exception{
        Path repository=temp.resolve("legacy-repo"),database=temp.resolve("index.db");
        Path jar=IndexFixtures.jar(repository,"legacy",IndexFixtures.generic(),false);
        byte[] original="legacy cache must not be opened or rewritten".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database,original);
        try(var index=new IndexService(database,repository)){
            index.indexJar(jar,"fixture:legacy:1","jar");
            assertThat(index.find("transform",null,false,10,0)).hasSize(1);
            assertThat(index.status()).doesNotContainKeys("generation_sink","shadow_validation");
        }
        assertThat(Files.readAllBytes(database)).isEqualTo(original);
        assertThat(temp.resolve("index-v2")).isDirectory();
    }

    @Test void repositoryPublicationHasOneOwner()throws Exception{
        Path repository=temp.resolve("repository");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        var storage=new RocksIndexStorage(temp.resolve("rocks"),8L*1024*1024);
        try(var index=new IndexService(storage,repository)){
            index.indexJar(jar,"fixture:sample:1","jar");
            assertThat(index.find("transform",null,false,10,0)).hasSize(1);
            @SuppressWarnings("unchecked") var status=(java.util.Map<String,Object>)index.status().get("storage");
            assertThat(((Number)((java.util.Map<?,?>)status.get("repository")).get("published")).longValue()).isEqualTo(1L);

            assertThat((java.util.Map<?,?>)status.get("repository")).extracting(m->m.get("reused")).isEqualTo(0L);
            index.indexJar(jar,"fixture:sample:1","jar");
            @SuppressWarnings("unchecked") var repeated=(java.util.Map<String,Object>)index.status().get("storage");
            assertThat(((Number)((java.util.Map<?,?>)repeated.get("repository")).get("published")).longValue()).isEqualTo(1L);
        }
    }

    @Test void repositoryScanMaintainsInventoryWithoutRewritingUnchangedArtifacts()throws Exception{
        Path repository=temp.resolve("scan-repository");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        try(var storage=new RocksIndexStorage(temp.resolve("scan-rocks"),8L*1024*1024);
            var index=new IndexService(storage,repository)){
            index.scan();
            @SuppressWarnings("unchecked") var first=(java.util.Map<String,Object>)index.status().get("storage");
            assertThat(((Number)((java.util.Map<?,?>)first.get("repository")).get("published")).longValue()).isEqualTo(1L);
            assertThat(((Number)first.get("inventory_entries")).longValue()).isEqualTo(1L);

            index.scan();
            @SuppressWarnings("unchecked") var unchanged=(java.util.Map<String,Object>)index.status().get("storage");
            assertThat(((Number)((java.util.Map<?,?>)unchanged.get("repository")).get("published")).longValue()).isEqualTo(1L);
            assertThat(((Number)unchanged.get("inventory_entries")).longValue()).isEqualTo(1L);

            Files.delete(jar);
            index.scan();
            @SuppressWarnings("unchecked") var deleted=(java.util.Map<String,Object>)index.status().get("storage");
            assertThat(((Number)deleted.get("inventory_entries")).longValue()).isZero();
        }
    }

    @Test void failedSkeletonScanDoesNotReconcileAwayPriorInventory()throws Exception{
        Path repository=temp.resolve("failed-repository");
        Path jar=IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
        try(var storage=new RocksIndexStorage(temp.resolve("failed-rocks"),8L*1024*1024);
            var index=new IndexService(storage,repository)){
            index.scan();
            @SuppressWarnings("unchecked") var first=(java.util.Map<String,Object>)index.status().get("storage");
            assertThat(((Number)first.get("inventory_entries")).longValue()).isEqualTo(1L);

            Files.writeString(jar,"not-a-jar");
            index.scan();
            @SuppressWarnings("unchecked") var failed=(java.util.Map<String,Object>)index.status().get("storage");
            assertThat(((Number)failed.get("inventory_entries")).longValue()).isEqualTo(1L);
            assertThat(((Number)index.status().get("faults")).longValue()).isGreaterThan(0L);
        }
    }

}
