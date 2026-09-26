package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: semantic edits use bound identities, transactional ranges and member diagnostics. */
@Tag("phase-8")
class SemanticEditsTest {
    @TempDir Path root;
    @Test void renameFollowsOverridesAndCallsButLeavesCommentsStringsAndOverloads()throws Exception{
        Path api=root.resolve("Api.java"),impl=root.resolve("Impl.java"),use=root.resolve("Use.java");
        Files.writeString(api,"interface Api { int value(int input); }");
        Files.writeString(impl,"class Impl implements Api { public int value(int input){return input;} int value(String input){return 7;} }");
        Files.writeString(use,"class Use { int call(Api api,Impl impl){return api.value(1)+impl.value(2)+impl.value(\"value\");} /* value */ }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var preview=call(app,session,"edit.rename",Map.of("ref","Impl/value(int)","new_name","amount","dry_run",true));
            assertThat(preview.path("applied").asBoolean()).isFalse();assertThat(Files.readString(api)).contains("value(int");
            var edited=call(app,session,"edit.rename",Map.of("ref","Impl/value(int)","new_name","amount"));
            assertThat(edited.path("diagnostics").isEmpty()).isTrue();assertThat(edited.path("changed_files").size()).isEqualTo(3);
            assertThat(Files.readString(api)).contains("amount(int");assertThat(Files.readString(impl)).contains("amount(int","value(String");
            assertThat(Files.readString(use)).contains("api.amount(1)","impl.amount(2)","impl.value(\"value\")","/* value */");
        }
    }
    @Test void classRenameUpdatesConstructorsImportsAndTheFileName()throws Exception{
        Path pkg=Files.createDirectories(root.resolve("sample")),file=pkg.resolve("Widget.java");Files.writeString(file,"package sample; public class Widget { public Widget(){} }");
        Path caller=root.resolve("Caller.java");Files.writeString(caller,"import sample.Widget; class Caller { Widget create(){return new Widget();} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);var result=call(app,session,"edit.rename",Map.of("ref","sample.Widget","new_name","Gadget"));
            assertThat(result.path("diagnostics").isEmpty()).isTrue();assertThat(file).doesNotExist();assertThat(Files.readString(pkg.resolve("Gadget.java"))).contains("class Gadget","public Gadget()");
            assertThat(Files.readString(caller)).contains("import sample.Gadget","Gadget create()","new Gadget()");
        }
    }
    @Test void bodyInsertionAndTextEditsReturnOnlyTouchedMemberDiagnostics()throws Exception{
        Path file=root.resolve("Example.java");Files.writeString(file,"class Example { int okay(){return 1;} int broken(){return missing;} }");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            var replaced=call(app,session,"edit.replaceBody",Map.of("ref","Example/okay()","body","{return 9;}"));
            assertThat(replaced.path("applied").asBoolean()).isTrue();assertThat(replaced.path("diagnostics").isEmpty()).isTrue();assertThat(replaced.path("members").size()).isEqualTo(1);
            var inserted=call(app,session,"edit.insert",Map.of("ref","Example/okay()","position","after","code","int added(){return nope;}"));
            assertThat(inserted.path("diagnostics").size()).as(inserted.toPrettyString()).isEqualTo(1);assertThat(inserted.path("diagnostics").get(0).path("code").asText()).contains("cant.resolve");
            String text=Files.readString(file);int offset=text.indexOf("nope");
            var fixed=call(app,session,"edit.text",Map.of("text_edits",List.of(Map.of("path",file.toString(),"start",offset,"end",offset+4,"new_text","11"))));
            assertThat(fixed.path("diagnostics").isEmpty()).isTrue();assertThat(Files.readString(file)).contains("return 11;","return missing;");
            var broken=call(app,session,"edit.replaceBody",Map.of("ref","Example/okay()","body","{return unknown;}"));
            assertThat(broken.path("diagnostics").size()).isEqualTo(1);assertThat(broken.path("verified").asBoolean()).isFalse();
        }
    }
    @Test void invalidOrStalePlansNeverPartiallyApply()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java");Files.writeString(a,"class A {}");Files.writeString(b,"class B {}");
        assertThatThrownBy(()->TextEdits.prepare(List.of(new TextEdits.Edit(a,6,7,"X"),new TextEdits.Edit(b,6,100,"Y")),Map.of())).isInstanceOf(RpcException.class);
        assertThat(Files.readString(a)).isEqualTo("class A {}");
        var plan=TextEdits.prepare(List.of(new TextEdits.Edit(a,6,7,"X"),new TextEdits.Edit(b,6,7,"Y")),Map.of());Files.writeString(b,"class Changed {}");
        assertThatThrownBy(()->TextEdits.apply(plan)).isInstanceOf(RpcException.class);assertThat(Files.readString(a)).isEqualTo("class A {}");assertThat(Files.readString(b)).isEqualTo("class Changed {}");
        assertThatThrownBy(()->TextEdits.prepare(List.of(new TextEdits.Edit(a,6,7,"X"),new TextEdits.Edit(a,6,8,"Y")),Map.of())).isInstanceOf(RpcException.class);
    }
    private static com.fasterxml.jackson.databind.JsonNode call(Application app,String session,String method,Map<String,Object> values){
        var params=new LinkedHashMap<>(values);params.put("session",session);var response=TestSupport.request(app.dispatcher(),method,params);
        assertThat(response.has("error")).as(response.toString()).isFalse();assertThat(response.path("result").path("warnings").toString()).doesNotContain("analyzer_fault");
        return response.path("result").path("result");
    }
}
