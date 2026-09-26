package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Unchanged diagnostics must reuse the resident project model without entering Maven. */
@Tag("phase-2")
class DiagnosticRequestContextTest {
    @TempDir Path temp;

    @Test void procFullCompletionMaintainsAnalyzerContextAcrossUnchangedReads()throws Exception{
        Files.writeString(temp.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>proc-context</artifactId><version>1</version>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <maven.compiler.proc>full</maven.compiler.proc>
                  </properties>
                </project>
                """);
        Path sources=Files.createDirectories(temp.resolve("src/main/java/fixture"));
        Files.writeString(sources.resolve("Api.java"),"package fixture; class Api { int value(){return 1;} }\n");
        Path use=sources.resolve("Use.java");
        String text="package fixture; class Use { Object f(Api api){ return api.val; } }";
        Files.writeString(use,text);
        try(var daemon=new AotDaemon(temp)){
            String session=daemon.request("session.open",Map.of("root",temp.toString())).path("result").path("session").asText();
            daemon.request("document.open",Map.of("session",session,"path",use.toString(),"text",text,"version",1));
            var admitted=daemon.request("session.status",Map.of("session",session)).path("result");
            long admittedConstructions=admitted.path("analysis_contexts").path("context_constructions").asLong();
            assertThat(admittedConstructions).as("document admission must publish the analyzer context").isEqualTo(1L);

            int cursor=text.indexOf("api.val")+"api.val".length();
            var params=Map.<String,Object>of("session",session,"path",use.toString(),"line",0,"character",cursor,"limit",50);
            var first=daemon.request("symbol.completion",params);
            assertThat(first.path("result").path("items").findValuesAsText("name")).contains("value");
            var second=daemon.request("symbol.completion",params);
            assertThat(second.path("result").path("items")).isEqualTo(first.path("result").path("items"));
            var status=daemon.request("session.status",Map.of("session",session)).path("result");
            assertThat(status.path("analysis_contexts").path("context_constructions").asLong())
                    .as("completion must not reconstruct the context admitted by the mutation").isEqualTo(admittedConstructions);
            assertThat(status.path("analysis_contexts").path("maintained_hits").asLong()).isPositive();
        }
    }

    @Test void workspaceResolutionStaysResidentAcrossUnchangedDiagnosticRpcs()throws Exception{
        Files.writeString(temp.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>diag-context</artifactId><version>1</version>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                </project>
                """);
        Path sources=Files.createDirectories(temp.resolve("src/main/java/fixture"));
        for(int i=0;i<4;i++)Files.writeString(sources.resolve("Source"+i+".java"),"package fixture; class Source"+i+" { int value(){ return "+i+"; } }\n");
        try(var daemon=new AotDaemon(temp)){
            String session=daemon.request("session.open",Map.of("root",temp.toString())).path("result").path("session").asText();
            var before=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            long calls=before.path("resolve_calls").asLong(),workspaceCalls=before.path("resolve_workspace_calls").asLong(),
                    hits=before.path("project_model_fast_hits").asLong();
            daemon.request("diag.get",Map.of("session",session,"limit",1000));
            var after=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            assertThat(after.path("resolve_calls").asLong()-calls).as("unchanged diagnostics must not enter Maven bundle resolution").isZero();
            assertThat(after.path("resolve_workspace_calls").asLong()).as("unchanged diagnostics must not enter resolveWorkspace at all")
                    .isEqualTo(workspaceCalls);
            assertThat(after.path("project_model_fast_hits").asLong()).as("no request-time resolver lookup means no resolver fast-hit either")
                    .isEqualTo(hits);
            var status=daemon.request("session.status",Map.of("session",session)).path("result");
            assertThat(status.path("analysis_contexts").path("context_constructions").asLong()).isEqualTo(1L);

            Files.writeString(temp.resolve("pom.xml"),Files.readString(temp.resolve("pom.xml")).replace("</project>","<!-- model change -->\n</project>"));
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();JsonNode changed=null;
            while(System.nanoTime()<deadline){
                changed=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
                if(changed.path("resolve_workspace_calls").asLong()>workspaceCalls)break;
                Thread.sleep(20);
            }
            assertThat(changed).isNotNull();
            assertThat(changed.path("resolve_calls").asLong()).isEqualTo(calls+1);
            assertThat(changed.path("resolve_workspace_calls").asLong()).isEqualTo(workspaceCalls+1);
            assertThat(changed.path("project_model_invalidations").asLong()).isPositive();

            long settledCalls=changed.path("resolve_calls").asLong(),settledWorkspaceCalls=changed.path("resolve_workspace_calls").asLong(),
                    settledHits=changed.path("project_model_fast_hits").asLong();
            daemon.request("diag.get",Map.of("session",session,"limit",1000));
            daemon.request("diag.get",Map.of("session",session,"limit",1000));
            var settled=daemon.request("daemon.status",Map.of()).path("result").path("resolver");
            assertThat(settled.path("resolve_calls").asLong()).isEqualTo(settledCalls);
            assertThat(settled.path("resolve_workspace_calls").asLong()).isEqualTo(settledWorkspaceCalls);
            assertThat(settled.path("project_model_fast_hits").asLong()).isEqualTo(settledHits);
        }
    }
}
