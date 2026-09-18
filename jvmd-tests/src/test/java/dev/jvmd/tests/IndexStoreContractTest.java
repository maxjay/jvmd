package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Backend-neutral query/workspace contract exercised against the SQLite control backend. */
@Tag("phase-3")
class IndexStoreContractTest {
    @TempDir Path temp;

    @Test void sqliteImplementsArtifactWorkspaceAndQueryContract()throws Exception{
        Path a=IndexFixtures.jar(temp.resolve("a"),"a",IndexFixtures.generic(),false);
        Path b=IndexFixtures.jar(temp.resolve("b"),"b",IndexFixtures.generic(),false);
        try(var index=new IndexService(temp.resolve("index.db"),temp)){
            index.indexJar(a,"fixture:a:1","jar");
            index.indexJar(b,"fixture:b:1","jar");
            var store=index.store();

            assertThat(store.backend()).isEqualTo("sqlite");
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
            assertThat(store.descendants("fixture/Sample","one",2,50,0,Set.of())).isNotEmpty();
            assertThat(store.status()).containsEntry("backend","sqlite");
        }
    }
}
