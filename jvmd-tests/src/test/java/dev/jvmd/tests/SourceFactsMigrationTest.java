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
        try(var index=TestMachine.index(root.resolve("edges.db"),root.resolve("repository"))){
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
}
