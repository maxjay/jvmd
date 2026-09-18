package dev.jvmd.index.rocks;

import dev.jvmd.index.ArtifactIndexFormat;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksArtifactRepositoryTest {
    @TempDir Path temp;

    @Test void atomicallyPublishesAndReusesImmutableGeneration()throws Exception{
        var data=facts(5000,10000);
        String cacheKey=data.key().cacheKey();
        try(var store=new RocksArtifactRepository(temp.resolve("rocks"))){
            long before=store.storageBytes();
            var first=store.publish(data,Set.of("dep.Type12","dep.Type77"));
            assertThat(first.reused()).isFalse();
            assertThat(store.contains(cacheKey)).isTrue();
            assertThat(store.artifact(cacheKey)).isEqualTo(data);
            assertThat(store.binaryId(cacheKey,"fixture.Type#method123()V")).isEqualTo(123);
            assertThat(store.nameIds(cacheKey,"method12",100)).isNotEmpty();
            assertThat(store.reverseSources(cacheKey,"dep.Type12","calls",100)).isNotEmpty();
            long published=store.storageBytes();
            assertThat(published).isGreaterThan(before);

            var second=store.publish(data,Set.of("dep.Type12","dep.Type77"));
            assertThat(second.reused()).isTrue();
            assertThat(store.storageBytes()).isEqualTo(published);
        }
    }

    @Test void abandonedStagingFileNeverBecomesVisible()throws Exception{
        Path root=temp.resolve("rocks"),staging=Files.createDirectories(root.resolve("staging"));
        Files.writeString(staging.resolve("orphan.sst.tmp"),"partial");
        var data=facts(10,20);
        try(var store=new RocksArtifactRepository(root)){
            assertThat(Files.list(staging).toList()).isEmpty();
            assertThat(store.contains(data.key().cacheKey())).isFalse();
        }
    }

    private static ArtifactIndexFormat.ArtifactData facts(int symbols,int relationships){
        var key=new ArtifactIndexFormat.Key("a".repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        var values=new ArrayList<ArtifactIndexFormat.SymbolRecord>();
        for(int i=0;i<symbols;i++)values.add(new ArtifactIndexFormat.SymbolRecord(
                i,-1,"fixture.Type#method"+i+"()V","fixture.Type","method"+i,"method",
                "void method"+i+"()","()V",1,"fixture/Type.class",List.of(),"{}"));
        var edges=new ArrayList<ArtifactIndexFormat.Relationship>();
        for(int i=0;i<relationships;i++){
            int source=i%symbols,target=(i/symbols)*1000+(i%1000);
            edges.add(new ArtifactIndexFormat.Relationship(source,"dep.Type"+target,(i&1)==0?"calls":"return_type"));
        }
        return new ArtifactIndexFormat.ArtifactData(key,List.copyOf(values),List.copyOf(edges));
    }
}
