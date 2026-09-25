package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Run the same production semantic fixtures with SQL completely absent. */
@Tag("phase-3")
class RocksStoreIntegrationTest {
    @TempDir Path root;
    @Test void selectedVariantsKeepHierarchyDocumentationAndCode()throws Exception{
        var fixture=new ArtifactSignatureVariantTest();fixture.root=root;fixture.sameScipUsesOnlyTheSelectedContentVariantsRelationships();assertThat(root.resolve("index.db")).doesNotExist();
    }
    @Test void installedAndLocalSourceVariantsSurviveEdits()throws Exception{
        var fixture=new LocalModuleIndexTest();fixture.root=root;fixture.localAndInstalledCoordinatesKeepIndependentSourcesThroughEdits();assertThat(root.resolve("index.db")).doesNotExist();
    }
    @Test void sourceDeletionRemovesOnlyThatFile()throws Exception{
        var fixture=new LocalModuleIndexTest();fixture.root=root;fixture.unchangedContentReusesIdentityAndDeletingAFileRemovesItsSymbols();assertThat(root.resolve("plain.db")).doesNotExist();
    }
    @Test void documentationMatchesSourceParameters()throws Exception{
        var fixture=new SourceDocumentationJoinTest();fixture.temp=root;fixture.joinsBoundedTypeVariablesAndRendersDocs();assertThat(root.resolve("index.db")).doesNotExist();
    }
    @Test void applicationReopensTheSameAuthoritativeStore()throws Exception{
        var fixture=new DependencyHierarchyTest();fixture.root=root;fixture.nestedDependencyHierarchyHonorsDirectionAndDepth();assertThat(root.resolve("state/index.db")).doesNotExist();
    }
    @Test void lazyBytecodeCallsFieldsAllocationsAndReferencesKeepTheirScope()throws Exception{
        var fixture=new LazyCodeReferencesTest();fixture.root=root;fixture.callsFieldsAllocationCastsAndDynamicMethodReferencesAreLazyAndScoped();assertThat(root.resolve("index.db")).doesNotExist();
    }
    @Test void codeEnrichmentPreservesPublicIdsAndAddsPrivateCallers()throws Exception{
        Path jar=IndexFixtures.jar(root.resolve("repo"),"fixture","package fixture; public class Sample { public static class Target { public static int answer(){return 3;} } private int hidden(){return Target.answer();} public int value(){return Target.answer();} }",false);
        try(var index=new IndexService(root.resolve("index.db"),root.resolve("repo"))){
            index.indexJar(jar,"fixture:api:1","jar");var value=index.find("value",null,false,10,0).getFirst();long before=((Number)value.get("id")).longValue();
            var expansion=new CodePass(index).expand(List.of(value),true,Set.of("calls"),null);
            assertThat(expansion.symbols()).extracting(s->s.get("name")).contains("answer");
            assertThat(index.byId(before).get("scip")).isEqualTo(value.get("scip"));
            var hidden=index.find("hidden",null,false,10,0).getFirst();
            assertThat(index.byId(((Number)hidden.get("id")).longValue()).get("scip")).isEqualTo(hidden.get("scip"));
            assertThat(new CodePass(index).expand(List.of(hidden),true,Set.of("calls"),null).symbols())
                    .extracting(s->s.get("name")).contains("answer");
            for(String query:List.of("","Sample")){
                var all=index.find(query,null,true,100,0);var pages=new ArrayList<Map<String,Object>>();long after=0;
                for(int page=0;page<all.size()+1;page++){
                    var values=index.find(query,null,true,1,after);if(values.isEmpty())break;
                    pages.addAll(values);after=((Number)values.getLast().get("id")).longValue();
                }
                assertThat(pages).as("Enriched IDs must retain original signature order: %s",query).isEqualTo(all);
                assertThat(pages).extracting(s->s.get("name")).contains("hidden","value");
            }
        }
    }
    @Test void canonicalSearchPagesAgreeWithSqliteAndReopenWithoutRebuilding()throws Exception{
        Path repo=root.resolve("repository"),jar=IndexFixtures.jar(repo,"fixture",IndexFixtures.generic(),false);
        try(var sqlite=new IndexService(new ReferenceIndexStorage(root.resolve("control.db")),repo);
            var rocks=new IndexService(root.resolve("index.db"),repo)){
            for(var index:List.of(sqlite,rocks)){
                index.indexJar(jar,"fixture:api:1","jar");index.indexSources(jar.resolveSibling("fixture-sources.jar"));
                index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            }
            for(String query:List.of("transform","Sample/transform(T,U)","Sample/Nested","Sa","Nested","","missingRareType","ansf","%' OR 1=1 --"))
            for(Set<String> kinds:List.of(Set.<String>of(),Set.of("class"),Set.of("interface"),Set.of("class","method"),Set.of("class","interface","enum","record","annotation"))){
                boolean substring=Set.of("Sa","Nested","","missingRareType","ansf","%' OR 1=1 --").contains(query);
                var control=sqlite.find(query,"w",substring,100,0,kinds);var found=new ArrayList<Map<String,Object>>();long after=0;
                for(int page=0;page<100;page++){
                    var values=rocks.find(query,"w",substring,1,after,kinds);if(values.isEmpty())break;
                    var value=values.getFirst();found.add(value);after=((Number)value.get("id")).longValue();
                    assertThat(rocks.byId(after,"w").get("scip")).isEqualTo(value.get("scip"));
                }
                assertThat(canonical(found)).as(query+" "+kinds).containsExactlyInAnyOrderElementsOf(canonical(control));
            }
        }
        assertThat(root.resolve("index.db")).doesNotExist();
        try(var index=new IndexService(root.resolve("index.db"),repo)){
            index.indexJar(jar,"fixture:api:1","jar");assertThat(index.status()).containsEntry("indexed",0L);
            assertThat(index.find("transform",null,false,10,0)).hasSize(1);
            assertThat(index.store().status()).containsEntry("backend","rocksdb-sst").containsEntry("link_passes",0L);
        }
    }
    @Test void signatureClosureEnrichesJdkDocumentationAndPaginates()throws Exception{
        Path jar=IndexFixtures.jar(root.resolve("repo"),"fixture",IndexFixtures.generic(),true);
        try(var index=new IndexService(root.resolve("index.db"),root.resolve("repo"))){
            index.indexJar(jar,"fixture:api:1","jar");index.indexSources(jar.resolveSibling("fixture-sources.jar"));
            var symbol=index.find("transform",null,false,10,0).getFirst();var docs=new Documentation(index,Path.of(System.getProperty("java.home")));
            var identities=new LinkedHashSet<String>();int cursor=0,pages=0;
            while(true){
                var page=docs.describe(symbol,null,"summary",3,2,cursor);var result=Json.MAPPER.valueToTree(page.result());
                assertThat(result.path("closure").size()).isLessThanOrEqualTo(2);
                for(var member:result.path("closure")){assertThat(identities.add(member.path("scip").asText())).isTrue();assertThat(member.path("signature").asText()).isNotBlank();}
                if(!page.truncated())break;cursor=Integer.parseInt(page.cursor());assertThat(++pages).isLessThan(30);
            }
            assertThat(identities).doesNotContain(symbol.get("scip").toString()).anyMatch(s->s.contains("java/util/List#")).anyMatch(s->s.contains("java/lang/CharSequence#")).anyMatch(s->s.contains("java/lang/Number#"));
            assertThat(index.find("java.util.List",null,false,10,0).stream().filter(s->s.get("kind").equals("interface")).findFirst().orElseThrow().get("doc")).isNotNull();
        }
    }
    @Test void aliasesDoNotDuplicateLaterPagesAndKeepIndependentCoordinates()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));Path a=IndexFixtures.jar(repo,"first",IndexFixtures.generic(),false);
        Path b=Files.copy(a,repo.resolve("second.jar")),c=Files.copy(a,repo.resolve("third.jar"));
        try(var index=new IndexService(root.resolve("index.db"),repo)){
            index.indexJar(a,"fixture:api:1","jar");index.indexJar(b,"fixture:api:1","jar");index.indexJar(c,"fixture:alias:1","jar");
            var identities=new LinkedHashSet<String>();long after=0;
            for(int page=0;page<20;page++){
                var values=index.find("Sample",null,false,1,after);if(values.isEmpty())break;
                assertThat(identities.add(values.getFirst().get("scip").toString())).isTrue();after=((Number)values.getFirst().get("id")).longValue();
            }
            assertThat(identities).hasSize(4);index.store().reconcilePaths(repo,Set.of(b,c));
            assertThat(index.find("Sample",null,false,20,0)).hasSize(4);
        }
    }
    private static List<String> canonical(List<Map<String,Object>> values)throws Exception{
        var result=new ArrayList<String>();
        for(var value:values){var selected=new TreeMap<String,Object>();for(String field:List.of("scip","kind","name","name_path","signature","erased_descriptor","source_file","doc","fqn","binary_key","gav","parameters"))selected.put(field,value.get(field));result.add(Json.MAPPER.writeValueAsString(selected));}
        return result;
    }
}
