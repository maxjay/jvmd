package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.analyzer.*;
import dev.jvmd.dist.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class InputBoundaryRepairTest {
    @TempDir Path root;
    private static void patch(Path source,Path classes,boolean changed)throws Exception {
        Path file=source.resolve("java/lang/ReviewApi.java");Files.createDirectories(file.getParent());
        Files.writeString(file,"package java.lang; public class ReviewApi { public static "+(changed?"String answer(){return \"new\";}":"int answer(){return 1;}")+" }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--patch-module","java.base="+source,"-d",classes.toString(),file.toString())).isZero();
    }
    @Test void patchModuleChangesInvalidateRealNavigation()throws Exception {
        Path src=Files.createDirectory(root.resolve("src")),patchSrc=Files.createDirectory(root.resolve("patch-src")),classes=Files.createDirectory(root.resolve("patch"));
        patch(patchSrc,classes,false);Path file=Files.writeString(src.resolve("A.java"),"class A { int n=java.lang.ReviewApi.answer(); }");
        var files=new FileStateRegistry();var docs=new Documents(files);var options=List.of("--release","25","--patch-module","java.base="+classes);
        var context=new Analyzer.Context("test:review:1","25",List.of(),List.of(src),"module",Map.of(),options);
        var observation=new CompilerInputs(files);var config=new CompilerInputs.Configuration("module",List.of(src),List.of(),options);
        try(var analyzer=new Analyzer(files);var navigation=new WorkspaceBindings(files)){
            analyzer.configure(context,null,128L*1024*1024);analyzer.documents(docs);
            WorkspaceBindings.Loader loader=(path,text)->analyzer.bindings(path,text,null);
            var beforeEnvironment=observation.capture(config,docs).environment();
            try(var before=navigation.get(()->List.of(file),List.of(),docs,"module",0,loader)){
                assertThat(before.tier()).isEqualTo(2);assertThat(before.warnings()).isEmpty();assertThat(before.diagnostics()).isEmpty();
                patch(patchSrc,classes,true);
                assertThat(observation.capture(config,docs).environment()).isNotEqualTo(beforeEnvironment);
                try(var after=navigation.get(()->List.of(file),List.of(),docs,"module",0,loader);var fresh=new Analyzer(files)){
                    assertThat(after.revision()).isNotSameAs(before.revision());
                    fresh.configure(context,null,128L*1024*1024);fresh.documents(docs);
                    var expected=fresh.bindings(file,docs.text(file),null);
                    assertThat(after.warnings()).isEmpty();
                    assertThat(after.diagnostics()).extracting(CompilerPool.Problem::code).contains("compiler.err.prob.found.req");
                    assertThat(after.diagnostics()).extracting(CompilerPool.Problem::code).containsExactlyElementsOf(expected.diagnostics().stream().map(CompilerPool.Problem::code).toList());
                }
            }
        }
    }
    @Test void overlappingSourceAndEnvironmentHaveStableFencesAndCompile()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A {}");var files=new FileStateRegistry();var docs=new Documents(files);docs.open(file,Files.readString(file),1);
        var config=new CompilerInputs.Configuration("module",List.of(root),List.of(),List.of("--release","25","--patch-module","java.base="+root));
        var inputs=new CompilerInputs(files);var before=inputs.capture(config,docs);
        for(int i=0;i<3;i++)assertThat(inputs.capture(config,docs)).isEqualTo(before);
        try(var pool=new CompilerPool(files)){
            pool.configure("module","25",List.of(),List.of(root),null,128L*1024*1024,config.options());pool.documents(docs);
            var result=pool.query(file,docs.text(file),2,(task,units,tier)->"accepted");assertThat(result.result()).isEqualTo("accepted");assertThat(result.warnings()).isEmpty();
        }
        Files.writeString(file,"class A { int disk; }");assertThat(inputs.capture(config,docs)).isNotEqualTo(before);
        var diskChanged=inputs.capture(config,docs);docs.change(file,2,List.of(new Documents.Change(null,"class A { int overlay; }")));
        assertThat(inputs.capture(config,docs)).isNotEqualTo(diskChanged);
    }
    @Test void closedOverlayTransitionsSupersedeWithoutPreventingCompletedReuse()throws Exception {
        Path src=Files.createDirectory(root.resolve("src")),file=Files.writeString(src.resolve("A.java"),"class A {}");var files=new FileStateRegistry();var docs=new Documents(files);var inputs=new CompilerInputs(files);
        var config=new CompilerInputs.Configuration("module",List.of(src),List.of(),List.of("--release","25"));var before=inputs.capture(config,docs);
        docs.open(file,"class A { int temporary; }",1);docs.close(file);var after=inputs.capture(config,docs);
        assertThat(after.sameInputs(before)).isTrue();assertThat(after).isNotEqualTo(before);
        Path unrelated=root.resolve("Unrelated.java");docs.open(unrelated,"class Unrelated {}",1);docs.close(unrelated);
        assertThat(inputs.capture(config,docs)).isEqualTo(after);
        docs.open(file,"class A {}",1);var opened=inputs.capture(config,docs);
        docs.change(file,2,List.of(new Documents.Change(null,"class A { int temporary; }")));docs.change(file,3,List.of(new Documents.Change(null,"class A {}")));
        var reverted=inputs.capture(config,docs);assertThat(reverted.sameInputs(opened)).isTrue();assertThat(reverted).isNotEqualTo(opened);docs.close(file);
        try(var pool=new CompilerPool(files)){
            pool.configure("module","25",List.of(),List.of(src),null,128L*1024*1024);pool.documents(docs);
            var result=pool.query(file,docs.text(file),2,(task,units,tier)->{docs.open(file,"class A { int temporary; }",1);docs.close(file);return "must reject";});
            assertThat(result.result()).isNull();assertThat(result.warnings()).anyMatch(w->w.startsWith("diagnostics_superseded"));
            assertThat(pool.query(file,docs.text(file),2,(task,units,tier)->"retry").result()).isEqualTo("retry");
        }
    }
}
