package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class SourceContextReuseTest {
    @TempDir Path root;
    @Test void retainedContextAgreesWithFreshCompilerAcrossUnsavedApiChangesAndMissingTypes()throws Exception {
        Path sources=Files.createDirectories(root.resolve("p")),api=sources.resolve("Api.java"),use=sources.resolve("Use.java"),missing=sources.resolve("Missing.java");
        String original="package p; class Api { static int value(){return 1;} }";
        String caller="package p; class Use { int use(){return Api.value();} }";
        Files.writeString(api,original);Files.writeString(use,caller);
        var documents=new Documents();documents.open(api,original,1);documents.open(use,caller,1);
        var context=new Analyzer.Context("test:reuse:1","25",List.of(),List.of(root),"reuse",Map.of());
        List<String> declarations=List.of(original,original.replace("int value(){return 1;}","String value(){return \"changed\";}"),original.replace("value","other"),original,original,original,original);
        try(var retained=new Analyzer()){
            retained.configure(context,null,512L*1024*1024);
            for(int i=0;i<declarations.size();i++){
                String source=i>=4?caller.replace("Api.value()","Missing.value()"):caller;
                if(i>0){documents.change(api,i+1,List.of(new Documents.Change(null,declarations.get(i))));documents.change(use,i+1,List.of(new Documents.Change(null,source)));}
                if(i==5)documents.open(missing,"package p; class Missing { static int value(){return 3;} }",1);
                if(i==6)documents.close(missing);
                retained.documents(documents);
                if(i>0){retained.changed(api,documents.hash(api));retained.changed(use,documents.hash(use));}
                var actual=retained.bindings(use,source,null);
                try(var fresh=new Analyzer()){
                    fresh.configure(context,null,512L*1024*1024);fresh.documents(documents);
                    var expected=fresh.bindings(use,source,null);
                    assertThat(actual.warnings()).as("warnings at edit %s",i).isEmpty();
                    assertThat(actual.diagnostics()).as("diagnostics at edit %s",i).isEqualTo(expected.diagnostics());
                    com.fasterxml.jackson.databind.JsonNode actualBindings=Json.MAPPER.valueToTree(actual.result());
                    assertThat(actualBindings).as("bindings at edit %s",i).isEqualTo(Json.MAPPER.valueToTree(expected.result()));
                }
                assertThat(actual.diagnostics().isEmpty()).as("expected validity at edit %s",i).isEqualTo(Set.of(0,3,5).contains(i));
            }
            assertThat(retained.status()).containsEntry("recycles",0L);
            assertThat(retained.status().get("pool_statistics").toString()).contains("6 reused Contexts");
        }
    }
}
