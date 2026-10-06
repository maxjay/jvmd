package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.ModuleDescriptor;
import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
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
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Descriptor scheduling and identity consume just its two keyed semantic entries and moduleEmitFormat. */
@Tag("phase-3")
class ModuleDerivationTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    record State(SourceLeaf source, Root t, Root a, Stage3.Result bodies) { }

    @ParameterizedTest @MethodSource("digests")
    void exactModuleKeysScheduleWithoutBodyOrReverseProofs(Digest digest) throws Exception {
        var source = new TreeMap<>(Map.of("module-info.java", "@Deprecated(since=\"one\") module example { exports p; }",
                "p/A.java", "package p; @Deprecated(since=\"one\") public class A {public int x(){return 1;}}"));
        var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var first = run(digest, tree, store, model, source);
        source.put("module-info.java", source.get("module-info.java").replace("one", "two"));
        var annotation = run(digest, tree, store, model, source);
        assertThat(annotation.t()).isEqualTo(first.t());
        assertThat(annotation.a()).isNotEqualTo(first.a());
        assertChanged(tree, store, first, annotation, true);
        assertThat(descriptor(annotation).aci()).isNotEqualTo(descriptor(first).aci());
        assertThat(annotation.bodies().scopes().get("m/main").descriptorEmissions()).isEqualTo(1);
        source.put("module-info.java", source.get("module-info.java").replace("since=\"two\"", "since=\"two\",forRemoval=true"));
        var warning = run(digest, tree, store, model, source);
        assertThat(warning.t()).isNotEqualTo(annotation.t());
        assertChanged(tree, store, annotation, warning, true);
        source.put("p/A.java", source.get("p/A.java").replace("one", "two").replace("return 1;", "return 2;")
                .replace("public int x()", "public int added(){return 3;} public int x()"));
        var unrelated = run(digest, tree, store, model, source);
        assertThat(unrelated.t()).isNotEqualTo(warning.t());
        assertThat(unrelated.a()).isNotEqualTo(warning.a());
        assertChanged(tree, store, warning, unrelated, false);
        assertThat(descriptor(unrelated).aci()).isEqualTo(descriptor(warning).aci());
        assertThat(unrelated.bodies().scopes().get("m/main").descriptorEmissions()).isZero();
        var previous = unrelated;
        for (String module : List.of("module example { exports p; }", "@Deprecated module example { exports p; }",
                "@Deprecated module example { exports p; requires java.logging; }")) {
            source.put("module-info.java", module);
            var next = run(digest, tree, store, model, source);
            assertChanged(tree, store, previous, next, true);
            assertThat(descriptor(next).aci()).isNotEqualTo(descriptor(previous).aci());
            assertThat(next.bodies().scopes().get("m/main").descriptorEmissions()).isEqualTo(1);
            previous = next;
        }
        int start = store.events().size();
        var options = new ModuleDescriptor.Options(Stage2Support.FEATURE + 44, true);
        var direct = ModuleDescriptor.derive(tree, store, previous.source(), options);
        assertThat(direct.emitted()).isFalse();
        assertThat(direct.computed().aci()).isEqualTo(descriptor(previous).aci());
        assertThat(store.events().subList(start, store.events().size()))
                .allMatch(event -> List.of("read:N", "read:AL", "read:RS", "read:CF").contains(event));
        var t = tree.get(previous.source().k(), id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"));
        var a = tree.get(previous.a().hash(), id -> store.get(MachineStore.nodeKey(id)), Keys.typeKey("module-info"));
        assertThat(direct.computed().aci()).isEqualTo(ModuleDescriptor.identity(digest, t.h(), a.h(), options));
        assertThat(ModuleDescriptor.identity(digest, t.h(), a.h(), new ModuleDescriptor.Options(options.majorVersion(), false)))
                .isNotEqualTo(direct.computed().aci());
        assertThat(ModuleDescriptor.identity(digest, t.h(), a.h(), new ModuleDescriptor.Options(options.majorVersion() - 1, true)))
                .isNotEqualTo(direct.computed().aci());
        assertThat(ModuleDescriptor.Options.of(List.of("-Xlint:all", "-parameters", "-g:source", "-Werror"))).isEqualTo(options);
    }

    private State run(Digest digest, ContentTree tree, InMemoryLocalStore store, ProjectModel model, Map<String, String> sources) throws Exception {
        Stage2Support.write(dir.resolve("m/src/main/java"), sources);
        assertThat(new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of).run(store, model).faults()).isEmpty();
        var project = Stage2.projectKey(digest, model);
        var source = SourceLeaf.decode(store.get(LocalStore.sourceLeafKey(project, "m", 0)), digest.width());
        var leaf = MachineLeaf.decode(store.get(MachineStore.leafKey(source.k())), digest.width());
        var t = new Root(source.k(), leaf.r(), leaf.factCount(), Node.level(store.get(MachineStore.nodeKey(source.k()))));
        var a = AnnotationLeaf.decode(store.get(MachineStore.annotationLeafKey(source.a())), digest.width()).annotations();
        var bodies = new Stage3(digest, tree, Stage2Support.FEATURE, 1, dir).run(store, model);
        var result = new State(source, t, a, bodies);
        var expected = Stage2Support.compile(dir.resolve("native-" + descriptor(result).aci()), sources, List.of(), List.of());
        var bytes = store.get(LocalStore.classFileKey(descriptor(result).result().classFiles().getFirst().contentHash()));
        assertThat(bytes).isEqualTo(expected.get("module-info.class"));
        assertThat(descriptor(result).proof()).isNull();
        assertThat(tree.get(bodies.bodies().bodiesRoot(), id -> store.get(MachineStore.nodeKey(id)),
                LocalStore.proofKey(project, "m", 0, "m/src/main/java/module-info.java"))).isNull();
        var reverse = ReverseIndex.bodyConsumers(digest, store, new ReverseIndex.Dependency(ReverseIndex.T, "module-info", Keys.TYPE, ""));
        assertThat(reverse).isEmpty();
        return result;
    }

    private static void assertChanged(ContentTree tree, InMemoryLocalStore store, State before, State after, boolean changed) {
        var t = Diff.trees(tree.digest(), before.t(), after.t(), id -> store.get(MachineStore.nodeKey(id)));
        var a = Diff.trees(tree.digest(), before.a(), after.a(), id -> store.get(MachineStore.nodeKey(id)));
        assertThat(ModuleDescriptor.changed(t, a)).isEqualTo(changed);
    }

    private static dev.jvmd.boot.cold.stage3.Attribute.Computed descriptor(State state) {
        return state.bodies().scopes().get("m/main").files().stream().filter(f -> f.path().endsWith("/module-info.java"))
                .findFirst().orElseThrow().computed();
    }
}
