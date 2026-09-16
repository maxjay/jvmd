package dev.jvmd.tests;

import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Architectural diagnostics assertions: warm snapshots must not fall back to javac. */
@Tag("phase-4")
class IncrementalDiagnosticsStoreTest {
    @TempDir Path temp;

    @Test void unchangedWorkspaceAndPaginationPerformZeroAdditionalJavacQueries()throws Exception{
        for(int i=0;i<40;i++)Files.writeString(temp.resolve("Source"+i+".java"),"class Source"+i+" { int value() { return "+i+"; } }\n");
        try(var daemon=new AotDaemon(temp)){
            String session=daemon.request("session.open",Map.of("root",temp.toString())).path("result").path("session").asText();
            long started=System.nanoTime();var first=daemon.request("diag.get",Map.of("session",session,"limit",1000));double coldMs=(System.nanoTime()-started)/1e6;
            assertThat(first.path("result").path("diagnostics").isArray()).isTrue();
            var warm=daemon.request("session.status",Map.of("session",session)).path("result").path("analyzer");
            long warmQueries=warm.path("queries").asLong(),warmAnalysed=warm.path("diagnostic_files_analysed").asLong(),warmIndexWrites=warm.path("index_record_source_calls").asLong();
            assertThat(warmQueries).isEqualTo(40L);
            assertThat(warmAnalysed).isEqualTo(40L);
            assertThat(warm.path("diagnostic_store").path("entries").asLong()).isEqualTo(40L);

            started=System.nanoTime();var second=daemon.request("diag.get",Map.of("session",session,"limit",1000));double repeatedMs=(System.nanoTime()-started)/1e6;
            assertThat(second.path("result").path("diagnostics")).isEqualTo(first.path("result").path("diagnostics"));
            var repeated=daemon.request("session.status",Map.of("session",session)).path("result").path("analyzer");
            assertThat(repeated.path("queries").asLong()).as("unchanged javac queries").isEqualTo(warmQueries);
            assertThat(repeated.path("diagnostic_files_analysed").asLong()).as("unchanged files reanalysed").isEqualTo(warmAnalysed);
            assertThat(repeated.path("index_record_source_calls").asLong()).as("unchanged synchronous index writes").isEqualTo(warmIndexWrites);
            assertThat(repeated.path("diagnostic_files_reused").asLong()).isGreaterThanOrEqualTo(40L);

            var page1=daemon.request("diag.get",Map.of("session",session,"limit",10));
            if(page1.path("more").asBoolean())daemon.request("diag.get",Map.of("session",session,"limit",10,"cursor",page1.path("cursor").asText()));
            var paged=daemon.request("session.status",Map.of("session",session)).path("result").path("analyzer");
            assertThat(paged.path("queries").asLong()).as("pagination javac queries").isEqualTo(warmQueries);
            assertThat(paged.path("diagnostic_files_analysed").asLong()).as("pagination files reanalysed").isEqualTo(warmAnalysed);

            var measured=new LinkedHashMap<String,Object>();measured.put("files",40);measured.put("cold_ms",coldMs);measured.put("repeated_ms",repeatedMs);measured.put("cold_javac_queries",warmQueries);measured.put("repeated_additional_javac_queries",repeated.path("queries").asLong()-warmQueries);measured.put("repeated_files_reanalysed",repeated.path("diagnostic_files_analysed").asLong()-warmAnalysed);measured.put("repeated_index_writes",repeated.path("index_record_source_calls").asLong()-warmIndexWrites);
            System.out.println("diagnostics-perf "+Json.MAPPER.writeValueAsString(measured));Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/diagnostics-perf.json").toFile(),measured);
            assertThat(repeatedMs).as("40-file unchanged warm diag.get ms").isLessThan(500d);
        }
    }
}
