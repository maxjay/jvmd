package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompilerInputsTest {
    @TempDir Path root;
    private CompilerInputs.Configuration config(List<Path> cp){return new CompilerInputs.Configuration("module",List.of(root),cp,List.of("--release","25"));}
    private static long count(Map<String,Object> status,String key){return ((Number)status.get(key)).longValue();}

    @Test void compilerInventoriesFollowRootAndPackageLinksAndRejectCycles()throws Exception {
        Path target=Files.createDirectories(root.resolve("target"));
        Path binary=Files.write(target.resolve("A.class"),new byte[]{1});
        Path cp=root.resolve("cp");
        try{Files.createSymbolicLink(cp,target);}catch(UnsupportedOperationException|java.nio.file.FileSystemException unsupported){
            Assumptions.abort("Symbolic links unavailable: "+unsupported);return;
        }
        var files=new FileStateRegistry();var inputs=new CompilerInputs(files);var configuration=config(List.of(cp));
        var initial=inputs.environment(configuration);
        Files.write(binary,new byte[]{2});assertThat(inputs.environment(configuration)).isNotEqualTo(initial);
        assertThat(files.inventory(cp,".class")).isEmpty();
        Path external=Files.createDirectories(root.resolve("external"));
        Files.createSymbolicLink(target.resolve("pkg"),external);
        var empty=inputs.environment(configuration);
        Path member=Files.write(external.resolve("B.class"),new byte[]{3});
        var added=inputs.environment(configuration);assertThat(added).isNotEqualTo(empty);
        Files.write(member,new byte[]{4});assertThat(inputs.environment(configuration)).isNotEqualTo(added);
        Files.delete(member);Files.delete(external);inputs.environment(configuration);
        Files.createDirectory(external);Files.write(external.resolve("C.class"),new byte[]{5});
        assertThat(inputs.environment(configuration)).isNotEqualTo(empty);
        Files.createSymbolicLink(external.resolve("loop"),target);
        assertThatThrownBy(()->inputs.environment(configuration)).isInstanceOf(FileSystemLoopException.class);
        Files.delete(external.resolve("loop"));
        assertThat(files.inventory(cp,".class",true)).contains(cp.resolve("pkg/C.class"));
    }

    @Test void everyAdditionalJavacInputPathObservesEditsAndMembership()throws Exception {
        Path input=Files.createDirectories(root.resolve("options"));
        Path file=Files.writeString(input.resolve("Input.java"),"class Input {}");
        var inputs=new CompilerInputs(new FileStateRegistry());int version=0;
        for(String option:List.of("--source-path","-sourcepath","--module-source-path","--boot-class-path","-bootclasspath",
                "-extdirs","-endorseddirs","-Djava.ext.dirs","-Djava.endorsed.dirs")){
            for(boolean equals:List.of(false,true)){
                var options=equals?List.of(option+"="+input):List.of(option,input.toString());
                var configuration=new CompilerInputs.Configuration("module",List.of(),List.of(),options);
                var before=inputs.environment(configuration);
                Files.writeString(file,"class Input { int n="+(++version)+"; }");
                assertThat(inputs.environment(configuration)).as(options.toString()).isNotEqualTo(before);
            }
        }
        for(String value:List.of("-Xbootclasspath:","-Xbootclasspath/a:","-Xbootclasspath/p:")){
            var configuration=new CompilerInputs.Configuration("module",List.of(),List.of(),List.of(value+input));
            var before=inputs.environment(configuration);Files.writeString(file,"class Input { int n="+(++version)+"; }");
            assertThat(inputs.environment(configuration)).isNotEqualTo(before);
        }
        for(String value:List.of("m="+input,input+"/*/src",input+"/{one,two}/*")){
            var configuration=new CompilerInputs.Configuration("module",List.of(),List.of(),List.of("--module-source-path",value));
            var before=inputs.environment(configuration);
            Files.writeString(input.resolve("Added"+(++version)+".java"),"class Added {}");
            assertThat(inputs.environment(configuration)).isNotEqualTo(before);
        }
    }

    @Test void warmCaptureReadsMaintainedSourceIdentityWithoutSourceInventory()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A { int n=1; }");
        var files=new FileStateRegistry();try(var docs=new Documents(files)){
            var inputs=new CompilerInputs(files);var configuration=config(List.of());
            var initial=inputs.capture(configuration,docs);var work=files.status();var tracked=inputs.status();
            for(int i=0;i<20;i++)assertThat(inputs.capture(configuration,docs)).isSameAs(initial);
            assertThat(files.status()).containsEntry("hashes",work.get("hashes")).containsEntry("directory_enumerations",work.get("directory_enumerations"));
            assertThat(inputs.status()).containsEntry("snapshot_rebuilds",tracked.get("snapshot_rebuilds"));
            assertThat(inputs.status()).containsEntry("source_inventory_calls",0L).containsEntry("source_candidates_inspected",0L);

            Files.writeString(file,"class A { int n=2; }");
            docs.liveState(configuration.roots()).observe(file);
            var changed=inputs.capture(configuration,docs);
            assertThat(changed.environment()).isEqualTo(initial.environment());
            assertThat(changed.membership()).isEqualTo(initial.membership());
            assertThat(changed.content()).isNotEqualTo(initial.content());
            assertThat(changed.changedSince(initial)).containsExactly(file.toAbsolutePath().normalize());
        }
    }

    @Test void overlaysAreSessionLocalAndCloseReturnsToCurrentDisk()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A {}"),unsaved=root.resolve("New.java");
        var files=new FileStateRegistry();var first=new CompilerInputs(files);var second=new CompilerInputs(files);
        try(var left=new Documents(files);var right=new Documents(files)){
            left.open(file,"class A { int x; }",1);right.open(file,"class A { int y; }",1);left.open(unsaved,"class New {}",1);
            var l=first.capture(config(List.of()),left);var r=second.capture(config(List.of()),right);
            assertThat(l.source(file)).isNotEqualTo(r.source(file));
            assertThat(l.membership()).isNotEqualTo(r.membership());
            assertThat(left.liveState(List.of(root)).paths()).contains(unsaved.toAbsolutePath().normalize());
            assertThat(right.liveState(List.of(root)).paths()).doesNotContain(unsaved.toAbsolutePath().normalize());

            Files.writeString(file,"class A { int disk; }");left.close(file);left.close(unsaved);
            var closed=first.capture(config(List.of()),left);
            assertThat(closed.source(file).value()).isEqualTo(Hashing.sha256(file));
            assertThat(left.liveState(List.of(root)).paths()).doesNotContain(unsaved.toAbsolutePath().normalize());
            assertThat(second.capture(config(List.of()),right).sameInputs(r)).isTrue();
        }
    }

    @Test void sourceReversionRestoresStableIdentityButAdvancesTransactionEpoch()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A {}");
        var files=new FileStateRegistry();try(var docs=new Documents(files)){
            var inputs=new CompilerInputs(files);var configuration=config(List.of());
            var before=inputs.capture(configuration,docs);
            docs.open(file,"class A {}",1);var opened=inputs.capture(configuration,docs);
            docs.change(file,2,List.of(new Documents.Change(null,"class A { int temporary; }")));
            docs.change(file,3,List.of(new Documents.Change(null,"class A {}")));
            var reverted=inputs.capture(configuration,docs);
            assertThat(reverted.sameInputs(opened)).isTrue();
            assertThat(reverted.observation()).isGreaterThan(opened.observation());
            assertThat(opened.transactionCurrent()).isFalse();
            assertThat(reverted.transactionCurrent()).isTrue();
            docs.close(file);
            assertThat(inputs.capture(configuration,docs).sameInputs(before)).isTrue();
        }
    }

    @Test void environmentRetainsOrderAndDetectsContentsOptionsAndPlatform()throws Exception {
        Path a=Files.write(root.resolve("a.jar"),new byte[]{1}),b=Files.write(root.resolve("b.jar"),new byte[]{2});
        var inputs=new CompilerInputs(new FileStateRegistry());try(var docs=new Documents()){
            var initial=inputs.capture(config(List.of(a,b)),docs);
            assertThat(inputs.capture(config(List.of(b,a)),docs).environment()).isNotEqualTo(initial.environment());
            Files.write(a,new byte[]{3});assertThat(inputs.capture(config(List.of(a,b)),docs).environment()).isNotEqualTo(initial.environment());
            var options=new CompilerInputs.Configuration("module",List.of(root),List.of(a,b),List.of("--release","17"),"other-jdk");
            assertThat(inputs.capture(options,docs).environment()).isNotEqualTo(initial.environment());
        }
        var base=CompilerInputs.environment("module",List.of(root),List.of(),List.of("processor"),Map.of("generated","1"),List.of(a.toString()),"jdk",Map.of());
        assertThat(CompilerInputs.environment("module",List.of(root),List.of("-g"),List.of("processor"),Map.of("generated","1"),List.of(a.toString()),"jdk",Map.of())).isNotEqualTo(base);
        assertThat(CompilerInputs.environment("module",List.of(root),List.of(),List.of("processor"),Map.of("generated","2"),List.of(a.toString()),"jdk",Map.of())).isNotEqualTo(base);
        assertThat(CompilerInputs.environment("module",List.of(root),List.of(),List.of("processor"),Map.of("generated","1"),List.of(a.toString()),"other-jdk",Map.of())).isNotEqualTo(base);
    }

    @Test void providerWithoutUnixMetadataRehashesAndReconciles()throws Exception {
        Path zip=root.resolve("provider.zip");
        try(var fs=FileSystems.newFileSystem(java.net.URI.create("jar:"+zip.toUri()),Map.of("create","true"))){
            Path dir=Files.createDirectory(fs.getPath("/src")),a=Files.writeString(dir.resolve("A.java"),"class A {}");
            var files=new FileStateRegistry();String initial=files.hash(a);files.inventory(dir,".java");var time=Files.getLastModifiedTime(a);
            Files.writeString(a,"class B {}");Files.setLastModifiedTime(a,time);
            assertThat(files.hash(a)).isNotEqualTo(initial);Files.writeString(dir.resolve("B.java"),"class B {}");
            assertThat(files.inventory(dir,".java")).hasSize(2);
        }
    }

    @Test void compilerRejectsSourceEnvironmentAndOverlayChangesDuringItsCallback()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A {}"),classes=Files.createDirectory(root.resolve("classes"));
        try(var documents=new Documents();var pool=new CompilerPool()){
            pool.configure("test","25",List.of(classes),List.of(root),null,128L*1024*1024);pool.documents(documents);
            var disk=pool.query(file,"class A {}",2,(task,units,tier)->{Files.writeString(file,"class A { int x; }");return "uncommitted";});
            assertThat(disk.result()).isNull();assertThat(disk.warnings()).anyMatch(w->w.startsWith("diagnostics_superseded:"));
            var environment=pool.query(file,Files.readString(file),2,(task,units,tier)->{Files.write(classes.resolve("New.class"),new byte[]{1});return "uncommitted";});
            assertThat(environment.result()).isNull();
            var overlay=pool.query(file,Files.readString(file),2,(task,units,tier)->{documents.open(file,"class A { int overlay; }",1);return "uncommitted";});
            assertThat(overlay.result()).isNull();
        }
    }

    @Test void delayedPublicationCannotKeepTheLiveInputIdentityUnchanged()throws Exception {
        Path file=Files.writeString(root.resolve("A.java"),"class A {}");
        try(var documents=new Documents();var publisher=new dev.jvmd.index.SourceIndexPublisher(delta->{},4096)){
            documents.open(file,"class A {}",1);var inputs=new CompilerInputs(new FileStateRegistry());var before=inputs.capture(config(List.of()),documents);
            var contribution=new dev.jvmd.index.FileSemanticContribution(file,before.source(file).value(),"api",Set.of(),Set.of(),Set.of());
            publisher.enqueue(new dev.jvmd.index.SourceIndexPublisher.Delta(contribution,"semantic",List.of(),2,List.of(),256,"module","context"));
            documents.change(file,2,List.of(new Documents.Change(null,"class A { int x; }")));
            assertThat(inputs.capture(config(List.of()),documents).sameInputs(before)).isFalse();
        }
    }
}
