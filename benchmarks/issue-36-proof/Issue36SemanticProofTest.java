package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import dev.jvmd.index.IndexService;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

public final class Issue36SemanticProofTest {
    private static final long BUDGET=256L*1024*1024;

    @Test void runFrozenScenario() throws Exception {
        String scenario=System.getProperty("issue36.scenario","query-matrix");
        Path report=Path.of(System.getProperty("issue36.report","target/issue36-"+scenario+".json"));
        Path root=Files.createTempDirectory("issue36-"+scenario+"-");
        Map<String,Object> body=switch(scenario){
            case "query-matrix" -> queryMatrix(root);
            case "semantic-mutations" -> semanticMutations(root);
            case "dependency-proofs" -> dependencyProofs(root);
            case "namespace-negative" -> namespaceNegative(root);
            case "classpath-composition" -> classpathComposition(root);
            case "hierarchy-mutation" -> hierarchyMutation(root);
            case "machine-workspace-composition" -> machineWorkspaceComposition(root);
            default -> throw new IllegalArgumentException("Unknown Issue #36 proof scenario: "+scenario);
        };
        var result=new LinkedHashMap<String,Object>();
        result.put("schema",1);
        result.put("scenario",scenario);
        result.put("subject_sha",System.getenv().getOrDefault("ISSUE36_SUBJECT_SHA","unknown"));
        result.put("java",System.getProperty("java.version"));
        result.put("body",body);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report,Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n");
        System.out.println("ISSUE36_REPORT="+report.toAbsolutePath());
        System.out.println(Json.MAPPER.writeValueAsString(result));
    }

    private static Analyzer.Context context(Path root,List<Path> classpath,String generation){
        return new Analyzer.Context("fixture:issue36:1","25",classpath,List.of(root),generation,Map.of());
    }

    private static Map<String,Object> queryMatrix(Path root)throws Exception{
        var result=new LinkedHashMap<String,Object>();
        result.put("incomplete_statement",runCompletionCase(root,"Incomplete.java",
                "class IncompleteApi { int getOne(){return 1;} } class Incomplete { void f(IncompleteApi value){ value.\n } }","value."));
        result.put("parameter",runCompletionCase(root,"Parameter.java",
                "class PApi { int getOne(){return 1;} } class Parameter { Object f(PApi value){ return value.; } }","value."));
        result.put("field",runCompletionCase(root,"Field.java",
                "class FApi { int getField(){return 1;} } class Field { FApi field; Object f(){ return field.; } }","field."));
        result.put("static_receiver",runCompletionCase(root,"StaticUse.java",
                "class StaticApi { static int getStatic(){return 1;} int getInstance(){return 2;} } class StaticUse { Object f(){ return StaticApi.; } }","StaticApi."));
        result.put("chained",runCompletionCase(root,"Chain.java",
                "class Model { int getModelValue(){return 1;} } class ChainApi { Model getModel(){return null;} } class Chain { Object f(ChainApi api){ return api.getModel().; } }","api.getModel()."));
        result.put("generic_fallback",runCompletionCase(root,"Generic.java",
                "import java.util.*; class Generic { Object f(List<String> values){ return values.stream().; } }","values.stream()."));

        StringBuilder deep=new StringBuilder("class Deep0 { int getRoot(){return 1;} }\n");
        for(int i=1;i<=8;i++)deep.append("class Deep").append(i).append(" extends Deep").append(i-1).append(" {}\n");
        deep.append("class DeepUse { Object f(Deep8 value){ return value.get; } }");
        result.put("deep_hierarchy",runCompletionCase(root,"DeepUse.java",deep.toString(),"value.get"));

        StringBuilder wide=new StringBuilder("class WideApi {");
        for(int i=0;i<64;i++)wide.append(" int get").append(String.format("%02d",i)).append("(){return ").append(i).append(";} ");
        wide.append("} class WideUse { Object f(WideApi value){ return value.get; } }");
        result.put("wide_hierarchy",runCompletionCase(root,"WideUse.java",wide.toString(),"value.get"));

        Path accessRoot=Files.createDirectories(root.resolve("access"));
        Path a=Files.createDirectories(accessRoot.resolve("a")),b=Files.createDirectories(accessRoot.resolve("b"));
        Files.writeString(a.resolve("AccessBase.java"),"package a; public class AccessBase { public int getPublic(){return 1;} protected int getProtected(){return 2;} int getPackage(){return 3;} private int getPrivate(){return 4;} }");
        String access="package b; class AccessApi extends a.AccessBase { public static int getStatic(){return 1;} public int getInstance(){return 2;} } class AccessUse { Object f(AccessApi value){ return value.get; } }";
        result.put("access_filtering",runCompletionCase(accessRoot,"b/AccessUse.java",access,"value.get"));

        result.put("prefix_narrowing",prefixNarrowing(root.resolve("prefix")));
        result.put("unsaved_overlay",unsavedOverlay(root.resolve("unsaved")));
        return result;
    }

    private static Map<String,Object> runCompletionCase(Path root,String relative,String source,String needle)throws Exception{
        Files.createDirectories(root);
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root,List.of(),"query-"+relative.replace('/','-')),null,BUDGET);
            return measured(analyzer,()->completion(analyzer,file,source,needle));
        }
    }

    private static Map<String,Object> prefixNarrowing(Path root)throws Exception{
        Files.createDirectories(root);
        Files.writeString(root.resolve("Api.java"),"class Api { int getModel(){return 1;} int getMode(){return 2;} int give(){return 3;} }");
        Path use=root.resolve("Use.java");
        String first="class Use { Object f(Api project){ return project.g; } }";Files.writeString(use,first);
        var docs=new Documents();docs.open(use,first,1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root,List.of(),"prefix"),null,BUDGET);analyzer.documents(docs);
            var out=new LinkedHashMap<String,Object>();int version=1;
            for(String prefix:List.of("g","get","getM")){
                String text="class Use { Object f(Api project){ return project."+prefix+"; } }";
                if(version>1||!text.equals(first)){
                    docs.change(use,++version,List.of(new Documents.Change(null,text)));
                    analyzer.changed(use,docs.hash(use));analyzer.documents(docs);
                }
                out.put(prefix,measured(analyzer,()->completion(analyzer,use,text,"project."+prefix)));
            }
            return out;
        }
    }

    private static Map<String,Object> unsavedOverlay(Path root)throws Exception{
        Files.createDirectories(root);
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getDisk(){return 1;} }");
        String useText="class Use { Object f(Api api){ return api.get; } }";Path use=Files.writeString(root.resolve("Use.java"),useText);
        var docs=new Documents();docs.open(use,useText,1);docs.open(api,"class Api { int getDisk(){return 1;} int getUnsaved(){return 2;} }",1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root,List.of(),"unsaved"),null,BUDGET);analyzer.documents(docs);
            return measured(analyzer,()->completion(analyzer,use,useText,"api.get"));
        }
    }

    private static Map<String,Object> semanticMutations(Path root)throws Exception{
        Files.createDirectories(root);
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getOne(){return 1;} int setOne(){return 1;} }");
        String useText="class Use { Object f(Api api){ return api.get; } }";Path use=Files.writeString(root.resolve("Use.java"),useText);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root,List.of(),"mutations"),null,BUDGET);
            var out=new LinkedHashMap<String,Object>();
            out.put("initial",measured(analyzer,()->completion(analyzer,use,useText,"api.get")));

            Files.writeString(api,"class Api { int getOne(){int x=1; return x;} int setOne(){return 1;} }");analyzer.changed(api);
            out.put("body_only",measured(analyzer,()->completion(analyzer,use,useText,"api.get")));

            Files.writeString(api,"class Api { int getOne(){int x=1; return x;} int setOne(){return 1;} int setSomething(){return 2;} }");analyzer.changed(api);
            out.put("unrelated_api_member",measured(analyzer,()->completion(analyzer,use,useText,"api.get")));

            Files.writeString(api,"class Api { int getOne(){int x=1; return x;} int getSomething(){return 2;} int setOne(){return 1;} int setSomething(){return 2;} }");analyzer.changed(api);
            out.put("relevant_range_member",measured(analyzer,()->completion(analyzer,use,useText,"api.get")));
            return out;
        }
    }

    private static Map<String,Object> dependencyProofs(Path root)throws Exception{
        Files.createDirectories(root);
        Path a=Files.writeString(root.resolve("A.java"),"class A { int one(){return 1;} int two(){return 2;} int foo(int x){return x;} int bar(){return 0;} }");
        String bText="class B { int use(A a){ return a.one()+a.foo(1); } }";Path b=Files.writeString(root.resolve("B.java"),bText);
        String cText="class C { int use(B b,A a){ return b.use(a); } }";Path c=Files.writeString(root.resolve("C.java"),cText);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root,List.of(),"deps"),null,BUDGET);
            analyzer.bindings(a,Files.readString(a),null);analyzer.bindings(b,bText,null);analyzer.bindings(c,cText,null);
            var out=new LinkedHashMap<String,Object>();

            Files.writeString(a,"class A { int one(){return 1;} String two(){return \"2\";} int foo(int x){return x;} int bar(){return 0;} }");analyzer.changed(a);
            out.put("exact_symbol_irrelevant",measured(analyzer,()->bindingSummary(analyzer,b,bText)));

            Files.writeString(a,"class A { long one(){return 1;} String two(){return \"2\";} int foo(int x){return x;} int bar(){return 0;} }");analyzer.changed(a);
            out.put("exact_symbol_relevant",measured(analyzer,()->bindingSummary(analyzer,b,bText)));

            Files.writeString(a,"class A { long one(){return 1;} String two(){return \"2\";} int foo(int x){return x;} int bar(){return 1;} int baz(){return 2;} }");analyzer.changed(a);
            out.put("overload_irrelevant",measured(analyzer,()->bindingSummary(analyzer,b,bText)));

            Files.writeString(a,"class A { long one(){return 1;} String two(){return \"2\";} int foo(int x){return x;} int foo(String x){return x.length();} int bar(){return 1;} int baz(){return 2;} }");analyzer.changed(a);
            out.put("overload_relevant",measured(analyzer,()->bindingSummary(analyzer,b,bText)));

            out.put("downstream_consumer",measured(analyzer,()->bindingSummary(analyzer,c,cText)));
            return out;
        }
    }

    private static Map<String,Object> namespaceNegative(Path root)throws Exception{
        Path foo=Files.createDirectories(root.resolve("foo/bar")),other=Files.createDirectories(root.resolve("com/other"));
        Files.writeString(foo.resolve("Existing.java"),"package foo.bar; public class Existing {}");
        String useText="import foo.bar.*; class Use { Widget value; }";Path use=Files.writeString(root.resolve("Use.java"),useText);
        var docs=new Documents();
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root,List.of(),"namespace"),null,BUDGET);analyzer.documents(docs);
            var out=new LinkedHashMap<String,Object>();
            out.put("initial_negative",measured(analyzer,()->bindingSummary(analyzer,use,useText)));

            Path random=Files.writeString(other.resolve("RandomClass.java"),"package com.other; public class RandomClass {}");
            analyzer.sourceMembershipChanged(random);
            out.put("unrelated_namespace",measured(analyzer,()->bindingSummary(analyzer,use,useText)));

            Path widget=Files.writeString(foo.resolve("Widget.java"),"package foo.bar; public class Widget {}");
            analyzer.sourceMembershipChanged(widget);
            out.put("relevant_namespace",measured(analyzer,()->bindingSummary(analyzer,use,useText)));
            return out;
        }
    }

    private static Map<String,Object> classpathComposition(Path root)throws Exception{
        Files.createDirectories(root);
        Path repo=Files.createDirectories(root.resolve("repo")),src=Files.createDirectories(root.resolve("src"));
        Path a=IndexFixtures.jar(repo.resolve("a"),"a","package a; public class Sample { public int getA(){return 1;} }",true);
        Path b=IndexFixtures.jar(repo.resolve("b"),"b","package b; public class Sample { public int getB(){return 1;} }",true);
        Path c=IndexFixtures.jar(repo.resolve("c"),"c","package c; public class Sample { public int getC(){return 1;} }",true);
        Path d=IndexFixtures.jar(repo.resolve("d"),"d","package d; public class Sample { public int getD(){return 1;} }",true);
        String useText="import a.Sample; class Use { Object f(Sample value){ return value.get; } }";Path use=Files.writeString(src.resolve("Use.java"),useText);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(src,List.of(a,b,c,d),"classpath"),null,BUDGET);
            var out=new LinkedHashMap<String,Object>();
            out.put("initial",measured(analyzer,()->completion(analyzer,use,useText,"value.get")));

            Path c2=IndexFixtures.jar(repo.resolve("c2"),"c","package c; public class Sample { public int getC2(){return 2;} }",true);
            Files.copy(c2,c,StandardCopyOption.REPLACE_EXISTING);
            out.put("unreferenced_c_change",measured(analyzer,()->completion(analyzer,use,useText,"value.get")));

            Path a2=IndexFixtures.jar(repo.resolve("a2"),"a","package a; public class Sample { public int getA2(){return 2;} }",true);
            Files.copy(a2,a,StandardCopyOption.REPLACE_EXISTING);
            out.put("relevant_a_change",measured(analyzer,()->completion(analyzer,use,useText,"value.get")));

            analyzer.configure(context(src,List.of(a,c,b,d),"classpath"),null,BUDGET);
            out.put("reorder_b_c_after_winner",measured(analyzer,()->completion(analyzer,use,useText,"value.get")));

            Path e=IndexFixtures.jar(repo.resolve("e"),"e","package e; public class Sample { public int getE(){return 1;} }",true);
            analyzer.configure(context(src,List.of(a,e,c,b,d),"classpath"),null,BUDGET);
            out.put("insert_after_winner",measured(analyzer,()->completion(analyzer,use,useText,"value.get")));

            analyzer.configure(context(src,List.of(a,e,c,b),"classpath"),null,BUDGET);
            out.put("remove_unreferenced_d",measured(analyzer,()->completion(analyzer,use,useText,"value.get")));
            return out;
        }
    }

    private static Map<String,Object> hierarchyMutation(Path root)throws Exception{
        Files.createDirectories(root);
        Path baseA=Files.writeString(root.resolve("BaseA.java"),"class BaseA { int getShared(){return 1;} }");
        Path baseB=Files.writeString(root.resolve("BaseB.java"),"class BaseB { int getShared(){return 2;} }");
        Path mid=Files.writeString(root.resolve("Mid.java"),"class Mid extends BaseA {}");
        String leafText="class Leaf extends Mid {}";Path leaf=Files.writeString(root.resolve("Leaf.java"),leafText);
        String useText="class Use { int f(Leaf value){ return value.getShared(); } }";Path use=Files.writeString(root.resolve("Use.java"),useText);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root,List.of(),"hierarchy-mutation"),null,BUDGET);
            analyzer.bindings(baseA,Files.readString(baseA),null);
            analyzer.bindings(baseB,Files.readString(baseB),null);
            analyzer.bindings(mid,Files.readString(mid),null);
            analyzer.bindings(leaf,leafText,null);
            analyzer.bindings(use,useText,null);
            var out=new LinkedHashMap<String,Object>();

            Files.writeString(mid,"class Mid extends BaseB {}");analyzer.changed(mid);
            out.put("direct_parent_change",measured(analyzer,()->bindingSummary(analyzer,use,useText)));

            Files.writeString(baseB,"class BaseB { int getShared(){return 2;} private int unrelatedPrivate(){return 3;} }");analyzer.changed(baseB);
            out.put("ancestor_irrelevant_member",measured(analyzer,()->bindingSummary(analyzer,use,useText)));

            out.put("downstream_after_equal_surface",measured(analyzer,()->bindingSummary(analyzer,leaf,leafText)));
            return out;
        }
    }

    private static Map<String,Object> machineWorkspaceComposition(Path root)throws Exception{
        Path repository=Files.createDirectories(root.resolve("repository"));
        Path state=Files.createDirectories(root.resolve("state"));
        Path a=IndexFixtures.jar(repository.resolve("g/a/1"),"a","package machine.a; public class Sample { public int getA(){return 1;} }",true);
        Path b=IndexFixtures.jar(repository.resolve("g/b/1"),"b","package machine.b; public class Sample { public int getB(){return 1;} }",true);
        Path c=IndexFixtures.jar(repository.resolve("g/c/1"),"c","package machine.c; public class Sample { public int getC(){return 1;} }",true);
        try(var index=new IndexService(state.resolve("index.db"),repository)){
            index.indexJar(a,"g:a:1","jar");index.indexJar(b,"g:b:1","jar");index.indexJar(c,"g:c:1","jar");
            index.loadWorkspace("workspace-ab",List.of(
                    new IndexService.WorkspaceArtifact(a.toString(),"compile"),
                    new IndexService.WorkspaceArtifact(b.toString(),"compile")),List.of());
            index.loadWorkspace("workspace-c",List.of(new IndexService.WorkspaceArtifact(c.toString(),"compile")),List.of());

            var before=index.status();
            var abBefore=index.findNamePrefix("Sample","workspace-ab",20,Set.of("class"));
            var cBefore=index.findNamePrefix("Sample","workspace-c",20,Set.of("class"));

            Path replacement=IndexFixtures.jar(repository.resolve("replacement"),"c","package machine.c; public class Sample { public int getC2(){return 2;} }",true);
            Files.copy(replacement,c,StandardCopyOption.REPLACE_EXISTING);
            index.indexJar(c,"g:c:1","jar");

            var after=index.status();
            var abAfter=index.findNamePrefix("Sample","workspace-ab",20,Set.of("class"));
            var cAfter=index.findNamePrefix("Sample","workspace-c",20,Set.of("class"));
            var out=new LinkedHashMap<String,Object>();
            out.put("machine_status_before",before);
            out.put("machine_status_after",after);
            out.put("machine_root_before",findValue(before,"machine_dependency_root"));
            out.put("machine_root_after",findValue(after,"machine_dependency_root"));
            out.put("workspace_ab_root_before",findValue(before,"workspace_ab_dependency_root"));
            out.put("workspace_ab_root_after",findValue(after,"workspace_ab_dependency_root"));
            out.put("workspace_ab_before_gavs",abBefore.stream().map(row->Objects.toString(row.get("gav"),"")).toList());
            out.put("workspace_ab_after_gavs",abAfter.stream().map(row->Objects.toString(row.get("gav"),"")).toList());
            out.put("workspace_c_before",cBefore);
            out.put("workspace_c_after",cAfter);
            out.put("baseline_identity_note","null roots mean the baseline exposes no compositional machine/workspace semantic identity");
            return out;
        }
    }

    private static Object findValue(Object value,String key){
        if(value instanceof Map<?,?> map){
            if(map.containsKey(key))return map.get(key);
            for(Object nested:map.values()){Object found=findValue(nested,key);if(found!=null)return found;}
        }else if(value instanceof Collection<?> values){
            for(Object nested:values){Object found=findValue(nested,key);if(found!=null)return found;}
        }
        return null;
    }

    private static Object findMapValue(Object value,String mapKey,String entryKey){
        if(value instanceof Map<?,?> map){
            Object candidate=map.get(mapKey);
            if(candidate instanceof Map<?,?> values&&values.containsKey(entryKey))return values.get(entryKey);
            for(Object nested:map.values()){Object found=findMapValue(nested,mapKey,entryKey);if(found!=null)return found;}
        }else if(value instanceof Collection<?> values){
            for(Object nested:values){Object found=findMapValue(nested,mapKey,entryKey);if(found!=null)return found;}
        }
        return null;
    }

    private static Object bindingSummary(Analyzer analyzer,Path file,String text)throws Exception{
        var answer=analyzer.bindings(file,text,null);
        var out=new LinkedHashMap<String,Object>();out.put("tier",answer.tier());out.put("warnings",answer.warnings());
        out.put("diagnostics",answer.diagnostics().stream().map(d->Map.of("kind",d.kind(),"code",d.code())).toList());
        return out;
    }

    private static Object completion(Analyzer analyzer,Path file,String text,String needle)throws Exception{
        int cursor=text.indexOf(needle);
        if(cursor<0)throw new IllegalArgumentException("Needle not found: "+needle);
        cursor+=needle.length();var pos=Documents.position(text,cursor);
        var answer=analyzer.completion(file,text,pos.line(),pos.character(),200,0);
        JsonNode json=Json.MAPPER.valueToTree(answer.result());
        var out=new LinkedHashMap<String,Object>();out.put("tier",answer.tier());out.put("warnings",answer.warnings());
        out.put("names",json.path("items").findValuesAsText("name"));
        out.put("labels",json.path("items").findValuesAsText("label"));
        out.put("count",json.path("items").size());
        return out;
    }

    private static Map<String,Object> measured(Analyzer analyzer,ThrowingSupplier<Object> operation)throws Exception{
        var beforeCounters=counters(analyzer);var beforeResources=resources();long started=System.nanoTime();
        Object value;Throwable failure=null;
        try{value=operation.get();}catch(Throwable t){value=null;failure=t;}
        long elapsed=System.nanoTime()-started;var afterCounters=counters(analyzer);var afterResources=resources();
        var out=new LinkedHashMap<String,Object>();
        out.put("latency_ms",elapsed/1_000_000.0);
        out.put("counters_before",beforeCounters);out.put("counters_after",afterCounters);out.put("counter_delta",delta(beforeCounters,afterCounters));
        out.put("resources_before",beforeResources);out.put("resources_after",afterResources);
        out.put("result",value);
        if(failure!=null)out.put("failure",failure.getClass().getName()+": "+Objects.toString(failure.getMessage(),""));
        return out;
    }

    private static Map<String,Long> counters(Analyzer analyzer){
        try{
            Object status=analyzer.status();var out=new LinkedHashMap<String,Long>();
            for(String key:List.of(
                    "queries","completion_requests","semantic_fact_mutations","semantic_tree_range_entries_read",
                    "api_fingerprint_changes","api_fingerprint_unchanged","pending_api_files","conditional_files",
                    "proof_validations","proof_validation_hits","proof_invalidations","derived_proofs_recomputed",
                    "proof_propagation_stopped_equal","resident_entries_read","machine_index_entries_read",
                    "owner_prefix_queries","consumers_visited","consumers_recomputed","consumers_skipped_by_proof_equality",
                    "namespace_proofs_checked","negative_lookup_hits","negative_lookup_invalidations",
                    "classpath_artifacts_reconsidered","fallback_receiver_queries","completion_tier2_invocations"))
                out.put(key,findNumber(status,key));
            return out;
        }catch(Exception e){return Map.of("status_error",-1L);}
    }

    private static long findNumber(Object value,String key){
        if(value instanceof Map<?,?> map){
            Object direct=map.get(key);if(direct instanceof Number number)return number.longValue();
            for(Object nested:map.values()){long found=findNumber(nested,key);if(found!=Long.MIN_VALUE)return found;}
        }else if(value instanceof Collection<?> values){
            for(Object nested:values){long found=findNumber(nested,key);if(found!=Long.MIN_VALUE)return found;}
        }
        return Long.MIN_VALUE;
    }

    private static Map<String,Long> delta(Map<String,Long> before,Map<String,Long> after){
        var out=new LinkedHashMap<String,Long>();
        for(String key:after.keySet()){
            long a=before.getOrDefault(key,Long.MIN_VALUE),b=after.get(key);
            out.put(key,a==Long.MIN_VALUE||b==Long.MIN_VALUE?Long.MIN_VALUE:b-a);
        }
        return out;
    }

    private static Map<String,Object> resources(){
        var out=new LinkedHashMap<String,Object>();
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        out.put("heap_used_bytes",heap.getUsed());out.put("heap_committed_bytes",heap.getCommitted());
        long peak=0;for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getPeakUsage()!=null)peak+=Math.max(0,pool.getPeakUsage().getUsed());
        out.put("memory_pool_peak_used_bytes",peak);
        try{
            var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            if(bean.isThreadAllocatedMemorySupported()&&!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
            out.put("request_thread_allocated_bytes",bean.isThreadAllocatedMemorySupported()?bean.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1L);
            long all=0;if(bean.isThreadAllocatedMemorySupported())for(long id:bean.getAllThreadIds()){long n=bean.getThreadAllocatedBytes(id);if(n>0)all+=n;}
            out.put("all_live_thread_allocated_bytes",all);
        }catch(Throwable ignored){
            out.put("request_thread_allocated_bytes",-1L);out.put("all_live_thread_allocated_bytes",-1L);
        }
        try{
            String status=Files.readString(Path.of("/proc/self/status"));
            out.put("rss_kb",procValue(status,"VmRSS"));out.put("peak_rss_kb",procValue(status,"VmHWM"));
        }catch(Exception ignored){out.put("rss_kb",-1L);out.put("peak_rss_kb",-1L);}
        return out;
    }

    private static long procValue(String status,String name){
        for(String line:status.split("\\R"))if(line.startsWith(name+":")){
            String[] parts=line.trim().split("\\s+");if(parts.length>=2)return Long.parseLong(parts[1]);
        }
        return -1;
    }

    @FunctionalInterface private interface ThrowingSupplier<T>{ T get()throws Exception; }
}
