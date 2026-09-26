package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SemanticUpdatePolicyTest {
    @TempDir Path root;
    private FileSemanticContribution value(String name,String hash,String api,Set<Path> deps,Set<String> exports,Set<String> unresolved){
        return new FileSemanticContribution(root.resolve(name),hash,api,deps,exports,unresolved);
    }
    @Test void completeReplacesEdgesWhileFocusedAndFailedCannotEraseThem(){
        var live=new SemanticUpdatePolicy.Live();Path a=root.resolve("A"),b=root.resolve("B"),c=root.resolve("C");
        var first=value("B","1","b",Set.of(a),Set.of("p.B"),Set.of("Missing"));
        live.update(first,SemanticUpdatePolicy.Completeness.COMPLETE);
        live.update(value("B","2","partial",Set.of(c),Set.of(),Set.of()),SemanticUpdatePolicy.Completeness.FOCUSED);
        assertThat(live.dependencies(b)).containsExactlyInAnyOrder(a,c);
        assertThat(live.contribution(b)).isEqualTo(first);
        live.update(value("B","3","failed",Set.of(),Set.of(),Set.of()),SemanticUpdatePolicy.Completeness.FAILED);
        assertThat(live.dependencies(b)).containsExactlyInAnyOrder(a,c);
        live.update(value("B","4","b",Set.of(),Set.of("p.B"),Set.of()),SemanticUpdatePolicy.Completeness.COMPLETE);
        assertThat(live.dependants(a)).isEmpty();assertThat(live.dependants(c)).isEmpty();
        assertThat(live.unresolved(Set.of("Missing"))).isEmpty();
    }
    @Test void preciseClasspathEnvironmentDoesNotWidenToEveryFile(){
        var postings=new SemanticUpdatePolicy.Postings(){
            public Set<Path> dependants(Path file){return Set.of();}
            public Set<Path> unresolved(Set<String> exports){return Set.of();}
            public Set<Path> files(){return Set.of(root.resolve("A"),root.resolve("B"),root.resolve("C"));}
        };
        var bodyBefore=value("A","1","api",Set.of(),Set.of("A"),Set.of());
        var bodyAfter=value("A","2","api",Set.of(),Set.of("A"),Set.of());

        var precise=SemanticUpdatePolicy.decide(
                List.of(new SemanticUpdatePolicy.Change(bodyBefore,bodyAfter)),
                SemanticUpdatePolicy.EnvironmentTransition.PRECISE_CLASSPATH,postings);
        assertThat(precise.reanalyze()).containsExactly(bodyAfter.file());
        assertThat(precise.contextChanged()).isFalse();

        var unknown=SemanticUpdatePolicy.decide(
                List.of(new SemanticUpdatePolicy.Change(bodyBefore,bodyAfter)),
                SemanticUpdatePolicy.EnvironmentTransition.UNKNOWN,postings);
        assertThat(unknown.reanalyze()).containsExactlyInAnyOrder(root.resolve("A"),root.resolve("B"),root.resolve("C"));
        assertThat(unknown.contextChanged()).isTrue();
    }

    @Test void liveAndRocksAgreeAcrossReplacementDeletionCyclesAndUnresolvedChanges()throws Exception{
        var live=new SemanticUpdatePolicy.Live();
        try(var rocks=new RocksSemanticInvalidation(root.resolve("store"))){
            Path a=root.resolve("A"),b=root.resolve("B");
            var changes=List.of(
                value("A","1","a",Set.of(b),Set.of("p.A"),Set.of()),
                value("B","1","b",Set.of(a),Set.of("p.B"),Set.of()),
                value("U","1","u",Set.of(),Set.of("p.U"),Set.of("p.A.Child")),
                value("E","1","e",Set.of(),Set.of("p.E"),Set.of("*")),
                value("A","2","a",Set.of(b),Set.of("p.A"),Set.of()),
                value("A","3","a2",Set.of(b),Set.of("p.A"),Set.of()),
                value("B","2","b",Set.of(),Set.of("p.B"),Set.of()),
                value("A","4","a2",Set.of(b),Set.of("p.Renamed"),Set.of()));
            for(var change:changes)assertThat(live.update(change,SemanticUpdatePolicy.Completeness.COMPLETE)).isEqualTo(rocks.observeFile("m","ctx",change));
            assertThat(live.remove(a)).isEqualTo(rocks.removeFiles("m",Set.of(a)));
            assertThat(live.dependants(b)).isEmpty();
        }
    }
    @Test void bodyChangesDoNotInvalidateDependantsAndPendingIsResolvedOnlyByComplete(){
        var live=new SemanticUpdatePolicy.Live();Path a=root.resolve("A"),b=root.resolve("B");
        live.update(value("A","1","a",Set.of(),Set.of("A"),Set.of()),SemanticUpdatePolicy.Completeness.COMPLETE);
        live.update(value("B","1","b",Set.of(a),Set.of("B"),Set.of()),SemanticUpdatePolicy.Completeness.COMPLETE);
        live.changed(a);assertThat(live.prerequisites(b)).containsExactly(a);
        live.update(value("A","2","a",Set.of(),Set.of(),Set.of()),SemanticUpdatePolicy.Completeness.FOCUSED);
        assertThat(live.prerequisites(b)).containsExactly(a);
        var result=live.update(value("A","2","a",Set.of(),Set.of("A"),Set.of()),SemanticUpdatePolicy.Completeness.COMPLETE);
        assertThat(result.reanalyze()).containsExactly(a);assertThat(live.prerequisites(b)).isEmpty();
    }
}
