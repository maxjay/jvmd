package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class BatchBindingsTest {
    @TempDir Path root;
    @Test void batchAgreesWithIndividualFilesAndInvalidatesAfterUnsavedApiChange()throws Exception{
        Path api=root.resolve("Api.java"),caller=root.resolve("Caller.java");
        String apiText="class Api { static int value(int n){return n;} }",callerText="class Caller { int run(){return Api.value(1);} }";
        Files.writeString(api,apiText);Files.writeString(caller,callerText);
        var sources=new LinkedHashMap<Path,String>();sources.put(api,apiText);sources.put(caller,callerText);var documents=new Documents();
        var context=new Analyzer.Context("test:batch:1","25",List.of(),List.of(root),"same",Map.of(root.toUri().toString(),"test:batch:1"));
        try(var batch=new Analyzer();var individual=new Analyzer()){
            batch.configure(context,null,256L*1024*1024);batch.documents(documents);
            individual.configure(context,null,256L*1024*1024);individual.documents(documents);
            var results=batch.bindingsBatch(sources);
            for(var entry:sources.entrySet()){
                var expected=individual.bindings(entry.getKey(),entry.getValue(),null);var actual=results.get(entry.getKey());
                assertThat(actual.tier()).isEqualTo(2);assertThat(actual.warnings()).isEmpty();assertThat(actual.diagnostics()).isEmpty();
                assertThat((com.fasterxml.jackson.databind.JsonNode)Json.MAPPER.valueToTree(actual.result())).isEqualTo(Json.MAPPER.valueToTree(expected.result()));
            }
            assertThat(batch.status()).containsEntry("batch_queries",1L).containsEntry("queries",1L);
            var cached=batch.bindings(caller,callerText,null);assertThat(cached.result()).isEqualTo(results.get(caller).result());
            assertThat(batch.status()).containsEntry("queries",1L);
            String changed=apiText.replace("int value(int n)","int other(int n)");documents.open(api,changed,1);batch.documents(documents);batch.changed(api);
            sources.put(api,changed);var invalid=batch.bindingsBatch(sources);
            assertThat(invalid.get(caller).diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
            documents.change(api,2,List.of(new Documents.Change(null,apiText)));batch.documents(documents);batch.changed(api);
            sources.put(api,apiText);assertThat(batch.bindingsBatch(sources).get(caller).diagnostics()).isEmpty();
        }
    }
}
