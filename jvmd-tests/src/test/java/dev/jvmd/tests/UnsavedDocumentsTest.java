package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 9: unsaved cross-file Java sources and lazy, versioned invalidation. */
@Tag("phase-9")
class UnsavedDocumentsTest {
    @TempDir Path root;
    private com.fasterxml.jackson.databind.JsonNode request(Application app,String session,String method,Map<String,Object> values){
        var params=new LinkedHashMap<>(values);params.put("session",session);return TestSupport.request(app.dispatcher(),method,params);
    }
    private com.fasterxml.jackson.databind.JsonNode diagnostics(Application app,String session,Path file){
        var result=request(app,session,"diag.get",Map.of("paths",List.of(file.toString())));assertThat(result.has("error")).as(result.toString()).isFalse();
        assertThat(result.path("result").path("tier").asInt()).isEqualTo(2);return result.path("result").path("result").path("diagnostics");
    }
    @Test void unsavedDependencyChangesAreObservedLazilyAndClosingRestoresDisk()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java");
        String originalA="class A { static int value(){return 1;} }",originalB="class B { int read(){return A.value();} }";
        Files.writeString(a,originalA);Files.writeString(b,originalB);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);assertThat(diagnostics(app,session,b)).isEmpty();
            var before=request(app,session,"session.status",Map.of()).path("result").path("result").path("analyzer").path("queries");
            request(app,session,"document.open",Map.of("path",a.toString(),"version",1,"text","class A { static String value(){return \"new\";} }"));
            var after=request(app,session,"session.status",Map.of()).path("result").path("result").path("analyzer").path("queries");assertThat(after).isEqualTo(before);
            assertThat(diagnostics(app,session,b)).isNotEmpty();
            request(app,session,"document.open",Map.of("path",b.toString(),"version",1,"text","class B { String read(){return A.value();} }"));
            assertThat(diagnostics(app,session,b)).isEmpty();
            var verified=request(app,session,"diag.get",Map.of("verified",true));assertThat(verified.path("error").path("code").asInt()).isEqualTo(-32003);
            var plan=request(app,session,"edit.rename",Map.of("ref","A/value","new_name","fresh","dry_run",true));
            assertThat(plan.has("error")).as(plan.toString()).isFalse();assertThat(plan.path("result").path("result").path("changes").size()).isEqualTo(2);
            var applied=request(app,session,"edit.rename",Map.of("ref","A/value","new_name","fresh"));
            assertThat(applied.path("error").path("code").asInt()).isEqualTo(-32003);assertThat(Files.readString(a)).isEqualTo(originalA);assertThat(Files.readString(b)).isEqualTo(originalB);
            request(app,session,"document.close",Map.of("path",a.toString()));assertThat(diagnostics(app,session,b)).isNotEmpty();
            request(app,session,"document.close",Map.of("path",b.toString()));assertThat(diagnostics(app,session,b)).isEmpty();
        }
    }
    @Test void newSourceFilesParticipateInLookupWithoutBeingWritten()throws Exception{
        Path b=root.resolve("B.java"),created=root.resolve("Created.java");Files.writeString(b,"class B { int value(){return Created.answer();} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);assertThat(diagnostics(app,session,b)).isNotEmpty();
            long residentBefore=request(app,session,"session.status",Map.of()).path("result").path("result").path("analyzer")
                    .path("resident_semantic_state").path("semantic_facts").asLong();
            assertThat(residentBefore).isPositive();
            request(app,session,"document.open",Map.of("path",created.toString(),"version",1,"text","class Created { static int answer(){return 42;} }"));
            long residentAfterOpen=request(app,session,"session.status",Map.of()).path("result").path("result").path("analyzer")
                    .path("resident_semantic_state").path("semantic_facts").asLong();
            assertThat(residentAfterOpen).isEqualTo(residentBefore);
            assertThat(diagnostics(app,session,b)).isEmpty();assertThat(Files.exists(created)).isFalse();
            var found=request(app,session,"symbol.describe",Map.of("ref","Created/answer"));
            assertThat(found.has("error")).as(found.toString()).isFalse();assertThat(found.path("result").path("result").path("source_file").asText()).isEqualTo(created.toString());
            var changed=request(app,session,"document.change",Map.of("path",created.toString(),"version",2,"changes",List.of(Map.of("text","class Created { static long answer(){return 42L;} }"))));
            assertThat(changed.has("error")).as(changed.toString()).isFalse();assertThat(diagnostics(app,session,b)).isNotEmpty();
            var stale=request(app,session,"document.change",Map.of("path",created.toString(),"version",1,"changes",List.of(Map.of("text","class Created {}"))));
            assertThat(stale.path("error").path("code").asInt()).isEqualTo(-32602);
            request(app,session,"document.close",Map.of("path",created.toString()));assertThat(diagnostics(app,session,b)).isNotEmpty();
        }
    }
}
