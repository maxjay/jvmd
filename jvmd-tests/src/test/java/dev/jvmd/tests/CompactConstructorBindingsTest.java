package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.2 and 4.8: compact record constructors retain source identity and editable header positions. */
@Tag("phase-4") @Tag("phase-8")
class CompactConstructorBindingsTest {
    @TempDir Path root;
    @Test void compactHeaderBindsDescribesAndParticipatesInReferences()throws Exception{
        String source="record Example(int value) { Example { if(value<0)throw new IllegalArgumentException(); } static Example make(){return new Example(1);} }";
        Path file=root.resolve("Example.java");Files.writeString(file,source);int header=source.indexOf("Example {");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var at=TestSupport.complete(app.dispatcher(),"symbol.atPosition",Map.of("session",session,"path",file.toString(),"line",0,"character",header)).path("result").path("result");
            String scip=at.path("scip").asText();assertThat(scip).contains("Example#").endsWith("(int).");assertThat(at.path("kind").asText()).isEqualTo("ctor");assertThat(at.path("name_start").asInt()).isEqualTo(header);
            var description=TestSupport.complete(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref",scip)).path("result").path("result");
            assertThat(description.path("name_start").asInt()).isEqualTo(header);assertThat(description.path("source_file").asText()).isEqualTo(file.toString());
            var references=TestSupport.complete(app.dispatcher(),"symbol.references",Map.of("session",session,"ref",scip,"direction","in")).path("result").path("result");
            assertThat(references.path("edges").toString()).contains("Example#make().").contains("instantiates");
            var outline=TestSupport.complete(app.dispatcher(),"symbol.overview",Map.of("session",session,"path",file.toString(),"depth",3)).path("result").path("result").path("symbols");
            boolean located=false;for(var symbol:outline)if(symbol.path("scip").asText().equals(scip)){assertThat(symbol.path("name_start").asInt()).isEqualTo(header);located=true;}assertThat(located).isTrue();
        }
    }
}
