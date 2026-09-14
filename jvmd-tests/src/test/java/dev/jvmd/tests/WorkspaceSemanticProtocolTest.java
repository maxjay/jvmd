package dev.jvmd.tests;

import dev.jvmd.analyzer.SourceText;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4: the dispatcher exposes navigation and source reference graphs. */
@Tag("phase-4")
class WorkspaceSemanticProtocolTest {
    @TempDir Path root;
    @Test void navigationAndGraphsShareCompilerIdentity()throws Exception{
        String text="interface Parent { int echo(int input); } class Example implements Parent { public int echo(int input){return input;} int caller(){return echo(1);} }";
        Path file=root.resolve("Example.java");Files.writeString(file,text);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);int offset=text.indexOf("echo(1)");var position=new SourceText(text).position(offset);
            var at=TestSupport.request(app.dispatcher(),"symbol.atPosition",Map.of("session",session,"path",file.toString(),"line",position.line(),"character",position.character())).path("result");
            assertThat(at.path("tier").asInt()).isEqualTo(2);String scip=at.path("result").path("scip").asText();assertThat(scip).endsWith("Example#echo(int).");
            var described=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref",scip)).path("result").path("result");
            assertThat(described.path("scip").asText()).isEqualTo(scip);assertThat(described.path("file").asText()).isEqualTo(file.toString());
            var references=TestSupport.request(app.dispatcher(),"symbol.references",Map.of("session",session,"ref","Example/echo(int)","kinds",List.of("calls"),"direction","in")).path("result");
            assertThat(references.path("warnings").isEmpty()).isTrue();assertThat(references.path("result").path("edges").toString()).contains("Example#caller().");
            var first=TestSupport.request(app.dispatcher(),"symbol.references",Map.of("session",session,"ref","Example/caller()","direction","out","limit",1)).path("result");
            assertThat(first.path("result").path("symbols").get(0).path("name").asText()).isEqualTo("caller");assertThat(first.path("truncated").asBoolean()).isTrue();
            var next=TestSupport.request(app.dispatcher(),"symbol.references",Map.of("session",session,"ref","Example/caller()","direction","out","limit",1,"cursor",first.path("cursor").asText())).path("result");
            assertThat(next.path("result").path("symbols").get(0).path("name").asText()).isEqualTo("echo");assertThat(next.path("truncated").asBoolean()).isFalse();
            var hierarchy=TestSupport.request(app.dispatcher(),"symbol.hierarchy",Map.of("session",session,"ref","Example","direction","up")).path("result").path("result");
            assertThat(hierarchy.path("edges").toString()).contains("implements").contains("Parent#");
            var find=TestSupport.request(app.dispatcher(),"symbol.find",Map.of("session",session,"name_path","echo","scope","workspace","limit",1)).path("result");
            assertThat(find.path("truncated").asBoolean()).isTrue();assertThat(find.path("cursor").asText()).isNotBlank();
        }
    }
}
