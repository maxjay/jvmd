package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: explicit reference queries scan only candidate artifacts and retain bytecode edges. */
@Tag("phase-8")
class LazyCodeReferencesTest {
    @TempDir Path root;
    @Test void staticInitializersAndBridgesDoNotBreakDependencyReferencePublication()throws Exception{
        Path classes=compile(),api=jar(classes,"a"),caller=jar(classes,"b");
        try(var index=new IndexService(root.resolve("index.db"),root.resolve("repository"))){
            index.indexJar(api,"fixture:api:1","jar");index.indexJar(caller,"fixture:caller:1","jar");
            var content=new BinaryReader().read(caller,true);
            var edges=CodeReader.read(content.models().values());
            assertThat(edges).anyMatch(e->e.src().equals("b.Caller")&&e.target().equals("a.Api#target()I"));
            assertThat(edges).noneMatch(e->e.src().contains("<clinit>")||e.src().equals("b.Caller#get()Ljava/lang/Object;"));
            var code=new CodePass(index);
            var target=index.find("Api/target()",null,false,10,0).getFirst();
            var incoming=code.expand(List.of(target),false,Set.of("calls"),null);
            assertThat(incoming.edges()).anyMatch(e->e.src().endsWith("b/Caller#"));
            assertThat(index.artifact(caller).hasCodeEdges()).isTrue();
            assertThat(code.expand(List.of(target),false,Set.of("calls"),null).edges()).isEqualTo(incoming.edges());
        }
    }
    @Test void callsFieldsAllocationCastsAndDynamicMethodReferencesAreLazyAndScoped()throws Exception{
        Path classes=compile();
        Path api=jar(classes,"a"),caller=jar(classes,"b"),top=jar(classes,"c"),unrelated=jar(classes,"d");
        try(var index=new IndexService(root.resolve("index.db"),root.resolve("repository"))){
            for(var entry:Map.of(api,"fixture:api:1",caller,"fixture:caller:1",top,"fixture:top:1",unrelated,"fixture:unrelated:1").entrySet())index.indexJar(entry.getKey(),entry.getValue(),"jar");
            index.loadWorkspace("active",List.of(new IndexService.WorkspaceArtifact(api.toString(),"compile"),new IndexService.WorkspaceArtifact(caller.toString(),"compile"),new IndexService.WorkspaceArtifact(top.toString(),"compile"),new IndexService.WorkspaceArtifact(unrelated.toString(),"compile")),List.of());
            index.loadWorkspace("isolated",List.of(new IndexService.WorkspaceArtifact(api.toString(),"compile")),List.of());
            assertThat(index.artifact(caller).hasCodeEdges()).isFalse();assertThat(index.artifact(unrelated).hasCodeEdges()).isFalse();
            var code=new CodePass(index);var target=index.find("Api/target()",null,false,10,0).getFirst();
            var incoming=code.expand(List.of(target),false,Set.of("calls"),"active");
            assertThat(incoming.edges().toString()).contains("Caller#call(","Caller#reference(");
            assertThat(index.artifact(caller).hasCodeEdges()).isTrue();assertThat(index.artifact(unrelated).hasCodeEdges()).isFalse();
            var call=index.find("Caller/call",null,false,10,0).getFirst();var next=code.expand(List.of(call),false,Set.of("calls"),"active");
            assertThat(next.edges().toString()).contains("Top#next(");assertThat(index.artifact(top).hasCodeEdges()).isTrue();
            assertThat(code.expand(List.of(target),false,Set.of("calls"),"isolated").edges()).isEmpty();
            var out=code.expand(List.of(call),true,Set.of("reads","writes"),"active");assertThat(out.edges()).extracting(IndexService.SourceEdge::kind).contains("reads","writes");
            var creation=code.expand(index.find("Caller/create()",null,false,10,0),true,Set.of("instantiates"),"active");assertThat(creation.edges()).extracting(IndexService.SourceEdge::kind).containsExactly("instantiates");
            var casts=code.expand(index.find("Caller/cast(Object)",null,false,10,0),true,Set.of("reads"),"active");assertThat(casts.edges().toString()).contains("Api#");
            // The SQL control additionally checks its physical foreign keys.
        }
    }
    private Path compile()throws Exception{
        Path source=Files.createDirectories(root.resolve("sources")),classes=Files.createDirectories(root.resolve("classes"));var files=new ArrayList<String>();
        Map<String,String> code=Map.of(
            "a/Api.java","package a; public class Api { public int count; public static int target(){return 7;} }",
            "b/Caller.java","package b; public class Caller implements java.util.function.Supplier<String> { static int initial=a.Api.target(); public String get(){return String.valueOf(a.Api.target());} public int call(a.Api api,Object ignored){api.count++;return a.Api.target();} public a.Api create(){return new a.Api();} public a.Api cast(Object input){return (a.Api)input;} public java.util.function.IntSupplier reference(){return a.Api::target;} }",
            "c/Top.java","package c; public class Top { public int next(b.Caller caller,a.Api api){return caller.call(api,api);} }",
            "d/Other.java","package d; public class Other { public int untouched(){return 1;} }");
        for(var entry:code.entrySet()){Path file=source.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());files.add(file.toString());}
        var args=new ArrayList<>(List.of("-g","-parameters","-d",classes.toString()));args.addAll(files);assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))).isZero();return classes;
    }
    private Path jar(Path classes,String pkg)throws Exception{
        Path result=root.resolve(pkg+".jar");try(var out=new JarOutputStream(Files.newOutputStream(result));var paths=Files.walk(classes.resolve(pkg))){
            for(Path file:paths.filter(Files::isRegularFile).sorted().toList()){out.putNextEntry(new JarEntry(classes.relativize(file).toString()));out.write(Files.readAllBytes(file));out.closeEntry();}
        }return result;
    }
}
