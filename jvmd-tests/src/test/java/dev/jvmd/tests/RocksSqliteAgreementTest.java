package dev.jvmd.tests;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Canonical user-visible query agreement between the SQLite oracle and Rocks artifact resolver. */
@Tag("phase-3")
class RocksSqliteAgreementTest {
    @TempDir Path temp;

    @Test void deterministicWorkspaceQueriesAgreeWithoutComparingBackendIds()throws Exception{
        Path repo=temp.resolve("repo");
        Path a=IndexFixtures.jar(repo.resolve("fixture/a/1"),"a-1",
                IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerA;"),false);
        Path b=IndexFixtures.jar(repo.resolve("fixture/b/1"),"b-1",
                IndexFixtures.generic().replace("private String hidden;","private String hidden; public int markerB;"),false);

        try(var sqlite=new IndexService(new SqliteIndexStore(temp.resolve("index.db")),repo,ArtifactGenerationSink.none());
            var artifacts=new RocksArtifactRepository(temp.resolve("rocks-artifacts"));
            var resolver=new RocksWorkspaceResolver(temp.resolve("rocks-resolution"),artifacts)){
            var entries=new ArrayList<RocksWorkspaceResolver.Entry>();
            for(var item:List.of(Map.entry(a,"fixture:a:1"),Map.entry(b,"fixture:b:1"))){
                Path path=item.getKey();String gav=item.getValue();
                sqlite.indexJar(path,gav,"jar");
                var content=new BinaryReader().read(path,false);
                var key=ArtifactIndexFormat.key(Hashing.sha256(path),"signatures");
                var facts=ArtifactIndexFormat.from(content,key);
                artifacts.publish(facts,CodeReader.classReferences(content.models().values()));
                entries.add(new RocksWorkspaceResolver.Entry(key.cacheKey(),new ArtifactContext(gav,"jar",path.toString()),
                        "compile","", ""));
            }
            sqlite.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(a.toString(),"compile"),
                    new IndexService.WorkspaceArtifact(b.toString(),"compile")),List.of());
            var workspace=new RocksWorkspaceResolver.Workspace(entries,"compiler");

            assertThat(rocksScips(resolver.findName(workspace,"transform",false,20)))
                    .containsExactlyInAnyOrderElementsOf(sqliteScips(sqlite.find("transform","w",false,20,0)));

            assertThat(rocksScips(resolver.findSubstring(workspace,"form",20)))
                    .containsExactlyInAnyOrderElementsOf(sqliteScips(sqlite.find("form","w",true,20,0)));

            var first=resolver.findName(workspace,"transform",false,20).getFirst();
            String binaryKey=first.symbol().key();
            assertThat(rocksScips(resolver.resolveAll(workspace,binaryKey).stream().map(resolved->{
                var entry=workspace.classpath().get(resolved.classpathIndex());
                try{
                    var data=artifacts.artifact(resolved.artifactCacheKey());
                    return entry.context().scip(data.symbols().get(resolved.localId()));
                }catch(Exception e){throw new RuntimeException(e);}
            }).toList())).containsExactlyInAnyOrderElementsOf(sqliteScips(sqlite.store().symbolsByBinaryKey(binaryKey,"w")));

            String scip=first.scip();
            assertThat(resolver.byScip(workspace,scip)).isPresent();
            assertThat(sqlite.store().byScip(scip,"w")).isNotNull().extracting(value->value.get("scip")).isEqualTo(scip);
        }
    }

    private static List<String> sqliteScips(List<Map<String,Object>> values){
        return values.stream().map(value->value.get("scip").toString()).sorted().toList();
    }
    private static List<String> rocksScips(List<RocksWorkspaceResolver.WorkspaceSymbol> values){
        return values.stream().map(RocksWorkspaceResolver.WorkspaceSymbol::scip).sorted().toList();
    }
    private static List<String> rocksScips(Collection<String> values){return values.stream().sorted().toList();}
}
