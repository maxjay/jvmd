package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksIndexStorageProviderTest {
    @TempDir Path temp;

    @Test void candidateValidationStreamsOnReopenAndReusesOwnedPublicationProof()throws Exception{
        var key=ArtifactIndexFormat.key("a".repeat(64),"signatures");
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"dep.Type","dep.Type","Type","class","class dep.Type",null,1,"dep/Type.class",java.util.List.of(),"{}");
        var facts=new ArtifactIndexFormat.ArtifactData(key,java.util.List.of(symbol),java.util.List.of());
        Path jar=Files.writeString(temp.resolve("candidate.jar"),"fixture");
        var input=new IndexStore.ArtifactInput(new ArtifactContext("fixture:dep:1","jar",jar.toString()),key,Files.size(jar),1);
        for(boolean reopen:java.util.List.of(false,true)){
            Path root=temp.resolve(reopen?"reopened":"owned");
            try(var storage=IndexStorage.open(root,8L*1024*1024)){
                storage.store().publishBinary(input,facts,java.util.Set.of());long scan=storage.inventory().beginScan();storage.inventory().observe(scan,input);
                if(!reopen){
                    storage.inventory().completeScan(scan);
                    assertThat(repositoryStatus(storage)).containsEntry("native_publication_verifications",1L).containsEntry("verification_passes",0L).containsEntry("activation_verification_reuses",1L).containsEntry("oracle_materializations",0L);
                }
            }
            if(reopen)try(var storage=IndexStorage.open(root,8L*1024*1024)){
                long scan=storage.inventory().beginScan();storage.inventory().observe(scan,input);storage.inventory().completeScan(scan);
                assertThat(repositoryStatus(storage)).containsEntry("native_publication_verifications",0L).containsEntry("verification_passes",1L).containsEntry("activation_verification_reuses",0L).containsEntry("oracle_materializations",0L);
            }
            assertThat(new RocksMigrationManager(root).manifest().active()).isNotEmpty();
        }
    }
    @SuppressWarnings("unchecked") private static java.util.Map<String,Object> repositoryStatus(IndexStorage storage){
        return (java.util.Map<String,Object>)storage.status().get("repository");
    }

    @Test void serviceLoaderOpensOneAuthoritativeStore()throws Exception{
        try(var storage=IndexStorage.open(temp.resolve("rocks"),8L*1024*1024)){
            assertThat(storage.status()).containsEntry("backend","rocksdb-sst");
            assertThat(storage.store()).isSameAs(storage.store());
            assertThat(storage.store().backend()).isEqualTo("rocksdb-sst");
        }
    }
    @Test void rejectedPublicationKeepsThePreviousManifestAcrossReopen()throws Exception{
        Path root=temp.resolve("failed-publication"),file=temp.resolve("artifact.jar");
        var original=ArtifactIndexFormat.key("a".repeat(64),"signatures");
        var replacement=ArtifactIndexFormat.key("b".repeat(64),"signatures");
        var context=new ArtifactContext("fixture:artifact:1","jar",file.toString());
        var facts=new ArtifactIndexFormat.ArtifactData(original,java.util.List.of(),java.util.List.of());
        try(var storage=IndexStorage.open(root,8L*1024*1024)){
            long id=storage.store().publishBinary(new IndexStore.ArtifactInput(context,original,0,0),facts,java.util.Set.of());
            assertThatThrownBy(()->storage.store().publishBinary(new IndexStore.ArtifactInput(context,replacement,0,0),facts,java.util.Set.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("facts/key mismatch");
            assertThat(storage.store().artifact(file).id()).isEqualTo(id);
            assertThat(storage.store().artifact(file).sha256()).isEqualTo(original.binarySha256());
            assertThat(storage.status()).containsEntry("estimated_bytes_in_flight",0L);
        }
        try(var storage=IndexStorage.open(root,8L*1024*1024)){
            assertThat(storage.store().artifact(file).sha256()).isEqualTo(original.binarySha256());
            assertThat(storage.store().counts()).containsEntry("artifacts",1L);
        }
    }

    @Test void candidateIsNotActivatedWhenInventoryReferencesMissingGeneration()throws Exception{
        Path root=temp.resolve("invalid-candidate");
        var migration=new RocksMigrationManager(root);
        String generation="format-"+ArtifactIndexFormat.FORMAT_VERSION+"-jdk"+Runtime.version().feature()+"-"+ArtifactIndexFormat.INDEXER_VERSION;
        Path file=Files.writeString(temp.resolve("missing.jar"),"not-a-real-jar");
        try(var storage=new RocksIndexStorage(migration.candidate(generation),8L*1024*1024,migration,generation)){
            long scan=storage.inventory().beginScan();
            var key=new ArtifactIndexFormat.Key("a".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                    ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
            var input=new IndexStore.ArtifactInput(new ArtifactContext("g:a:1","jar",file.toAbsolutePath().toString()),
                    key,Files.size(file),Files.getLastModifiedTime(file).toMillis()*1_000_000L);
            storage.inventory().observe(scan,input);
            assertThatThrownBy(()->storage.inventory().completeScan(scan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("candidate validation failed");
            assertThat(migration.manifest().active()).isEmpty();
            assertThat(storage.status()).containsEntry("validation_failures",1L);
        }
    }

}
