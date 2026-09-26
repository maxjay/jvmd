package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class MaintainedSemanticObservationTest {
    @TempDir Path root;

    @Test void fullyAdmittedDocumentValidationConsumesMaintainedState()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path jar=IndexFixtures.jar(repo,"api","MavenProject.java",
                "package p; public class MavenProject { public int getValue(){return 1;} }",false);
        Path sources=Files.createDirectories(root.resolve("src/p"));
        String text="package p; class Use { MavenProject choose(MavenProject p){return p;} Object f(MavenProject project){return choose(project).;} }";
        Path file=Files.writeString(sources.resolve("Use.java"),text);
        var documents=new Documents();documents.open(file,text,1);
        try(var index=new IndexService(root.resolve("index.db"),repo);var analyzer=new Analyzer()){
            index.indexJar(jar,"fixture:api:1","jar");
            index.loadWorkspace("w",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            var context=new Analyzer.Context("fixture:app:1","25",List.of(jar),List.of(root.resolve("src")),
                    "admitted",Map.of(),List.of("--release","25"),Set.of(),List.of(),List.of(root.resolve("src")),true,"w");
            analyzer.configure(context,index,256L*1024*1024);analyzer.documents(documents);
            analyzer.diagnostics(file,text);
            assertCompletion(analyzer,file,text);
            assertThat(analyzer.status()).containsEntry("document_semantic_contexts",1);
            assertCompletion(analyzer,file,text);
            var before=work(analyzer);var storage=index.store().semanticWork();
            long queries=((Number)analyzer.status().get("queries")).longValue();
            var live=documents.liveState(context.sources()).status();
            for(int i=0;i<20;i++){
                // Exercise normal context selection too: a warm configure cannot rebuild classpath structure.
                analyzer.configure(context,index,256L*1024*1024);
                assertCompletion(analyzer,file,text);
            }
            assertThat(index.store().semanticWork()).isEqualTo(storage);
            assertThat(work(analyzer)).containsAllEntriesOf(before);
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(queries);
            var after=documents.liveState(context.sources()).status();
            for(String key:List.of("reconciliations","targeted_reconciliations"))assertThat(after.get(key)).as(key).isEqualTo(live.get(key));

            // Unknown history rejects retained facts. Recovery happens once, then the same cheap
            // validation path is available again; UNKNOWN is never treated as equality.
            documents.liveState(context.sources()).markUncertain("test lost watch history");
            analyzer.settleSourceEvents();assertCompletion(analyzer,file,text);
            assertCompletion(analyzer,file,text);
            var recovered=work(analyzer);assertCompletion(analyzer,file,text);
            assertThat(work(analyzer)).containsAllEntriesOf(recovered);
        }
    }

    @Test void publicationRefreshesExactAndNegativeObservationsBeforeTheNextRead()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path a=IndexFixtures.jar(repo.resolve("a"),"a","Target.java","package p; public class Target {}",false);
        Path b=IndexFixtures.jar(repo.resolve("b"),"b","Target.java","package p; public final class Target {}",false);
        try(var index=new IndexService(root.resolve("index.db"),repo)){
            index.indexJar(a,"fixture:a:1","jar");index.indexJar(b,"fixture:b:1","jar");
            load(index,a,b);
            var store=index.store();var layer=IndexStore.SemanticLayer.MACHINE;
            var first=store.semanticType("p.Target","w",layer);
            store.semanticByScip(first.id(),"w",layer);
            var search=store.semanticClasspathSearch("w","p.Target").orElseThrow();
            store.semanticClasspathSequence("w");
            assertThat(store.semanticType("p.Missing","w",layer)).isNull();
            var initial=store.semanticWork();
            for(int i=0;i<20;i++){
                assertThat(store.semanticByScip(first.id(),"w",layer)).isEqualTo(first);
                assertThat(store.semanticType("p.Missing","w",layer)).isNull();
                assertThat(store.semanticClasspathSearch("w","p.Target").orElseThrow()).isEqualTo(search);
                store.semanticClasspathSequence("w");
            }
            load(index,a,b);
            assertThat(store.semanticWork()).as("unchanged workspace selection is not a mutation").isEqualTo(initial);
            load(index,b,a);var published=store.semanticWork();
            assertThat(store.semanticType("p.Target","w",layer).id()).isNotEqualTo(first.id());
            assertThat(store.semanticClasspathSearch("w","p.Target").orElseThrow().winnerArtifactKey()).isEqualTo(b.toString());
            store.semanticClasspathSequence("w");
            assertThat(store.semanticWork()).as("reorder refresh belongs to publication, not the following read").isEqualTo(published);

            // Source overlays may add a previously absent exact lookup even on an installed handle.
            String scip="maven fixture/a 1 p/Missing#";Path source=root.resolve("Missing.java");
            var row=Map.<String,Object>of("scip",scip,"name","Missing","fqn","p.Missing","kind","class",
                    "binary_key","p.Missing","source_file",source.toString());
            assertThat(store.semanticByScip(scip,"w",layer)).isNull();
            store.publishSourceFile(index.artifact(a).id(),source,"hash",List.of(row),2,List.of());
            var admitted=store.semanticWork();
            assertThat(store.semanticByScip(scip,"w",layer)).isNotNull();
            assertThat(store.semanticType("p.Missing","w",layer)).isNotNull();
            assertThat(store.semanticWork()).isEqualTo(admitted);
            store.publishSourceFile(index.artifact(a).id(),source,"removed",List.of(),2,List.of());
            var removed=store.semanticWork();
            assertThat(store.semanticByScip(scip,"w",layer)).isNull();
            assertThat(store.semanticType("p.Missing","w",layer)).isNull();
            assertThat(store.semanticWork()).isEqualTo(removed);
            store.publishSourceFile(index.artifact(a).id(),source,"restored",List.of(row),2,List.of());
            // Memory pressure can retire a subscription, but cannot turn UNKNOWN into absence
            // or trigger index reconstruction inside a read-only proof lookup.
            store.semanticType("p.Victim","w",layer);
            for(int i=0;i<600;i++)store.semanticType("p.Other"+i,"w",layer);
            var bounded=store.semanticWork();
            assertThatThrownBy(()->store.observedSemanticType("p.Victim","w",layer))
                    .isInstanceOf(IndexStore.UnobservedSemanticQuery.class);
            assertThat(store.semanticWork()).isEqualTo(bounded);
        }
        try(var reopened=new IndexService(root.resolve("index.db"),repo)){
            load(reopened,a,b);
            assertThat(reopened.store().semanticType("p.Missing","w",IndexStore.SemanticLayer.MACHINE))
                    .as("source artifact membership is reconstructed on reopen").isNotNull();
        }
    }

    @Test void unchangedArtifactPublicationStillRetiresRemovedSourceObservations()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path module=Files.createDirectories(root.resolve("module"));
        Path source=Files.writeString(module.resolve("Gone.java"),"package p; class Gone {}");
        try(var index=new IndexService(root.resolve("index.db"),repo)){
            var store=index.store();var layer=IndexStore.SemanticLayer.LOCAL;
            var key=ArtifactIndexFormat.key("1".repeat(64),"local-signatures");
            var input=new IndexStore.ArtifactInput(new ArtifactContext("fixture:app:1","local",module.toString()),key,0,0);
            var facts=new ArtifactIndexFormat.ArtifactData(key,List.of(),List.of());
            long id=store.publishArtifact(input,facts,Set.of(),Map.of());
            load(index,module);
            String scip="maven fixture/app 1 p/Gone#";
            var row=Map.<String,Object>of("scip",scip,"name","Gone","fqn","p.Gone","kind","class",
                    "binary_key","p.Gone","source_file",source.toString());
            store.publishSourceFile(id,source,Hashing.sha256(source),List.of(row),2,List.of());
            assertThat(store.semanticType("p.Gone","w",layer)).isNotNull();
            Files.delete(source);
            // Binary metadata can be equal while publication removes a stale source overlay.
            store.publishArtifact(input,facts,Set.of(),Map.of());
            var published=store.semanticWork();
            assertThat(store.observedSemanticType("p.Gone","w",layer)).isNull();
            assertThat(store.semanticWork()).isEqualTo(published);
        }
    }

    private static void load(IndexService index,Path... paths)throws Exception{
        index.loadWorkspace("w",Arrays.stream(paths).map(path->new IndexService.WorkspaceArtifact(path.toString(),"compile")).toList(),List.of());
    }
    private static Map<String,Long> work(Analyzer analyzer){
        @SuppressWarnings("unchecked") var values=(Map<String,Long>)analyzer.status().get("semantic_read_work");
        var result=new HashMap<>(values);result.remove("document_proof_checks");
        result.put("bindings",((Number)analyzer.status().get("binding_computations")).longValue());return result;
    }
    private static void assertCompletion(Analyzer analyzer,Path file,String text)throws Exception{
        int cursor=text.indexOf("choose(project).")+"choose(project).".length();
        var position=Documents.position(text,cursor);
        var answer=analyzer.completion(file,text,position.line(),position.character(),100,0);
        assertThat(answer.warnings()).isEmpty();
        assertThat(Json.MAPPER.valueToTree(answer.result()).path("items").findValuesAsText("name")).contains("getValue");
    }
}
