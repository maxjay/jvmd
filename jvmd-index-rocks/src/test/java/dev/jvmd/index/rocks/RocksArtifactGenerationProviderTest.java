package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksArtifactGenerationProviderTest {
    @TempDir Path temp;

    @Test void serviceLoaderSelectsRocksAndSupportsExplicitRollback()throws Exception{
        try(var sink=ArtifactGenerationSink.open("rocksdb-sst",temp.resolve("rocks"),8L*1024*1024)){
            assertThat(sink.status()).containsEntry("backend","rocksdb-sst");
        }
        try(var sink=ArtifactGenerationSink.open("none",temp.resolve("none"),8L*1024*1024)){
            assertThat(sink.status()).containsEntry("backend","none");
        }
    }
    @Test void candidateIsNotActivatedWhenInventoryReferencesMissingGeneration()throws Exception{
        Path root=temp.resolve("invalid-candidate");
        var migration=new RocksMigrationManager(root);
        String generation="format-"+ArtifactIndexFormat.FORMAT_VERSION+"-jdk"+Runtime.version().feature()+"-"+ArtifactIndexFormat.INDEXER_VERSION;
        Path file=Files.writeString(temp.resolve("missing.jar"),"not-a-real-jar");
        try(var sink=new RocksArtifactGenerationSink(migration.candidate(generation),8L*1024*1024,migration,generation)){
            long scan=sink.beginScan();
            var key=new ArtifactIndexFormat.Key("a".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                    ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
            var input=new IndexStore.ArtifactInput(new ArtifactContext("g:a:1","jar",file.toAbsolutePath().toString()),
                    key,Files.size(file),Files.getLastModifiedTime(file).toMillis()*1_000_000L);
            sink.observe(scan,input);
            assertThatThrownBy(()->sink.completeScan(scan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("candidate validation failed");
            assertThat(migration.manifest().active()).isEmpty();
            assertThat(sink.status()).containsEntry("validation_failures",1L);
        }
    }

    @Test void shadowSearchUsesInventoryBackedWorkspaceAndStableScipIdentity()throws Exception{
        Path root=temp.resolve("shadow");
        Path jar=Files.writeString(temp.resolve("dep.jar"),"fixture");
        var key=new ArtifactIndexFormat.Key("b".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"dep.Type","dep.Type","Type","class",
                "class dep.Type",null,1,"dep/Type.class",java.util.List.of(),"{}");
        var facts=new ArtifactIndexFormat.ArtifactData(key,java.util.List.of(symbol),java.util.List.of());

        try(var sink=new RocksArtifactGenerationSink(root,8L*1024*1024)){
            sink.publish(facts,java.util.Set.of());
            long scan=sink.beginScan();
            var input=new IndexStore.ArtifactInput(new ArtifactContext("fixture:dep:1","jar",jar.toAbsolutePath().toString()),
                    key,Files.size(jar),Files.getLastModifiedTime(jar).toMillis()*1_000_000L);
            sink.observe(scan,input);
            sink.completeScan(scan);
            sink.configureWorkspace("workspace",java.util.List.of(new IndexStore.WorkspaceEntry(jar.toString(),"compile")),java.util.List.of());

            var exact=sink.shadowFind("workspace","Type",false,10,java.util.Set.of("class")).orElseThrow();
            assertThat(exact).hasSize(1);
            assertThat(exact.getFirst().get("scip")).isEqualTo(new ArtifactContext("fixture:dep:1","jar",jar.toAbsolutePath().toString()).scip(symbol));

            var substring=sink.shadowFind("workspace","ype",true,10,java.util.Set.of()).orElseThrow();
            assertThat(substring).hasSize(1);
            assertThat(sink.status()).containsEntry("shadow_workspaces",1);
        }
    }

}
