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
        Path a=IndexFixtures.jar(temp.resolve("a"),"a",IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerA;"),false);
        Path b=IndexFixtures.jar(temp.resolve("b"),"b",IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerB;"),false);
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
            assertThat(store.descendants("fixture.Sample","one",2,50,0,Set.of())).isNotEmpty();
            assertThat(store.status()).containsEntry("backend","sqlite");
        }
    }
    @Test void readBackendCanCutOverToRocksAndRollBackToSqlite()throws Exception{
        Path jar=IndexFixtures.jar(temp.resolve("mode"),"mode",
                IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerMode;"),false);
        var sink=new ArtifactGenerationSink(){
            @Override public void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences){ }
            @Override public Optional<List<Map<String,Object>>> shadowFind(String workspace,String query,boolean substring,int limit,long after,Set<String> kinds){
                if(!"mode-workspace".equals(workspace))return Optional.empty();
                return Optional.of(List.of(Map.of("id",42L,"scip","rocks-sentinel","kind","method","name","transform",
                        "name_path","rocks/sentinel","binary_key","rocks#sentinel","gav","rocks:sentinel:1",
                        "artifact_path","/rocks/sentinel.jar","artifact_kind","jar","parameters",List.of(),"metadata",Map.of())));
            }
        };
        String previous=System.getProperty("jvmd.index.read.backend");
        try(var index=new IndexService(temp.resolve("mode.db"),temp,sink)){
            index.indexJar(jar,"fixture:mode:1","jar");
            index.loadWorkspace("mode-workspace",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());

            System.setProperty("jvmd.index.read.backend","rocksdb-sst");
            assertThat(index.find("transform","mode-workspace",false,20,0,Set.of("method")))
                    .extracting(row->row.get("scip")).containsExactly("rocks-sentinel");

            System.setProperty("jvmd.index.read.backend","sqlite");
            assertThat(index.find("transform","mode-workspace",false,20,0,Set.of("method")))
                    .extracting(row->row.get("scip").toString()).allMatch(value->value.startsWith("maven fixture/mode"));
        }finally{
            if(previous==null)System.clearProperty("jvmd.index.read.backend");
            else System.setProperty("jvmd.index.read.backend",previous);
        }
    }

}
