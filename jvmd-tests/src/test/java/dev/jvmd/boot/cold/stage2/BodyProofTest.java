package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Arrange;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Body proof descent against real Stage 2 bindings; collection from attributed bodies is tested separately. */
@Tag("phase-3")
class BodyProofTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }
    static final String BASE = "package q; public class Base { public static final int C = 1; public Number get() { return null; } }";
    static final Proof.Range HEADER = new Proof.Range(Proof.T, "q/Base", Keys.TYPE, "");
    static final Proof.Range CONSTANT = new Proof.Range(Proof.T, "q/Base", Keys.FIELD, "C");
    static final Proof.Range METHOD = new Proof.Range(Proof.T, "q/Base", Keys.METHOD, "get");
    static final Proof.Range MEMBER_TYPE = new Proof.Range(Proof.N, "q/Base", Keys.TYPE, "Foo");

    record State(ContentTree tree, InMemoryLocalStore store, MachineLeaf own, Route route) {
        Proof proof(List<Proof.Range> ranges, List<String> absent) { return Arrange.proof(tree, own, route, null, ranges, absent, store::get); }
        boolean valid(Proof proof) { return proof.valid(tree, own, route, null, store::get); }
    }

    private State boot(Digest digest, ProjectModel model) throws java.io.IOException {
        var tree = new ContentTree(digest);
        var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, tree, Stage2Support.FEATURE, 2, dir, ClassFacts::of).run(store, model);
        assertThat(result.faults()).isEmpty();
        var own = MachineLeaf.decode(store.get(MachineStore.leafKey(result.leaves().get("app/main"))), digest.width());
        var route = Route.decode(store.get(LocalStore.routeKey(Stage2.projectKey(digest, model), "app", 0)), digest.width());
        return new State(tree, store, own, route);
    }

    private ProjectModel fixture() throws Exception {
        Stage2Support.write(dir, Map.of("dep/src/main/java/q/Base.java", BASE,
                "dep/src/main/java/q/Other.java", "package q; public class Other {}",
                "app/src/main/java/p/App.java", "package p; public class App extends q.Base { int body() { return C; } }"));
        return ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("dep", "g:dep:1", List.of()),
                new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.module("g:dep:1", "dep")))));
    }

    @ParameterizedTest @MethodSource("digests")
    void rangesIgnoreUnreadMembersButObserveConstantsAndOverloadAdditions(Digest digest) throws Exception {
        var model = fixture();
        var before = boot(digest, model);
        var proof = before.proof(List.of(HEADER, CONSTANT, METHOD), List.of("p/Missing"));
        assertThat(proof.valid(before.tree, before.own, before.route, null, key -> { throw new AssertionError("Equal header read storage"); })).isTrue();
        assertThat(Proof.decode(proof.encode(), digest.width())).isEqualTo(proof);
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("public Number", "public void unrelated() {} public Number"));
        var unrelated = boot(digest, model);
        assertThat(unrelated.valid(proof)).isTrue();
        assertThat(unrelated.proof(List.of(METHOD, HEADER, CONSTANT, METHOD), List.of("p/Missing"))
                .aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})))
                .isEqualTo(proof.aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})));
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("C = 1", "C = 2"));
        assertThat(boot(digest, model).valid(proof)).isFalse();
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("public Number", "public String get(String x) { return x; } public Number"));
        assertThat(boot(digest, model).valid(proof)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void memberTypeRangeMustBeCheckedWhenItsOwnerSumIsUnchanged(Digest digest) throws Exception {
        var model = fixture();
        var before = boot(digest, model);
        var proof = before.proof(List.of(HEADER, MEMBER_TYPE), List.of());
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("public Number", "public static class Foo {} public Number"));
        var after = boot(digest, model);
        var current = after.proof(List.of(HEADER, MEMBER_TYPE), List.of());
        assertThat(current.types().getFirst().oSum()).isEqualTo(proof.types().getFirst().oSum());
        assertThat(current.types().getFirst().entries()).isNotEqualTo(proof.types().getFirst().entries());
        var definer = new DefinerIndex.Reader(after.tree, after.own, after.route, after.store::get).definer("q/Base");
        var tReads = after.store.watchReads(MachineStore.nodeKey(definer.k()));
        var nReads = after.store.watchReads(MachineStore.nodeKey(definer.nHash()));
        assertThat(after.valid(proof)).isFalse();
        assertThat(tReads.get()).isZero();
        assertThat(nReads.get()).isPositive();
    }

    @ParameterizedTest @MethodSource("digests")
    void absencesCoverOwnAndRouteTypesAndOwnDefinitionsPrecedeTheRoute(Digest digest) throws Exception {
        var model = fixture();
        var before = boot(digest, model);
        var proof = before.proof(List.of(CONSTANT), List.of("p/Missing"));
        String missing = "package p; public class Missing {}";
        Stage2Support.write(dir, Map.of("app/src/main/java/p/Missing.java", missing));
        var ownAdded = boot(digest, model);
        assertThat(ownAdded.route.routeHash()).isEqualTo(before.route.routeHash());
        assertThat(ownAdded.valid(proof)).isFalse();
        Files.delete(dir.resolve("app/src/main/java/p/Missing.java"));
        Stage2Support.write(dir, Map.of("dep/src/main/java/p/Missing.java", missing));
        var routeAdded = boot(digest, model);
        assertThat(routeAdded.own.r()).isEqualTo(before.own.r());
        assertThat(routeAdded.valid(proof)).isFalse();
        Files.delete(dir.resolve("dep/src/main/java/p/Missing.java"));
        Stage2Support.write(dir, Map.of("app/src/main/java/q/Base.java", BASE.replace("C = 1", "C = 3")));
        assertThat(boot(digest, model).valid(proof)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void repackagingEqualApisUsesIndexSumsWithoutOpeningAnyTree(Digest digest) throws Exception {
        var classes = Stage2Support.compile(dir.resolve("binary"), Map.of("q/Base.java", BASE,
                "q/Other.java", "package q; public class Other {}"), List.of(), List.of());
        var combined = Stage2Support.pack(dir.resolve("combined.jar"), classes);
        var base = Stage2Support.pack(dir.resolve("base.jar"), Map.of("q/Base.class", classes.get("q/Base.class")));
        var other = Stage2Support.pack(dir.resolve("other.jar"), Map.of("q/Other.class", classes.get("q/Other.class")));
        Stage2Support.write(dir, Map.of("app/src/main/java/p/App.java", "package p; public class App extends q.Base {}"));
        var first = boot(digest, ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("app", "g:app:1",
                List.of(Stage2Support.Dep.jar("g:combined:1", combined.toString()))))));
        var second = boot(digest, ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("app", "g:app:1",
                List.of(Stage2Support.Dep.jar("g:base:1", base.toString()), Stage2Support.Dep.jar("g:other:1", other.toString()))))));
        var proof = first.proof(List.of(CONSTANT, METHOD), List.of("q/Missing"));
        assertThat(second.route.routeHash()).isNotEqualTo(first.route.routeHash());
        var reads = new java.util.ArrayList<byte[]>();
        assertThat(proof.valid(second.tree, second.own, second.route, null, key -> {
            reads.add(key);
            if (key[0] == 'N') throw new AssertionError("Equal index sums opened a tree");
            return second.store.get(key);
        })).isTrue();
        assertThat(reads).hasSize(3);
        var current = second.proof(List.of(CONSTANT, METHOD), List.of("q/Missing"));
        assertThat(current.aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})))
                .isEqualTo(proof.aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})));
    }

    @ParameterizedTest @MethodSource("digests")
    void reversingConflictingJarsRequiresANewPoolEvenWithTheSameLeafSets(Digest digest) throws Exception {
        String source="package p; public class App { public int value(){ return q.Base.C; } }";
        var firstJar=Stage2Support.jar(dir,"first.jar",Map.of("q/Base.java",BASE),List.of());
        var secondJar=Stage2Support.jar(dir,"second.jar",Map.of("q/Base.java",BASE.replace("C = 1","C = 2")),List.of());
        Stage2Support.write(dir,Map.of("app/src/main/java/p/App.java",source));
        var firstDep=Stage2Support.Dep.jar("g:first:1",firstJar.toString());
        var secondDep=Stage2Support.Dep.jar("g:second:1",secondJar.toString());
        var first=boot(digest,ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("app","g:app:1",List.of(firstDep,secondDep)))));
        var second=boot(digest,ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("app","g:app:1",List.of(secondDep,firstDep)))));
        assertThat(first.own().k()).isEqualTo(second.own().k());
        assertThat(first.route().leafSetExt()).isEqualTo(second.route().leafSetExt());
        assertThat(first.route().leafSetSib()).isEqualTo(second.route().leafSetSib());
        var firstKey=new Pool.Key(first.route().routeHash(),first.own().k());
        var secondKey=new Pool.Key(second.route().routeHash(),second.own().k());
        assertThat(firstKey).isNotEqualTo(secondKey);
        var options=List.of("-proc:none","-implicit:none","-encoding","UTF-8","-g","-parameters");
        var own=Files.createDirectories(dir.resolve("own"));
        for(var stub:Stubs.stubs(digest,first.tree(),first.own(),id -> first.store().get(MachineStore.nodeKey(id)),Stubs.Cache.NONE)) {
            var path=own.resolve(stub.internalName()+".class");Files.createDirectories(path.getParent());Files.write(path,stub.bytes());
        }
        var unit=new javax.tools.SimpleJavaFileObject(dir.resolve("app/src/main/java/p/App.java").toUri(),javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        byte[] previous=null;
        for(int i=0;i<2;i++) {
            var route=i==0 ? List.of(firstJar,secondJar) : List.of(secondJar,firstJar);
            var configuration=new Pool.Configuration(i==0 ? firstKey : secondKey,own,route,java.nio.charset.StandardCharsets.UTF_8,options,List.of("p/App"));
            var expected=Stage2Support.compile(dir.resolve("oracle-"+i),Map.of("p/App.java",source),options,route).get("p/App.class");
            try(var pool=new Pool(configuration,1)) {
                var result=pool.withTask(unit,null,task -> {
                    try { task.parse();task.analyze();task.generate();return null; }
                    catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                });
                var actual=result.classes().get("p/App");assertThat(actual).isEqualTo(expected);
                if(previous!=null) assertThat(actual).isNotEqualTo(previous);
                previous=actual;
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void contextChangesPrecedeShortcutsAndAciBindsKeysNamesBytesAndOptions(Digest digest) throws Exception {
        var current = boot(digest, fixture());
        var zeroA = new Proof.Range(Proof.T, "q/Base", Keys.FIELD, "absentA");
        var zeroB = new Proof.Range(Proof.T, "q/Base", Keys.FIELD, "absentB");
        var a = current.proof(List.of(zeroA), List.of());
        var b = current.proof(List.of(zeroB), List.of());
        var one = digest.hash(new byte[] {1}); var two = digest.hash(new byte[] {2});
        var context = new ProcessorRecords.Context(one, two, List.of(new ProcessorRecords.ConfigEntry("p/lombok.config", one)));
        assertThat(a.valid(current.tree, current.own, current.route, context, key -> { throw new AssertionError("Invalid context read storage"); })).isFalse();
        var withProcessor = Arrange.proof(current.tree, current.own, current.route, context, List.of(zeroA), List.of(), current.store::get);
        assertThat(withProcessor.valid(current.tree, current.own, current.route, null, key -> { throw new AssertionError("Removed context read storage"); })).isFalse();
        assertThat(Proof.decode(withProcessor.encode(), digest.width())).isEqualTo(withProcessor);
        assertThat(a.aci(digest, "App.java", one, two)).isNotEqualTo(b.aci(digest, "App.java", one, two))
                .isNotEqualTo(a.aci(digest, "Other.java", one, two)).isNotEqualTo(a.aci(digest, "App.java", two, two))
                .isNotEqualTo(a.aci(digest, "App.java", one, one)).isNotEqualTo(withProcessor.aci(digest, "App.java", one, two));
        assertThatThrownBy(() -> new Proof.Range(7, "q/Base", Keys.TYPE, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Proof.Range(Proof.N, "q/Base", Keys.METHOD, "get")).isInstanceOf(IllegalArgumentException.class);
        var trailing = java.util.Arrays.copyOf(a.encode(), a.encode().length + 1);
        assertThatThrownBy(() -> Proof.decode(trailing, digest.width())).isInstanceOf(IllegalArgumentException.class);
    }
}
