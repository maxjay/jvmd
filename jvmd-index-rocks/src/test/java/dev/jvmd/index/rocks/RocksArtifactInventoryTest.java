package dev.jvmd.index.rocks;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksArtifactInventoryTest {
    @TempDir Path temp;

    @Test void deletingSeveralAliasesDecrementsTheWholeBatch()throws Exception{
        try(var inventory=new RocksArtifactInventory(temp.resolve("multi-delete"),TestOptions.creating())){
            long generation=inventory.beginScan();
            var stamp=new RocksArtifactInventory.Stamp(1,1,1,"");
            for(String name:List.of("a.jar","b.jar","c.jar"))
                inventory.observe(generation,temp.resolve(name),"fixture:"+name+":1","jar","a".repeat(64),"b".repeat(64),stamp);
            inventory.completeScan(generation);
            long next=inventory.beginScan();
            inventory.observe(next,temp.resolve("c.jar"),"fixture:c.jar:1","jar","a".repeat(64),"b".repeat(64),stamp);
            assertThat(inventory.completeScan(next)).isEmpty();
            assertThat(inventory.refcount("a".repeat(64))).isEqualTo(1);
            assertThat(inventory.completeScan(inventory.beginScan())).containsExactly("a".repeat(64));
            assertThat(inventory.refcount("a".repeat(64))).isZero();
        }
    }

    @Test void tracksReuseDeletionRenameAndContentReplacement()throws Exception{
        Path a=Files.writeString(temp.resolve("a.jar"),"a"),b=Files.writeString(temp.resolve("b.jar"),"b"),c=temp.resolve("c.jar");
        var aStamp=RocksArtifactInventory.Stamp.read(a);
        var bStamp=RocksArtifactInventory.Stamp.read(b);
        String one="1".repeat(64),two="2".repeat(64),shaOne="a".repeat(64),shaTwo="b".repeat(64);
        Path root=temp.resolve("inventory");
        try(var inventory=new RocksArtifactInventory(root,TestOptions.creating())){
            long first=inventory.beginScan();
            inventory.observe(first,a,"g:a:1","jar",one,shaOne,aStamp);
            inventory.observe(first,b,"g:b:1","jar",one,shaOne,bStamp);
            assertThat(inventory.completeScan(first)).isEmpty();
            assertThat(inventory.refcount(one)).isEqualTo(2);

            long second=inventory.beginScan();
            inventory.observe(second,a,"g:a:1","jar",one,shaOne,aStamp);
            assertThat(inventory.completeScan(second)).isEmpty();
            assertThat(inventory.entries()).extracting(RocksArtifactInventory.Entry::path)
                    .containsExactly(a.toAbsolutePath().normalize().toString());
            assertThat(inventory.refcount(one)).isEqualTo(1);

            Files.move(a,c);var cStamp=RocksArtifactInventory.Stamp.read(c);
            long third=inventory.beginScan();
            inventory.observe(third,c,"g:a:2","jar",two,shaTwo,cStamp);
            assertThat(inventory.completeScan(third)).containsExactly(one);
            assertThat(inventory.refcount(one)).isZero();assertThat(inventory.refcount(two)).isEqualTo(1);
        }
        try(var reopened=new RocksArtifactInventory(root,TestOptions.creating())){
            assertThat(reopened.entries()).hasSize(1);
            assertThat(reopened.entries().getFirst().cacheKey()).isEqualTo(two);
            assertThat(reopened.refcount(two)).isEqualTo(1);
        }
    }
}
