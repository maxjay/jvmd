package dev.jvmd.index.rocks;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksArtifactInventoryTest {
    @TempDir Path temp;

    @Test void tracksReuseDeletionRenameAndContentReplacement()throws Exception{
        Path a=Files.writeString(temp.resolve("a.jar"),"a"),b=Files.writeString(temp.resolve("b.jar"),"b"),c=temp.resolve("c.jar");
        var aStamp=RocksArtifactInventory.Stamp.read(a);
        var bStamp=RocksArtifactInventory.Stamp.read(b);
        String one="1".repeat(64),two="2".repeat(64),shaOne="a".repeat(64),shaTwo="b".repeat(64);
        Path root=temp.resolve("inventory");
        try(var inventory=new RocksArtifactInventory(root)){
            long first=inventory.beginScan();
            inventory.observe(first,a,"g:a:1","jar",one,shaOne,aStamp);
            inventory.observe(first,b,"g:b:1","jar",one,shaOne,bStamp);
            assertThat(inventory.completeScan(first)).isEmpty();
            assertThat(inventory.refcount(one)).isEqualTo(2);
            assertThat(inventory.reusable(a,"g:a:1","jar",aStamp,false)).isPresent();
            assertThat(inventory.reusable(a,"g:a:1-SNAPSHOT","jar",aStamp,true)).isEmpty();

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
        try(var reopened=new RocksArtifactInventory(root)){
            assertThat(reopened.entries()).hasSize(1);
            assertThat(reopened.entries().getFirst().cacheKey()).isEqualTo(two);
            assertThat(reopened.refcount(two)).isEqualTo(1);
        }
    }

    @Test void changedStampPreventsUnsafeReuse()throws Exception{
        Path file=Files.writeString(temp.resolve("artifact.jar"),"first");
        try(var inventory=new RocksArtifactInventory(temp.resolve("changed"))){
            long generation=inventory.beginScan();var first=RocksArtifactInventory.Stamp.read(file);
            inventory.observe(generation,file,"g:a:1","jar","3".repeat(64),"c".repeat(64),first);inventory.completeScan(generation);
            Files.writeString(file,"second-content");var changed=RocksArtifactInventory.Stamp.read(file);
            assertThat(changed).isNotEqualTo(first);
            assertThat(inventory.reusable(file,"g:a:1","jar",changed,false)).isEmpty();
        }
    }
}
