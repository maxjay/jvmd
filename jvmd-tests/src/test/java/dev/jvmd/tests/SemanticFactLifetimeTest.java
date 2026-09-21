package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class SemanticFactLifetimeTest {
    @TempDir Path root;
    @Test void zeroDecodedBudgetRetainsFactsAndOldReadRevisionAcrossEditsAndDeletion()throws Exception {
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java"),other=root.resolve("Other.java");
        Files.writeString(api,"class Api { static int a(){return 1;} static int b(){return 2;} }");
        Files.writeString(use,"class Use { int value(){return Api.a();} }");Files.writeString(other,"class Other {}");
        var files=new ArrayList<>(List.of(api,use,other));var loaded=new ArrayList<Path>();var documents=new Documents();
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,64L*1024*1024);
            WorkspaceBindings.Loader loader=(file,text)->{loaded.add(file);return analyzer.bindings(file,text,null);};
            var old=cache.get(()->files,List.of(),documents,"1",0,loader);assertThat(loaded).hasSize(3);loaded.clear();
            var method=old.lookup("Use/value()").getFirst().get("scip").toString();
            assertThat(old.adjacent(Set.of(method),true)).anyMatch(e->e.dst().endsWith("Api#a()."));
            var warm=cache.get(()->files,List.of(),documents,"1",0,loader);assertThat(warm).isSameAs(old);assertThat(loaded).isEmpty();
            Files.writeString(use,"class Use { int value(){return Api.b();} }");
            var changed=cache.get(()->files,List.of(),documents,"1",0,loader);assertThat(loaded).containsExactly(use);loaded.clear();
            assertThat(changed.adjacent(Set.of(method),true)).anyMatch(e->e.dst().endsWith("Api#b().")).noneMatch(e->e.dst().endsWith("Api#a()."));
            assertThat(old.adjacent(Set.of(method),true)).anyMatch(e->e.dst().endsWith("Api#a().")).noneMatch(e->e.dst().endsWith("Api#b()."));
            Files.delete(use);files.remove(use);var deleted=cache.get(()->files,List.of(),documents,"1",0,loader);
            assertThat(deleted.symbol(method)).isNull();assertThat(deleted.adjacent(Set.of(method),true)).isEmpty();assertThat(loaded).isEmpty();
            assertThat(cache.status()).containsEntry("fragment_files",2).containsEntry("decoded_estimated_bytes",0L).doesNotContainKey("serialized_bytes");
        }
    }
    @Test void incompleteObservationIsRetriedInsteadOfBecomingAuthoritative()throws Exception {
        Path file=root.resolve("A.java");Files.writeString(file,"class A {}");int[] loads={0};
        try(var cache=new WorkspaceBindings()){
            WorkspaceBindings.Loader loader=(path,text)->{loads[0]++;return new CompilerPool.Outcome<>(1,new Bindings.Snapshot(Map.of(),List.of(),List.of(),Set.of()),List.of(),List.of("analyzer_fault: fixture"));};
            cache.get(()->List.of(file),List.of(),new Documents(),"1",0,loader);cache.get(()->List.of(file),List.of(),new Documents(),"1",0,loader);
            assertThat(loads[0]).isEqualTo(2);
        }
    }
}
