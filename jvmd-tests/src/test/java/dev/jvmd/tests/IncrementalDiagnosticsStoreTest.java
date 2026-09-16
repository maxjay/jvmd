package dev.jvmd.tests;

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
            String session=daemon.request("session.open",Map.of("root",temp.toString())).path("session").asText();
            var first=daemon.request("diag.get",Map.of("session",session,"limit",1000));
            assertThat(first.path("diagnostics").isArray()).isTrue();
            var warm=daemon.request("session.status",Map.of("session",session)).path("analyzer");
            long warmQueries=warm.path("queries").asLong(),warmAnalysed=warm.path("diagnostic_files_analysed").asLong(),warmIndexWrites=warm.path("index_record_source_calls").asLong();
            assertThat(warmQueries).isEqualTo(40L);
            assertThat(warmAnalysed).isEqualTo(40L);
            assertThat(warm.path("diagnostic_store").path("entries").asLong()).isEqualTo(40L);

            var second=daemon.request("diag.get",Map.of("session",session,"limit",1000));
            assertThat(second.path("diagnostics")).isEqualTo(first.path("diagnostics"));
            var repeated=daemon.request("session.status",Map.of("session",session)).path("analyzer");
            assertThat(repeated.path("queries").asLong()).as("unchanged javac queries").isEqualTo(warmQueries);
            assertThat(repeated.path("diagnostic_files_analysed").asLong()).as("unchanged files reanalysed").isEqualTo(warmAnalysed);
            assertThat(repeated.path("index_record_source_calls").asLong()).as("unchanged synchronous index writes").isEqualTo(warmIndexWrites);
            assertThat(repeated.path("diagnostic_files_reused").asLong()).isGreaterThanOrEqualTo(40L);

            var page1=daemon.request("diag.get",Map.of("session",session,"limit",10));
            if(page1.path("more").asBoolean())daemon.request("diag.get",Map.of("session",session,"limit",10,"cursor",page1.path("cursor").asText()));
            var paged=daemon.request("session.status",Map.of("session",session)).path("analyzer");
            assertThat(paged.path("queries").asLong()).as("pagination javac queries").isEqualTo(warmQueries);
            assertThat(paged.path("diagnostic_files_analysed").asLong()).as("pagination files reanalysed").isEqualTo(warmAnalysed);
        }
    }
}
