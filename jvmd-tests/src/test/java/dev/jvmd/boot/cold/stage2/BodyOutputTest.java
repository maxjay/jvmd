package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Output;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class BodyOutputTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }

    private static ResultRecord result(Digest digest,InMemoryLocalStore store,boolean clean,Map<String,byte[]> classes) {
        var refs=new ArrayList<ResultRecord.ClassFile>();
        for(var entry:classes.entrySet()) {
            var id=digest.hash(entry.getValue());store.put(LocalStore.classFileKey(id),entry.getValue());
            refs.add(new ResultRecord.ClassFile(entry.getKey(),id));
        }
        store.flush();return new ResultRecord(clean,refs,List.of());
    }

    private static Root root(ContentTree tree,InMemoryLocalStore store,ResultRecord... results) {
        var root=Output.build(tree,store,List.of(results));store.flush();return root;
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @ParameterizedTest @MethodSource("digests")
    void materialisationTouchesExactlyTheDeltaAndKeepsUntrackedFiles(Digest digest) throws Exception {
        var tree=new ContentTree(digest);var store=new InMemoryLocalStore();var project=digest.hash(bytes("project"));
        var original=root(tree,store,result(digest,store,true,Map.of("p/A",bytes("old"),"p/B",bytes("removed"),"p/C",bytes("same"))));
        var output=dir.resolve("output");
        assertThat(Output.materialise(tree,store,project,"app",0,original,output)).isEqualTo(new Output.Changes(3,0));
        var untouched=output.resolve("p/C.class");var oldTime=FileTime.fromMillis(1_000);Files.setLastModifiedTime(untouched,oldTime);
        var user=output.resolve("User.class");Files.writeString(user,"untracked");
        var current=root(tree,store,result(digest,store,true,Map.of("p/A",bytes("new"),"p/C",bytes("same"),"p/D",bytes("added"))));
        var unchangedReads=store.watchReads(LocalStore.classFileKey(digest.hash(bytes("same"))));
        assertThat(Output.materialise(tree,store,project,"app",0,current,output)).isEqualTo(new Output.Changes(2,1));
        assertThat(Files.readString(output.resolve("p/A.class"))).isEqualTo("new");
        assertThat(output.resolve("p/B.class")).doesNotExist();assertThat(Files.readString(output.resolve("p/D.class"))).isEqualTo("added");
        assertThat(Files.getLastModifiedTime(untouched)).isEqualTo(oldTime);assertThat(Files.readString(user)).isEqualTo("untracked");
        assertThat(unchangedReads.get()).isZero();
        var nodes=store.watchReads(MachineStore.nodeKey(current.hash()));
        assertThat(Output.materialise(tree,store,project,"app",0,current,output.resolve("."))).isEqualTo(new Output.Changes(0,0));
        assertThat(nodes.get()).isZero();
        var directoryId=digest.hash(bytes(output.toRealPath().toString()));
        assertThat(DefinerIndex.decodeRoot(store.get(LocalStore.materialisedKey(project,"app",0,directoryId)),digest.width())).isEqualTo(current);
        assertThat(Output.materialise(tree,store,project,"app",0,current,dir.resolve("another"))).isEqualTo(new Output.Changes(3,0));
    }

    @ParameterizedTest @MethodSource("digests")
    void failedAttributionRemovesItsOldClassesButLeavesTheDirectoryAlone(Digest digest) throws Exception {
        var tree=new ContentTree(digest);var store=new InMemoryLocalStore();var project=digest.hash(bytes("project"));
        var before=root(tree,store,result(digest,store,true,Map.of("p/A",bytes("a"),"p/A$Inner",bytes("inner"))));
        var output=dir.resolve("out");Output.materialise(tree,store,project,"app",0,before,output);
        var unrelated=output.resolve("keep.txt");Files.writeString(unrelated,"keep");
        var after=root(tree,store,result(digest,store,false,Map.of("p/A",bytes("partial"))));
        assertThat(after.count()).isZero();
        assertThat(Output.materialise(tree,store,project,"app",0,after,output)).isEqualTo(new Output.Changes(0,2));
        assertThat(output.resolve("p/A.class")).doesNotExist();assertThat(output.resolve("p/A$Inner.class")).doesNotExist();
        assertThat(Files.readString(unrelated)).isEqualTo("keep");
    }

    @ParameterizedTest @MethodSource("digests")
    void invalidContentDoesNotDeleteOldOutputsOrPublishMaterialisation(Digest digest) throws Exception {
        var tree=new ContentTree(digest);var store=new InMemoryLocalStore();var project=digest.hash(bytes("project"));
        var before=root(tree,store,result(digest,store,true,Map.of("Old",bytes("old"))));
        var output=dir.resolve("out");Output.materialise(tree,store,project,"app",0,before,output);
        var marker=LocalStore.materialisedKey(project,"app",0,digest.hash(bytes(output.toRealPath().toString())));
        var markerBefore=store.get(marker);
        var after=root(tree,store,result(digest,store,true,Map.of("New",bytes("new"))));
        store.put(LocalStore.classFileKey(digest.hash(bytes("new"))),bytes("corrupt"));store.flush();
        assertThatThrownBy(() -> Output.materialise(tree,store,project,"app",0,after,output)).isInstanceOf(java.io.IOException.class).hasMessageContaining("corrupt");
        assertThat(Files.readString(output.resolve("Old.class"))).isEqualTo("old");assertThat(output.resolve("New.class")).doesNotExist();
        assertThat(store.get(marker)).isEqualTo(markerBefore);
        store.put(LocalStore.classFileKey(digest.hash(bytes("new"))),bytes("new"));store.flush();
        assertThat(Output.materialise(tree,store,project,"app",0,after,output)).isEqualTo(new Output.Changes(1,1));
    }

    @ParameterizedTest @MethodSource("digests")
    void outputNamesAreUniqueAndCannotEscapeTheDestination(Digest digest) {
        var tree=new ContentTree(digest);var store=new InMemoryLocalStore();
        for(String bad:List.of("../escape","/absolute","x/../../escape","x\\escape","C:/escape","x//Y"))
            assertThatThrownBy(() -> root(tree,store,result(digest,store,true,Map.of(bad,bytes("x")))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid internal class name");
        var unit=result(digest,store,true,Map.of("p/A",bytes("x")));
        assertThatThrownBy(() -> root(tree,store,unit,unit)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Two clean units");
    }

    @ParameterizedTest @MethodSource("digests")
    void poolClassesMaterialiseAsRunnableJavaClasspath(Digest digest) throws Exception {
        var tree=new ContentTree(digest);var store=new InMemoryLocalStore();var project=digest.hash(bytes("project"));
        var own=Files.createDirectories(dir.resolve("stubs"));
        var source=new javax.tools.SimpleJavaFileObject(java.net.URI.create("memory:///p/Main.java"),javax.tools.JavaFileObject.Kind.SOURCE) {
            public CharSequence getCharContent(boolean ignore) { return "package p; public class Main { public static void main(String[] args){ System.out.print(\"stage3-ok\"); } }"; }
        };
        var config=new Pool.Configuration(new Pool.Key(project,project),own,List.of(),StandardCharsets.UTF_8,
                List.of("-proc:none","-implicit:none","-encoding","UTF-8"),List.of());
        Map<String,byte[]> classes;
        try(var pool=new Pool(config,1)) {
            classes=pool.withTask(source,d -> { assertThat(d.getKind()).isNotEqualTo(javax.tools.Diagnostic.Kind.ERROR); },task -> {
                try { task.parse();task.analyze();task.generate();return null; }
                catch(java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }).classes();
        }
        var outputRoot=root(tree,store,result(digest,store,true,classes));
        var directory=dir.resolve("run");Output.materialise(tree,store,project,"app",0,outputRoot,directory);
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-cp",directory.toString(),"p.Main").redirectErrorStream(true).start();
        assertThat(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).isEqualTo("stage3-ok");
        assertThat(process.exitValue()).isZero();
    }
}
