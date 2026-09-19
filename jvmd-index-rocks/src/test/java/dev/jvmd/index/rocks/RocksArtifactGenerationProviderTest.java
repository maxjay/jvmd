package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksArtifactGenerationProviderTest {
    @TempDir Path temp;

    @Test void candidateValidationStreamsOnReopenAndReusesOwnedPublicationProof()throws Exception{
        var key=ArtifactIndexFormat.key("a".repeat(64),"signatures");
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"dep.Type","dep.Type","Type","class","class dep.Type",null,1,"dep/Type.class",java.util.List.of(),"{}");
        var facts=new ArtifactIndexFormat.ArtifactData(key,java.util.List.of(symbol),java.util.List.of());
        Path jar=Files.writeString(temp.resolve("candidate.jar"),"fixture");
        var input=new IndexStore.ArtifactInput(new ArtifactContext("fixture:dep:1","jar",jar.toString()),key,Files.size(jar),1);
        for(boolean reopen:java.util.List.of(false,true)){
            Path root=temp.resolve(reopen?"reopened":"owned");
            try(var sink=ArtifactGenerationSink.open("rocksdb-sst",root,8L*1024*1024)){
                sink.publish(facts,java.util.Set.of());long scan=sink.beginScan();sink.observe(scan,input);
                if(!reopen){
                    sink.completeScan(scan);
                    assertThat(repositoryStatus(sink)).containsEntry("native_publication_verifications",1L).containsEntry("verification_passes",0L).containsEntry("activation_verification_reuses",1L).containsEntry("oracle_materializations",0L);
                }
            }
            if(reopen)try(var sink=ArtifactGenerationSink.open("rocksdb-sst",root,8L*1024*1024)){
                long scan=sink.beginScan();sink.observe(scan,input);sink.completeScan(scan);
                assertThat(repositoryStatus(sink)).containsEntry("native_publication_verifications",0L).containsEntry("verification_passes",1L).containsEntry("activation_verification_reuses",0L).containsEntry("oracle_materializations",0L);
            }
            assertThat(new RocksMigrationManager(root).manifest().active()).isNotEmpty();
        }
    }
    @SuppressWarnings("unchecked") private static java.util.Map<String,Object> repositoryStatus(ArtifactGenerationSink sink){
        return (java.util.Map<String,Object>)sink.status().get("repository");
    }

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
            assertThat(sink.shadowById("workspace",((Number)exact.getFirst().get("id")).longValue()))
                    .contains(exact.getFirst());
            assertThat(sink.shadowById("workspace",0)).isEmpty();
            assertThat(sink.shadowById("workspace",2L<<32)).isEmpty();

            var substring=sink.shadowFind("workspace","ype",true,10,java.util.Set.of()).orElseThrow();
            assertThat(substring).hasSize(1);
            assertThat(sink.status()).containsEntry("shadow_workspaces",1);
        }
    }

    @Test void shadowRelationshipsReturnStableScipEdges()throws Exception{
        Path root=temp.resolve("shadow-relationships");
        Path sourceJar=Files.writeString(temp.resolve("source.jar"),"source");
        Path targetJar=Files.writeString(temp.resolve("target.jar"),"target");
        var sourceKey=new ArtifactIndexFormat.Key("c".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var targetKey=new ArtifactIndexFormat.Key("d".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var sourceSymbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"source.Type","source.Type","Type","class",
                "class source.Type",null,1,"source/Type.class",java.util.List.of(),"{}");
        var targetSymbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"target.Type","target.Type","Type","class",
                "class target.Type",null,1,"target/Type.class",java.util.List.of(),"{}");
        var sourceFacts=new ArtifactIndexFormat.ArtifactData(sourceKey,java.util.List.of(sourceSymbol),
                java.util.List.of(new ArtifactIndexFormat.Relationship(0,"target.Type","extends")));
        var targetFacts=new ArtifactIndexFormat.ArtifactData(targetKey,java.util.List.of(targetSymbol),java.util.List.of());

        try(var sink=new RocksArtifactGenerationSink(root,8L*1024*1024)){
            sink.publish(sourceFacts,java.util.Set.of("target.Type"));sink.publish(targetFacts,java.util.Set.of());
            long scan=sink.beginScan();
            var sourceContext=new ArtifactContext("fixture:source:1","jar",sourceJar.toAbsolutePath().toString());
            var targetContext=new ArtifactContext("fixture:target:1","jar",targetJar.toAbsolutePath().toString());
            sink.observe(scan,new IndexStore.ArtifactInput(sourceContext,sourceKey,Files.size(sourceJar),1));
            sink.observe(scan,new IndexStore.ArtifactInput(targetContext,targetKey,Files.size(targetJar),1));
            sink.completeScan(scan);
            sink.configureWorkspace("workspace",java.util.List.of(
                    new IndexStore.WorkspaceEntry(sourceJar.toString(),"compile"),
                    new IndexStore.WorkspaceEntry(targetJar.toString(),"compile")),java.util.List.of());

            String sourceScip=sourceContext.scip(sourceSymbol),targetScip=targetContext.scip(targetSymbol);
            assertThat(sink.shadowRelationships("workspace",java.util.List.of(sourceScip),true,java.util.Set.of("extends"),10).orElseThrow())
                    .containsExactly(sourceScip+"|extends|"+targetScip);
            assertThat(sink.shadowRelationships("workspace",java.util.List.of(targetScip),false,java.util.Set.of("extends"),10).orElseThrow())
                    .containsExactly(sourceScip+"|extends|"+targetScip);
        }
    }

    @Test void shadowSearchMaterializesActiveDocumentationOverlay()throws Exception{
        Path root=temp.resolve("shadow-docs");
        Path jar=Files.writeString(temp.resolve("documented.jar"),"binary");
        var binaryKey=new ArtifactIndexFormat.Key("e".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,"dep.Type","dep.Type","Type","class",
                "class dep.Type",null,1,"dep/Type.class",java.util.List.of(),"{}");
        var facts=new ArtifactIndexFormat.ArtifactData(binaryKey,java.util.List.of(symbol),java.util.List.of());
        var sourceKey=new ArtifactIndexFormat.Key("f".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"sources");
        var context=new ArtifactContext("fixture:dep:1","jar",jar.toAbsolutePath().toString());

        try(var sink=new RocksArtifactGenerationSink(root,8L*1024*1024)){
            sink.publish(facts,java.util.Set.of());
            long scan=sink.beginScan();
            sink.observe(scan,new IndexStore.ArtifactInput(context,binaryKey,Files.size(jar),1));
            sink.publishDocumentation(binaryKey.cacheKey(),
                    new IndexStore.ArtifactInput(new ArtifactContext("fixture:dep:1","sources",jar.toAbsolutePath()+".sources"),
                            sourceKey,100,1),
                    java.util.Map.of("dep.Type",java.util.Map.of("doc","Dependency docs","source_file","jar:file:///dep-sources.jar!/dep/Type.java","line",4)),0);
            sink.completeScan(scan);
            sink.configureWorkspace("workspace",java.util.List.of(new IndexStore.WorkspaceEntry(jar.toString(),"compile")),java.util.List.of());

            var row=sink.shadowFind("workspace","Type",false,10,java.util.Set.of()).orElseThrow().getFirst();
            assertThat(row).containsEntry("doc","Dependency docs").containsEntry("line",4);
            assertThat(sink.shadowById("workspace",((Number)row.get("id")).longValue())).contains(row);
        }
    }

}
