package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.charset.StandardCharsets;
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

@Tag("phase-3")
class PersistedLeavesTest {
    @TempDir Path root;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    @ParameterizedTest @MethodSource("digests")
    void anUnreferencedModulesLeavesAndAnnotationsAreReachableFromItsCommittedRoot(Digest digest) throws Exception {
        Stage2Support.write(root, Map.of("alone/src/main/java/p/K.java", """
                package p; import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @interface Mark {}
                @Mark public class K { @Mark public int x; }
                """));
        var model = ProjectModel.parse(Stage2Support.model(root, new Stage2Support.Mod("alone", "g:alone:1", List.of())));
        var store = Stage2Support.jdkOnly(digest).copy();
        var tree = new ContentTree(digest);
        new Stage2(digest, tree, Stage2Support.FEATURE, 2, root, ClassFacts::of).run(store, model);
        // No Result/Built state: only the committed root and records survive.
        var project = Stage2.projectKey(digest, model);
        var committed = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(project)));
        for (int scope : new int[] {LocalStore.MAIN, LocalStore.TEST}) {
            byte[] key = new Codec.Writer().raw("SL|".getBytes(StandardCharsets.US_ASCII)).id(project)
                    .raw("|alone|".getBytes(StandardCharsets.US_ASCII)).u8(scope).toBytes();
            var encoded = store.get(key);
            assertThat(encoded).as("source leaf binding for scope %s", scope).isNotNull();
            assertThat(tree.get(committed.local().hash(), h -> store.get(MachineStore.nodeKey(h)), key).h()).isEqualTo(digest.hash(encoded));
            var binding = new Codec.Reader(encoded);
            var k = binding.id(digest.width());
            var a = binding.id(digest.width());
            var leaf = MachineLeaf.decode(store.get(MachineStore.leafKey(k)), digest.width());
            assertThat(leaf.k()).isEqualTo(k);
            byte[] annotationKey = new Codec.Writer().raw("AL|".getBytes(StandardCharsets.US_ASCII)).id(a).toBytes();
            var annotationBytes = store.get(annotationKey);
            assertThat(annotationBytes).as("annotation roots").isNotNull();
            var in = new Codec.Reader(annotationBytes);
            var annotations = new Root(in.id(digest.width()), in.id(digest.width()), in.count(), in.u8());
            var edges = new Root(in.id(digest.width()), in.id(digest.width()), in.count(), in.u8());
            assertThat(digest.hash(annotations.hash().view(), edges.hash().view())).isEqualTo(a);
            tree.verify(annotations, h -> store.get(MachineStore.nodeKey(h)));
            tree.verify(edges, h -> store.get(MachineStore.nodeKey(h)));
            assertThat(annotations.count()).as("Mark's Retention and the two Mark uses").isEqualTo(scope == LocalStore.MAIN ? 3 : 0);
        }
    }
}
