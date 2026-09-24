package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class ResidentCompletionCorrectnessTest {
    @TempDir Path root;

    private Analyzer.Context context(){
        return new Analyzer.Context("fixture:resident-completion:1","25",List.of(),List.of(root),"resident-completion",Map.of());
    }

    private static JsonNode complete(Analyzer analyzer,Path file,String text,String needle)throws Exception{
        int cursor=text.indexOf(needle)+needle.length();
        var position=dev.jvmd.core.Documents.position(text,cursor);
        var answer=analyzer.completion(file,text,position.line(),position.character(),200,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        return Json.MAPPER.valueToTree(answer.result()).path("items");
    }

    private static JsonNode named(JsonNode items,String name){
        for(var item:items)if(item.path("name").asText().equals(name))return item;
        fail("Missing completion "+name+" in "+items);return null;
    }

    @Test void inheritedAndJdkGenericMembersUseInstantiatedReceiverTypes()throws Exception{
        Files.writeString(root.resolve("Base.java"),"class Base<T> { T getValue(){return null;} <U> U convert(U value){return value;} }");
        Files.writeString(root.resolve("Api.java"),"class Api extends Base<String> {}");
        String source="""
                import java.util.*;
                class Use {
                    Object a(Api api){ return api.getV; }
                    Object b(List<String> list){ return list.get; }
                    Object c(Map<String,Integer> map){ return map.get; }
                    Object d(Api api){ return api.conv; }
                }
                """;
        Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(named(complete(analyzer,file,source,"api.getV"),"getValue").path("label").asText()).contains("java.lang.String");
            assertThat(named(complete(analyzer,file,source,"list.get"),"get").path("label").asText()).contains("java.lang.String");
            assertThat(named(complete(analyzer,file,source,"map.get"),"get").path("label").asText()).contains("java.lang.Integer");
            assertThat(named(complete(analyzer,file,source,"api.conv"),"convert").path("label").asText()).contains("U","convert");
        }
    }

    @Test void residentAccessibilityAndStaticContextMatchJavaScopes()throws Exception{
        Path a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b"));
        Files.writeString(a.resolve("Base.java"),"""
                package a;
                public class Base {
                    public int publicMember(){return 1;}
                    protected int protectedMember(){return 2;}
                    int packageMember(){return 3;}
                    private int privateMember(){return 4;}
                }
                """);
        Files.writeString(b.resolve("Api.java"),"""
                package b;
                public class Api extends a.Base {
                    public static int staticMember(){return 1;}
                    public int instanceMember(){return 2;}
                }
                """);
        String ordinary="package b; class Use { Object f(Api api){ return api.; } }";
        Path use=Files.writeString(b.resolve("Use.java"),ordinary);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            var names=complete(analyzer,use,ordinary,"api.").findValuesAsText("name");
            assertThat(names).contains("publicMember","staticMember","instanceMember")
                    .doesNotContain("protectedMember","packageMember","privateMember");

            String subclass="package b; class Use extends Api { Object f(){ return this.pro; } }";
            Files.writeString(use,subclass);analyzer.changed(use);
            assertThat(complete(analyzer,use,subclass,"this.pro").findValuesAsText("name")).contains("protectedMember");

            String statik="package b; class Use { static Object f(){ return Api.; } }";
            Files.writeString(use,statik);analyzer.changed(use);
            var staticNames=complete(analyzer,use,statik,"Api.").findValuesAsText("name");
            assertThat(staticNames).contains("staticMember").doesNotContain("instanceMember");
        }
    }

    @Test void documentationOnlyEditPreservesCandidatesWhileRemovalAndRenameChangeThem()throws Exception{
        Path api=Files.writeString(root.resolve("Api.java"),"""
                class Api {
                    /** before documentation */
                    int oldName(){return 1;}
                    int removed(){return 2;}
                }
                """);
        String source="class Use { Object f(Api api){ return api.; } }";
        Path use=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            var first=complete(analyzer,use,source,"api.");
            JsonNode old=named(first,"oldName");
            assertThat(first.findValuesAsText("name")).contains("removed");
            String scip=old.path("scip").asText();
            assertThat(analyzer.residentDescription(scip).get("doc").toString()).contains("before documentation");
            @SuppressWarnings("unchecked") var beforeState=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            String apiIdentity=beforeState.get("semantic_api").toString();

            Files.writeString(api,"""
                    class Api {
                        /** after documentation */
                        int oldName(){return 1;}
                        int removed(){return 2;}
                    }
                    """);
            analyzer.changed(api);
            var docOnly=complete(analyzer,use,source,"api.");
            assertThat(docOnly.findValuesAsText("name")).contains("oldName","removed");
            assertThat(analyzer.residentDescription(scip).get("doc").toString()).contains("after documentation");
            @SuppressWarnings("unchecked") var docState=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(docState.get("semantic_api").toString()).isEqualTo(apiIdentity);
            assertThat(docState.get("semantic_documentation")).isNotEqualTo(beforeState.get("semantic_documentation"));

            Files.writeString(api,"""
                    class Api {
                        /** after documentation */
                        int newName(){return 1;}
                    }
                    """);
            analyzer.changed(api);
            var changed=complete(analyzer,use,source,"api.").findValuesAsText("name");
            assertThat(changed).contains("newName").doesNotContain("oldName","removed");
        }
    }

    @Test void localVariableWinsOverShadowedFieldInUnqualifiedCompletion()throws Exception{
        String source="class Use { int value; Object f(){ String value=\"x\"; return val; } }";
        Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            var items=complete(analyzer,file,source,"return val");
            var values=new ArrayList<JsonNode>();for(var item:items)if(item.path("name").asText().equals("value"))values.add(item);
            assertThat(values).hasSize(1);
            assertThat(values.getFirst().path("kind").asText()).isEqualTo("local_variable");
            long queries=((Number)analyzer.status().get("queries")).longValue();
            complete(analyzer,file,source,"return val");
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(queries);
        }
    }
}
