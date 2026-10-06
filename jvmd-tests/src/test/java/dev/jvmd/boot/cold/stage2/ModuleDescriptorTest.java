package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.ModuleDescriptor;
import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class ModuleDescriptorTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void everyDirectiveAndEmissionOptionMatchesNativeJavac(Digest digest) throws Exception {
        var sources = Map.of("module-info.java", """
                module example {
                    requires transitive java.logging;
                    requires static java.sql;
                    exports p to java.logging, java.sql;
                    opens p to java.base;
                    uses p.Service;
                    provides p.Service with p.First, p.Second;
                }
                """, "p/Service.java", "package p; public interface Service {}",
                "p/First.java", "package p; public class First implements Service {}",
                "p/Second.java", "package p; public class Second implements Service {}");
        Stage2Support.write(dir.resolve("m/src/main/java"), sources);
        for (String debug : List.of("-g", "-g:none", "-g:lines", "-g:source")) {
            var options = List.of(debug, "--module-version", "1.2.3");
            var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())
                    .withOptions(options.toArray(String[]::new))));
            var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
            var boot = new Stage2(digest, tree, Stage2Support.FEATURE, 2, dir, ClassFacts::of).run(store, model);
            assertThat(boot.faults()).isEmpty();
            var actual = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir).run(store, model);
            var scope = actual.scopes().get("m/main"); assertThat(scope.descriptorEmissions()).isEqualTo(1);
            var descriptor = descriptor(actual);
            assertThat(descriptor.proof().types()).singleElement().satisfies(t -> {
                assertThat(t.key()).isEqualTo("module-info");
                assertThat(t.entries()).singleElement().satisfies(e -> assertThat(e.range())
                        .isEqualTo(new Proof.Range(Proof.T, "module-info", Keys.TYPE, "")));
            });
            var expected = Stage2Support.compile(dir.resolve("native-" + debug.replace(':', '-')), sources, options, List.of());
            var bytes = store.get(LocalStore.classFileKey(descriptor.result().classFiles().getFirst().contentHash()));
            assertThat(bytes).as(debug).isEqualTo(expected.get("module-info.class"));
            var second = new Stage3(digest, tree, Stage2Support.FEATURE, 4, dir).run(store, model);
            assertThat(second.scopes().get("m/main").descriptorEmissions()).isZero();
            assertThat(second.bodies()).isEqualTo(actual.bodies());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void descriptorTargetAndRequiredVersionsFollowTheDeclaredRelease(Digest digest) throws Exception {
        var sources=Map.of("module-info.java","module example { requires java.logging; }");
        Stage2Support.write(dir.resolve("m/src/main/java"),sources);
        for(int release:List.of(21,Stage2Support.FEATURE)) {
            var options=List.of("--release",String.valueOf(release));
            var model=ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("m","g:m:1",List.of()).withOptions(options.toArray(String[]::new))));
            var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();
            new Stage2(digest,tree,Stage2Support.FEATURE,1,dir,ClassFacts::of).run(store,model);
            var actual=new Stage3(digest,tree,Stage2Support.FEATURE,1,dir).run(store,model);
            var expected=Stage2Support.compile(dir.resolve("native-release-"+release),sources,options,List.of());
            assertThat(store.get(LocalStore.classFileKey(descriptor(actual).result().classFiles().getFirst().contentHash())))
                    .isEqualTo(expected.get("module-info.class"));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void onlyTheDescriptorDerivationMovesOnDirectiveEdits(Digest digest) throws Exception {
        String path = "m/src/main/java/module-info.java", ordinary = "m/src/main/java/p/A.java";
        Stage2Support.write(dir, Map.of(path, "module example { exports p; }", ordinary,
                "package p; public class A {public int x(){return 1;}}"));
        var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var stage2 = new Stage2(digest, tree, Stage2Support.FEATURE, 2, dir, ClassFacts::of);
        var stage3 = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir);
        stage2.run(store, model); var first = stage3.run(store, model);
        Stage2Support.write(dir, Map.of(path, "/* comment only */ module example { exports p; }",
                ordinary, "package p; public class A {public int x(){return 2;}}"));
        stage2.run(store, model); var bodyEdit = stage3.run(store, model);
        assertThat(bodyEdit.scopes().get("m/main").descriptorEmissions()).isZero();
        assertThat(descriptor(bodyEdit).aci()).isEqualTo(descriptor(first).aci());
        Stage2Support.write(dir, Map.of(path, "open module example { exports p; requires java.logging; }"));
        stage2.run(store, model); var directive = stage3.run(store, model);
        assertThat(directive.scopes().get("m/main").descriptorEmissions()).isEqualTo(1);
        assertThat(descriptor(directive).aci()).isNotEqualTo(descriptor(bodyEdit).aci());
        var before = bodyEdit.scopes().get("m/main").files().stream().filter(f -> f.path().equals(ordinary)).findFirst().orElseThrow();
        var after = directive.scopes().get("m/main").files().stream().filter(f -> f.path().equals(ordinary)).findFirst().orElseThrow();
        assertThat(after.computed().aci()).isEqualTo(before.computed().aci());
        assertThat(after.computed().result()).isEqualTo(before.computed().result());
        var expected = Stage2Support.compile(dir.resolve("native-open"), Map.of("module-info.java",
                "open module example { exports p; requires java.logging; }", "p/A.java",
                "package p; public class A {public int x(){return 2;}}"), List.of(), List.of());
        assertThat(store.get(LocalStore.classFileKey(descriptor(directive).result().classFiles().getFirst().contentHash())))
                .isEqualTo(expected.get("module-info.class"));
    }

    private static dev.jvmd.boot.cold.stage3.Attribute.Computed descriptor(Stage3.Result result) {
        return result.scopes().get("m/main").files().stream().filter(f -> f.path().endsWith("/module-info.java"))
                .findFirst().orElseThrow().computed();
    }

    @ParameterizedTest @MethodSource("digests")
    void serviceNamesUseResolvedBinaryNamesAndExactHeaderReads(Digest digest) throws Exception {
        var sources = Map.of("module-info.java", """
                import p.Api.Service;
                import p.Implementation.*;
                module example {
                    exports p;
                    uses Service;
                    provides Service with Provider;
                }
                """, "p/Api.java", "package p; public class Api { public interface Service {} }",
                "p/Implementation.java", """
                package p;
                public class Implementation {
                    public static class Provider implements Api.Service { public Provider() {} }
                    public static class Unread { public int value() { return 1; } }
                }
                """);
        Stage2Support.write(dir.resolve("m/src/main/java"), sources);
        var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var stage2 = new Stage2(digest, tree, Stage2Support.FEATURE, 2, dir, ClassFacts::of);
        var boot = stage2.run(store, model);
        assertThat(boot.faults()).isEmpty();
        var expected = Stage2Support.compile(dir.resolve("native-nested"), sources, List.of(), List.of());
        var expectedFact = ClassFacts.of(digest, expected.get("module-info.class"), "module-info").facts().getFirst();
        var fact = tree.get(boot.leaves().get("m/main"), id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"));
        assertThat(fact.value()).isEqualTo(expectedFact.res());
        var body = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir).run(store, model);
        assertThat(store.get(LocalStore.classFileKey(descriptor(body).result().classFiles().getFirst().contentHash())))
                .isEqualTo(expected.get("module-info.class"));
        String path = "m/src/main/java/module-info.java";
        var row = FileRow.decode(path, store.get(LocalStore.fileKey(Stage2.projectKey(digest, model), "m", 0, path)), digest.width());
        assertThat(row.headerProof()).extracting(FileRow.Proof::typeKey).contains("p/Api$Service", "p/Implementation$Provider")
                .doesNotContain("p/Implementation$Unread");
        var changed = new java.util.HashMap<>(sources);
        changed.put("p/Implementation.java", sources.get("p/Implementation.java").replace("return 1;", "return 2;")
                .replace("int value()", "long value()"));
        Stage2Support.write(dir.resolve("m/src/main/java"), changed);
        var unrelatedHeader = stage2.run(store, model);
        var route = Route.decode(store.get(LocalStore.routeKey(Stage2.projectKey(digest, model), "m", 0)), digest.width());
        assertThat(HeaderProof.valid(row, tree, MachineLeaf.decode(store.get(MachineStore.leafKey(unrelatedHeader.leaves().get("m/main"))), digest.width()),
                route, store::get)).isTrue();
        var bodyEdit = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir).run(store, model);
        assertThat(bodyEdit.scopes().get("m/main").descriptorEmissions()).isZero();
        assertThat(descriptor(bodyEdit).aci()).isEqualTo(descriptor(body).aci());
        changed.put("p/Implementation.java", changed.get("p/Implementation.java").replace("class Provider", "final class Provider"));
        Stage2Support.write(dir.resolve("m/src/main/java"), changed);
        var changedMetadata = stage2.run(store, model);
        assertThat(HeaderProof.valid(row, tree, MachineLeaf.decode(store.get(MachineStore.leafKey(changedMetadata.leaves().get("m/main"))), digest.width()),
                route, store::get)).isFalse();
        var metadataEdit = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir).run(store, model);
        assertThat(metadataEdit.scopes().get("m/main").descriptorEmissions()).isEqualTo(1);
        assertThat(descriptor(metadataEdit).aci()).isNotEqualTo(descriptor(body).aci());
        var nativeMetadata = Stage2Support.compile(dir.resolve("native-metadata"), changed, List.of(), List.of());
        assertThat(store.get(LocalStore.classFileKey(descriptor(metadataEdit).result().classFiles().getFirst().contentHash())))
                .isEqualTo(nativeMetadata.get("module-info.class"));
    }

    @ParameterizedTest @MethodSource("digests")
    void deepNestedServicesRetainOuterOrderAndHeaderDependencies(Digest digest) throws Exception {
        var sources = Map.of("module-info.java", """
                import p.Api.Container;
                import p.Providers.Factory.Impl;
                module example {
                    provides Container.Service with Impl;
                    uses Container.Service;
                }
                """, "p/Api.java", "package p; public class Api { public static class Container { public interface Service {} } }",
                "p/Providers.java", "package p; public class Providers { public static class Factory { public static class Impl implements Api.Container.Service {} } }");
        Stage2Support.write(dir.resolve("m/src/main/java"), sources);
        var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var stage2 = new Stage2(digest, tree, Stage2Support.FEATURE, 2, dir, ClassFacts::of);
        var boot = stage2.run(store, model);
        assertThat(boot.faults()).isEmpty();
        var expected = Stage2Support.compile(dir.resolve("native-deep"), sources, List.of(), List.of());
        var expectedFact = ClassFacts.of(digest, expected.get("module-info.class"), "module-info").facts().getFirst();
        var fact = tree.get(boot.leaves().get("m/main"), id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"));
        assertThat(fact.value()).isEqualTo(expectedFact.res());
        var body = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir).run(store, model);
        assertThat(store.get(LocalStore.classFileKey(descriptor(body).result().classFiles().getFirst().contentHash())))
                .isEqualTo(expected.get("module-info.class"));
        String path = "m/src/main/java/module-info.java";
        var row = FileRow.decode(path, store.get(LocalStore.fileKey(Stage2.projectKey(digest, model), "m", 0, path)), digest.width());
        assertThat(row.headerProof()).extracting(FileRow.Proof::typeKey)
                .contains("p/Api$Container", "p/Api$Container$Service", "p/Providers$Factory", "p/Providers$Factory$Impl");
    }

    @ParameterizedTest @MethodSource("digests")
    void unresolvedServiceIsAHeaderFaultBesideHealthyDeclarations(Digest digest) throws Exception {
        Stage2Support.write(dir.resolve("m/src/main/java"), Map.of("module-info.java", "module example { uses p.Missing; }",
                "p/Good.java", "package p; public class Good {}"));
        var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var boot = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of).run(store, model);
        assertThat(boot.faults()).singleElement().asString().contains("module-info.java", "Unresolved module service type");
        var leaf = boot.leaves().get("m/main");
        assertThat(tree.get(leaf, id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("p/Good"))).isNotNull();
        assertThat(tree.get(leaf, id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"))).isNull();
        String path = "m/src/main/java/module-info.java";
        var row = FileRow.decode(path, store.get(LocalStore.fileKey(Stage2.projectKey(digest, model), "m", 0, path)), digest.width());
        assertThat(row.absences()).contains(new HeaderProof.Absence(0, "p/Missing", ""));
        var driver = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir);
        var failed = driver.run(store, model);
        var result = descriptor(failed);
        assertThat(result.result().attributed()).isFalse();
        assertThat(result.result().classFiles()).isEmpty();
        assertThat(result.result().diagnostics()).isEqualTo(nativeDescriptorDiagnostics(dir.resolve("m/src/main/java")));
        assertThat(failed.scopes().get("m/main").files().stream().flatMap(f -> f.computed().result().classFiles().stream()))
                .extracting(ResultRecord.ClassFile::internalName).containsExactly("p/Good");
        assertThat(failed.scopes().get("m/main").descriptorEmissions()).isZero();
        assertThat(driver.run(store, model).bodies()).isEqualTo(failed.bodies());
        Stage2Support.write(dir.resolve("m/src/main/java"), Map.of("p/Missing.java", "package p; public interface Missing {}"));
        var repaired = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of).run(store, model);
        var route = Route.decode(store.get(LocalStore.routeKey(Stage2.projectKey(digest, model), "m", 0)), digest.width());
        assertThat(result.proof().valid(tree, MachineLeaf.decode(store.get(MachineStore.leafKey(repaired.leaves().get("m/main"))), digest.width()),
                route, null, store::get)).isFalse();
        assertThat(descriptor(driver.run(store, model)).result().attributed()).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void malformedDescriptorDoesNotAbortHealthyBodies(Digest digest) throws Exception {
        Stage2Support.write(dir.resolve("m/src/main/java"), Map.of("module-info.java", "module example { exports ; }",
                "p/Good.java", "package p; public class Good {}"));
        var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of).run(store, model);
        var failed = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir).run(store, model);
        assertThat(descriptor(failed).result().attributed()).isFalse();
        assertThat(descriptor(failed).result().diagnostics()).isEqualTo(nativeDescriptorDiagnostics(dir.resolve("m/src/main/java")));
        assertThat(failed.scopes().get("m/main").files().stream().flatMap(f -> f.computed().result().classFiles().stream()))
                .extracting(ResultRecord.ClassFile::internalName).containsExactly("p/Good");
    }

    private static List<ResultRecord.Diagnostic> nativeDescriptorDiagnostics(Path source) throws Exception {
        var messages = new java.util.ArrayList<ResultRecord.Diagnostic>();
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        javax.tools.DiagnosticListener<javax.tools.JavaFileObject> listener = d -> {
            if (d.getSource() != null && d.getSource().isNameCompatible("module-info", javax.tools.JavaFileObject.Kind.SOURCE))
                messages.add(new ResultRecord.Diagnostic(switch (d.getKind()) {
                    case ERROR -> 0; case WARNING, MANDATORY_WARNING -> 1; default -> 2;
                }, d.getStartPosition(), d.getEndPosition(), d.getCode(), d.getMessage(java.util.Locale.ROOT)));
        };
        try (var manager = compiler.getStandardFileManager(listener, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8);
             var paths = java.nio.file.Files.walk(source)) {
            var output = java.nio.file.Files.createDirectories(source.resolveSibling("native-errors"));
            var task = compiler.getTask(null, manager, listener, List.of("-proc:none", "-d", output.toString()), null,
                    manager.getJavaFileObjectsFromPaths(paths.filter(p -> p.toString().endsWith(".java")).toList()));
            task.setLocale(java.util.Locale.ROOT);
            assertThat(task.call()).isFalse();
        }
        return List.copyOf(messages);
    }

    @ParameterizedTest @MethodSource("digests")
    void makingAnInaccessibleServicePublicInvalidatesItsHeaderError(Digest digest) throws Exception {
        for (boolean nested : List.of(false, true)) {
            String reference = nested ? "p.Hidden.Service" : "p.Hidden";
            String inaccessible = nested ? "package p; public class Hidden { private interface Service {} }" : "package p; interface Hidden {}";
            String accessible = nested ? "package p; public class Hidden { public interface Service {} }" : "package p; public interface Hidden {}";
            Stage2Support.write(dir.resolve("m/src/main/java"), Map.of("module-info.java", "module example { uses " + reference + "; }",
                    "p/Hidden.java", inaccessible));
            var expected = nativeDescriptorDiagnostics(dir.resolve("m/src/main/java"));
            var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
            var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
            var headers = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
            headers.run(store, model);
            var driver = new Stage3(digest, tree, Stage2Support.FEATURE, 2, dir);
            var failed = descriptor(driver.run(store, model));
            assertThat(failed.result().diagnostics()).isEqualTo(expected);
            Stage2Support.write(dir.resolve("m/src/main/java"), Map.of("p/Hidden.java", accessible));
            var repaired = headers.run(store, model);
            var route = Route.decode(store.get(LocalStore.routeKey(Stage2.projectKey(digest, model), "m", 0)), digest.width());
            assertThat(failed.proof().valid(tree, MachineLeaf.decode(store.get(MachineStore.leafKey(repaired.leaves().get("m/main"))), digest.width()),
                    route, null, store::get)).isFalse();
            assertThat(descriptor(driver.run(store, model)).result().attributed()).isTrue();
        }
    }
}
