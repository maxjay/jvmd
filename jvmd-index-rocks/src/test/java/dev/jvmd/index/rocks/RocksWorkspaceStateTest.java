package dev.jvmd.index.rocks;

import dev.jvmd.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksWorkspaceStateTest {
    @TempDir Path temp;

    @Test void persistsCanonicalStateWithoutDiscoveringOrHashingTheFilesystem()throws Exception{
        Path src=temp.resolve("not-required-to-exist"),a=src.resolve("A.java"),b=src.resolve("p/B.java");
        var tree=new LiveStateTree(List.of(src));tree.put(leaf(a,"a1","api-a","A"));tree.put(leaf(b,"b1","api-b","p.B"));
        Path storage=temp.resolve("state");var input=input(src,List.of("-g"),List.of("cp-a","cp-b"),"jdk-25");
        String firstFingerprint;
        try(var state=new RocksWorkspaceState(storage)){
            var first=state.update(input,tree.state(),tree.paths());firstFingerprint=first.fingerprint();
            assertThat(first.changedFiles()).containsExactlyInAnyOrder(a.toAbsolutePath().normalize(),b.toAbsolutePath().normalize());
            assertThat(first.fileWrites()).isEqualTo(2);
            var unchanged=state.update(input,tree.state(),tree.paths());
            assertThat(unchanged.fingerprint()).isEqualTo(firstFingerprint);
            assertThat(unchanged.fileWrites()+unchanged.directoryWrites()+unchanged.metadataWrites()).isZero();

            tree.put(leaf(a,"a2","api-a","A"));
            var body=state.update(input,tree.state(),tree.paths());
            assertThat(body.changedFiles()).isEmpty();assertThat(body.fileWrites()).isZero();
            assertThat(body.metadataWrites()).isEqualTo(1);assertThat(body.fingerprint()).isNotEqualTo(firstFingerprint);
        }
        try(var reopened=new RocksWorkspaceState(storage)){
            var unchanged=reopened.update(input,tree.state(),tree.paths());
            assertThat(unchanged.fileWrites()+unchanged.directoryWrites()+unchanged.metadataWrites()).isZero();
        }
    }

    @Test void membershipDiffComesFromCanonicalStateRatherThanASecondRocksTree()throws Exception{
        Path src=temp.resolve("src"),a=src.resolve("A.java"),b=src.resolve("B.java"),renamed=src.resolve("Renamed.java");
        var tree=new LiveStateTree(List.of(src));tree.put(leaf(a,"a","api-a","A"));tree.put(leaf(b,"b","api-b","B"));
        try(var state=new RocksWorkspaceState(temp.resolve("membership"))){
            var input=input(src,List.of("-g"),List.of(),"jdk-25");state.update(input,tree.state(),tree.paths());
            tree.remove(b);tree.put(leaf(renamed,"b","api-b","B"));
            var changed=state.update(input,tree.state(),tree.paths());
            assertThat(changed.deletedFiles()).containsExactly(b.toAbsolutePath().normalize());
            assertThat(changed.changedFiles()).containsExactly(renamed.toAbsolutePath().normalize());
            assertThat(changed.fileWrites()).isEqualTo(2);
        }
    }

    @Test void moduleEnvironmentStillParticipatesWithoutReconstructingSourceState()throws Exception{
        Path src=temp.resolve("src");var tree=new LiveStateTree(List.of(src));tree.put(leaf(src.resolve("A.java"),"a","api","A"));
        try(var state=new RocksWorkspaceState(temp.resolve("environment"))){
            var base=input(src,List.of("-g"),List.of("cp-a","cp-b"),"jdk-25");
            String initial=state.update(base,tree.state(),tree.paths()).fingerprint();
            var options=state.update(input(src,List.of("-g","-parameters"),List.of("cp-a","cp-b"),"jdk-25"),tree.state(),tree.paths());
            assertThat(options.changedFiles()).isEmpty();assertThat(options.fingerprint()).isNotEqualTo(initial);
            var classpath=state.update(input(src,List.of("-g","-parameters"),List.of("cp-b","cp-a"),"jdk-25"),tree.state(),tree.paths());
            assertThat(classpath.fingerprint()).isNotEqualTo(options.fingerprint());
            var jdk=state.update(input(src,List.of("-g","-parameters"),List.of("cp-b","cp-a"),"jdk-26"),tree.state(),tree.paths());
            assertThat(jdk.fingerprint()).isNotEqualTo(classpath.fingerprint());
        }
    }

    private static LiveStateTree.Leaf leaf(Path path,String content,String api,String... names){
        return LiveStateTree.source(path,Hashing.sha256(content.getBytes(StandardCharsets.UTF_8)),api,List.of(names));
    }
    private static RocksWorkspaceState.ModuleInput input(Path source,List<String> options,List<String> classpath,String jdk){
        return new RocksWorkspaceState.ModuleInput("fixture:module",List.of(source),options,List.of("processor-a"),
                Map.of("generated","gen-v1"),classpath,jdk);
    }
}
