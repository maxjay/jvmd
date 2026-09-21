package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class PersistentDiagnosticsTest {
    @TempDir Path root;
    @Test void revertingToPersistedApiResolvesPendingChangeWithoutRecompilingTheApi()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String original="class Api { int value(){return 1;} }";
        Files.writeString(api,original);Files.writeString(use,"class Use { int n=new Api().value(); }");
        String originalFingerprint;
        try(var analyzer=analyzer(new Documents())){
            analyzer.diagnostics(api,new Documents());originalFingerprint=analyzer.apiFingerprint(api);
        }
        // Keep the original persisted snapshot while a separate live analyzer learns
        // the changed API. Attaching persistence afterwards makes the restore deterministic.
        Files.writeString(api,"class Api { String value(){return \"changed\";} }");
        var documents=new Documents();
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"ctx",Map.of(root.toString(),"test:app:1")),null,128L*1024*1024);
            analyzer.documents(documents);
            analyzer.diagnostics(api,documents);
            assertThat(((Map<?,?>)analyzer.diagnostics(use,documents).result()).get("diagnostics")).asList().hasSize(1);
            assertThat(analyzer.apiFingerprint(api)).isNotEqualTo(originalFingerprint);
            analyzer.persistence(root.resolve("cache"));
            Files.writeString(api,original);analyzer.changed(api,documents.sourceHash(api));
            long before=((Number)analyzer.status().get("queries")).longValue();
            analyzer.diagnostics(api,documents);
            assertThat(analyzer.status()).containsEntry("queries",before);
            assertThat(analyzer.apiFingerprint(api)).isEqualTo(originalFingerprint);
            assertThat(analyzer.pendingPrerequisites(use)).isEmpty();
            assertThat(((Map<?,?>)analyzer.diagnostics(use,documents).result()).get("diagnostics")).asList().isEmpty();
        }
    }
    @Test void restartRestoresWithoutJavacAndCorruptionRebuilds()throws Exception{
        Path file=root.resolve("A.java");Files.writeString(file,"class A { int value=\"bad\"; }");Envelope initial;
        try(var analyzer=analyzer(new Documents())){initial=analyzer.diagnostics(file,new Documents());assertThat(analyzer.status()).containsEntry("queries",1L);}
        try(var analyzer=analyzer(new Documents())){
            assertThat(analyzer.diagnostics(file,new Documents())).isEqualTo(initial);
            assertThat(analyzer.status()).containsEntry("queries",0L);
        }
        try(var paths=Files.walk(root.resolve("cache/objects"))){for(Path object:paths.filter(Files::isRegularFile).toList())Files.writeString(object,"corrupt");}
        try(var analyzer=analyzer(new Documents())){
            assertThat(analyzer.diagnostics(file,new Documents())).isEqualTo(initial);assertThat(analyzer.status()).containsEntry("queries",1L);
        }
    }
    @Test void restoredStatesValidateUnsavedDependencyContentAndRehydrateReverseEdges()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");String original="class Api { int value(){return 1;} }";
        Files.writeString(api,original);Files.writeString(use,"class Use { int n=new Api().value(); }");
        try(var analyzer=analyzer(new Documents())){assertThat(analyzer.diagnostics(api,new Documents()).warnings()).isEmpty();assertThat(analyzer.diagnostics(use,new Documents()).warnings()).isEmpty();}
        var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=analyzer(documents)){
            analyzer.diagnostics(api,documents);analyzer.diagnostics(use,documents);assertThat(analyzer.status()).containsEntry("queries",0L);
            documents.change(api,2,List.of(new Documents.Change(null,"class Api { String value(){return \"bad\";} }")));analyzer.documents(documents);analyzer.changed(api);
            analyzer.diagnostics(api,documents);
            var result=analyzer.diagnostics(use,documents);
            assertThat(((List<?>)((Map<?,?>)result.result()).get("diagnostics"))).isNotEmpty();
            assertThat(analyzer.status()).containsEntry("queries",2L);
        }
    }
    private Analyzer analyzer(Documents documents)throws Exception{
        var analyzer=new Analyzer();analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"ctx",Map.of(root.toString(),"test:app:1")),null,128L*1024*1024);
        analyzer.documents(documents);analyzer.persistence(root.resolve("cache"));return analyzer;
    }
}
