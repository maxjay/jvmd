package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.analyzer.SourceText;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 6: source navigation and diagnostic origins cross repository boundaries. */
@Tag("phase-6")
class SourceOverlayNavigationTest {
    @TempDir Path root;
    @Test void editingAnUnbuiltDependencyChangesNavigationAndDiagnostics()throws Exception{
        Path a=OverlayFixtures.project(root.resolve("a"),"app","1","<dependencies>"+MavenFixtures.dependency("library","1")+"</dependencies>");
        Path b=OverlayFixtures.project(root.resolve("b"),"library","2","");
        Path provider=OverlayFixtures.source(b,"Library","public class Library { public int value(){return 1;} }");
        Path use=OverlayFixtures.source(a,"Use","class Use { int read(){return new Library().value();} }");String text=Files.readString(use);var position=new SourceText(text).position(text.indexOf("value"));
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            var opened=TestSupport.request(app.dispatcher(),"session.open",Map.of("root",a.toString(),"manifest",Map.of("roots",List.of(a.toString(),b.toString()))));
            assertThat(opened.has("error")).withFailMessage(opened.toPrettyString()).isFalse();String session=opened.path("result").path("result").path("session").asText();
            var at=TestSupport.request(app.dispatcher(),"symbol.atPosition",Map.of("session",session,"path",use.toString(),"line",position.line(),"character",position.character())).path("result");
            assertThat(at.path("tier").asInt()).withFailMessage(at.toPrettyString()).isEqualTo(2);String scip=at.path("result").path("scip").asText();assertThat(scip).startsWith("maven fixture/library 2 ");
            var described=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref",scip)).path("result").path("result");
            assertThat(described.path("source_file").asText()).isEqualTo(provider.toString());
            var timestamp=Files.getLastModifiedTime(provider);Files.writeString(provider,Files.readString(provider).replace("value","other"));Files.setLastModifiedTime(provider,timestamp);
            var diagnostics=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"paths",List.of(use.toString()))).path("result");
            assertThat(diagnostics.path("tier").asInt()).isEqualTo(2);assertThat(diagnostics.path("result").path("diagnostics").toString()).contains("cant.resolve");assertThat(diagnostics.path("warnings").toString()).contains("originates: fixture:library:2");
        }
    }
}
