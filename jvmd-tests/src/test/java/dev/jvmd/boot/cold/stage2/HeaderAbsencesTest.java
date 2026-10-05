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
}
