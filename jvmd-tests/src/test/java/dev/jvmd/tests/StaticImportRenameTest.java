package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements D6 and 4.9: overloaded imports retain every identity and rename only the selected overload. */
@Tag("phase-8") @Tag("phase-9")
class StaticImportRenameTest {
    @TempDir Path root;
    @Test void resolvesTheOverloadSetAndPreservesImportsForUnchangedCalls()throws Exception{
        Path packageDir=Files.createDirectories(root.resolve("sample")),api=packageDir.resolve("Ops.java"),caller=root.resolve("Use.java");
        Files.writeString(api,"package sample; public class Ops { public static int value(int n){return n;} public static int value(String s){return s.length();} }\n");
        String source="import static sample.Ops.value;\nclass Use { int sum(){return value(1)+value(\"value\");} /* value */ }\n";Files.writeString(caller,source);
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);int offset=source.indexOf("value");
            var at=call(app,session,"symbol.atPosition",Map.of("path",caller.toString(),"line",0,"character",offset));
            assertThat(at.path("ambiguous").asBoolean()).isTrue();assertThat(at.has("scip")).isFalse();assertThat(at.path("candidates").size()).isEqualTo(2);
            for(var candidate:at.path("candidates")){var described=call(app,session,"symbol.describe",Map.of("ref",candidate.path("scip").asText()));assertThat(described.path("name").asText()).isEqualTo("value");}
            var nativeParams=Map.of("textDocument",Map.of("uri",caller.toUri().toString()),"position",Map.of("line",0,"character",offset));
            var hover=call(app,session,"lsp.request",Map.of("method","textDocument/hover","params",nativeParams)).path("value");assertThat(hover.path("contents").path("value").asText()).contains("int","String","value");
            var definitions=call(app,session,"lsp.request",Map.of("method","textDocument/definition","params",nativeParams)).path("value");assertThat(definitions.size()).isEqualTo(2);
            var plan=call(app,session,"edit.rename",Map.of("ref","sample.Ops/value(int)","new_name","amount","dry_run",true));assertThat(plan.path("applied").asBoolean()).isFalse();assertThat(Files.readString(caller)).isEqualTo(source);
            var applied=call(app,session,"edit.rename",Map.of("ref","sample.Ops/value(int)","new_name","amount"));assertThat(applied.path("diagnostics").isEmpty()).as(applied.toPrettyString()).isTrue();
            assertThat(Files.readString(caller)).contains("import static sample.Ops.value;","import static sample.Ops.amount;","amount(1)","value(\"value\")","/* value */");
            assertThat(Files.readString(api)).contains("amount(int","value(String");
        }
    }
    @Test void aSingleImportedMemberIsRenamedInPlace()throws Exception{
        Path dir=Files.createDirectories(root.resolve("sample")),api=dir.resolve("Ops.java"),caller=root.resolve("Use.java");
        Files.writeString(api,"package sample; public class Ops { public static int value(){return 1;} }");
        Files.writeString(caller,"import static sample.Ops.value; class Use { int use(){return value();} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);var result=call(app,session,"edit.rename",Map.of("ref","sample.Ops/value()","new_name","answer"));
            assertThat(result.path("diagnostics").isEmpty()).isTrue();assertThat(Files.readString(caller)).contains("import static sample.Ops.answer;","return answer();").doesNotContain("Ops.value");
        }
    }
    private static JsonNode call(Application app,String session,String method,Map<String,?> params){var arguments=new LinkedHashMap<String,Object>(params);arguments.put("session",session);var result=TestSupport.request(app.dispatcher(),method,arguments);assertThat(result.has("error")).as(result.toPrettyString()).isFalse();assertThat(result.path("result").path("warnings").toString()).doesNotContain("analyzer_fault");return result.path("result").path("result");}
}
