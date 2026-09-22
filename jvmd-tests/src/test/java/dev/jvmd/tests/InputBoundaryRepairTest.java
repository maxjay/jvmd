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
            WorkspaceBindings.InputSource inputSource=()->Map.of("module",new WorkspaceBindings.ModuleInputs(analyzer.inputSnapshot(),Set.of(file)));
            WorkspaceBindings.BatchLoader loader=analyzer::bindingsBatch;
            var beforeEnvironment=observation.capture(config,docs).environment();
            try(var before=navigation.getBatch(inputSource,docs,0,loader)){
                assertThat(before.tier()).isEqualTo(2);assertThat(before.warnings()).isEmpty();assertThat(before.diagnostics()).isEmpty();
                patch(patchSrc,classes,true);
                assertThat(observation.capture(config,docs).environment()).isNotEqualTo(beforeEnvironment);
                try(var after=navigation.getBatch(inputSource,docs,0,loader);var fresh=new Analyzer(files)){
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
    @Test void navigationKeepsModuleEnvironmentsOrderedAndInvalidatesOnlyTheirOwners()throws Exception {
        Path left=Files.createDirectory(root.resolve("left")),right=Files.createDirectory(root.resolve("right"));
        Path a=Files.writeString(left.resolve("A.java"),"class A { int n=new fixture.Sample().value(); }"),b=Files.writeString(right.resolve("B.java"),"class B {}");
        Path first=IndexFixtures.jar(root,"int-api","package fixture; public class Sample { public int value(){return 1;} }",true);
        Path second=IndexFixtures.jar(root,"string-api","package fixture; public class Sample { public String value(){return \"x\";} }",true);
        var files=new FileStateRegistry();var docs=new Documents(files);
        try(var leftAnalyzer=new Analyzer(files);var rightAnalyzer=new Analyzer(files);var navigation=new WorkspaceBindings(files)){
            leftAnalyzer.configure(new Analyzer.Context("test:left:1","25",List.of(first,second),List.of(left),"left",Map.of()),null,128L*1024*1024);leftAnalyzer.documents(docs);
            rightAnalyzer.configure(new Analyzer.Context("test:right:1","25",List.of(),List.of(right),"right",Map.of()),null,128L*1024*1024);rightAnalyzer.documents(docs);
            WorkspaceBindings.InputSource source=()->Map.of("left",new WorkspaceBindings.ModuleInputs(leftAnalyzer.inputSnapshot(),Set.of(a)),"right",new WorkspaceBindings.ModuleInputs(rightAnalyzer.inputSnapshot(),Set.of(b)));
            int[] loads={0,0};
            WorkspaceBindings.BatchLoader loader=texts->{
                var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
                for(var entry:texts.entrySet()){
                    boolean isLeft=entry.getKey().equals(a);loads[isLeft?0:1]++;
                    results.put(entry.getKey(),(isLeft?leftAnalyzer:rightAnalyzer).bindings(entry.getKey(),entry.getValue(),null));
                }
                return results;
            };
            try(var before=navigation.getBatch(source,docs,0,loader)){
                assertThat(before.warnings()).isEmpty();assertThat(before.diagnostics()).isEmpty();assertThat(loads).containsExactly(1,1);
                leftAnalyzer.configure(new Analyzer.Context("test:left:1","25",List.of(second,first),List.of(left),"left",Map.of()),null,128L*1024*1024);leftAnalyzer.documents(docs);
                try(var after=navigation.getBatch(source,docs,0,loader)){
                    assertThat(after.revision()).isNotSameAs(before.revision());assertThat(after.warnings()).isEmpty();
                    assertThat(after.diagnostics()).extracting(CompilerPool.Problem::code).contains("compiler.err.prob.found.req");
                    assertThat(loads).containsExactly(2,1);
                }
            }
        }
    }
    @Test void nestedAnalysisUsesOneStartAndOneEndCapture()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A { int n; }");var docs=new Documents();
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"module",Map.of()),null,128L*1024*1024);analyzer.documents(docs);
            var result=analyzer.diagnostics(file,docs);assertThat(result.warnings()).isEmpty();
            assertThat(((Map<?,?>)analyzer.status().get("input_validation")).get("observations")).isEqualTo(2L);
            analyzer.diagnostics(file,docs);
            assertThat(((Map<?,?>)analyzer.status().get("input_validation")).get("observations")).isEqualTo(3L);
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
    @Test void evictedDiskObservationsDoNotManufactureSupersession()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A {}");var files=new FileStateRegistry();var docs=new Documents(files);
        var inputs=new CompilerInputs(files);var config=new CompilerInputs.Configuration("module",List.of(root),List.of(),List.of("--release","25"));
        var before=inputs.capture(config,docs);files.forget(file);
        assertThat(inputs.capture(config,docs)).isEqualTo(before);
        files.reconcile();assertThat(inputs.capture(config,docs)).isEqualTo(before);
        try(var pool=new CompilerPool(files)){
            pool.configure("module","25",List.of(),List.of(root),null,128L*1024*1024);pool.documents(docs);
            assertThat(pool.query(file,docs.text(file),2,(task,units,tier)->{files.forget(file);return "stable";}).result()).isEqualTo("stable");
        }
        Files.writeString(file,"class A { int n; }");assertThat(inputs.capture(config,docs)).isNotEqualTo(before);
    }
    @Test void revertedDirectoryMembershipStillSupersedesCompilerWork()throws Exception {
        Path src=Files.createDirectory(root.resolve("src")),cp=Files.createDirectory(root.resolve("classes"));
        Path file=Files.writeString(src.resolve("A.java"),"class A {}");var files=new FileStateRegistry();var docs=new Documents(files);var inputs=new CompilerInputs(files);
        var config=new CompilerInputs.Configuration("module",List.of(src),List.of(cp),List.of("--release","25"));var before=inputs.capture(config,docs);
        var time=Files.getLastModifiedTime(cp);Path transientClass=Files.write(cp.resolve("Transient.class"),new byte[]{1});Files.delete(transientClass);
        Files.setLastModifiedTime(cp,java.nio.file.attribute.FileTime.fromMillis(time.toMillis()+1000));
        var after=inputs.capture(config,docs);assertThat(after.sameInputs(before)).isTrue();assertThat(after).isNotEqualTo(before);
        assertThat(inputs.capture(config,docs)).isEqualTo(after);
        try(var pool=new CompilerPool(files)){
            pool.configure("module","25",List.of(cp),List.of(src),null,128L*1024*1024);pool.documents(docs);
            var result=pool.query(file,docs.text(file),2,(task,units,tier)->{
                var stamp=Files.getLastModifiedTime(cp);Files.write(transientClass,new byte[]{1});Files.delete(transientClass);
                Files.setLastModifiedTime(cp,java.nio.file.attribute.FileTime.fromMillis(stamp.toMillis()+1000));return "must reject";
            });
            assertThat(result.result()).isNull();assertThat(result.warnings()).anyMatch(w->w.startsWith("diagnostics_superseded"));
            assertThat(pool.query(file,docs.text(file),2,(task,units,tier)->"retry").result()).isEqualTo("retry");
        }
    }
    @Test void observationsReleasePathsRemovedFromEitherRole()throws Exception {
        Path src=Files.createDirectory(root.resolve("src")),cp=Files.createDirectory(root.resolve("classes"));
        Path file=Files.writeString(src.resolve("A.java"),"class A {}"),binary=Files.write(cp.resolve("A.class"),new byte[]{1});
        var files=new FileStateRegistry();var inputs=new CompilerInputs(files);var docs=new Documents(files);
        var config=new CompilerInputs.Configuration("module",List.of(src),List.of(cp),List.of());inputs.capture(config,docs);
        Files.delete(file);Files.delete(binary);inputs.capture(config,docs);
        for(String role:List.of("sourceEvidence","environmentEvidence")){
            var field=CompilerInputs.class.getDeclaredField(role);field.setAccessible(true);
            var evidence=(Map<?,?>)field.get(inputs);assertThat(evidence.containsKey(file)).isFalse();assertThat(evidence.containsKey(binary)).isFalse();
        }
    }

}
