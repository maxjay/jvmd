package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import dev.jvmd.core.RequestScope;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-local Issue #36 proof probe.
 *
 * This file is copied into the measured subject worktree by the frozen workflow.
 * It is never committed into production source and intentionally records failures
 * rather than weakening the semantic oracle.
 */
public class Issue36ProofTest {
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private final Path reportDir=Path.of(System.getProperty("issue36.reportDir")).toAbsolutePath();
    private final String subject=System.getProperty("issue36.subjectSha","unknown");

    @Test void proof() throws Exception {
        Files.createDirectories(reportDir);
        scenario("simple_parameter_receiver", this::simpleParameterReceiver);
        scenario("prefix_narrowing", this::prefixNarrowing);
        scenario("field_receiver", this::fieldReceiver);
        scenario("static_receiver", this::staticReceiver);
        scenario("chained_receiver", this::chainedReceiver);
        scenario("generic_receiver", this::genericReceiver);
        scenario("deep_hierarchy", this::deepHierarchy);
        scenario("wide_hierarchy", this::wideHierarchy);
        scenario("access_filtering", this::accessFiltering);
        scenario("unsaved_edit", this::unsavedEdit);

        scenario("body_only_irrelevant_edit", this::bodyOnlyEdit);
        scenario("unrelated_api_member", this::unrelatedApiMember);
        scenario("relevant_range_member", this::relevantRangeMember);
        scenario("exact_symbol_irrelevant", this::exactSymbolIrrelevant);
        scenario("exact_symbol_relevant", this::exactSymbolRelevant);
        scenario("overload_irrelevant", this::overloadIrrelevant);
        scenario("overload_relevant", this::overloadRelevant);
        scenario("hierarchy_edit", this::hierarchyEdit);

        scenario("namespace_unrelated", this::namespaceUnrelated);
        scenario("namespace_relevant", this::namespaceRelevant);
        scenario("negative_lookup_unchanged", this::negativeLookupUnchanged);
        scenario("negative_lookup_becomes_valid", this::negativeLookupBecomesValid);

        scenario("classpath_c_changes", this::classpathCChanges);
        scenario("classpath_unreferenced_c", this::classpathUnreferencedC);
        scenario("classpath_relevant_c", this::classpathRelevantC);
        scenario("classpath_reorder", this::classpathReorder);
        scenario("classpath_insert", this::classpathInsert);
        scenario("classpath_remove", this::classpathRemove);
        scenario("machine_workspace_composition", this::machineWorkspaceComposition);

        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportDir.resolve("direct-proof.json").toFile(),
                Map.of("schema",1,"subject_sha",subject,"scenarios",rows));
        assertThat(rows).hasSize(29);
    }

    private void scenario(String name, Callable<Map<String,Object>> work) throws Exception {
        var row=new LinkedHashMap<String,Object>();
        row.put("name",name);
        long beforeHeap=heap(), beforeAlloc=allocated(), started=System.nanoTime();
        try {
            Map<String,Object> value=RequestScope.traced("issue36."+name,"issue-36",name,subject,work::call);
            row.put("result",value);
        } catch(Throwable error) {
            row.put("error",error.getClass().getName()+": "+String.valueOf(error.getMessage()));
        }
        row.put("latency_ms",Math.round((System.nanoTime()-started)/1000.0)/1000.0);
        row.put("thread_allocated_bytes",delta(beforeAlloc,allocated()));
        row.put("heap_before_bytes",beforeHeap);
        row.put("heap_after_bytes",heap());
        rows.add(row);
    }

    private static long heap(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    private static long allocated(){
        var bean=ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean x&&x.isThreadAllocatedMemorySupported()
                ?x.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
    }
    private static long delta(long before,long after){return before<0||after<0?-1:Math.max(0,after-before);}

    private Analyzer.Context context(Path root){
        return new Analyzer.Context("issue36:fixture:1","25",List.of(),List.of(root),"issue36",Map.of());
    }
    private Analyzer.Context context(Path root,List<Path> classpath,String generation){
        return new Analyzer.Context("issue36:fixture:1","25",classpath,List.of(root),generation,Map.of());
    }
    private static JsonNode complete(Analyzer analyzer,Path file,String text,String needle)throws Exception{
        int cursor=text.indexOf(needle)+needle.length();
        if(cursor<needle.length())throw new IllegalArgumentException("needle not found: "+needle);
        var pos=Documents.position(text,cursor);
        var answer=analyzer.completion(file,text,pos.line(),pos.character(),200,0);
        var node=Json.MAPPER.valueToTree(answer.result());
        var result=new LinkedHashMap<String,Object>();
        result.put("warnings",answer.warnings());
        result.put("names",node.path("items").findValuesAsText("name"));
        result.put("labels",node.path("items").findValuesAsText("label"));
        return Json.MAPPER.valueToTree(result);
    }
    private static long number(Map<String,Object> status,String key){
        Object value=status.get(key);return value instanceof Number n?n.longValue():-1;
    }
    @SuppressWarnings("unchecked")
    private static long nestedNumber(Map<String,Object> status,String outer,String key){
        Object value=status.get(outer);
        return value instanceof Map<?,?> map&&map.get(key) instanceof Number n?n.longValue():-1;
    }
    private static Map<String,Object> delta(Map<String,Object> before,Map<String,Object> after){
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("queries","completion_requests","focus_layout_parses","api_fingerprint_changes","api_fingerprint_unchanged",
                "pending_api_files","conditional_files","classpath_fingerprints")){
            long a=number(before,key),b=number(after,key);if(a>=0&&b>=0)out.put(key,b-a);
        }
        for(String key:List.of("semantic_fact_mutations","semantic_tree_mutations","semantic_tree_range_entries_read","semantic_units",
                "semantic_stale_units")){
            long a=nestedNumber(before,"resident_semantic_state",key),b=nestedNumber(after,"resident_semantic_state",key);
            if(a>=0&&b>=0)out.put("resident."+key,b-a);
        }
        return out;
    }
    private static Map<String,Object> completionResult(Analyzer analyzer,Path file,String text,String needle,Map<String,Object> before)throws Exception{
        JsonNode value=complete(analyzer,file,text,needle);
        var after=analyzer.status();
        return Map.of("completion",value,"status_delta",delta(before,after),"status_after",after);
    }

    private Map<String,Object> simpleParameterReceiver()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"parameter-");
        Files.writeString(root.resolve("Api.java"),"class Api { int getOne(){return 1;} int setOne(){return 2;} }");
        String source="class Use { Object f(Api project){ return project.; } }";
        Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);var before=analyzer.status();
            return completionResult(analyzer,file,source,"project.",before);}
    }
    private Map<String,Object> prefixNarrowing()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"prefix-");
        Files.writeString(root.resolve("Api.java"),"class Api { int getModel(){return 1;} int getName(){return 2;} int setName(){return 3;} }");
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root),null,256L*1024*1024);
            var result=new LinkedHashMap<String,Object>();
            for(String prefix:List.of("g","get","getM")){
                String source="class Use { Object f(Api project){ return project."+prefix+"; } }";
                Path file=root.resolve("Use.java");Files.writeString(file,source);
                var before=analyzer.status();result.put(prefix,completionResult(analyzer,file,source,"project."+prefix,before));
            }
            return result;
        }
    }
    private Map<String,Object> fieldReceiver()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"field-");
        Files.writeString(root.resolve("Api.java"),"class Api { int getField(){return 1;} }");
        String source="class Use { Api projectField; Object a(){ return this.projectField.; } Object b(){ return projectField.; } }";
        Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);
            var result=new LinkedHashMap<String,Object>();
            var before=analyzer.status();result.put("this",completionResult(analyzer,file,source,"this.projectField.",before));
            before=analyzer.status();result.put("field",completionResult(analyzer,file,source,"projectField.",before));return result;}
    }
    private Map<String,Object> staticReceiver()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"static-");
        Files.writeString(root.resolve("SomeType.java"),"class SomeType { static int getStatic(){return 1;} int getInstance(){return 2;} }");
        String source="class Use { Object f(){ return SomeType.; } }";Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);return completionResult(analyzer,file,source,"SomeType.",analyzer.status());}
    }
    private Map<String,Object> chainedReceiver()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"chain-");
        Files.writeString(root.resolve("Model.java"),"class Model { int getName(){return 1;} }");
        Files.writeString(root.resolve("Api.java"),"class Api { Model getModel(){return new Model();} }");
        String source="class Use { Object f(Api project){ return project.getModel().; } }";Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);return completionResult(analyzer,file,source,"project.getModel().",analyzer.status());}
    }
    private Map<String,Object> genericReceiver()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"generic-");
        Files.writeString(root.resolve("Box.java"),"class Box<T> { T value(){return null;} }");
        String source="class Use { <T extends CharSequence> Object f(Box<T> box){ return box.value().; } }";Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);return completionResult(analyzer,file,source,"box.value().",analyzer.status());}
    }
    private Map<String,Object> deepHierarchy()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"deep-");
        for(int i=0;i<12;i++)Files.writeString(root.resolve("D"+i+".java"),"class D"+i+(i==0?"":" extends D"+(i-1))+" { int getD"+i+"(){return "+i+";} }");
        String source="class Use { Object f(D11 value){ return value.get; } }";Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);return completionResult(analyzer,file,source,"value.get",analyzer.status());}
    }
    private Map<String,Object> wideHierarchy()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"wide-");var api=new StringBuilder("class Wide {");
        for(int i=0;i<120;i++)api.append(" int get").append(i).append("(){return ").append(i).append(";}");api.append("}");
        Files.writeString(root.resolve("Wide.java"),api);
        String source="class Use { Object f(Wide value){ return value.get; } }";Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);return completionResult(analyzer,file,source,"value.get",analyzer.status());}
    }
    private Map<String,Object> accessFiltering()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"access-"),a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b"));
        Files.writeString(a.resolve("Api.java"),"package a; public class Api { public int pub(){return 1;} protected int pro(){return 2;} int pkg(){return 3;} private int pri(){return 4;} public static int stat(){return 5;} }");
        String source="package b; class Use { Object f(a.Api value){ return value.; } }";Path file=Files.writeString(b.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);return completionResult(analyzer,file,source,"value.",analyzer.status());}
    }
    private Map<String,Object> unsavedEdit()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"unsaved-");
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getDisk(){return 1;} }");
        String source="class Use { Object f(Api value){ return value.get; } }";Path file=Files.writeString(root.resolve("Use.java"),source);
        var docs=new Documents();docs.open(api,"class Api { int getOverlay(){return 2;} }",1);docs.open(file,source,1);
        try(var analyzer=new Analyzer()){analyzer.configure(context(root),null,256L*1024*1024);analyzer.documents(docs);return completionResult(analyzer,file,source,"value.get",analyzer.status());}
    }

    private Map<String,Object> bodyOnlyEdit()throws Exception{return completionMutation("body",false,false);}
    private Map<String,Object> unrelatedApiMember()throws Exception{return completionMutation("unrelated",true,false);}
    private Map<String,Object> relevantRangeMember()throws Exception{return completionMutation("relevant",true,true);}
    private Map<String,Object> completionMutation(String mode,boolean apiChange,boolean relevant)throws Exception{
        Path root=Files.createTempDirectory(reportDir,"mut-"+mode+"-");
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getOne(){return 1;} }");
        Path other=Files.writeString(root.resolve("Other.java"),"class Other { int value(){return 1;} }");
        String source="class Use { Object f(Api value){ return value.get; } }";Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root),null,256L*1024*1024);complete(analyzer,file,source,"value.get");
            var before=analyzer.status();
            if(!apiChange){Files.writeString(other,"class Other { int value(){ int x=1; return x;} }");analyzer.changed(other);}
            else {Files.writeString(api,"class Api { int getOne(){return 1;} int "+(relevant?"getTwo":"setTwo")+"(){return 2;} }");analyzer.changed(api);}
            JsonNode completion=complete(analyzer,file,source,"value.get");var after=analyzer.status();
            return Map.of("completion",completion,"status_delta",delta(before,after));
        }
    }
    private Map<String,Object> exactSymbolIrrelevant()throws Exception{return dependencyMutation("exact_irrelevant","int one(){return 1;} int two(){return 2;}","int one(){return 1;} String two(){return \"2\";}","return a.one();");}
    private Map<String,Object> exactSymbolRelevant()throws Exception{return dependencyMutation("exact_relevant","int one(){return 1;} int two(){return 2;}","String one(){return \"1\";} int two(){return 2;}","return a.one();");}
    private Map<String,Object> overloadIrrelevant()throws Exception{return dependencyMutation("overload_irrelevant","int foo(int x){return x;} int bar(){return 1;}","int foo(int x){return x;} String bar(){return \"x\";}","return a.foo(1);");}
    private Map<String,Object> overloadRelevant()throws Exception{return dependencyMutation("overload_relevant","int foo(int x){return x;}","int foo(int x){return x;} int foo(String x){return x.length();}","return a.foo(1);");}
    private Map<String,Object> dependencyMutation(String label,String beforeApi,String afterApi,String call)throws Exception{
        Path root=Files.createTempDirectory(reportDir,label+"-");Path api=Files.writeString(root.resolve("A.java"),"class A { "+beforeApi+" }");
        Path use=Files.writeString(root.resolve("B.java"),"class B { Object f(A a){ "+call+" } }");var docs=new Documents();
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root),null,256L*1024*1024);analyzer.documents(docs);analyzer.diagnostics(use,docs);
            var before=analyzer.status();Files.writeString(api,"class A { "+afterApi+" }");analyzer.changed(api);analyzer.diagnostics(use,docs);var after=analyzer.status();
            return Map.of("status_delta",delta(before,after),"warnings",List.of());
        }
    }
    private Map<String,Object> hierarchyEdit()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"hierarchy-");
        Files.writeString(root.resolve("BaseA.java"),"class BaseA { int getA(){return 1;} }");
        Files.writeString(root.resolve("BaseB.java"),"class BaseB { int getB(){return 2;} }");
        Path api=Files.writeString(root.resolve("Api.java"),"class Api extends BaseA {}");
        String source="class Use { Object f(Api value){ return value.get; } }";Path use=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root),null,256L*1024*1024);complete(analyzer,use,source,"value.get");var before=analyzer.status();
            Files.writeString(api,"class Api extends BaseB {}");analyzer.changed(api);JsonNode completion=complete(analyzer,use,source,"value.get");var after=analyzer.status();
            return Map.of("completion",completion,"status_delta",delta(before,after));
        }
    }

    private Map<String,Object> namespaceUnrelated()throws Exception{return namespaceScenario(false,false);}
    private Map<String,Object> namespaceRelevant()throws Exception{return namespaceScenario(true,false);}
    private Map<String,Object> negativeLookupUnchanged()throws Exception{return namespaceScenario(false,true);}
    private Map<String,Object> negativeLookupBecomesValid()throws Exception{return namespaceScenario(true,true);}
    private Map<String,Object> namespaceScenario(boolean relevant,boolean negative)throws Exception{
        Path root=Files.createTempDirectory(reportDir,"namespace-"),foo=Files.createDirectories(root.resolve("foo/bar")),other=Files.createDirectories(root.resolve("com/other"));
        if(!negative)Files.writeString(foo.resolve("Widget.java"),"package foo.bar; public class Widget { public int getOne(){return 1;} }");
        String source="import foo.bar.*; class Use { Object f(Widget value){ return value.get; } }";Path use=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(root),null,256L*1024*1024);complete(analyzer,use,source,"value.get");var before=analyzer.status();
            Path added;
            if(relevant)added=Files.writeString(foo.resolve(negative?"Widget.java":"Added.java"),negative?"package foo.bar; public class Widget { public int getTwo(){return 2;} }":"package foo.bar; public class Added {}");
            else added=Files.writeString(other.resolve("RandomClass.java"),"package com.other; public class RandomClass {}");
            analyzer.sourceMembershipChanged(added);
            JsonNode completion=complete(analyzer,use,source,"value.get");var after=analyzer.status();
            return Map.of("completion",completion,"status_delta",delta(before,after));
        }
    }

    private record Cp(Path root,Path a,Path b,Path c,Path d){}
    private Cp classpathFixture()throws Exception{
        Path root=Files.createTempDirectory(reportDir,"cp-");
        Path a=IndexFixtures.jar(root.resolve("a"),"a","package dep; public class Sample { public int fromA(){return 1;} }",true);
        Path b=IndexFixtures.jar(root.resolve("b"),"b","package dep; public class Sample { public int fromB(){return 2;} }",true);
        Path c=IndexFixtures.jar(root.resolve("c"),"c","package dep; public class Sample { public int fromC(){return 3;} }",true);
        Path d=IndexFixtures.jar(root.resolve("d"),"d","package dep; public class Sample { public int fromD(){return 4;} }",true);
        return new Cp(root,a,b,c,d);
    }
    private Path cpCaller(Cp cp)throws Exception{return Files.writeString(cp.root.resolve("Use.java"),"import dep.Sample; class Use { Object f(Sample value){ return value.; } }");}
    private Map<String,Object> cpRun(Cp cp,List<Path> order,Path caller,String generation)throws Exception{
        String source=Files.readString(caller);try(var analyzer=new Analyzer()){analyzer.configure(context(cp.root,order,generation),null,256L*1024*1024);return completionResult(analyzer,caller,source,"value.",analyzer.status());}
    }
    private Map<String,Object> classpathCChanges()throws Exception{
        Cp cp=classpathFixture();Path caller=cpCaller(cp);var before=cpRun(cp,List.of(cp.a,cp.b,cp.c,cp.d),caller,"cp-c");
        Path replacement=IndexFixtures.jar(cp.root.resolve("cr"),"cr","package dep; public class Sample { public int changedC(){return 3;} }",true);
        Files.copy(replacement,cp.c,StandardCopyOption.REPLACE_EXISTING);var after=cpRun(cp,List.of(cp.a,cp.b,cp.c,cp.d),caller,"cp-c2");return Map.of("before",before,"after",after);
    }
    private Map<String,Object> classpathUnreferencedC()throws Exception{return classpathCChanges();}
    private Map<String,Object> classpathRelevantC()throws Exception{
        Cp cp=classpathFixture();Path caller=cpCaller(cp);var before=cpRun(cp,List.of(cp.c,cp.a,cp.b,cp.d),caller,"cp-rel");
        Path replacement=IndexFixtures.jar(cp.root.resolve("cr"),"cr","package dep; public class Sample { public int changedC(){return 3;} }",true);
        Files.copy(replacement,cp.c,StandardCopyOption.REPLACE_EXISTING);var after=cpRun(cp,List.of(cp.c,cp.a,cp.b,cp.d),caller,"cp-rel2");return Map.of("before",before,"after",after);
    }
    private Map<String,Object> classpathReorder()throws Exception{
        Cp cp=classpathFixture();Path caller=cpCaller(cp);return Map.of("before",cpRun(cp,List.of(cp.a,cp.b,cp.c,cp.d),caller,"cp-r1"),"after",cpRun(cp,List.of(cp.a,cp.c,cp.b,cp.d),caller,"cp-r2"));
    }
    private Map<String,Object> classpathInsert()throws Exception{
        Cp cp=classpathFixture();Path caller=cpCaller(cp);return Map.of("before",cpRun(cp,List.of(cp.a,cp.b,cp.c),caller,"cp-i1"),"after",cpRun(cp,List.of(cp.d,cp.a,cp.b,cp.c),caller,"cp-i2"));
    }
    private Map<String,Object> classpathRemove()throws Exception{
        Cp cp=classpathFixture();Path caller=cpCaller(cp);return Map.of("before",cpRun(cp,List.of(cp.a,cp.b,cp.c,cp.d),caller,"cp-x1"),"after",cpRun(cp,List.of(cp.b,cp.c,cp.d),caller,"cp-x2"));
    }
    private Map<String,Object> machineWorkspaceComposition()throws Exception{
        Cp cp=classpathFixture();Path caller=cpCaller(cp);
        var abBefore=cpRun(cp,List.of(cp.a,cp.b),caller,"workspace-ab-1");
        var cBefore=cpRun(cp,List.of(cp.c),caller,"workspace-c-1");
        Path replacement=IndexFixtures.jar(cp.root.resolve("cr"),"cr","package dep; public class Sample { public int changedC(){return 9;} }",true);
        Files.copy(replacement,cp.c,StandardCopyOption.REPLACE_EXISTING);
        var abAfter=cpRun(cp,List.of(cp.a,cp.b),caller,"workspace-ab-2");
        var cAfter=cpRun(cp,List.of(cp.c),caller,"workspace-c-2");
        return Map.of("workspace_ab_before",abBefore,"workspace_ab_after",abAfter,"workspace_c_before",cBefore,"workspace_c_after",cAfter,
                "note","baseline has no compositional MachineRoot/WorkspaceDependencyRoot proof API; behavior is recorded explicitly");
    }
}
