package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** The complete header loop: semantic tree delta -> reverse prefix -> actual paths -> proof validation. */
@Tag("phase-3")
class HeaderReverseTest {
    @TempDir Path root;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }
    record Booted(InMemoryLocalStore store, ProjectModel model, Stage2.Result result, Identity project) { }

    private Booted boot(Digest digest) throws Exception { return boot(digest, Stage2Support.jdkOnly(digest).copy()); }
    private Booted boot(Digest digest, InMemoryLocalStore store) throws Exception {
        var model = ProjectModel.parse(Stage2Support.model(root,
                new Stage2Support.Mod("dep", "g:dep:1", List.of()),
                new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.module("g:dep:1", "dep"))),
                new Stage2Support.Mod("other", "g:other:1", List.of(Stage2Support.Dep.module("g:dep:1", "dep")))));
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, root, ClassFacts::of).run(store, model);
        return new Booted(store, model, result, Stage2.projectKey(digest, model));
    }

    private MachineLeaf leaf(Digest digest, Booted boot, String module) {
        return MachineLeaf.decode(boot.store.get(MachineStore.leafKey(boot.result.leaves().get(module + "/main"))), digest.width());
    }

    private Set<String> candidates(Digest digest, Booted before, Booted after) {
        var a = leaf(digest, before, "dep");
        var b = leaf(digest, after, "dep");
        Function<Identity, byte[]> nodes = h -> {
            var value = after.store.get(MachineStore.nodeKey(h));
            return value == null ? before.store.get(MachineStore.nodeKey(h)) : value;
        };
        var t = Diff.trees(digest, new Root(a.k(), a.r(), a.factCount(), Node.level(nodes.apply(a.k()))),
                new Root(b.k(), b.r(), b.factCount(), Node.level(nodes.apply(b.k()))), nodes);
        var n = Diff.trees(digest, new Root(a.nHash(), a.r(), a.factCount(), a.nLevel()),
                new Root(b.nHash(), b.r(), b.factCount(), b.nLevel()), nodes);
        var d = Diff.trees(digest, new Root(a.oHash(), a.r(), a.typeCount(), a.oLevel()),
                new Root(b.oHash(), b.r(), b.typeCount(), b.oLevel()), nodes);
        int start = before.store.events().size();
        var consumers = ReverseIndex.candidates(digest, before.store, new ReverseIndex.Delta(t, n, d, new Diff.Result(List.of(), List.of())));
        assertThat(before.store.events().subList(start, before.store.events().size())).doesNotContain("read:F", "prefix:F");
        var paths = new TreeSet<String>();
        for (var consumer : consumers) {
            assertThat(consumer.project()).isEqualTo(before.project);
            paths.add(consumer.path());
            var old = FileRow.decode(consumer.path(), before.store.get(LocalStore.fileKey(consumer.project(), consumer.source())), digest.width());
            String module = consumer.module();
            var route = Route.decode(after.store.get(LocalStore.routeKey(after.project, module, LocalStore.MAIN)), digest.width());
            assertThat(HeaderProof.valid(old, new ContentTree(digest), leaf(digest, after, module), route, after.store::get))
                    .as("the selected fixture's candidate must actually have a changed consumed range: " + consumer.path()).isFalse();
        }
        return paths;
    }

    @ParameterizedTest @MethodSource("digests")
    void fieldDeltaFindsBothIdenticalByteFilesWithoutAConsumerList(Digest digest) throws Exception {
        String same = "package p; public class B { public static final int COPY = q.K.VALUE; }";
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/K.java", "package q; public class K { public static final int VALUE = 1; }",
                "app/src/main/java/p/B.java", same,
                "other/src/main/java/p/B.java", same,
                "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { q.K onlyType; }"));
        var before = boot(digest);
        var row1 = FileRow.decode("app/src/main/java/p/B.java", before.store.get(LocalStore.fileKey(before.project, Stage2Support.source("app/src/main/java/p/B.java"))), digest.width());
        var row2 = FileRow.decode("other/src/main/java/p/B.java", before.store.get(LocalStore.fileKey(before.project, Stage2Support.source("other/src/main/java/p/B.java"))), digest.width());
        assertThat(row1.kappa()).isEqualTo(row2.kappa());
        assertThat(Route.decode(before.store.get(LocalStore.routeKey(before.project, "app", LocalStore.MAIN)), digest.width()).leafSetExt())
                .isEqualTo(Route.decode(before.store.get(LocalStore.routeKey(before.project, "other", LocalStore.MAIN)), digest.width()).leafSetExt());
        var dependency = new ReverseIndex.Dependency(ReverseIndex.T, "q/K", Keys.FIELD, "VALUE");
        assertThat(ReverseIndex.consumers(digest, before.store, dependency)).hasSize(2);
        assertThat(before.store.get(dependency.key(before.project, Stage2Support.source(row1.path())))).isEmpty();
        assertThat(before.store.get(dependency.key(before.project, Stage2Support.source(row2.path())))).isEmpty();
        Stage2Support.write(root, Map.of("dep/src/main/java/q/K.java", "package q; public class K { public static final int VALUE = 2; }"));
        assertThat(candidates(digest, before, boot(digest))).containsExactly("app/src/main/java/p/B.java", "other/src/main/java/p/B.java");
    }

    @ParameterizedTest @MethodSource("digests")
    void memberTypeDeltaFindsAnExpectedZeroNReadWithUnchangedOwnerSum(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/Base.java", "package q; public class Base {}",
                "dep/src/main/java/q/X.java", "package q; public class X {}",
                "app/src/main/java/p/B.java", "package p; import q.*; public class B extends Base { X value; }",
                "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { void body() { q.X unused; } }"));
        var before = boot(digest);
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Base.java", "package q; public class Base { public static class X {} }"));
        var after = boot(digest);
        var tree = new ContentTree(digest);
        assertThat(tree.get(leaf(digest, before, "dep").oHash(), h -> before.store.get(MachineStore.nodeKey(h)), Keys.ownerKey("q/Base")).h())
                .isEqualTo(tree.get(leaf(digest, after, "dep").oHash(), h -> after.store.get(MachineStore.nodeKey(h)), Keys.ownerKey("q/Base")).h());
        assertThat(candidates(digest, before, after)).containsExactly("app/src/main/java/p/B.java");
    }

    @ParameterizedTest @MethodSource("digests")
    void newTypeFindsItsExpectedZeroDefinerConsumer(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "app/src/main/java/p/B.java", "package p; public class B { Missing field; }",
                "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated {}"));
        var before = boot(digest);
        Stage2Support.write(root, Map.of("dep/src/main/java/p/Missing.java", "package p; public class Missing {}"));
        assertThat(candidates(digest, before, boot(digest))).containsExactly("app/src/main/java/p/B.java");
    }

    @ParameterizedTest @MethodSource("digests")
    void aNewHidingFieldFindsAnExpectedZeroTRange(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/Base.java", "package q; public class Base { public static final int VALUE = 1; }",
                "dep/src/main/java/q/Sub.java", "package q; public class Sub extends Base {}",
                "app/src/main/java/p/B.java", "package p; public class B { public static final int COPY = q.Sub.VALUE; }",
                "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { q.Sub onlyType; }"));
        var before = boot(digest);
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Sub.java", "package q; public class Sub extends Base { public static final int VALUE = 2; }"));
        assertThat(candidates(digest, before, boot(digest))).containsExactly("app/src/main/java/p/B.java");
    }

    @ParameterizedTest @MethodSource("digests")
    void routePermutationReachesPositiveAndMemberTypeConsumersWithoutAnyLeafEdit(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "a/src/main/java/q/K.java", "package q; public class K { public static final int VALUE=1; }",
                "b/src/main/java/q/K.java", "package q; public class K { public static final int VALUE=2; }",
                "a/src/main/java/q/Base.java", "package q; public class Base {}",
                "b/src/main/java/q/Base.java", "package q; public class Base { public static class X {} }",
                "a/src/main/java/q/X.java", "package q; public class X {}",
                "app/src/main/java/p/Value.java", "package p; public class Value { public static final int COPY=q.K.VALUE; }",
                "app/src/main/java/p/Member.java", "package p; import q.*; public class Member extends Base { X value; }"));
        var states = new java.util.ArrayList<Booted>();
        for (var order : List.of(List.of("a", "b"), List.of("b", "a"))) {
            var model = ProjectModel.parse(Stage2Support.model(root,
                    new Stage2Support.Mod("a", "g:a:1", List.of()),
                    new Stage2Support.Mod("b", "g:b:1", List.of()),
                    new Stage2Support.Mod("app", "g:app:1", order.stream().map(n -> Stage2Support.Dep.module("g:"+n+":1", n)).toList())));
            var store = Stage2Support.jdkOnly(digest).copy();
            var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, root, ClassFacts::of).run(store, model);
            states.add(new Booted(store, model, result, Stage2.projectKey(digest, model)));
        }
        var before = states.getFirst(); var after = states.getLast();
        for (var module : List.of("a", "b")) assertThat(leaf(digest, before, module)).isEqualTo(leaf(digest, after, module));
        var oldRoute = Route.decode(before.store.get(LocalStore.routeKey(before.project, "app", 0)), digest.width());
        var newRoute = Route.decode(after.store.get(LocalStore.routeKey(after.project, "app", 0)), digest.width());
        var oldDC = DefinerIndex.decodeRoot(before.store.get(LocalStore.conflictsKey(oldRoute.routeHash())), digest.width());
        var newDC = DefinerIndex.decodeRoot(after.store.get(LocalStore.conflictsKey(newRoute.routeHash())), digest.width());
        var delta = Diff.content(digest, oldDC, newDC, h -> {
            var bytes = after.store.get(MachineStore.nodeKey(h));
            return bytes == null ? before.store.get(MachineStore.nodeKey(h)) : bytes;
        });
        assertThat(delta.added()).isNotEmpty();
        var none = new Diff.Result(List.of(), List.of());
        var candidates = ReverseIndex.candidates(digest, before.store, new ReverseIndex.Delta(none, none, none, delta));
        assertThat(candidates).extracting(ReverseIndex.Consumer::path)
                .contains("app/src/main/java/p/Value.java", "app/src/main/java/p/Member.java");
        assertThat(oldDC.sum()).isNotEqualTo(newDC.sum()); // K.VALUE changes.
        var oldBase = new ContentTree(digest).get(oldDC.hash(), h -> before.store.get(MachineStore.nodeKey(h)), Keys.ownerKey("q/Base"));
        var newBase = new ContentTree(digest).get(newDC.hash(), h -> after.store.get(MachineStore.nodeKey(h)), Keys.ownerKey("q/Base"));
        assertThat(oldBase.h()).as("Base's own resolution identity excludes its separately keyed member type").isEqualTo(newBase.h());
        assertThat(oldBase.value()).isNotEqualTo(newBase.value());
        for (var path : List.of("app/src/main/java/p/Value.java", "app/src/main/java/p/Member.java")) {
            var old = FileRow.decode(path, before.store.get(LocalStore.fileKey(before.project, Stage2Support.source(path))), digest.width());
            assertThat(HeaderProof.valid(old, new ContentTree(digest), leaf(digest, after, "app"), newRoute, after.store::get)).isFalse();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void freshColdBootReusesExactPersistedConflictsWithoutBuildingOrWritingNodes(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/K.java", "package q; public class K { public static final int VALUE=1; }",
                "app/src/main/java/p/B.java", "package p; public class B { public static final int COPY=q.K.VALUE; }"));
        var first = boot(digest);
        assertThat(first.result.timings().conflictBuilds()).isPositive();
        assertThat(first.result.timings().conflictNodeWrites()).isPositive();
        assertThat(first.result.timings().definerOwnerOpens()).isPositive();
        var second = boot(digest, first.store);
        assertThat(second.result.timings().conflictCacheHits()).isEqualTo(first.result.timings().conflictBuilds() + first.result.timings().conflictApplies());
        assertThat(second.result.timings().conflictBuilds()).isZero();
        assertThat(second.result.timings().conflictNodeWrites()).isZero();
        assertThat(second.result.timings().definerOwnerOpens()).isZero();
        assertThat(second.result.timings().definerCacheHits()).isPositive();
        assertThat(second.result.root()).isEqualTo(first.result.root());
        // A separate checkout shares the derivable states, without sharing a project's LOCAL root.
        root = java.nio.file.Files.createDirectories(root.resolve("another-checkout"));
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/K.java", "package q; public class K { public static final int VALUE=1; }",
                "app/src/main/java/p/B.java", "package p; public class B { public static final int COPY=q.K.VALUE; }"));
        var third = boot(digest, first.store);
        assertThat(third.project).isNotEqualTo(first.project);
        assertThat(third.result.timings().definerOwnerOpens()).isZero();
        assertThat(third.result.timings().conflictBuilds()).isZero();
        assertThat(third.result.timings().conflictNodeWrites()).isZero();
    }

    @ParameterizedTest @MethodSource("digests")
    void publicationDeletesObsoleteCurrentKeys(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/K.java", "package q; public class K { public static final int VALUE = 1; }",
                "app/src/main/java/p/B.java", "package p; public class B { public static final int COPY = q.K.VALUE; }"));
        var before = boot(digest);
        var dependency = new ReverseIndex.Dependency(ReverseIndex.T, "q/K", Keys.FIELD, "VALUE");
        byte[] oldKey = dependency.key(before.project, Stage2Support.source("app/src/main/java/p/B.java"));
        Stage2Support.write(root, Map.of("app/src/main/java/p/B.java", "package p; public class B {}"));
        var after = boot(digest, before.store);
        assertThat(after.store.get(oldKey)).as("current secondary keys are removed atomically with root publication").isNull();
        assertThat(ReverseIndex.consumers(digest, after.store, dependency)).as("only the published LOCAL tree defines live records").isEmpty();
    }
}
