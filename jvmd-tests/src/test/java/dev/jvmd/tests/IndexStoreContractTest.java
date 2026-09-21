package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Backend-neutral query/workspace contract exercised against the production Rocks backend. */
@Tag("phase-3")
class IndexStoreContractTest {
    @TempDir Path temp;

    @Test void rocksImplementsArtifactWorkspaceAndQueryContract()throws Exception{
        Path a=IndexFixtures.jar(temp.resolve("a"),"a",IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerA;"),false);
        Path b=IndexFixtures.jar(temp.resolve("b"),"b",IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerB;"),false);
        try(var index=new IndexService(temp.resolve("index.db"),temp)){
            index.indexJar(a,"fixture:a:1","jar");
            index.indexJar(b,"fixture:b:1","jar");
            var store=index.store();

            assertThat(store.backend()).isEqualTo("rocksdb-sst");
            var artifact=store.artifact(a);
            assertThat(artifact).isNotNull();
            assertThat(artifact.gav()).isEqualTo("fixture:a:1");
            assertThat(artifact.sha256()).hasSize(64);

            var all=store.find("transform",null,false,20,0,Set.of("method"));
            assertThat(all).hasSize(2);
            assertThat(all).extracting(value->value.get("scip").toString())
                    .allMatch(identity->identity.startsWith("maven fixture/"));

            var warnings=store.loadWorkspace("one",List.of(new IndexStore.WorkspaceEntry(a.toString(),"compile")),List.of());
            assertThat(warnings).isEmpty();
            var selected=store.find("transform","one",false,20,0,Set.of("method"));
            assertThat(selected).hasSize(1);
            assertThat(selected.getFirst().get("gav")).isEqualTo("fixture:a:1");

            long id=((Number)selected.getFirst().get("id")).longValue();
            assertThat(store.byId(id,"one").get("scip")).isEqualTo(selected.getFirst().get("scip"));
            assertThat(store.descendants("fixture.Sample","one",2,50,0,Set.of())).isNotEmpty();
            assertThat(store.status()).containsEntry("backend","rocksdb-sst");
        }
    }
    @Test void legacyBackendSettingsFailBeforeOpeningAnyStore()throws Exception{
        for(var entry:Map.of("jvmd.index.store.backend","sqlite","jvmd.index.read.backend","shadow","jvmd.index.generation.backend","none").entrySet()){
            String previous=System.getProperty(entry.getKey());
            try{
                System.setProperty(entry.getKey(),entry.getValue());
                assertThatThrownBy(()->new IndexService(temp.resolve("legacy.db"),temp))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sole production backend");
                assertThat(temp.resolve("legacy.db")).doesNotExist();
                assertThat(temp.resolve("legacy.db.rocks")).doesNotExist();
            }finally{if(previous==null)System.clearProperty(entry.getKey());else System.setProperty(entry.getKey(),previous);}
        }
    }
}
