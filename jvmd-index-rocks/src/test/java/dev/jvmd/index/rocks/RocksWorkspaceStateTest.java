package dev.jvmd.index.rocks;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksWorkspaceStateTest {
    @TempDir Path temp;

    @Test void unchangedUpdateWritesNothingAndOneFileEditIsNarrow()throws Exception{
        Path src=Files.createDirectories(temp.resolve("src"));
        Path a=Files.writeString(src.resolve("A.java"),"class A { int value(){ return 1; } }");
        Path b=Files.writeString(src.resolve("B.java"),"class B {}");
        Path root=temp.resolve("state");
        var input=input(src,Map.of(),List.of("-g"),List.of("cp-a","cp-b"),"jdk-25");
        String firstFingerprint;
        try(var state=new RocksWorkspaceState(root)){
            var first=state.update(input);firstFingerprint=first.fingerprint();
            assertThat(first.changedFiles()).containsExactlyInAnyOrder(a.toAbsolutePath().normalize(),b.toAbsolutePath().normalize());
            assertThat(first.fileWrites()).isEqualTo(2);

            var unchanged=state.update(input);
            assertThat(unchanged.fingerprint()).isEqualTo(firstFingerprint);
            assertThat(unchanged.changedFiles()).isEmpty();assertThat(unchanged.deletedFiles()).isEmpty();
            assertThat(unchanged.fileWrites()+unchanged.directoryWrites()+unchanged.metadataWrites()).isZero();

            Files.writeString(a,"class A { int value(){ return 2; } }");
            var edited=state.update(input);
            assertThat(edited.changedFiles()).containsExactly(a.toAbsolutePath().normalize());
            assertThat(edited.unchangedFiles()).isEqualTo(1);
            assertThat(edited.fingerprint()).isNotEqualTo(firstFingerprint);

            Files.setLastModifiedTime(src,FileTime.fromMillis(System.currentTimeMillis()+10_000));
            var directoryOnly=state.update(input);
            assertThat(directoryOnly.fileWrites()+directoryOnly.directoryWrites()+directoryOnly.metadataWrites()).isZero();
        }

        try(var reopened=new RocksWorkspaceState(root)){
            var unchanged=reopened.update(input);
            assertThat(unchanged.fileWrites()+unchanged.directoryWrites()+unchanged.metadataWrites()).isZero();
        }
    }

    @Test void overlayCreateDeleteRenameAndCompilerInputsChangeMerkleIdentity()throws Exception{
        Path src=Files.createDirectories(temp.resolve("module"));
        Path a=Files.writeString(src.resolve("A.java"),"class A {}"),b=Files.writeString(src.resolve("B.java"),"class B {}");
        try(var state=new RocksWorkspaceState(temp.resolve("state2"))){
            var base=input(src,Map.of(),List.of("-g"),List.of("cp-a","cp-b"),"jdk-25");
            String initial=state.update(base).fingerprint();

            Path unsaved=src.resolve("Unsaved.java");
            var overlay=input(src,Map.of(unsaved,"class Unsaved { int x; }"),List.of("-g"),List.of("cp-a","cp-b"),"jdk-25");
            var overlaid=state.update(overlay);
            assertThat(overlaid.changedFiles()).contains(unsaved.toAbsolutePath().normalize());
            assertThat(overlaid.fingerprint()).isNotEqualTo(initial);

            Path renamed=src.resolve("Renamed.java");Files.move(b,renamed);
            var renamedState=state.update(input(src,Map.of(),List.of("-g"),List.of("cp-a","cp-b"),"jdk-25"));
            assertThat(renamedState.deletedFiles()).contains(b.toAbsolutePath().normalize(),unsaved.toAbsolutePath().normalize());
            assertThat(renamedState.changedFiles()).contains(renamed.toAbsolutePath().normalize());

            String filesStable=renamedState.fingerprint();
            var option=state.update(input(src,Map.of(),List.of("-g","-parameters"),List.of("cp-a","cp-b"),"jdk-25"));
            assertThat(option.changedFiles()).isEmpty();assertThat(option.fingerprint()).isNotEqualTo(filesStable);

            var classpath=state.update(input(src,Map.of(),List.of("-g","-parameters"),List.of("cp-b","cp-a"),"jdk-25"));
            assertThat(classpath.fingerprint()).isNotEqualTo(option.fingerprint());
            var jdk=state.update(input(src,Map.of(),List.of("-g","-parameters"),List.of("cp-b","cp-a"),"jdk-26"));
            assertThat(jdk.fingerprint()).isNotEqualTo(classpath.fingerprint());
        }
    }

    @Test void knownFileChangeUpdatesOnlyAncestorsAndMatchesAFullReconciliation()throws Exception{
        Path src=Files.createDirectories(temp.resolve("known-src"));
        Path folder=Files.createDirectories(src.resolve("a/b"));
        Path file=Files.writeString(folder.resolve("A.java"),"class A {}");
        Files.writeString(Files.createDirectories(src.resolve("unrelated")).resolve("B.java"),"class B {}");
        var config=input(src,Map.of(),List.of("-g"),List.of(),"jdk-25");
        try(var state=new RocksWorkspaceState(temp.resolve("known-state"))){
            state.update(config);Files.writeString(file,"class A { int value; }");
            var changed=state.observeFile(config,file,dev.jvmd.core.Hashing.sha256(file));
            assertThat(changed.fileWrites()).isEqualTo(1);assertThat(changed.directoryWrites()).isEqualTo(6);
            var reconciled=state.update(config);
            assertThat(reconciled.fingerprint()).isEqualTo(changed.fingerprint());
            assertThat(reconciled.fileWrites()+reconciled.directoryWrites()+reconciled.metadataWrites()).isZero();
            Path created=folder.resolve("Unsaved.java");String text="class Unsaved {}";
            var known=state.observeFile(config,created,dev.jvmd.core.Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var full=state.update(input(src,Map.of(created,text),List.of("-g"),List.of(),"jdk-25"));
            assertThat(full.fingerprint()).isEqualTo(known.fingerprint());
            assertThat(full.fileWrites()+full.directoryWrites()+full.metadataWrites()).isZero();
        }
    }

    private static RocksWorkspaceState.ModuleInput input(Path source,Map<Path,String> overlays,List<String> options,List<String> classpath,String jdk){
        return new RocksWorkspaceState.ModuleInput("fixture:module",List.of(source),overlays,options,List.of("processor-a"),
                Map.of("generated","gen-v1"),classpath,jdk);
    }
}
