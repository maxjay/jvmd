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
}
