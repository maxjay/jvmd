package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.CompilerInputs;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import dev.jvmd.dist.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * E3 / M3 (corrective pass): a whole-workspace bindings build streams compiler outcomes and commits its
 * encoded facts in bounded slices, instead of holding every outcome and every fact in one write batch.
 * A build that fails after some slices were committed discards the store, so no half-built revision is
 * ever read.
 */
class BoundedBindingsBuildTest {
    @TempDir Path root;
    private String previous;
    @BeforeEach void tinySlices(){previous=System.getProperty("jvmd.bindings.slice_bytes");System.setProperty("jvmd.bindings.slice_bytes","1");}
    @AfterEach void restore(){if(previous==null)System.clearProperty("jvmd.bindings.slice_bytes");else System.setProperty("jvmd.bindings.slice_bytes",previous);}

    private static JsonNode request(Application app,String method,Map<String,Object> params)throws Exception{
        var response=TestSupport.complete(app.dispatcher(),method,params);assertThat(response.has("error")).as(response.toString()).isFalse();return response.path("result").path("result");
    }

    @Test void referencesAreCompleteWhenFactsAreCommittedInSlices()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { static int a(){return 1;} }");
        for(int i=0;i<6;i++)Files.writeString(root.resolve("Caller"+i+".java"),"class Caller"+i+" { int call(){return Api.a();} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var incoming=request(app,"symbol.references",Map.of("session",session,"ref","Api/a()","direction","in")).path("edges").toString();
            for(int i=0;i<6;i++)assertThat(incoming).contains("Caller"+i+"#call().");
            var status=request(app,"session.status",Map.of("session",session)).path("workspace_bindings");
            assertThat(status.path("committed_slices").asLong()).as("one slice per file at a 1-byte slice size").isGreaterThanOrEqualTo(6);
            // An incremental rebuild after an edit still reads one consistent revision.
            Files.writeString(root.resolve("Caller3.java"),"class Caller3 { int call(){return 0;} }");
            var after=request(app,"symbol.references",Map.of("session",session,"ref","Api/a()","direction","in")).path("edges").toString();
            assertThat(after).doesNotContain("Caller3#call().").contains("Caller2#call().").contains("Caller4#call().");
        }
    }

    @Test void aBuildThatFailsAfterCommittingASliceLeavesNoPartialRevision()throws Exception{
        Path api=root.resolve("Api.java"),caller=root.resolve("Caller.java");
        Files.writeString(api,"class Api { static int a(){return 1;} }");Files.writeString(caller,"class Caller { int call(){return Api.a();} }");
        var files=List.of(api,caller);
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings(new FileStateRegistry())){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            var documents=new Documents(new FileStateRegistry());analyzer.documents(documents);
            var configuration=new CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25"));
            WorkspaceBindings.StreamingLoader failing=(sources,sink)->{
                var outcomes=analyzer.bindingsBatch(sources);boolean first=true;
                for(var entry:outcomes.entrySet()){if(!first)throw new IllegalStateException("injected failure after a committed slice");sink.accept(entry.getKey(),entry.getValue());first=false;}
            };
            assertThatThrownBy(()->cache.getStreaming(()->files,configuration,documents,1L<<20,failing)).hasMessageContaining("injected failure");
            assertThat(cache.status().get("fragment_files")).as("the failed build published nothing").isEqualTo(0);
            WorkspaceBindings.StreamingLoader working=(sources,sink)->{for(var entry:analyzer.bindingsBatch(sources).entrySet())sink.accept(entry.getKey(),entry.getValue());};
            try(var snapshot=cache.getStreaming(()->files,configuration,documents,1L<<20,working)){
                assertThat(snapshot.tier()).isEqualTo(2);
                assertThat(snapshot.edges()).anyMatch(edge->edge.kind().equals("calls")&&edge.dst().endsWith("Api#a()."));
                assertThat(snapshot.symbols().keySet()).anyMatch(scip->scip.endsWith("Caller#call()."));
            }
            assertThat(((Number)cache.status().get("full_builds")).longValue()).as("rebuilt from scratch").isGreaterThanOrEqualTo(1);
        }
    }
}
