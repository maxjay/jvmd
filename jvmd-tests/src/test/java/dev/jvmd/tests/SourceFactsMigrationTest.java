package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import org.rocksdb.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class SourceFactsMigrationTest {
    @TempDir Path root;
    @Test void sourceReplacementMasksOldBinaryRelationshipsInBothDirections()throws Exception {
        Path jar=IndexFixtures.jar(root.resolve("jar"),"sample","package fixture; public class Sample { public static class Base {} public static class Child extends Base {} }",false);
        Path source=root.resolve("Child.java");Files.writeString(source,"class Child {}");
        try(var index=new IndexService(root.resolve("edges.db"),root.resolve("repository"))){
            long artifact=index.indexJar(jar,"fixture:sample:1","jar");var store=index.store();
            var parent=store.find("Base",null,false,10,0,Set.of("class")).getFirst();var child=store.find("Child",null,false,10,0,Set.of("class")).getFirst();
            String target=parent.get("scip").toString(),identity=child.get("scip").toString();
            assertThat(store.relationships(List.of(target),false,Set.of("extends"),null)).anyMatch(e->e.source().get("scip").equals(identity));
            var replacement=new LinkedHashMap<>(child);replacement.put("source_file",source.toString());
            store.publishSourceFile(artifact,source,List.of(replacement),2,List.of());
            assertThat(store.relationships(List.of(identity),true,Set.of("extends"),null)).isEmpty();
            assertThat(store.relationships(List.of(target),false,Set.of("extends"),null)).noneMatch(e->e.source().get("scip").equals(identity));
            store.publishSourceFile(artifact,source,List.of(replacement),2,List.of(new IndexStore.SourceRelationship(identity,target,"extends")));
            assertThat(store.relationships(List.of(target),false,Set.of("extends"),null)).filteredOn(e->e.source().get("scip").equals(identity)).hasSize(1);
        }
    }
    @Test void legacyJsonBecomesIndexedFactsWithoutChangingHandlesAndMigrationIsIdempotent()throws Exception {
        Path module=Files.createDirectories(root.resolve("module")),source=module.resolve("A.java"),database=root.resolve("index.db");Files.writeString(source,"class A {}");
        long artifact;
        try(var index=new IndexService(database,root.resolve("repository"))){
            var key=ArtifactIndexFormat.key("1".repeat(64),"local-signatures");
            artifact=index.store().publishArtifact(new IndexStore.ArtifactInput(new ArtifactContext("fixture:app:1","local",module.toString()),key,0,0),new ArtifactIndexFormat.ArtifactData(key,List.of(),List.of()),Set.of(),Map.of());
        }
        Path store;try(var files=Files.walk(root)){store=files.filter(p->p.getFileName().toString().equals("store")).findFirst().orElseThrow();}
        long handle=(artifact<<32)|0x80000000L;String scip="maven fixture/app 1 A#";
        var symbol=Map.of("id",handle,"artifact_id",artifact,"scip",scip,"name","A","kind","class","name_path","A","binary_key","A","source_file",source.toString());
        byte[] legacyKey=("S|"+String.format(Locale.ROOT,"%016x",artifact)+"|"+Hashing.sha256(source.toString().getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        try(var options=new Options();var rocks=RocksDB.open(options,store.toString())){
            rocks.put(legacyKey,Json.MAPPER.writeValueAsBytes(Map.of("file",source.toString(),"hash",Hashing.sha256(source),"symbols",List.of(symbol),"edges",List.of())));
            rocks.put("next-source".getBytes(StandardCharsets.UTF_8),"2147483649".getBytes(StandardCharsets.UTF_8));
        }
        for(int restart=0;restart<2;restart++)try(var index=new IndexService(database,root.resolve("repository"))){
            var value=index.store().byScip(scip,null);assertThat(value).containsEntry("id",handle).containsEntry("source_file",source.toString());
            assertThat(index.store().byId(handle,null)).containsEntry("scip",scip);
            assertThat(index.store().findNamePrefix("A",null,10,Set.of("class"))).hasSize(1);
        }
        try(var options=new Options();var rocks=RocksDB.open(options,store.toString())){assertThat(rocks.get(legacyKey)).isNull();}
    }
}
