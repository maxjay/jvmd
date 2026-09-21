package dev.jvmd.tests;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Canonical user-visible query agreement between the SQLite oracle and production Rocks store. */
@Tag("phase-3")
class RocksSqliteAgreementTest {
    @TempDir Path temp;

    @Test void deterministicWorkspaceQueriesAgreeWithoutComparingBackendIds()throws Exception{
        Path repo=temp.resolve("repo");
        Path a=IndexFixtures.jar(repo.resolve("fixture/a/1"),"a-1",
                IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerA;"),false);
        Path b=IndexFixtures.jar(repo.resolve("fixture/b/1"),"b-1",
                IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerB;"),false);

        try(var sqlite=new IndexService(new ReferenceIndexStorage(temp.resolve("control.db")),repo);
            var rocks=new IndexService(temp.resolve("index.db"),repo)){
            for(var index:List.of(sqlite,rocks)){
                index.indexJar(a,"fixture:a:1","jar");index.indexJar(b,"fixture:b:1","jar");
                index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(a.toString(),"compile"),
                        new IndexService.WorkspaceArtifact(b.toString(),"compile")),List.of());
            }
            for(String query:List.of("transform","form"))
                assertThat(scips(rocks.find(query,"w",query.equals("form"),20,0)))
                        .containsExactlyInAnyOrderElementsOf(scips(sqlite.find(query,"w",query.equals("form"),20,0)));
            var first=rocks.find("transform","w",false,20,0).getFirst();String binaryKey=first.get("binary_key").toString();
            assertThat(scips(rocks.store().symbolsByBinaryKey(binaryKey,"w")))
                    .containsExactlyInAnyOrderElementsOf(scips(sqlite.store().symbolsByBinaryKey(binaryKey,"w")));
            String scip=first.get("scip").toString();
            for(var index:List.of(sqlite,rocks))assertThat(index.store().byScip(scip,"w")).isNotNull().extracting(value->value.get("scip")).isEqualTo(scip);
        }
    }
    private static List<String> scips(List<Map<String,Object>> values){return values.stream().map(value->value.get("scip").toString()).sorted().toList();}
}
