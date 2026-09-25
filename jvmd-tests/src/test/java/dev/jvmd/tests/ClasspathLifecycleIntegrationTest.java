package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class ClasspathLifecycleIntegrationTest {
    @TempDir Path root;

    @Test void realClasspathLifecycleUsesSearchProofFixedPointsAndProofDag()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo"));
        Path a=IndexFixtures.jar(repo,"a","package a; public class Sample { public int getA(){return 1;} }",true);
        Path b=IndexFixtures.jar(repo,"b","package b; public class Sample { public int getB(){return 2;} }",true);
        Path c=IndexFixtures.jar(repo,"c","package c; public class Sample { public int getC(){return 3;} }",true);
        Path sources=Files.createDirectories(root.resolve("src"));
        Path file=sources.resolve("Use.java");

        try(var index=new IndexService(root.resolve("index.db"),repo);
            var analyzer=new Analyzer()){
            index(index,a,"fixture:a:1");index(index,b,"fixture:b:1");index(index,c,"fixture:c:1");
            load(index,List.of(a,b,c));

            String aSource="class Use { a.Sample choose(a.Sample value){return value;} Object f(a.Sample value){return choose(value).get;} }";
            Files.writeString(file,aSource);var documents=new Documents();documents.open(file,aSource,1);int version=1;
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g1");
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            long initialQueries=currentQueries(analyzer);
            assertThat(initialQueries).isPositive();
            long aMutations=semantic(analyzer,"semantic_fact_mutations");
            long aUnits=semantic(analyzer,"semantic_units");
            long aContexts=statusLong(analyzer,"document_semantic_contexts");
            assertThat(aContexts).isPositive();
            assertThat(statusLong(analyzer,"semantic_proof_consumers")).isPositive();

            // Lazy environment detection: C changes in place without Analyzer.configure(). javac's
            // environment is reset, but the detached A-winning proof is reconciled before any
            // semantic context/accessibility/resident state is destroyed.
            c=IndexFixtures.jar(repo,"c","package c; public class Sample { public int getC(){return 3;} public int extra(){return 4;} }",true);
            index(index,c,"fixture:c:1");load(index,List.of(a,b,c));
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            assertThat(currentQueries(analyzer)).as("lazy later-C change must not re-run javac").isEqualTo(initialQueries);
            assertThat(evidence(analyzer,"validated_input_reconciliations")).isPositive();
            assertThat(evidence(analyzer,"last_reconsidered")).isZero();
            assertThat(evidence(analyzer,"last_changed")).isZero();
            assertThat(semantic(analyzer,"semantic_fact_mutations")).isEqualTo(aMutations);
            assertThat(semantic(analyzer,"semantic_units")).isEqualTo(aUnits);
            assertThat(statusLong(analyzer,"document_semantic_contexts")).isEqualTo(aContexts);

            // Reorder strictly after the first-slot winner. The ordered root changes, but the old
            // search prefix is untouched, so the A proof is not even reconsidered.
            load(index,List.of(a,c,b));
            configure(analyzer,index,documents,sources,List.of(a,c,b),"g2");
            assertThat(evidence(analyzer,"last_intervals")).isGreaterThan(0);
            assertThat(evidence(analyzer,"last_reconsidered")).isZero();
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            assertThat(currentQueries(analyzer)).isZero();
            assertThat(semantic(analyzer,"semantic_fact_mutations")).isEqualTo(aMutations);

            // Restore the tail order; this is still strictly after A and therefore still cannot
            // affect the A-winning proof.
            load(index,List.of(a,b,c));
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g3");
            assertThat(evidence(analyzer,"last_reconsidered")).isZero();
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            assertThat(currentQueries(analyzer)).isZero();

            // Insert D before A but without a.Sample. The search is structurally affected and is
            // recomputed, but the semantic winner remains A, so equality stops before ProofDag.
            Path d=IndexFixtures.jar(repo,"d","Other.java","package d; public class Other { public int value(){return 1;} }",true);
            index(index,d,"fixture:d:1");load(index,List.of(d,a,b,c));
            configure(analyzer,index,documents,sources,List.of(d,a,b,c),"g4");
            assertThat(evidence(analyzer,"last_reconsidered")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_equal")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_changed")).isZero();
            assertThat(evidence(analyzer,"last_consumers_visited")).isZero();
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            assertThat(currentQueries(analyzer)).isZero();
            assertThat(semantic(analyzer,"semantic_fact_mutations")).isEqualTo(aMutations);

            // D now introduces the searched binary before A. The CLASSPATH_SEARCH leaf changes,
            // the existing ProofDag visits only the registered document context, and the next
            // request performs exactly one bounded semantic re-attribution.
            d=IndexFixtures.jar(repo,"d","package a; public class Sample { public int getD(){return 4;} }",true);
            index(index,d,"fixture:d:1");load(index,List.of(d,a,b,c));
            configure(analyzer,index,documents,sources,List.of(d,a,b,c),"g5");
            assertThat(evidence(analyzer,"last_reconsidered")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_changed")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_consumers_visited")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_consumers_changed")).isEqualTo(1);
            assertThat(statusLong(analyzer,"document_semantic_contexts")).isZero();
            assertNames(analyzer,file,aSource,"choose(value).get","getD");
            assertThat(currentQueries(analyzer)).isEqualTo(1);
            assertThat(statusLong(analyzer,"document_semantic_contexts")).isPositive();

            // Remove D and return to A. This relevant winner transition is precise as well.
            load(index,List.of(a,b,c));
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g6");
            assertThat(evidence(analyzer,"last_changed")).isEqualTo(1);
            assertNames(analyzer,file,aSource,"choose(value).get","getA");
            assertThat(currentQueries(analyzer)).isEqualTo(1);

            // Establish a C-winning context. The first request on its fresh compiler is the bounded
            // context fallback; subsequent C transitions are evaluated through the search proof.
            String cSource="class Use { c.Sample choose(c.Sample value){return value;} Object f(c.Sample value){return choose(value).get;} }";
            Files.writeString(file,cSource);documents.change(file,++version,List.of(new Documents.Change(null,cSource)));
            analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertNames(analyzer,file,cSource,"choose(value).get","getC");
            assertThat(currentQueries(analyzer)).isGreaterThan(1);
            long cMutations=semantic(analyzer,"semantic_fact_mutations");
            long cUnits=semantic(analyzer,"semantic_units");

            // C changes structurally, but the winning type resolution identity is equal. Search is
            // reconsidered and reaches a fixed point; no ProofDag consumer or javac query runs.
            c=IndexFixtures.jar(repo,"c","package c; public class Sample { public int getC(){return 5;} public int unrelated(){return 6;} }",true);
            index(index,c,"fixture:c:1");load(index,List.of(a,b,c));
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g7");
            assertThat(evidence(analyzer,"last_reconsidered")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_equal")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_changed")).isZero();
            assertThat(evidence(analyzer,"last_consumers_visited")).isZero();
            assertNames(analyzer,file,cSource,"choose(value).get","getC");
            assertThat(currentQueries(analyzer)).isZero();
            assertThat(semantic(analyzer,"semantic_fact_mutations")).isEqualTo(cMutations);
            assertThat(semantic(analyzer,"semantic_units")).isEqualTo(cUnits);

            // A resolution-relevant C type change changes exactly the search leaf. Production
            // ProofDag invalidates the proof-backed context; one bounded javac context query follows.
            c=IndexFixtures.jar(repo,"c","package c; public final class Sample { public int getC(){return 7;} }",true);
            index(index,c,"fixture:c:1");load(index,List.of(a,b,c));
            configure(analyzer,index,documents,sources,List.of(a,b,c),"g8");
            assertThat(evidence(analyzer,"last_reconsidered")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_changed")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_consumers_visited")).isEqualTo(1);
            assertThat(evidence(analyzer,"last_consumers_changed")).isEqualTo(1);
            assertNames(analyzer,file,cSource,"choose(value).get","getC");
            assertThat(currentQueries(analyzer)).isEqualTo(1);

            // Every supported transition stayed precise: no coarse environment fallback was used.
            assertThat(evidence(analyzer,"coarse_fallbacks")).isZero();
            assertThat(evidence(analyzer,"search_proofs_unavailable")).isZero();
        }
    }

    private static void index(IndexService index,Path path,String gav)throws Exception{
        index.indexJar(path,gav,"jar");
    }
    private static void load(IndexService index,List<Path> paths)throws Exception{
        index.loadWorkspace("w",paths.stream().map(path->new IndexService.WorkspaceArtifact(path.toString(),"compile")).toList(),List.of());
    }
    private static void configure(Analyzer analyzer,IndexService index,Documents documents,Path sources,List<Path> classpath,String generation)throws Exception{
        analyzer.configure(new Analyzer.Context(
                "fixture:app:1","25",classpath,List.of(sources),generation,Map.of(),
                List.of("--release","25"),Set.of(),List.of(),List.of(sources),true,"w"),
                index,256L*1024*1024);
        analyzer.documents(documents);
    }
    private static void assertNames(Analyzer analyzer,Path file,String source,String needle,String expected)throws Exception{
        int cursor=source.indexOf(needle)+needle.length();
        var position=Documents.position(source,cursor);
        var answer=analyzer.completion(file,source,position.line(),position.character(),100,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        JsonNode result=Json.MAPPER.valueToTree(answer.result());
        assertThat(result.path("items").findValuesAsText("name")).contains(expected);
    }
    @SuppressWarnings("unchecked")
    private static long semantic(Analyzer analyzer,String key){
        var resident=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
        return ((Number)resident.get(key)).longValue();
    }
    @SuppressWarnings("unchecked")
    private static long evidence(Analyzer analyzer,String key){
        var value=(Map<String,Object>)analyzer.status().get("classpath_proof_evidence");
        Object found=value.get(key);
        if(found instanceof Collection<?> collection)return collection.size();
        return ((Number)found).longValue();
    }
    private static long statusLong(Analyzer analyzer,String key){
        return ((Number)analyzer.status().get(key)).longValue();
    }
    private static long currentQueries(Analyzer analyzer){
        return ((Number)analyzer.status().get("queries")).longValue();
    }
}
