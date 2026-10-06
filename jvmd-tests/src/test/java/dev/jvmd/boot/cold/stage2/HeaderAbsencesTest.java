package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
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

/** Appendix A.6: an absence is proved in both the own-module and route universes. */
@Tag("phase-3")
class HeaderAbsencesTest {
    @TempDir Path root;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    record Booted(InMemoryLocalStore store, ProjectModel model, Stage2.Result result) { }

    private Booted boot(Digest digest) throws Exception {
        var model = ProjectModel.parse(Stage2Support.model(root,
                new Stage2Support.Mod("dep", "g:dep:1", List.of()),
                new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.module("g:dep:1", "dep")))));
        var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, root, ClassFacts::of).run(store, model);
        return new Booted(store, model, result);
    }

    private FileRow row(Digest digest, Booted boot, String name) {
        String path = "app/src/main/java/p/" + name + ".java";
        return FileRow.decode(path, boot.store.get(LocalStore.fileKey(Stage2.projectKey(digest, boot.model), path)), digest.width());
    }

    private boolean valid(Digest digest, FileRow old, Booted current) {
        var tree = new ContentTree(digest);
        var project = Stage2.projectKey(digest, current.model);
        var route = Route.decode(current.store.get(LocalStore.routeKey(project, "app", LocalStore.MAIN)), digest.width());
        var own = MachineLeaf.decode(current.store.get(MachineStore.leafKey(current.result.leaves().get("app/main"))), digest.width());
        return HeaderProof.valid(old, tree, own, route, current.store::get);
    }

    private void fixture() throws Exception {
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/X.java", "package q; public class X {}",
                "dep/src/main/java/q/Base.java", "package q; public class Base {}",
                "app/src/main/java/p/B.java", "package p; import q.*; public class B { X value; }",
                "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { int f() { return 1; } }"));
    }

    @ParameterizedTest @MethodSource("digests")
    void ownPackageInsertionInvalidatesOnlyTheImportConsumer(Digest digest) throws Exception {
        fixture();
        var before = boot(digest);
        assertThat(row(digest, before, "B").absences()).extracting(HeaderProof.Absence::type).contains("p/X");
        Stage2Support.write(root, Map.of("app/src/main/java/p/X.java", "package p; public class X {}"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
        assertThat(valid(digest, row(digest, before, "Unrelated"), after)).isTrue();
        assertThat(row(digest, after, "B").ownR()).isNotEqualTo(row(digest, before, "B").ownR());
    }

    @ParameterizedTest @MethodSource("digests")
    void routePackageInsertionInvalidatesTheImportConsumer(Digest digest) throws Exception {
        fixture();
        var before = boot(digest);
        Stage2Support.write(root, Map.of("dep/src/main/java/p/X.java", "package p; public class X {}"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
        assertThat(valid(digest, row(digest, before, "Unrelated"), after)).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void inheritedMemberTypeInsertionInvalidatesTheHeader(Digest digest) throws Exception {
        fixture();
        Stage2Support.write(root, Map.of("app/src/main/java/p/B.java", "package p; import q.*; public class B extends Base { X value; }"));
        var before = boot(digest);
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Base.java", "package q; public class Base { public static class X {} }"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
        assertThat(valid(digest, row(digest, before, "Unrelated"), after)).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void inheritedMemberShadowsAnExplicitImport(Digest digest) throws Exception {
        fixture();
        Stage2Support.write(root, Map.of("app/src/main/java/p/B.java", "package p; import q.X; public class B extends q.Base { X value; }"));
        var before = boot(digest);
        assertThat(row(digest, before, "B").absences()).contains(new HeaderProof.Absence(1, "q/Base", "X"));
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Base.java", "package q; public class Base { public static class X {} }"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void samePackageAnswerDoesNotDependOnDemandImports(Digest digest) throws Exception {
        fixture();
        Stage2Support.write(root, Map.of("app/src/main/java/p/B.java", "package p; import q.*; public class B { Unrelated value; }"));
        var before = boot(digest);
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Unrelated.java", "package q; public class Unrelated {}"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void onDemandMemberTypeInsertionIsAnAmbiguity(Digest digest) throws Exception {
        fixture();
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/r/Outer.java", "package r; public class Outer {}",
                "app/src/main/java/p/B.java", "package p; import q.*; import r.Outer.*; public class B { X value; }"));
        var before = boot(digest);
        assertThat(row(digest, before, "B").absences()).contains(new HeaderProof.Absence(1, "r/Outer", "X"));
        Stage2Support.write(root, Map.of("dep/src/main/java/r/Outer.java", "package r; public class Outer { public static class X {} }"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void ownPackageTypeCanShadowStaticOnDemandMemberType(Digest digest) throws Exception {
        fixture();
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/r/Outer.java", "package r; public class Outer { public static class X {} }",
                "app/src/main/java/p/B.java", "package p; import static r.Outer.*; public class B { X value; }"));
        var before = boot(digest);
        assertThat(row(digest, before, "B").absences()).contains(new HeaderProof.Absence(0, "p/X", ""));
        Stage2Support.write(root, Map.of("app/src/main/java/p/X.java", "package p; public class X {}"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void memberTypeAbsencesUseExactSimpleNameAndDirectOwner(Digest digest) throws Exception {
        fixture();
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/Foo.java", "package q; public class Foo {}",
                "dep/src/main/java/q/Bar.java", "package q; public class Bar {}",
                "dep/src/main/java/q/Base.java", "package q; public class Base { public static class Foobar {} public static class Foo$Bar {} }",
                "app/src/main/java/p/B.java", "package p; import q.*; public class B extends Base { Foo f; Bar b; }"));
        var before = boot(digest);
        var foo = new HeaderProof.Absence(1, "q/Base", "Foo");
        var bar = new HeaderProof.Absence(1, "q/Base", "Bar");
        assertThat(row(digest, before, "B").absences()).contains(foo, bar);
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Base.java",
                "package q; public class Base { public static class Foobar {} public static class Foo$Bar {} public static class Foo {} }"));
        var after = boot(digest);
        var tree = new ContentTree(digest);
        var leaf = MachineLeaf.decode(after.store.get(MachineStore.leafKey(after.result.leaves().get("dep/main"))), digest.width());
        var oldLeaf = MachineLeaf.decode(before.store.get(MachineStore.leafKey(before.result.leaves().get("dep/main"))), digest.width());
        assertThat(tree.get(leaf.oHash(), h -> after.store.get(MachineStore.nodeKey(h)), Keys.ownerKey("q/Base")).h())
                .as("adding Base.Foo changes its N range while oSum(Base) stays equal")
                .isEqualTo(tree.get(oldLeaf.oHash(), h -> before.store.get(MachineStore.nodeKey(h)), Keys.ownerKey("q/Base")).h());
        assertThat(HeaderProof.absent(foo, tree, _ -> leaf, h -> after.store.get(MachineStore.nodeKey(h)))).isFalse();
        assertThat(HeaderProof.absent(bar, tree, _ -> leaf, h -> after.store.get(MachineStore.nodeKey(h)))).isTrue();
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
        assertThat(valid(digest, row(digest, before, "Unrelated"), after)).isTrue();
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Base.java",
                "package q; public class Base { public static class Foo { public static class Bar {} } }"));
        var nested = boot(digest);
        var nestedLeaf = MachineLeaf.decode(nested.store.get(MachineStore.leafKey(nested.result.leaves().get("dep/main"))), digest.width());
        assertThat(HeaderProof.absent(bar, tree, _ -> nestedLeaf, h -> nested.store.get(MachineStore.nodeKey(h)))).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void qualifiedPackageHeadCanBeShadowedOnlyForHeaderConsumers(Digest digest) throws Exception {
        fixture();
        Stage2Support.write(root, Map.of(
                "app/src/main/java/p/B.java", "package p; public class B { java.util.List<String> values; }",
                "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { void body() { java.util.List.of(); } }"));
        var before = boot(digest);
        assertThat(row(digest, before, "B").absences()).contains(new HeaderProof.Absence(0, "p/java", ""));
        assertThat(row(digest, before, "Unrelated").absences()).doesNotContain(new HeaderProof.Absence(0, "p/java", ""), new HeaderProof.Absence(0, "p/p", ""));
        Stage2Support.write(root, Map.of("app/src/main/java/p/java.java", "package p; public class java {}"));
        var after = boot(digest);
        assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
        assertThat(valid(digest, row(digest, before, "Unrelated"), after)).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void bodyOnlyEditLeavesEveryHeaderValid(Digest digest) throws Exception {
        fixture();
        var before = boot(digest);
        Stage2Support.write(root, Map.of("app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { int f() { return 2; } }"));
        var after = boot(digest);
        for (var name : List.of("B", "Unrelated")) {
            assertThat(valid(digest, row(digest, before, name), after)).isTrue();
            assertThat(row(digest, after, name).ownR()).isEqualTo(row(digest, before, name).ownR());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void qualifiedInheritedMemberTypesProveEveryOwnerBeforeTheWinner(Digest digest) throws Exception {
        for (var provider : List.of("dep", "app")) for (var owner : List.of("Sub", "Mid")) {
            Stage2Support.write(root, Map.of(
                    provider + "/src/main/java/q/Above.java", "package q; public class Above {}",
                    provider + "/src/main/java/q/Base.java", "package q; public class Base extends Above { public static class Inner {} }",
                    provider + "/src/main/java/q/Mid.java", "package q; public class Mid extends Base {}",
                    provider + "/src/main/java/q/Sub.java", "package q; public class Sub extends Mid {}",
                    "app/src/main/java/p/B.java", "package p; public class B { q.Sub.Inner field; }",
                    "app/src/main/java/p/OnDemand.java", "package p; import static q.Sub.*; public class OnDemand { Inner field; }",
                    "app/src/main/java/p/Imported.java", "package p; import static q.Sub.Inner; public class Imported { Inner field; }",
                    "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { void body() { q.Sub.Inner local; } }"));
            var before = boot(digest);
            assertThat(before.result.faults()).isEmpty();
            for (var consumer : List.of("B", "Imported", "OnDemand")) assertThat(row(digest, before, consumer).absences())
                    .contains(new HeaderProof.Absence(1, "q/Sub", "Inner"), new HeaderProof.Absence(1, "q/Mid", "Inner"))
                    .doesNotContain(new HeaderProof.Absence(1, "q/Above", "Inner"));
            assertThat(row(digest, before, "Unrelated").absences()).doesNotContain(new HeaderProof.Absence(1, "q/Sub", "Inner"));
            Stage2Support.write(root, Map.of(provider + "/src/main/java/q/" + owner + ".java",
                    "package q; public class " + owner + " extends " + (owner.equals("Sub") ? "Mid" : "Base") + " { public static class Inner {} }"));
            var after = boot(digest);
            assertThat(after.result.faults()).isEmpty();
            for (var consumer : List.of("B", "Imported", "OnDemand")) assertThat(valid(digest, row(digest, before, consumer), after)).isFalse();
            assertThat(valid(digest, row(digest, before, "Unrelated"), after)).isTrue();
            var tree = new ContentTree(digest);
            var oldLeaf = MachineLeaf.decode(before.store.get(MachineStore.leafKey(before.result.leaves().get(provider + "/main"))), digest.width());
            var newLeaf = MachineLeaf.decode(after.store.get(MachineStore.leafKey(after.result.leaves().get(provider + "/main"))), digest.width());
            for (var unchanged : List.of("q/Sub", "q/Mid", "q/Base"))
                assertThat(tree.get(newLeaf.oHash(), h -> after.store.get(MachineStore.nodeKey(h)), Keys.ownerKey(unchanged)).h())
                        .as("the qualified lookup must fail without a containing-owner oSum change")
                        .isEqualTo(tree.get(oldLeaf.oHash(), h -> before.store.get(MachineStore.nodeKey(h)), Keys.ownerKey(unchanged)).h());
            for (var name : List.of("Above", "Base", "Mid", "Sub")) java.nio.file.Files.delete(root.resolve(provider + "/src/main/java/q/" + name + ".java"));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void qualifiedMemberLookupChecksCompetingBranchesButStopsAtTheWinner(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/Above.java", "package q; public interface Above {}",
                "dep/src/main/java/q/Base.java", "package q; public class Base implements Above { public static class Inner {} }",
                "dep/src/main/java/q/Side.java", "package q; public interface Side {}",
                "dep/src/main/java/q/Sub.java", "package q; public class Sub extends Base implements Side {}",
                "app/src/main/java/p/B.java", "package p; public class B { q.Sub.Inner field; }",
                "app/src/main/java/p/OnDemand.java", "package p; import static q.Sub.*; public class OnDemand { Inner field; }",
                    "app/src/main/java/p/Imported.java", "package p; import static q.Sub.Inner; public class Imported { Inner field; }"));
        var before = boot(digest);
        assertThat(before.result.faults()).isEmpty();
        for (var name : List.of("B", "Imported", "OnDemand")) assertThat(row(digest, before, name).absences())
                .contains(new HeaderProof.Absence(1, "q/Sub", "Inner"), new HeaderProof.Absence(1, "q/Side", "Inner"))
                .doesNotContain(new HeaderProof.Absence(1, "q/Base", "Inner"), new HeaderProof.Absence(1, "q/Above", "Inner"));
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Above.java", "package q; public interface Above { class Inner {} }"));
        var hidden = boot(digest);
        assertThat(hidden.result.faults()).isEmpty();
        for (var name : List.of("B", "Imported", "OnDemand")) assertThat(valid(digest, row(digest, before, name), hidden))
                .as("Base.Inner stops lookup before its own ancestor Above").isTrue();
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Side.java", "package q; public interface Side { class Inner {} }"));
        var ambiguous = boot(digest);
        for (var name : List.of("B", "Imported", "OnDemand")) assertThat(valid(digest, row(digest, before, name), ambiguous)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void aWinningOwnersAncestorStillMattersWhenReachedThroughAnotherBranch(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of(
                "dep/src/main/java/q/Above.java", "package q; public interface Above {}",
                "dep/src/main/java/q/Base.java", "package q; public class Base implements Above { public static class Inner {} }",
                "dep/src/main/java/q/Side.java", "package q; public interface Side extends Above {}",
                "dep/src/main/java/q/Sub.java", "package q; public class Sub extends Base implements Side {}",
                "app/src/main/java/p/B.java", "package p; public class B { q.Sub.Inner field; }",
                "app/src/main/java/p/Imported.java", "package p; import static q.Sub.Inner; public class Imported { Inner field; }",
                "app/src/main/java/p/OnDemand.java", "package p; import static q.Sub.*; public class OnDemand { Inner field; }"));
        var before = boot(digest);
        assertThat(before.result.faults()).isEmpty();
        for (var name : List.of("B", "Imported", "OnDemand")) assertThat(row(digest, before, name).absences())
                .contains(new HeaderProof.Absence(1, "q/Above", "Inner"));
        Stage2Support.write(root, Map.of("dep/src/main/java/q/Above.java", "package q; public interface Above { class Inner {} }"));
        var ambiguous = boot(digest);
        for (var name : List.of("B", "Imported", "OnDemand")) assertThat(valid(digest, row(digest, before, name), ambiguous)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void longerPackagePrefixesAreExactAbsencesInHeadersAndImports(Digest digest) throws Exception {
        for (var provider : List.of("dep", "app")) for (var prefix : List.of("q/r", "q/r/s")) {
            Stage2Support.write(root, Map.of(
                    "dep/src/main/java/q/r/s/X.java", "package q.r.s; public class X {}",
                    "app/src/main/java/p/B.java", "package p; public class B { q.r.s.X field; }",
                    "app/src/main/java/p/Imported.java", "package p; import q.r.s.*; public class Imported { X field; }",
                    "app/src/main/java/p/Unrelated.java", "package p; public class Unrelated { void body() { q.r.s.X unused; } }"));
            var before = boot(digest);
            assertThat(before.result.faults()).isEmpty();
            for (var consumer : List.of("B", "Imported")) assertThat(row(digest, before, consumer).absences()).contains(
                    new HeaderProof.Absence(0, "q/r", ""), new HeaderProof.Absence(0, "q/r/s", ""));
            assertThat(row(digest, before, "Unrelated").absences()).doesNotContain(
                    new HeaderProof.Absence(0, "q/r", ""), new HeaderProof.Absence(0, "q/r/s", ""));
            var inserted = root.resolve(provider + "/src/main/java/" + prefix + ".java");
            Stage2Support.write(root, Map.of(provider + "/src/main/java/" + prefix + ".java",
                    prefix.equals("q/r") ? "package q; public class r {}" : "package q.r; public class s {}"));
            var after = boot(digest);
            assertThat(valid(digest, row(digest, before, "B"), after)).isFalse();
            assertThat(valid(digest, row(digest, before, "Imported"), after)).isFalse();
            assertThat(valid(digest, row(digest, before, "Unrelated"), after)).isTrue();
            java.nio.file.Files.delete(inserted);
        }
    }
}
