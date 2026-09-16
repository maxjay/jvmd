package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.Documents;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** API fingerprints keep reverse dependants warm for implementation-only edits. */
@Tag("phase-4")
class ApiFingerprintInvalidationTest {
    @TempDir Path root;

    @Test void bodyOnlyEditDoesNotAttributeDependantsButSignatureEditDoes()throws Exception{
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java");
        String original="class Api { int value() { return 1; } }";
        String useText="class Use { int use() { return new Api().value(); } }";
        Files.writeString(api,original);Files.writeString(use,useText);
        var documents=new Documents();documents.open(api,original,1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"ctx",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            analyzer.documents(documents);
            assertThat(diagnostics(analyzer,api,original)).isEmpty();
            assertThat(diagnostics(analyzer,use,useText)).isEmpty();
            long warmQueries=number(analyzer,"queries");

            String bodyOnly="class Api { int value() { return 2; } }";
            documents.change(api,2,List.of(new Documents.Change(null,bodyOnly)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,bodyOnly)).isEmpty();
            long afterBody=number(analyzer,"queries");
            assertThat(afterBody).isEqualTo(warmQueries+1);
            assertThat(diagnostics(analyzer,use,useText)).isEmpty();
            assertThat(number(analyzer,"queries")).as("body-only dependant attribution").isEqualTo(afterBody);
            assertThat(number(analyzer,"api_fingerprint_unchanged")).isEqualTo(1L);

            String signature="class Api { String value() { return \"2\"; } }";
            documents.change(api,3,List.of(new Documents.Change(null,signature)));analyzer.documents(documents);analyzer.changed(api);
            assertThat(diagnostics(analyzer,api,signature)).isEmpty();
            long afterApi=number(analyzer,"queries");
            assertThat(afterApi).isEqualTo(afterBody+1);
            assertThat(number(analyzer,"api_fingerprint_changes")).isEqualTo(1L);
            assertThat(diagnostics(analyzer,use,useText)).anyMatch(problem->problem.code().startsWith("compiler.err.prob.found.req"));
            assertThat(number(analyzer,"queries")).as("API-change dependant attribution").isEqualTo(afterApi+1);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CompilerPool.Problem> diagnostics(Analyzer analyzer,Path file,String text)throws Exception{
        var envelope=analyzer.diagnostics(file,text);return (List<CompilerPool.Problem>)((Map<String,Object>)envelope.result()).get("diagnostics");
    }
    private static long number(Analyzer analyzer,String key){return ((Number)analyzer.status().get(key)).longValue();}
}
