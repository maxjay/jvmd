package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.4: content identities retain separate hierarchy, signature, inherited-doc and code relationships. */
@Tag("phase-3") @Tag("phase-7") @Tag("phase-8")
class ArtifactSignatureVariantTest {
    @TempDir Path root;
    private Path variant(String name,String parent,String result) throws Exception {
        String source="package fixture; public class Sample extends "+parent+" { /** Variant result. */ public "+result+" value(){return new "+result+"();} /** {@inheritDoc} */ public String inherited(){return \"value\";} } "+
                "class First { /** First parent documentation. */ public String inherited(){return \"first\";} } "+
                "class Second { /** Second parent documentation. */ public String inherited(){return \"second\";} } "+
                "/** Left result. */ class Left {} /** Right result. */ class Right {}";
        return IndexFixtures.jar(root.resolve(name),name,source,false);
    }
    @Test void sameScipUsesOnlyTheSelectedContentVariantsRelationships() throws Exception {
        Path first=variant("first","First","Left"),second=variant("second","Second","Right");
        try(var index=new IndexService(root.resolve("index.db"),root)) {
            index.indexJar(first,"fixture:api:1","jar");index.indexSources(first.resolveSibling("first-sources.jar"));
            index.indexJar(second,"fixture:api:1","jar");index.indexSources(second.resolveSibling("second-sources.jar"));index.linkEdges();
            index.loadWorkspace("one",List.of(new IndexService.WorkspaceArtifact(first.toString(),"compile")),List.of());
            index.loadWorkspace("two",List.of(new IndexService.WorkspaceArtifact(second.toString(),"compile")),List.of());
            var docs=new Documentation(index,Path.of(System.getProperty("java.home")));var code=new CodePass(index);
            for(String workspace:List.of("one","two")) {
                String selected=workspace.equals("one")?"Left":"Right",other=workspace.equals("one")?"Right":"Left",parent=workspace.equals("one")?"First":"Second";
                var type=index.find("Sample",workspace,false,10,0).getFirst();
                var hierarchy=code.hierarchy(List.of(type),true,workspace);
                assertThat(hierarchy.symbols()).extracting(s->s.get("name")).contains(parent).doesNotContain(parent.equals("First")?"Second":"First");
                var method=index.find("Sample/value()",workspace,false,10,0).getFirst();
                var described=Json.MAPPER.valueToTree(docs.describe(method,workspace,"full",1,100,0).result());
                var closure=new ArrayList<String>();for(var symbol:described.path("closure"))closure.add(symbol.path("name").asText());
                assertThat(closure).contains(selected).doesNotContain(other);
                var inherited=index.find("Sample/inherited()",workspace,false,10,0).getFirst();
                assertThat(Json.MAPPER.valueToTree(docs.describe(inherited,workspace,"full",0,20,0).result()).path("doc").asText()).contains(parent+" parent documentation").doesNotContain("{@inheritDoc}");
                var calls=code.expand(List.of(method),true,Set.of("instantiates"),workspace);
                assertThat(calls.symbols()).extracting(s->s.get("name")).contains(selected).doesNotContain(other);
            }
        }
    }
    @Test void oldUnattributedEdgesAreRebuiltFromTheActualJarOnLookup() throws Exception {
        Path jar=variant("old","First","Left"),file=root.resolve("old-index.db");
        try(var index=new IndexService(file,root)) { index.indexJar(jar,"fixture:api:1","jar");index.linkEdges(); }
        // Simulate a populated schema-3 database, whose union edges cannot identify their artifact variant.
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var statement=connection.createStatement()) {
            statement.execute("DROP TABLE artifact_edges");statement.execute("DROP TABLE signature_targets");
            statement.execute("ALTER TABLE artifacts DROP COLUMN has_signature_edges");statement.execute("PRAGMA user_version=3");
        }
        try(var index=new IndexService(file,root)) {
            assertThat(index.artifact(jar).hasSignatureEdges()).isFalse();
            var type=index.find("Sample",null,false,10,0).getFirst();
            assertThat(new CodePass(index).hierarchy(List.of(type),true,null).symbols()).extracting(s->s.get("name")).contains("First").doesNotContain("Second");
            assertThat(index.artifact(jar).hasSignatureEdges()).isTrue();
        }
    }
}
