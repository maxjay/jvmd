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

    private Analyzer.Context context(){return context(root,"resident-completion");}
    private static Analyzer.Context context(Path sourceRoot,String generation){
        return new Analyzer.Context("fixture:"+generation+":1","25",List.of(),List.of(sourceRoot),generation,Map.of());
    }

    private static JsonNode complete(Analyzer analyzer,Path file,String text,String needle)throws Exception{
        return complete(analyzer,file,text,needle,200);
    }

    private static JsonNode complete(Analyzer analyzer,Path file,String text,String needle,int limit)throws Exception{
        int cursor=text.indexOf(needle)+needle.length();
        var position=dev.jvmd.core.Documents.position(text,cursor);
        var answer=analyzer.completion(file,text,position.line(),position.character(),limit,0);
        assertThat(answer.warnings()).as(answer.toString()).isEmpty();
        return Json.MAPPER.valueToTree(answer.result()).path("items");
    }

    private static JsonNode named(JsonNode items,String name){
        for(var item:items)if(item.path("name").asText().equals(name))return item;
        fail("Missing completion "+name+" in "+items);return null;
    }

    @Test void moduleSwitchingPreservesResidentSemanticState()throws Exception{
        Path a=Files.createDirectories(root.resolve("module-a")),b=Files.createDirectories(root.resolve("module-b"));
        Files.writeString(a.resolve("Api.java"),"class Api { int alpha(){return 1;} }");
        String useA="class UseA { Object f(Api api){ return api.al; } }";Path fileA=Files.writeString(a.resolve("UseA.java"),useA);
        Files.writeString(b.resolve("Other.java"),"class Other { int beta(){return 2;} }");
        String useB="class UseB { Object f(Other other){ return other.be; } }";Path fileB=Files.writeString(b.resolve("UseB.java"),useB);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(a,"module-a"),null,256L*1024*1024);
            assertThat(complete(analyzer,fileA,useA,"api.al").findValuesAsText("name")).contains("alpha");
            @SuppressWarnings("unchecked") var stateA=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            String rootA=stateA.get("semantic_root").toString();long factsA=((Number)stateA.get("semantic_facts")).longValue();
            assertThat(factsA).isPositive();

            analyzer.configure(context(b,"module-b"),null,256L*1024*1024);
            assertThat(complete(analyzer,fileB,useB,"other.be").findValuesAsText("name")).contains("beta");

            analyzer.configure(context(a,"module-a"),null,256L*1024*1024);
            @SuppressWarnings("unchecked") var restored=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(restored.get("semantic_root")).isEqualTo(rootA);
            assertThat(((Number)restored.get("semantic_facts")).longValue()).isEqualTo(factsA);
        }
    }

    @Test void qualifiedCompletionReadsPastFilteredRangeEntries()throws Exception{
        var api=new StringBuilder("class Api {\n");
        for(int i=0;i<16;i++)api.append("private int a").append(String.format("%02d",i)).append("(){return ").append(i).append(";}\n");
        api.append("public int azVisible0(){return 100;}\n");
        api.append("public int azVisible1(){return 101;}\n");
        api.append("public int azVisible2(){return 102;}\n}");
        Files.writeString(root.resolve("Api.java"),api);
        String source="class Use { Object f(Api api){ return api.a; } }";
        Path use=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            var names=complete(analyzer,use,source,"api.a",2).findValuesAsText("name");
            assertThat(names).containsExactly("azVisible0","azVisible1");
            @SuppressWarnings("unchecked") var resident=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(((Number)resident.get("semantic_tree_range_entries_read")).longValue()).isGreaterThan(2L);
        }
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

    @Test void subtypeDeclarationsSuppressOverriddenAndHiddenInheritedMembers()throws Exception{
        Files.writeString(root.resolve("Base.java"),"""
                class Base {
                    Number value(){return 1;}
                    int field;
                    int overload(int x){return x;}
                    int overload(String x){return x.length();}
                }
                """);
        Files.writeString(root.resolve("Api.java"),"""
                class Api extends Base {
                    @Override Integer value(){return 2;}
                    int field;
                    @Override int overload(int x){return x+1;}
                }
                """);
        String source="class Use { Object f(Api api){ return api.; } }";
        Path use=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            var items=complete(analyzer,use,source,"api.");
            var values=new ArrayList<JsonNode>();
            var fields=new ArrayList<JsonNode>();
            var overloads=new ArrayList<JsonNode>();
            for(var item:items){
                if(item.path("name").asText().equals("value"))values.add(item);
                if(item.path("name").asText().equals("field"))fields.add(item);
                if(item.path("name").asText().equals("overload"))overloads.add(item);
            }
            assertThat(values).hasSize(1);assertThat(values.getFirst().path("label").asText()).contains("java.lang.Integer");
            assertThat(fields).hasSize(1);
            assertThat(overloads).hasSize(2);
        }
    }

    @Test void wideReceiverSharesBoundedAccessibilityEvidenceAcrossCursorQueries()throws Exception{
        var api=new StringBuilder("class Api {\n");
        for(int i=0;i<1000;i++)api.append("public int member").append(String.format("%04d",i)).append("(){return ").append(i).append(";}\n");
        api.append("}\n");
        Files.writeString(root.resolve("Api.java"),api);
        String source="""
                class Use {
                    Object first(Api api){ return api.member0; }
                    Object second(Api api){ return api.member9; }
                }
                """;
        Path use=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,use,source,"api.member0",10)).hasSize(10);
            assertThat(complete(analyzer,use,source,"api.member9",10)).hasSize(10);
            @SuppressWarnings("unchecked") var access=(Map<String,Object>)analyzer.status().get("resident_accessibility_cache");
            assertThat(((Number)access.get("entries")).longValue()).isEqualTo(1L);
            assertThat(((Number)access.get("member_ids")).longValue()).isGreaterThanOrEqualTo(1000L);
            assertThat(((Number)access.get("max_entries")).longValue()).isEqualTo(16L);
            assertThat(((Number)access.get("hits")).longValue()).isGreaterThanOrEqualTo(2L);
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
            @SuppressWarnings("unchecked") var admitted=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(admitted).containsEntry("semantic_descriptions",0);
            assertThat(((Number)analyzer.status().get("resident_description_loads")).longValue()).isZero();
            String scip=old.path("scip").asText();
            assertThat(analyzer.residentDescription(scip).get("doc").toString()).contains("before documentation");
            assertThat(((Number)analyzer.status().get("resident_description_loads")).longValue()).isEqualTo(1L);
            assertThat(analyzer.residentDescription(scip).get("doc").toString()).contains("before documentation");
            assertThat(((Number)analyzer.status().get("resident_description_cache_hits")).longValue()).isPositive();
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

    @Test void sourceMutationMarksResidentSemanticsStaleBeforeTheNextQuery()throws Exception{
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getValue(){return 1;} }");
        String source="class Use { Object f(Api api){ return api.getV; } }";
        Path use=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(named(complete(analyzer,use,source,"api.getV"),"getValue")).isNotNull();

            @SuppressWarnings("unchecked")
            var before=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            String rootBefore=before.get("semantic_root").toString();
            assertThat(((Number)before.get("semantic_stale_units")).longValue()).isZero();

            Files.writeString(api,"class Api { int getValue(){return 2;} }");
            analyzer.changed(api);

            @SuppressWarnings("unchecked")
            var stale=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(stale.get("semantic_root").toString()).isNotEqualTo(rootBefore);
            assertThat(((Number)stale.get("semantic_stale_units")).longValue()).isPositive();

            assertThat(named(complete(analyzer,use,source,"api.getV"),"getValue")).isNotNull();
            @SuppressWarnings("unchecked")
            var current=(Map<String,Object>)analyzer.status().get("resident_semantic_state");
            assertThat(((Number)current.get("semantic_stale_units")).longValue()).isZero();
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
