package dev.jvmd.tests.boot;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.Stubs;
import dev.jvmd.index.layer.local.DefinerIndex;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Appendix A: semantic identities bind keys and exclude data javac never reads. */
@Tag("phase-3")
class Layout4Test {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new TestDigests.Sha3()); }

    record Leaf(ContentTree tree, MachineLeaf value, Identity a, Root annotations, Root annotationEdges, Map<Identity, byte[]> nodes) { }

    private Leaf leaf(Digest digest, String fixture, String source) throws Exception {
        var classes = BootFixtures.compile(dir.resolve(fixture), Map.of("p/K.java", source), "-parameters");
        var facts = ClassFacts.of(digest, classes.get("p/K.class"), "p/K");
        var nodes = new HashMap<Identity, byte[]>();
        var tree = new ContentTree(digest);
        var builder = new LeafBuilder(tree, new NodeSink() {
            public void write(Node node) { nodes.put(node.hash(), node.bytes()); }
            public void flush() { }
        });
        facts.facts().forEach(builder::add);
        builder.edges(facts.edges());
        builder.seal();
        return new Leaf(tree, builder.build(), builder.a(), builder.annotations(), builder.annotationEdges(), nodes);
    }

    @ParameterizedTest @MethodSource("digests")
    void annotationAndParameterNamesDoNotChangeApi(Digest digest) throws Exception {
        String prefix = "package p; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @interface Mark {} ";
        var plain = leaf(digest, "plain", prefix + "public class K { public void f(int first) {} }");
        var annotated = leaf(digest, "annotated", prefix + "public class K { @Mark public void f(int renamed) {} }");
        assertThat(annotated.value.k()).isEqualTo(plain.value.k());
        assertThat(annotated.value.encode()).isEqualTo(plain.value.encode());
        assertThat(annotated.a).isNotEqualTo(plain.a);
        assertThat(annotated.annotationEdges.count()).isEqualTo(1);
        assertThat(plain.annotationEdges.count()).isZero();
        var a = Stubs.stubs(digest, plain.tree, plain.value, plain.nodes::get, Stubs.Cache.NONE);
        var b = Stubs.stubs(digest, annotated.tree, annotated.value, annotated.nodes::get, Stubs.Cache.NONE);
        assertThat(b.getFirst().bytes()).isEqualTo(a.getFirst().bytes());
        // Independent reader instrumentation: resolution consumers may only open T and O, never annotation-tree nodes.
        var resolutionNodes = new HashSet<Identity>();
        reach(annotated.value.k(), annotated, resolutionNodes);
        reach(annotated.value.oHash(), annotated, resolutionNodes);
        assertThat(resolutionNodes).doesNotContain(annotated.annotations.hash(), annotated.annotationEdges.hash());
        var reads = new HashSet<Identity>();
        Stubs.stubs(digest, annotated.tree, annotated.value, h -> {
            reads.add(h);
            assertThat(resolutionNodes).as("stub read %s belongs to T/O", h).contains(h);
            return annotated.nodes.get(h);
        }, Stubs.Cache.NONE);
        assertThat(reads).contains(annotated.value.k(), annotated.value.oHash());
        var ownerNodes = new HashSet<Identity>();
        reach(annotated.value.oHash(), annotated, ownerNodes);
        DefinerIndex.fold(annotated.tree, List.of(annotated.value.k()), null, h -> annotated.value, h -> {
            assertThat(ownerNodes).as("definer fold reads only O").contains(h);
            return annotated.nodes.get(h);
        });
    }

    private void reach(Identity hash, Leaf leaf, java.util.Set<Identity> into) {
        if (!into.add(hash)) return;
        var bytes = leaf.nodes.get(hash);
        if (Node.level(bytes) > 0) for (var child : Node.children(bytes, leaf.tree.digest().width())) reach(child.hash(), leaf, into);
    }

    @ParameterizedTest @MethodSource("digests")
    void exchangingFieldValuesChangesRangeAndOwnerSums(Digest digest) throws Exception {
        var before = leaf(digest, "before", "package p; public class K { public static final int x=1, y=2; }");
        var after = leaf(digest, "after", "package p; public class K { public static final int x=2, y=1; }");
        assertThat(after.value.r()).isNotEqualTo(before.value.r());
        var key = Keys.ownerKey("p/K");
        assertThat(after.tree.get(after.value.oHash(), after.nodes::get, key).h())
                .isNotEqualTo(before.tree.get(before.value.oHash(), before.nodes::get, key).h());
    }

    @ParameterizedTest @MethodSource("digests")
    void warningAnnotationsChangeResAndSurviveStubs(Digest digest) throws Exception {
        var plain = leaf(digest, "plain-warning", "package p; public class K { public static <T> void f(T... xs) {} }");
        var marked = leaf(digest, "marked-warning", "package p; public class K { @Deprecated(forRemoval=true) @SafeVarargs public static <T> void f(T... xs) {} }");
        assertThat(marked.value.r()).isNotEqualTo(plain.value.r());
        var bytes = Stubs.stubs(digest, marked.tree, marked.value, marked.nodes::get, Stubs.Cache.NONE).getFirst().bytes();
        var stub = ClassFacts.of(digest, bytes, "p/K");
        var expected = marked.tree.get(marked.value.k(), marked.nodes::get, Keys.memberKey("p/K", Keys.METHOD, "f", "([Ljava/lang/Object;)V"));
        assertThat(stub.facts().stream().filter(f -> Keys.Member.decode(f.m()).name().equals("f")).findFirst().orElseThrow().h()).isEqualTo(expected.h());
    }

    @ParameterizedTest @MethodSource("digests")
    void annotationValueEditWritesOnlyItsTreePath(Digest digest) throws Exception {
        String prefix = "package p; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @interface Mark { int value(); } public class K {";
        var source = new StringBuilder(prefix);
        for (int i = 0; i < 2048; i++) source.append("@Mark(0) public void f").append(i).append("() {} ");
        source.append('}');
        var before = leaf(digest, "annotation-before", source.toString());
        var after = leaf(digest, "annotation-after", source.toString().replace("@Mark(0) public void f1024()", "@Mark(1) public void f1024()"));
        assertThat(after.value.encode()).isEqualTo(before.value.encode());
        assertThat(after.a).isNotEqualTo(before.a);
        assertThat(after.annotationEdges).isEqualTo(before.annotationEdges);
        long newNodes = after.nodes.keySet().stream().filter(h -> !before.nodes.containsKey(h)).count();
        // Level-zero boundaries depend on keys; interior boundaries use child hashes and may split the affected chunk.
        assertThat(newNodes).as("one annotation edit, %s levels", after.annotations.level()).isBetween(1L, 2L * after.annotations.level() + 1);
        after.tree.verify(after.annotations, after.nodes::get);
        before.tree.verify(before.annotations, before.nodes::get);
    }

    @ParameterizedTest @MethodSource("digests")
    void editsConserveFactAnnotationOwnerAndDefinerSums(Digest digest) throws Exception {
        String prefix = "package p; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @interface Mark { int value(); } ";
        var before = leaf(digest, "conservation-before", prefix + "public class K { @Mark(1) public static final int x=1; @Mark(2) public int remove; public void f(int first) {} }");
        var after = leaf(digest, "conservation-after", prefix + "public class K { @Mark(3) public static final int x=2; @Mark(4) public long added; public void f(int last) {} }");
        var nodes = new HashMap<>(before.nodes);
        nodes.putAll(after.nodes);
        var tree = before.tree;
        var sink = new NodeSink() {
            public void write(Node node) { nodes.put(node.hash(), node.bytes()); }
            public void flush() { }
        };
        var t0 = new Root(before.value.k(), before.value.r(), before.value.factCount(), Node.level(nodes.get(before.value.k())));
        var t1 = new Root(after.value.k(), after.value.r(), after.value.factCount(), Node.level(nodes.get(after.value.k())));
        var o0 = new Root(before.value.oHash(), before.value.r(), before.value.typeCount(), before.value.oLevel());
        var o1 = new Root(after.value.oHash(), after.value.r(), after.value.typeCount(), after.value.oLevel());
        for (var pair : List.of(new Root[] {t0, t1}, new Root[] {before.annotations, after.annotations},
                new Root[] {o0, o1}, new Root[] {before.annotationEdges, after.annotationEdges}))
            conserved(tree, pair[0], pair[1], nodes, sink);

        var rDelta = tree.sums().subtract(after.value.r(), before.value.r());
        var owners = Diff.trees(digest, o0, o1, nodes::get);
        assertThat(delta(tree, owners)).as("delta r equals the sum of changed owner deltas").isEqualTo(rDelta);
        var rangeDelta = tree.sums().zero();
        for (var name : List.of("x", "remove", "added")) {
            var key = Keys.groupKey("p/K", Keys.FIELD, name);
            rangeDelta = tree.sums().add(rangeDelta, tree.sums().subtract(
                    tree.rangeSum(after.value.k(), nodes::get, key), tree.rangeSum(before.value.k(), nodes::get, key)));
        }
        assertThat(rangeDelta).as("delta oSum equals the sum of touched member ranges").isEqualTo(rDelta);

        var leaves = Map.of(before.value.k(), before.value, after.value.k(), after.value);
        var state = DefinerIndex.fold(tree, List.of(before.value.k()), null, leaves::get, nodes::get);
        var dd0 = DefinerIndex.disjoint(digest, tree, state, nodes::get, sink);
        state.disjoint(dd0);
        var edited = DefinerIndex.fold(tree, List.of(after.value.k()), state, leaves::get, nodes::get);
        var dd1 = DefinerIndex.disjoint(digest, tree, edited, nodes::get, sink);
        conserved(tree, dd0, dd1, nodes, sink);
    }

    private void conserved(ContentTree tree, Root before, Root after, Map<Identity, byte[]> nodes, NodeSink sink) {
        var diff = Diff.trees(tree.digest(), before, after, nodes::get);
        assertThat(after.sum()).isEqualTo(tree.sums().add(before.sum(), delta(tree, diff)));
        var applied = tree.apply(before, diff.removed().stream().map(Entry::key).toList(), diff.added(), nodes::get, sink);
        assertThat(applied).isEqualTo(after);
        tree.verify(applied, nodes::get);
    }

    private Identity delta(ContentTree tree, Diff.Result diff) {
        var sum = tree.sums().zero();
        for (var e : diff.removed()) sum = tree.sums().subtract(sum, e.h());
        for (var e : diff.added()) sum = tree.sums().add(sum, e.h());
        return sum;
    }

    @ParameterizedTest @MethodSource("digests")
    void emptyTailsHaveNoAnnotationEntries(Digest digest) throws Exception {
        var plain = leaf(digest, "empty-tail", "package p; public class K { public int x; public void f() {} }");
        assertThat(plain.annotations.count()).isZero();
        assertThat(plain.annotationEdges.count()).isZero();
    }
}
