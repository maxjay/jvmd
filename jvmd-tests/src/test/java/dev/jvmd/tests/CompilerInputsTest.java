package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.*;
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
        assertThat(files.inventory(cp,".class")).isEmpty(); // source discovery stays no-follow
        Path external=Files.createDirectories(root.resolve("external"));
        Files.createSymbolicLink(target.resolve("pkg"),external);
        var empty=inputs.environment(configuration);
        Path member=Files.write(external.resolve("B.class"),new byte[]{3});
        var added=inputs.environment(configuration);assertThat(added).isNotEqualTo(empty);
        Files.write(member,new byte[]{4});assertThat(inputs.environment(configuration)).isNotEqualTo(added);
        Files.delete(member);Files.delete(external);
        inputs.environment(configuration); // dangling package link
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
    @Test void inventoryEvictionBoundsClosedWorkspaceRetentionAndReconcilesOnReuse()throws Exception {
        var files=new FileStateRegistry();var inputs=new CompilerInputs(files);var documents=new Documents();
        Path first=Files.createDirectories(root.resolve("first"));Path source=Files.writeString(first.resolve("A.java"),"class A {}");
        var configuration=new CompilerInputs.Configuration("module",List.of(first),List.of(),List.of());
        var accepted=inputs.capture(configuration,documents);
        for(int i=0;i<160;i++)files.inventory(Files.createDirectories(root.resolve("workspace"+i)),".java");
        assertThat(count(files.status(),"inventory_entries")).isLessThanOrEqualTo(128);
        assertThat(count(files.status(),"inventory_evictions")).isPositive();
        long enumerations=count(files.status(),"directory_enumerations");
        assertThat(inputs.capture(configuration,documents).sameInputs(accepted)).isTrue();
        assertThat(count(files.status(),"directory_enumerations")).isGreaterThan(enumerations);
        Files.writeString(source,"class A { int n; }");
        assertThat(inputs.capture(configuration,documents).changedSince(accepted)).containsExactly(source);
    }
    @Test void warmObservationsReuseMapsWithoutEnumeratingOrHashingAndBodyIsNotEnvironment()throws Exception {
        Path a=Files.writeString(root.resolve("A.java"),"class A { int n=1; }");
        var files=new FileStateRegistry();var inputs=new CompilerInputs(files);var docs=new Documents();
        var initial=inputs.capture(config(List.of()),docs);var work=files.status();var tracked=inputs.status();
        for(int i=0;i<20;i++)assertThat(inputs.capture(config(List.of()),docs)).isSameAs(initial);
        assertThat(files.status()).containsEntry("hashes",work.get("hashes")).containsEntry("directory_enumerations",work.get("directory_enumerations"));
        assertThat(inputs.status()).containsEntry("snapshot_rebuilds",tracked.get("snapshot_rebuilds"));
        assertThat(count(files.status(),"metadata_checks")).isGreaterThan(count(work,"metadata_checks"));
        Files.writeString(a,"class A { int n=2; }");var changed=inputs.capture(config(List.of()),docs);
        assertThat(changed.environment()).isEqualTo(initial.environment());assertThat(changed.membership()).isEqualTo(initial.membership());
        assertThat(changed.changedSince(initial)).containsExactly(a);
    }
    @Test void overlaysAreSessionLocalAndCloseReturnsToCurrentDiskIncludingUnsavedNewFiles()throws Exception {
        Path a=Files.writeString(root.resolve("A.java"),"class A {}");Path b=root.resolve("New.java");
        var files=new FileStateRegistry();var first=new CompilerInputs(files);var second=new CompilerInputs(files);
        var left=new Documents();var right=new Documents();left.open(a,"class A { int x; }",1);right.open(a,"class A { int y; }",1);left.open(b,"class New {}",1);
        var l=first.capture(config(List.of()),left);var r=second.capture(config(List.of()),right);
        assertThat(l.sources().get(a)).isNotEqualTo(r.sources().get(a));assertThat(l.sources()).containsKey(b);assertThat(r.sources()).doesNotContainKey(b);
        Files.writeString(a,left.text(a));assertThat(first.capture(config(List.of()),left).sameInputs(l)).isTrue();
        Files.writeString(a,"class A { int disk; }");left.close(a);left.close(b);
        var closed=first.capture(config(List.of()),left);assertThat(closed.sources().get(a)).isEqualTo(Hashing.sha256(a));assertThat(closed.sources()).doesNotContainKey(b);
        assertThat(second.capture(config(List.of()),right).sameInputs(r)).isTrue();
    }
    @Test void creationDeletionRenamePreservedTimesAtomicReplacementAndReconciliation()throws Exception {
        Path a=Files.writeString(root.resolve("A.java"),"class A { int x; }");var files=new FileStateRegistry();var inputs=new CompilerInputs(files);var docs=new Documents();
        var old=inputs.capture(config(List.of()),docs);var time=Files.getLastModifiedTime(a);
        Path tmp=Files.writeString(root.resolve("replacement"),"class A { int y; }");Files.setLastModifiedTime(tmp,time);Files.move(tmp,a,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        var replaced=inputs.capture(config(List.of()),docs);assertThat(replaced.source(a)).isNotEqualTo(old.source(a));
        Path b=root.resolve("B.java");Files.move(a,b);var renamed=inputs.capture(config(List.of()),docs);
        assertThat(renamed.sources()).containsKey(b).doesNotContainKey(a);assertThat(renamed.membership()).isNotEqualTo(replaced.membership());
        files.reconcile();assertThat(inputs.capture(config(List.of()),docs).sameInputs(renamed)).isTrue();
        var restart=new CompilerInputs(new FileStateRegistry());assertThat(restart.capture(config(List.of()),docs).sameInputs(renamed)).isTrue();
        Files.delete(b);assertThat(inputs.capture(config(List.of()),docs).sources()).isEmpty();
    }
    @Test void environmentRetainsOrderAndDetectsContentsOptionsAndPlatform()throws Exception {
        Path a=Files.write(root.resolve("a.jar"),new byte[]{1}),b=Files.write(root.resolve("b.jar"),new byte[]{2});
        var inputs=new CompilerInputs(new FileStateRegistry());var docs=new Documents();
        var initial=inputs.capture(config(List.of(a,b)),docs);
        assertThat(inputs.capture(config(List.of(b,a)),docs).environment()).isNotEqualTo(initial.environment());
        Files.write(a,new byte[]{3});assertThat(inputs.capture(config(List.of(a,b)),docs).environment()).isNotEqualTo(initial.environment());
        var options=new CompilerInputs.Configuration("module",List.of(root),List.of(a,b),List.of("--release","17"),"other-jdk");
        assertThat(inputs.capture(options,docs).environment()).isNotEqualTo(initial.environment());
        var base=CompilerInputs.environment("module",List.of(root),List.of(),List.of("processor"),Map.of("generated","1"),List.of(a.toString()),"jdk",Map.of());
        var changed=CompilerInputs.environment("module",List.of(root),List.of(),List.of("processor","-Aoption=true"),Map.of("generated","2"),List.of(a.toString()),"jdk",Map.of());
        assertThat(changed).isNotEqualTo(base);
        assertThat(CompilerInputs.environment("module",List.of(root),List.of(),List.of("other-processor"),Map.of("generated","1"),List.of(a.toString()),"jdk",Map.of())).isNotEqualTo(base);
        assertThat(CompilerInputs.environment("module",List.of(root),List.of(),List.of("processor"),Map.of("generated","2"),List.of(a.toString()),"jdk",Map.of())).isNotEqualTo(base);
        assertThat(CompilerInputs.environment("module",List.of(root),List.of("-g"),List.of("processor"),Map.of("generated","1"),List.of(a.toString()),"jdk",Map.of())).isNotEqualTo(base);
        assertThat(CompilerInputs.environment("module",List.of(root),List.of(),List.of("processor"),Map.of("generated","1"),List.of(a.toString()),"other-jdk",Map.of())).isNotEqualTo(base);
    }
    @Test void mutationDuringNavigationDoesNotCommitAndFailedRetryKeepsOwnerInventory()throws Exception {
        Path a=Files.writeString(root.resolve("A.java"),"class A {}");var docs=new Documents();var files=new ArrayList<>(List.of(a));
        try(var cache=new WorkspaceBindings()){
            WorkspaceBindings.Loader empty=(file,text)->new CompilerPool.Outcome<>(2,new Bindings.Snapshot(Map.of(),List.of(),List.of(),Set.of()),List.of(),List.of());
            try(var accepted=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),docs,0,empty)){
                Object revision=accepted.revision();Files.writeString(a,"class A { int x; }");
                assertThatThrownBy(()->cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),docs,0,(file,text)->{Files.writeString(a,"class A { int y; }");return empty.load(file,text);})).isInstanceOf(CompilerInputs.Superseded.class);
                assertThat(cache.status()).containsEntry("fragment_files",1);
                assertThatThrownBy(()->cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),docs,0,(file,text)->{throw new java.io.IOException("failed refresh");})).isInstanceOf(java.io.IOException.class);
                try(var retry=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),docs,0,empty)){assertThat(retry.revision()).isNotSameAs(revision);assertThat(retry.warnings()).isEmpty();}
            }
        }
    }
    @Test void readRejectsTextFromAnotherVersionAndReversionStillSupersedesInFlightWork()throws Exception {
        Path a=Files.writeString(root.resolve("A.java"),"class A {}");var inputs=new CompilerInputs(new FileStateRegistry());var docs=new Documents();
        docs.open(a,"class A {}",1);var before=inputs.capture(config(List.of()),docs);
        docs.change(a,2,List.of(new Documents.Change(null,"class B {}")));
        assertThatThrownBy(()->before.text(a,docs)).isInstanceOf(CompilerInputs.Superseded.class);
        docs.change(a,3,List.of(new Documents.Change(null,"class A {}")));var after=inputs.capture(config(List.of()),docs);
        assertThat(after.sameInputs(before)).isTrue();assertThat(after).isNotEqualTo(before);
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
        Path file=Files.writeString(root.resolve("A.java"),"class A {}");Path classes=Files.createDirectory(root.resolve("classes"));
        var documents=new Documents();
        try(var pool=new CompilerPool()){
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
        Path file=Files.writeString(root.resolve("A.java"),"class A {}");var documents=new Documents();documents.open(file,"class A {}",1);
        var inputs=new CompilerInputs(new FileStateRegistry());var before=inputs.capture(config(List.of()),documents);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var publisher=new dev.jvmd.index.SourceIndexPublisher(delta->{entered.countDown();release.await();},4096)){
            try{
                var contribution=new dev.jvmd.index.FileSemanticContribution(file,before.source(file).value(),"api",Set.of(),Set.of(),Set.of());
                publisher.enqueue(new dev.jvmd.index.SourceIndexPublisher.Delta(contribution,"semantic",List.of(),2,List.of(),256,"module","context"));
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                documents.change(file,2,List.of(new Documents.Change(null,"class A { int x; }")));
                assertThat(inputs.capture(config(List.of()),documents).sameInputs(before)).isFalse();
            }finally{release.countDown();}
        }
    }

}
