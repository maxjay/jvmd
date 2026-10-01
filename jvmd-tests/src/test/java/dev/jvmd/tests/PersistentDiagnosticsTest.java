package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.index.SemanticMemoStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Persisted diagnostics are the attributed LOCAL memos only (strict task W7). */
@Tag("phase-4")
class PersistentDiagnosticsTest {
    @TempDir Path root;
    private static final String GAV="test:app:1";
    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private SemanticMemoStore memos(){return new SemanticMemoStore(root.resolve("cache"));}

    @Test void revertingToPersistedApiResolvesPendingChangeWithoutRecompilingTheApi()throws Exception{
        Path api=sources().resolve("Api.java"),use=sources().resolve("Use.java");
        String original="class Api { int value(){return 1;} }";
        Files.writeString(api,original);Files.writeString(use,"class Use { int n=new Api().value(); }");
        String originalFingerprint;
        try(var analyzer=analyzer(new Documents())){
            analyzer.diagnostics(api,new Documents());originalFingerprint=analyzer.apiFingerprint(api);analyzer.awaitMemoWrites();
        }
        Files.writeString(api,"class Api { String value(){return \"changed\";} }");
        var documents=new Documents();
        try(var analyzer=analyzer(documents)){
            analyzer.diagnostics(api,documents);
            assertThat(((Map<?,?>)analyzer.diagnostics(use,documents).result()).get("diagnostics")).asList().hasSize(1);
            assertThat(analyzer.apiFingerprint(api)).isNotEqualTo(originalFingerprint);
            Files.writeString(api,original);analyzer.changed(api,documents.sourceHash(api));
            long before=((Number)analyzer.status().get("queries")).longValue();
            analyzer.diagnostics(api,documents);
            assertThat(analyzer.status()).as("the original content's memo is restored").containsEntry("queries",before);
            assertThat(analyzer.apiFingerprint(api)).isEqualTo(originalFingerprint);
            assertThat(analyzer.pendingPrerequisites(use)).isEmpty();
            assertThat(((Map<?,?>)analyzer.diagnostics(use,documents).result()).get("diagnostics")).asList().isEmpty();
        }
    }
    @Test void restartRestoresWithoutJavacAndCorruptionRebuilds()throws Exception{
        Path file=sources().resolve("A.java");Files.writeString(file,"class A { int value=\"bad\"; }");Envelope initial;
        try(var analyzer=analyzer(new Documents())){initial=analyzer.diagnostics(file,new Documents());assertThat(analyzer.status()).containsEntry("queries",1L);analyzer.awaitMemoWrites();}
        try(var analyzer=analyzer(new Documents())){
            assertThat(analyzer.diagnostics(file,new Documents())).isEqualTo(initial);
            assertThat(analyzer.status()).containsEntry("queries",0L);
        }
        try(var paths=Files.walk(root.resolve("cache"))){for(Path object:paths.filter(path->path.toString().endsWith(".memo")).toList())Files.writeString(object,"corrupt");}
        try(var analyzer=analyzer(new Documents())){
            assertThat(analyzer.diagnostics(file,new Documents())).isEqualTo(initial);assertThat(analyzer.status()).containsEntry("queries",1L);
        }
    }
    @Test void restoredStatesValidateUnsavedDependencyContentAndRehydrateReverseEdges()throws Exception{
        Path api=sources().resolve("Api.java"),use=sources().resolve("Use.java");String original="class Api { int value(){return 1;} }";
        Files.writeString(api,original);Files.writeString(use,"class Use { int n=new Api().value(); }");
        try(var analyzer=analyzer(new Documents())){
            assertThat(analyzer.diagnostics(api,new Documents()).warnings()).isEmpty();assertThat(analyzer.diagnostics(use,new Documents()).warnings()).isEmpty();
            analyzer.awaitMemoWrites();
        }
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
        var analyzer=new Analyzer();Path module=root.resolve("app"),sources=sources();
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"ctx",Map.of(module.toString(),GAV,sources.toString(),GAV)),null,128L*1024*1024);
        analyzer.documents(documents);analyzer.memos(memos());return analyzer;
    }
}
