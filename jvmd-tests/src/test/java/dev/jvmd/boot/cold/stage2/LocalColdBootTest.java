package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.Sum;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentList;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.Bind;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.ConsumerRecord;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.ReverseIndex;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.layer.machine.Stubs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The acceptance tests of stage 2 (7.3), run against an in-memory store: each invariant is a property the design makes an equality
 * or a sum. Fixtures are compiled here with {@code ToolProvider}, so what a fixture contains is visible in the test. MACHINE is one
 * boot of the fixture repository and the running JDK per digest; each test works on a copy of it. Every test runs with two
 * {@link Digest} implementations (invariant 13).
 */
@Tag("phase-3")
class LocalColdBootTest {
    static Path repository;

    @BeforeAll static void fixtures() throws Exception {
        repository = Files.createTempDirectory("stage2-repo");
        Stage2Support.jar(repository, Fixtures.LIB_AB_14, Fixtures.libAB(false), List.of());
        Stage2Support.jar(repository, Fixtures.LIB_AB_15, Fixtures.libAB(true), List.of());
        Stage2Support.jar(repository, Fixtures.LIB_X, Fixtures.libX(), List.of());
        Stage2Support.jar(repository, Fixtures.LIB_T, Fixtures.libT(), List.of());
        // Built with --release, as Maven builds: javac then records the release as the version of every system module a descriptor requires.
        var release = String.valueOf(Stage2Support.FEATURE);
        Stage2Support.jar(repository, "corp/rich/1/rich-1.jar", Fixtures.rich(), List.of("--release", release));
        // The same API compiled with -parameters: equal r, and a different k because the tail differs (3.5).
        Stage2Support.jar(repository, "corp/richp/1/richp-1.jar", Fixtures.rich(), List.of("--release", release, "-parameters"));
        // Two jars that declare one class with one constant each: a file that inlines the constant resolves to a different fact in each.
        Stage2Support.jar(repository, "corp/p1/1/p1-1.jar", Map.of("ns/K.java", "package ns; public class K { public static final int VALUE = 1; }"), List.of());
        Stage2Support.jar(repository, "corp/p2/1/p2-1.jar", Map.of("ns/K.java", "package ns; public class K { public static final int VALUE = 2; }"), List.of());
    }

    @AfterAll static void cleanup() throws Exception {
        Stage2Support.release();
        Stage2Support.delete(repository);
    }

    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    // ---- helpers -----------------------------------------------------------------------------------------------------------

    record Booted(InMemoryLocalStore store, Stage2.Result result, ProjectModel model, Identity projectKey) { }

    static Booted boot(Digest digest, InMemoryLocalStore machine, Path repository, byte[] model, int workers) throws Exception {
        var store = machine.copy();
        var parsed = ProjectModel.parse(model);
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, workers, repository, ClassFacts::of).run(store, parsed);
        return new Booted(store, result, parsed, Stage2.projectKey(digest, parsed));
    }

    static Booted boot(Digest digest, byte[] model, int workers) throws Exception {
        return boot(digest, Stage2Support.machine(digest, repository), repository, model, workers);
    }

    static MachineLeaf leaf(Digest digest, InMemoryLocalStore store, Identity k) { return MachineLeaf.decode(store.get(MachineStore.leafKey(k)), digest.width()); }

    static Identity jarLeaf(Digest digest, InMemoryLocalStore store, String location) {
        return MachineTree.decodePath(store.get(MachineStore.pathKey(location)), digest.width()).k();
    }

    static Function<Identity, byte[]> nodes(InMemoryLocalStore store) { return h -> store.get(MachineStore.nodeKey(h)); }

    static Identity treeSum(Digest digest, InMemoryLocalStore store, Identity hash) {
        return new ContentTree(digest).rangeSum(new Root(hash, Sum.forWidth(digest.width()).zero(), 0, 0), nodes(store), null, null);
    }

    static final NodeSink DISCARD = new NodeSink() { @Override public void write(Node node) { } @Override public void flush() { } };

    static Route route(Digest digest, Booted b, String module, int scope) {
        return Route.decode(b.store().get(LocalStore.routeKey(b.projectKey(), module, scope)), digest.width());
    }

    static final RouteEntry.Jar LIB_AB = new RouteEntry.Jar("org.example:libAB:1.4.0", Fixtures.LIB_AB_14, null);

    /** A jar entry with its MACHINE leaf. */
    static RouteEntry.Jar jar(Digest digest, InMemoryLocalStore store, String coordinate, String location) {
        return new RouteEntry.Jar(coordinate, location, jarLeaf(digest, store, location));
    }

    static dev.jvmd.index.layer.local.Bound bind(Digest digest, InMemoryLocalStore store, List<RouteEntry> entries, Bind.Provider built) {
        return Bind.bind(digest, entries, Bind.NONE, built, k -> leaf(digest, store, k), DISCARD);
    }

    static byte[] multiModel(Path project, boolean reversed) {
        var ab = Stage2Support.Dep.jar("org.example:libAB:1.4.0", Fixtures.LIB_AB_14);
        var x = Stage2Support.Dep.jar("org.example:libX:1.0", Fixtures.LIB_X);
        var t = Stage2Support.Dep.jar("org.example:libT:1.0", Fixtures.LIB_T);
        var common = Stage2Support.Dep.module("org.example:common:1.0", "common");
        var mods = new ArrayList<>(List.of(
                new Stage2Support.Mod("common", "org.example:common:1.0", List.of(ab)),
                new Stage2Support.Mod("server-a", "org.example:server-a:1.0", List.of(common, ab, x)).withTest(List.of(t)),
                new Stage2Support.Mod("server-b", "org.example:server-b:1.0", List.of(x, ab, common)),
                new Stage2Support.Mod("tool", "org.example:tool:1.0", List.of())));
        if (reversed) Collections.reverse(mods);
        return Stage2Support.model(project, mods.toArray(Stage2Support.Mod[]::new));
    }

    static Path multiProject() throws IOException {
        var project = Files.createTempDirectory("stage2-project");
        Stage2Support.write(project, Fixtures.multi());
        return project;
    }

    // ---- 7.3.1 -------------------------------------------------------------------------------------------------------------

    /**
     * Source equals class. Compile the fixture with javac, index the class files with stage 1 and the sources with Φ_src: equal
     * {@code r}, equal {@code O} sums, equal {@code N} and {@code E} roots. {@code k} may differ and is not compared: it covers
     * the tail (parameter names, type annotations), which depends on how the jar was compiled and is not a resolution fact (C.7).
     */
    @ParameterizedTest @MethodSource("digests")
    void invariant1_sourceEqualsClass(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-project");
        try {
            var files = new LinkedHashMap<String, String>();
            for (var e : Fixtures.rich().entrySet()) files.put("app/src/main/java/" + e.getKey(), e.getValue());
            Stage2Support.write(project, files);
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("app", "corp:app:1", List.of()).withOptions("--release", String.valueOf(Stage2Support.FEATURE))), 4);
            var store = booted.store();
            assertThat(booted.result().faults()).as("faults").isEmpty();

            var source = leaf(digest, store, booted.result().leaves().get("app/main"));
            for (var location : List.of("corp/rich/1/rich-1.jar", "corp/richp/1/richp-1.jar")) {
                var jar = leaf(digest, store, jarLeaf(digest, store, location));
                assertThat(source.factCount()).as("facts vs " + location).isEqualTo(jar.factCount()).isGreaterThan(100);
                assertThat(source.typeCount()).isEqualTo(jar.typeCount());
                assertThat(source.r()).as("r vs " + location).isEqualTo(jar.r());
                assertThat(source.oHash()).as("O root").isEqualTo(jar.oHash());
                assertThat(treeSum(digest, store, source.oHash())).as("O sum").isEqualTo(treeSum(digest, store, jar.oHash()));
                assertThat(source.nHash()).as("N root").isEqualTo(jar.nHash());
                assertThat(source.eHash()).as("E root").isEqualTo(jar.eHash());
                assertThat(source.edgeCount()).isEqualTo(jar.edgeCount());
            }
        } finally { Stage2Support.delete(project); }
    }

    // ---- 7.3.2 -------------------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariant2_routeIdentitiesCompose(Digest digest) throws Exception {
        var project = multiProject();
        try {
            var booted = boot(digest, multiModel(project, false), 4);
            var store = booted.store();
            var sums = Sum.forWidth(digest.width());
            var built = new HashMap<String, Identity>();
            for (var m : booted.model().modules()) built.put(m.coordinate(), booted.result().leaves().get(m.name() + "/main"));
            for (var module : booted.model().modules()) {
                for (int scope : new int[] {0, 1}) {
                    var stored = route(digest, booted, module.name(), scope);
                    var bound = Bind.bind(digest, stored.entries(), Bind.NONE, built::get, k -> leaf(digest, store, k), DISCARD);
                    var r = sums.zero();
                    var elements = new ArrayList<Entry>();
                    for (var k : bound.sequence()) {
                        var l = leaf(digest, store, k);
                        r = sums.add(r, l.r());
                        elements.add(new Entry(k.bytes(), Entry.NONE, l.r()));
                    }
                    // The leaf sets, recomputed here from the coordinates alone: a leaf bound through a sibling module is sibling, any other external.
                    var ext = new java.util.TreeSet<Identity>();
                    var sib = new java.util.TreeSet<Identity>();
                    for (var binding : bound.bindings()) (built.containsKey(binding.entry().coordinate()) ? sib : ext).add(binding.k());
                    var fresh = new ContentList(digest).build(elements, DISCARD);
                    assertThat(stored.r()).as("R of %s/%d", module.name(), scope).isEqualTo(r);
                    assertThat(stored.leafSetExt()).as("leafSetExt of %s/%d", module.name(), scope).isEqualTo(distinctDigest(digest, ext));
                    assertThat(stored.leafSetSib()).as("leafSetSib of %s/%d", module.name(), scope).isEqualTo(distinctDigest(digest, sib));
                    assertThat(stored.routeHash()).isEqualTo(fresh.hash());
                    assertThat(bound.routeHash()).isEqualTo(stored.routeHash());
                }
            }
        } finally { Stage2Support.delete(project); }
    }

    /** {@code Digest} of the sorted distinct keys, concatenated: how a leaf set is named. */
    static Identity distinctDigest(Digest digest, java.util.SortedSet<Identity> leaves) {
        var hasher = digest.hasher();
        for (var k : leaves) hasher.update(k.view(), 0, k.view().length);
        return hasher.finish();
    }

    // ---- 7.3.3 -------------------------------------------------------------------------------------------------------------

    /** Shuffled modules and another worker count give the same records; the model's own bytes (and so its hash) are the only thing that differs. */
    @ParameterizedTest @MethodSource("digests")
    void invariant3_theRootDoesNotDependOnModuleOrderOrWorkerCount(Digest digest) throws Exception {
        var project = multiProject();
        try {
            var model = multiModel(project, false);
            var reference = boot(digest, model, 1);
            for (int workers : new int[] {2, 8}) {
                var other = boot(digest, model, workers);
                assertThat(other.store().get(LocalStore.localRootKey(other.projectKey()))).as("LROOT with %d workers", workers)
                        .isEqualTo(reference.store().get(LocalStore.localRootKey(reference.projectKey())));
                assertSameRecords(other.store(), reference.store(), null);
            }
            var shuffled = boot(digest, multiModel(project, true), 4);
            var a = LocalRoot.decode(digest, reference.store().get(LocalStore.localRootKey(reference.projectKey())));
            var b = LocalRoot.decode(digest, shuffled.store().get(LocalStore.localRootKey(shuffled.projectKey())));
            assertThat(b.local()).isEqualTo(a.local());
            assertThat(b.machineRoot()).isEqualTo(a.machineRoot());
            assertThat(b.modelHash()).as("the model bytes differ, so their hash does").isNotEqualTo(a.modelHash());
            assertSameRecords(shuffled.store(), reference.store(), LocalStore.localRootKey(reference.projectKey()));
        } finally { Stage2Support.delete(project); }
    }

    static void assertSameRecords(InMemoryLocalStore actual, InMemoryLocalStore expected, byte[] except) {
        var a = actual.snapshot();
        var e = expected.snapshot();
        if (except != null) { a.remove(except); e.remove(except); }
        assertThat(a.keySet()).as("record keys").containsExactlyElementsOf(e.keySet());
        for (var entry : e.entrySet()) assertThat(a.get(entry.getKey())).isEqualTo(entry.getValue());
    }

    // ---- 7.3.4 -------------------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariant4_routesAreOrderSensitiveAndTheDisjointIndexIsNot(Digest digest) throws Exception {
        var store = Stage2Support.machine(digest, repository);
        var ab = jar(digest, store, "org.example:libAB:1.4.0", Fixtures.LIB_AB_14);
        var x = jar(digest, store, "org.example:libX:1.0", Fixtures.LIB_X);
        var rich = jar(digest, store, "corp:rich:1", "corp/rich/1/rich-1.jar");
        var richp = jar(digest, store, "corp:richp:1", "corp/richp/1/richp-1.jar");
        assertThat(rich.defaultK()).as("same API, different tail: different k").isNotEqualTo(richp.defaultK());
        assertThat(leaf(digest, store, rich.defaultK()).r()).isEqualTo(leaf(digest, store, richp.defaultK()).r());

        var one = bind(digest, store, List.of(ab, x, rich), Bind.NONE);
        var two = bind(digest, store, List.of(x, ab, rich), Bind.NONE);
        assertThat(two.routeHash()).as("a permutation changes routeHash").isNotEqualTo(one.routeHash());
        assertThat(two.r()).as("and leaves R").isEqualTo(one.r());
        assertThat(two.leafSetExt()).as("and leafSetExt").isEqualTo(one.leafSetExt());
        assertThat(two.leafSetSib()).as("and leafSetSib").isEqualTo(one.leafSetSib());

        var swapped = bind(digest, store, List.of(ab, x, richp), Bind.NONE);
        assertThat(swapped.r()).as("an API-identical leaf leaves R").isEqualTo(one.r());
        assertThat(swapped.leafSetExt()).as("and changes leafSetExt").isNotEqualTo(one.leafSetExt());
        assertThat(swapped.routeHash()).isNotEqualTo(one.routeHash());

        var tree = new ContentTree(digest);
        var before = DefinerIndex.disjoint(digest, tree, fold(digest, store, one.external(), null), nodes(store), DISCARD);
        var after = DefinerIndex.disjoint(digest, tree, fold(digest, store, swapped.external(), null), nodes(store), DISCARD);
        assertThat(after.sum()).as("the disjoint index resolves every name identically").isEqualTo(before.sum());
        assertThat(after.hash()).as("though it stores another k").isNotEqualTo(before.hash());
    }

    /** The definer state of a leaf set, folded from {@code base} (or nothing) over the stage 1 leaves of {@code store}. */
    static DefinerIndex.State fold(Digest digest, InMemoryLocalStore store, List<Identity> leaves, DefinerIndex.State base) {
        return fold(digest, leaf -> leaf(digest, store, leaf), nodes(store), leaves, base);
    }

    static DefinerIndex.State fold(Digest digest, Function<Identity, MachineLeaf> leafOf, Function<Identity, byte[]> reader, List<Identity> leaves, DefinerIndex.State base) {
        return DefinerIndex.fold(new ContentTree(digest), leaves.stream().distinct().sorted().toList(), base, leafOf, reader);
    }

    // ---- 7.3.5 -------------------------------------------------------------------------------------------------------------

    /** A route that differs by one element shares every level-0 chunk but one, because boundaries are a function of element content. */
    @ParameterizedTest @MethodSource("digests")
    void invariant5_routesDifferingByOneEntryShareAllButOneChunk(Digest digest) {
        var list = new ContentList(digest);
        int n = 400, at = 200;
        var ids = new ArrayList<Identity>();
        for (int i = 0; i < n; i++) ids.add(digest.hash(("leaf" + i).getBytes()));
        // The replaced element must not end a chunk before or after the replacement, or the chunks around it could re-cut.
        Identity replacement = null;
        for (int salt = 0; replacement == null; salt++) {
            var candidate = digest.hash(("swap" + salt).getBytes());
            if ((dev.jvmd.core.hash.Hash64.of(candidate.view()) & (ContentTree.B - 1)) != 0) replacement = candidate;
        }
        while ((dev.jvmd.core.hash.Hash64.of(ids.get(at).view()) & (ContentTree.B - 1)) == 0) at++;
        var first = level0(digest, list, ids, -1, null);
        var second = level0(digest, list, ids, at, replacement);
        assertThat(first).hasSizeGreaterThan(4);
        var common = new java.util.HashSet<>(first);
        common.retainAll(second);
        assertThat(second).hasSameSizeAs(first);
        assertThat(common).as("level-0 chunks shared").hasSize(first.size() - 1);
    }

    private static List<Identity> level0(Digest digest, ContentList list, List<Identity> ids, int replaceAt, Identity replacement) {
        var out = new ArrayList<Identity>();
        var sink = new NodeSink() {
            @Override public void write(Node node) { if (node.level() == 0) out.add(node.hash()); }
            @Override public void flush() { }
        };
        var builder = list.builder(sink);
        var sums = Sum.forWidth(digest.width());
        for (int i = 0; i < ids.size(); i++) {
            var id = i == replaceAt ? replacement : ids.get(i);
            builder.add(id.view(), digest.hash(id.view(), new byte[] {1}));
        }
        builder.finish();
        return out;
    }

    // ---- 7.3.6 -------------------------------------------------------------------------------------------------------------

    /** Diff finds exactly the changed members and types, reading O(d * depth) nodes; a list diff reads only the differing chunk. */
    @ParameterizedTest @MethodSource("digests")
    void invariant6_diffIsExact(Digest digest) throws Exception {
        var tree = new ContentTree(digest);
        // Two versions of a large generated "library": three members of three classes differ.
        var a = new TreeMapSink();
        var b = new TreeMapSink();
        var entriesA = new ArrayList<Entry>();
        var entriesB = new ArrayList<Entry>();
        for (int i = 0; i < 2000; i++) {
            var key = ("c" + String.format("%05d", i)).getBytes();
            var e = new Entry(key, Entry.NONE, digest.hash(key, new byte[] {1}));
            entriesA.add(e);
            if (i == 17) entriesB.add(new Entry(key, Entry.NONE, digest.hash(key, new byte[] {2}))); // changed
            else if (i == 900) continue;                                                                // removed
            else entriesB.add(e);
            if (i == 1500) entriesB.add(new Entry("c01500x".getBytes(), Entry.NONE, digest.hash("added".getBytes())));  // added
        }
        var rootA = tree.build(entriesA, a);
        var rootB = tree.build(entriesB, b);
        var all = new HashMap<Identity, byte[]>(a.nodes);
        all.putAll(b.nodes);
        var reads = new AtomicInteger();
        Function<Identity, byte[]> reader = h -> { reads.incrementAndGet(); return all.get(h); };
        var diff = Diff.trees(digest, rootA, rootB, reader);
        var removed = diff.removed().stream().map(e -> new String(e.key())).sorted().toList();
        var added = diff.added().stream().map(e -> new String(e.key())).sorted().toList();
        assertThat(removed).containsExactly("c00017", "c00900");
        assertThat(added).containsExactly("c00017", "c01500x");
        int d = 4, depth = Math.max(rootA.level(), rootB.level()) + 1;
        assertThat(reads.get()).as("nodes read").isLessThanOrEqualTo(2 * d * depth);
        assertThat(Diff.trees(digest, rootA, rootA, reader).isEmpty()).isTrue();

        // Two lists that differ in one element: the chunks that match are never read.
        var listA = new TreeMapSink();
        var listB = new TreeMapSink();
        var elementsA = new ArrayList<Entry>();
        var elementsB = new ArrayList<Entry>();
        for (int i = 0; i < 600; i++) {
            var id = digest.hash(("e" + i).getBytes());
            elementsA.add(new Entry(id.bytes(), Entry.NONE, digest.hash(id.view(), new byte[] {1})));
            elementsB.add(i == 300 ? new Entry(digest.hash("other".getBytes()).bytes(), Entry.NONE, digest.hash("other".getBytes(), new byte[] {1})) : elementsA.get(i));
        }
        var lists = new ContentList(digest);
        var lrA = lists.build(elementsA, listA);
        var lrB = lists.build(elementsB, listB);
        var listNodes = new HashMap<Identity, byte[]>(listA.nodes);
        listNodes.putAll(listB.nodes);
        var level0Reads = new AtomicInteger();
        var total = new AtomicInteger();
        var listDiff = Diff.lists(digest, lrA, lrB, h -> { total.incrementAndGet(); var bytes = listNodes.get(h); if (Node.level(bytes) == 0) level0Reads.incrementAndGet(); return bytes; });
        assertThat(listDiff.removed()).hasSize(1);
        assertThat(listDiff.added()).hasSize(1);
        assertThat(listDiff.removed().get(0).position()).isEqualTo(300);
        assertThat(listDiff.added().get(0).position()).isEqualTo(300);
        assertThat(level0Reads.get()).as("level-0 chunks read: one on each side").isEqualTo(2);
    }

    /** A sink that keeps what it is given, by hash. */
    static final class TreeMapSink implements NodeSink {
        final Map<Identity, byte[]> nodes = new HashMap<>();
        @Override public void write(Node node) { nodes.put(node.hash(), node.bytes()); }
        @Override public void flush() { }
    }

    /** The same on real leaves: libAB 1.4 against 1.5, with the count of nodes read. */
    @ParameterizedTest @MethodSource("digests")
    void invariant6_diffOfTwoLeavesNamesExactlyTheChangedMembersAndTypes(Digest digest) {
        var store = Stage2Support.machine(digest, repository);
        var v14 = leaf(digest, store, jarLeaf(digest, store, Fixtures.LIB_AB_14));
        var v15 = leaf(digest, store, jarLeaf(digest, store, Fixtures.LIB_AB_15));
        var reads = new AtomicInteger();
        Function<Identity, byte[]> reader = h -> { reads.incrementAndGet(); return nodes(store).apply(h); };
        var types = Diff.trees(digest, new Root(v14.oHash(), v14.r(), v14.typeCount(), v14.oLevel()), new Root(v15.oHash(), v15.r(), v15.typeCount(), v15.oLevel()), reader);
        assertThat(types.removed().stream().map(e -> zstr(e.key())).toList()).containsExactly("ab/Api");
        assertThat(types.added().stream().map(e -> zstr(e.key())).toList()).containsExactly("ab/Api", "ab/Extra");
    }

    private static String zstr(byte[] key) { return new String(key, 0, key.length - 1, java.nio.charset.StandardCharsets.UTF_8); }

    // ---- 7.3.7 -------------------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariant7_theDefinerIndexIsSplitAndIsAFold(Digest digest) throws Exception {
        var store = Stage2Support.machine(digest, repository);
        var ab = jarLeaf(digest, store, Fixtures.LIB_AB_14);
        var x = jarLeaf(digest, store, Fixtures.LIB_X);
        var t = jarLeaf(digest, store, Fixtures.LIB_T);
        var tree = new ContentTree(digest);
        Function<Identity, MachineLeaf> leafOf = k -> leaf(digest, store, k);
        var written = new TreeMapSink(); // the index nodes, so the conflict tables can be read back
        Function<Identity, byte[]> reader = h -> written.nodes.containsKey(h) ? written.nodes.get(h) : nodes(store).apply(h);
        var none = fold(digest, leafOf, reader, List.of(), null);

        // Two external leaves that both declare ab.Util: it is in neither disjoint index's single-definer set, and is in the conflict table with the first leaf in route order.
        var both = fold(digest, leafOf, reader, List.of(ab, x), null);
        var abFirst = DefinerIndex.conflicts(digest, tree, both, none, List.of(ab, x), written);
        var xFirst = DefinerIndex.conflicts(digest, tree, both, none, List.of(x, ab), written);
        var conflictsOne = entries(digest, abFirst, reader);
        var conflictsTwo = entries(digest, xFirst, reader);
        assertThat(conflictsOne).hasSize(1);
        assertThat(zstr(conflictsOne.get(0).key())).isEqualTo("ab/Util");
        assertThat(firstDefiner(conflictsOne.get(0), digest.width())).isEqualTo(ab);
        assertThat(firstDefiner(conflictsTwo.get(0), digest.width())).isEqualTo(x);
        var disjointOne = DefinerIndex.disjoint(digest, tree, fold(digest, leafOf, reader, List.of(ab, x), null), reader, written);
        var disjointTwo = DefinerIndex.disjoint(digest, tree, fold(digest, leafOf, reader, List.of(x, ab), null), reader, written);
        assertThat(disjointTwo).as("the disjoint part is byte-identical for a permuted route").isEqualTo(disjointOne);
        assertThat(entries(digest, disjointOne, reader).stream().map(e -> zstr(e.key()))).as("shadowed types are not in it").doesNotContain("ab/Util").contains("x/Thing", "ab/Api");

        // The same type split across the two parts (a sibling module that shadows a jar's class) is a conflict too: the parts meet only there.
        var external = fold(digest, leafOf, reader, List.of(ab), null);
        var sibling = fold(digest, leafOf, reader, List.of(x), null);
        var split = entries(digest, DefinerIndex.conflicts(digest, tree, external, sibling, List.of(ab, x), written), reader);
        assertThat(split).hasSize(1);
        assertThat(zstr(split.get(0).key())).isEqualTo("ab/Util");
        assertThat(firstDefiner(split.get(0), digest.width())).isEqualTo(ab);
        assertThat(firstDefiner(entries(digest, DefinerIndex.conflicts(digest, tree, external, sibling, List.of(x, ab), written), reader).get(0), digest.width())).isEqualTo(x);
        assertThat(new DefinerIndex.Resolver(external, sibling, List.of(x, ab)).oSum("ab/Util")).isNotNull();

        // From a base by difference, adding and removing leaves, equals from nothing; a leaf listed twice is one leaf. The base has its tree,
        // so the new disjoint tree is an edit of it (ContentTree.apply) and must be the tree a build over all its types gives.
        both.disjoint(DefinerIndex.disjoint(digest, tree, both, reader, written));
        var grown = fold(digest, leafOf, reader, List.of(x, ab, t, ab), both);
        var scratch = fold(digest, leafOf, reader, List.of(x, ab, t), null);
        var grownTree = DefinerIndex.disjoint(digest, tree, grown, reader, written);
        assertThat(grownTree).isEqualTo(DefinerIndex.disjoint(digest, tree, scratch, reader, written));
        assertThat(DefinerIndex.conflicts(digest, tree, grown, none, List.of(x, ab, t), written)).isEqualTo(DefinerIndex.conflicts(digest, tree, scratch, none, List.of(x, ab, t), written));
        grown.disjoint(grownTree);
        var shrunk = fold(digest, leafOf, reader, List.of(ab), grown);
        var alone = fold(digest, leafOf, reader, List.of(ab), null);
        assertThat(DefinerIndex.disjoint(digest, tree, shrunk, reader, written)).as("removing leaves, by edit").isEqualTo(DefinerIndex.disjoint(digest, tree, alone, reader, written));
        assertThat(entries(digest, DefinerIndex.conflicts(digest, tree, shrunk, none, List.of(ab), written), reader)).as("removing x leaves ab as the only definer: no conflict left").isEmpty();
        assertThat(entries(digest, DefinerIndex.conflicts(digest, tree, alone, none, List.of(ab, ab), written), reader)).as("a leaf is never in conflict with itself").isEmpty();
    }

    private static List<Entry> entries(Digest digest, Root root, Function<Identity, byte[]> reader) {
        var out = new ArrayList<Entry>();
        new ContentTree(digest).forEach(root.hash(), reader, out::add);
        return out;
    }

    private static Identity firstDefiner(Entry entry, int width) { return new dev.jvmd.core.tree.Codec.Reader(entry.value()).id(width); }

    // ---- 7.3.8 -------------------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariant8_fileSumsPartitionTheLeaf(Digest digest) throws Exception {
        var project = multiProject();
        try {
            var booted = boot(digest, multiModel(project, false), 4);
            var sums = Sum.forWidth(digest.width());
            var totals = new HashMap<String, Identity>();
            for (var path : Fixtures.multi().keySet()) {
                var row = FileRow.decode(path, booted.store().get(LocalStore.fileKey(booted.projectKey(), path)), digest.width());
                var module = path.substring(0, path.indexOf('/'));
                var scope = path.contains("/src/test/") ? "test" : "main";
                totals.merge(module + "/" + scope, row.sum(), sums::add);
            }
            for (var e : booted.result().leaves().entrySet()) {
                var expected = totals.getOrDefault(e.getKey(), sums.zero());
                assertThat(leaf(digest, booted.store(), e.getValue()).r()).as("r of %s", e.getKey()).isEqualTo(expected);
            }
        } finally { Stage2Support.delete(project); }
    }

    // ---- 7.3.9 -------------------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariant9_faultsAreAtTheRightGrain(Digest digest) throws Exception {
        // One unresolvable import in a file of twenty classes: one class's header faults, the other nineteen stay.
        var project = Files.createTempDirectory("stage2-faults");
        try {
            var many = new StringBuilder("package f; import missing.Foo;\n");
            for (int i = 0; i < 20; i++) many.append(i == 7 ? "class K7 extends Foo { public int n; }\n" : "class K" + i + " { public int n; public int m() { return 1; } }\n");
            Stage2Support.write(project, Map.of(
                    "m/src/main/java/f/Many.java", many.toString(),
                    "m/src/main/java/f/Fine.java", "package f; public class Fine { public String s; }",
                    "m/src/main/java/f/Broken.java", "package f; public class Broken { public int ( }"));
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("m", "corp:m:1", List.of())), 2);
            assertThat(booted.store().hasLocalRoot(booted.projectKey())).as("a broken file does not cost the project its root").isTrue();
            var store = booted.store();
            var rows = new HashMap<String, FileRow>();
            for (var name : List.of("Many", "Fine", "Broken")) {
                var path = "m/src/main/java/f/" + name + ".java";
                rows.put(name, FileRow.decode(path, store.get(LocalStore.fileKey(booted.projectKey(), path)), digest.width()));
            }
            assertThat(rows.get("Broken").faults()).as("an unparsable file is one fault covering the file").hasSize(1);
            assertThat(rows.get("Broken").faults().get(0).m()).isEmpty();
            assertThat(rows.get("Broken").typeKeys()).isEmpty();
            assertThat(rows.get("Many").typeKeys()).as("nineteen classes remain").hasSize(19).doesNotContain("f/K7");
            assertThat(rows.get("Many").faults()).as("the faulted declaration is listed by key").hasSize(1);
            assertThat(rows.get("Many").faults().get(0).reason()).contains("Foo");
            assertThat(rows.get("Fine").faults()).isEmpty();
            assertThat(rows.get("Fine").typeKeys()).containsExactly("f/Fine");
            var leaf = leaf(digest, store, booted.result().leaves().get("m/main"));
            assertThat(leaf.typeCount()).isEqualTo(20); // 19 of Many and Fine
            assertThat(booted.result().faults()).hasSize(2);
        } finally { Stage2Support.delete(project); }

        // A member whose header does not resolve is a fault of that member alone.
        var project2 = Files.createTempDirectory("stage2-faults");
        try {
            Stage2Support.write(project2, Map.of("m/src/main/java/g/G.java", "package g; import missing.Foo; public class G { public int ok; public Foo bad; public Foo make() { return null; } public void fine(int x) { } }"));
            var booted = boot(digest, Stage2Support.model(project2, new Stage2Support.Mod("m", "corp:m:1", List.of())), 2);
            var row = FileRow.decode("m/src/main/java/g/G.java", booted.store().get(LocalStore.fileKey(booted.projectKey(), "m/src/main/java/g/G.java")), digest.width());
            assertThat(row.faults()).hasSize(2);
            assertThat(row.typeKeys()).containsExactly("g/G");
            assertThat(leaf(digest, booted.store(), booted.result().leaves().get("m/main")).factCount()).as("type, ok, fine and the default constructor").isEqualTo(4);
        } finally { Stage2Support.delete(project2); }

        // A module cycle is the model's error: no root, one message naming the cycle.
        var project3 = Files.createTempDirectory("stage2-faults");
        try {
            var a = Stage2Support.Dep.module("corp:a:1", "a");
            var b = Stage2Support.Dep.module("corp:b:1", "b");
            var model = Stage2Support.model(project3, new Stage2Support.Mod("a", "corp:a:1", List.of(b)), new Stage2Support.Mod("b", "corp:b:1", List.of(a)));
            var store = Stage2Support.machine(digest, repository).copy();
            var parsed = ProjectModel.parse(model);
            assertThatThrownBy(() -> new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, repository, ClassFacts::of).run(store, parsed))
                    .isInstanceOf(ProjectModel.Fault.class).hasMessageContaining("cycle").hasMessageContaining("a -> b -> a");
            assertThat(store.hasLocalRoot(Stage2.projectKey(digest, parsed))).isFalse();
        } finally { Stage2Support.delete(project3); }
    }

    // ---- 7.3.10 ------------------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariant10_substitutionIsANoOpAtEqualR(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-project");
        try {
            var files = new LinkedHashMap<String, String>();
            for (var e : Fixtures.rich().entrySet()) files.put("rich/src/main/java/" + e.getKey(), e.getValue());
            Stage2Support.write(project, files);
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("rich", "corp:rich:1", List.of()).withOptions("--release", String.valueOf(Stage2Support.FEATURE))), 2);
            var store = booted.store();
            var source = booted.result().leaves().get("rich/main");
            var entry = jar(digest, store, "corp:rich:1", "corp/rich/1/rich-1.jar");
            var asJar = bind(digest, store, List.of(entry), Bind.NONE);
            var asSource = bind(digest, store, List.of(entry), coordinate -> coordinate.equals("corp:rich:1") ? source : null);
            assertThat(asSource.sequence()).as("bind yields another k").isNotEqualTo(asJar.sequence());
            assertThat(asSource.r()).as("and the same R").isEqualTo(asJar.r());
            var a = leaf(digest, store, asJar.sequence().get(0));
            var b = leaf(digest, store, asSource.sequence().get(0));
            var diff = Diff.trees(digest, new Root(a.oHash(), a.r(), a.typeCount(), a.oLevel()), new Root(b.oHash(), b.r(), b.typeCount(), b.oLevel()), nodes(store));
            assertThat(diff.isEmpty()).as("diff(O) is empty").isTrue();
        } finally { Stage2Support.delete(project); }
    }

    // ---- 7.3.11 ------------------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariant11_noLocalReadsBeforeTheRoot(Digest digest) throws Exception {
        var project = multiProject();
        try {
            var booted = boot(digest, multiModel(project, false), 4);
            // MACHINE (P, L, N, ROOT) and the shared derivable records (DD, DS, S, ST: another project may have written them) may be read; nothing of the project's own.
            assertThat(booted.store().readsBeforeRoot()).isNotEmpty().doesNotContainAnyElementsOf(List.of("MOD", "RT", "F", "DC", "C", "X", "RS", "LROOT"));
            assertThat(new java.util.HashSet<>(booted.store().readsBeforeRoot())).isSubsetOf("P", "L", "N", "ROOT", "DD", "DS", "S", "ST");
            var events = booted.store().events();
            assertThat(events.indexOf("sync")).as("sync once, then the root").isLessThan(events.indexOf("putLocalRoot"));
            assertThat(events.stream().filter("sync"::equals).count()).isEqualTo(1);
        } finally { Stage2Support.delete(project); }
    }

    // ---- 7.3.12 ------------------------------------------------------------------------------------------------------------

    /** Every N, L, S, DD and DC record a boot writes is the same bytes for the same fixture at another path. */
    @ParameterizedTest @MethodSource("digests")
    void invariant12_nothingPerPathInSharedRecords(Digest digest) throws Exception {
        var machine = Stage2Support.machine(digest, repository);
        var baseline = machine.snapshot().keySet();
        var a = multiProject();
        var b = Files.createTempDirectory("stage2-other-checkout-with-a-longer-name");
        try {
            Stage2Support.write(b, Fixtures.multi());
            var first = boot(digest, multiModel(a, false), 4);
            var second = boot(digest, multiModel(b, false), 4);
            var firstShared = shared(digest, first, baseline);
            var secondShared = shared(digest, second, baseline);
            assertThat(firstShared).isNotEmpty();
            assertThat(secondShared.keySet()).containsExactlyElementsOf(firstShared.keySet());
            for (var e : firstShared.entrySet()) assertThat(secondShared.get(e.getKey())).isEqualTo(e.getValue());
            assertThat(first.projectKey()).as("the project keys do differ").isNotEqualTo(second.projectKey());
            assertThat(firstShared.keySet().stream().anyMatch(k -> k[0] == 'S' && k[1] == '|')).as("a leaf's stub list was written").isTrue();
            assertThat(firstShared.keySet().stream().anyMatch(k -> k[0] == 'S' && k[1] == 'T')).as("per-type stubs were written").isTrue();
            assertThat(firstShared.keySet().stream().anyMatch(k -> k[0] == 'D' && k[1] == 'D')).isTrue();
            assertThat(firstShared.keySet().stream().anyMatch(k -> k[0] == 'D' && k[1] == 'S')).isTrue();
            assertThat(firstShared.keySet().stream().anyMatch(k -> k[0] == 'D' && k[1] == 'C')).isTrue();
        } finally { Stage2Support.delete(a); Stage2Support.delete(b); }
    }

    /**
     * The shared records this boot added to MACHINE's. The nodes of the LOCAL tree itself are left out: that tree is an index of the
     * project's own records, whose keys name the project and its files (B.3, 2.5), so it cannot be path-free; everything a module job
     * produces can, and is compared.
     */
    private static void collect(Digest digest, InMemoryLocalStore store, Identity hash, java.util.Set<Identity> out) {
        out.add(hash);
        var bytes = store.get(MachineStore.nodeKey(hash));
        if (Node.level(bytes) > 0) for (var child : Node.children(bytes, digest.width())) collect(digest, store, child.hash(), out);
    }

    private static Map<byte[], byte[]> shared(Digest digest, Booted booted, java.util.Set<byte[]> baseline) {
        var store = booted.store();
        var indexNodes = new java.util.HashSet<Identity>();
        collect(digest, store, LocalRoot.decode(digest, store.get(LocalStore.localRootKey(booted.projectKey()))).local().hash(), indexNodes);
        var out = new java.util.TreeMap<byte[], byte[]>(Arrays::compareUnsigned);
        for (var e : store.snapshot().entrySet()) {
            var k = e.getKey();
            if (baseline.contains(k)) continue;
            if (k[0] == 'N' && k.length == 33 && indexNodes.contains(Identity.of(Arrays.copyOfRange(k, 1, k.length)))) continue;
            boolean node = k[0] == 'N' && k.length == 33, leaf = k[0] == 'L' && k.length == 33;
            boolean tagged = k.length > 2 && k[1] == '|' && k[0] == 'S' || k.length > 3 && k[0] == 'S' && k[1] == 'T' && k[2] == '|' || k.length > 3 && k[2] == '|' && (k[0] == 'D' && (k[1] == 'D' || k[1] == 'C' || k[1] == 'S'));
            if (node || leaf || tagged) out.put(k, e.getValue());
        }
        return out;
    }

    // ---- 7.3.14 ------------------------------------------------------------------------------------------------------------

    /**
     * Stubs are sufficient: compile B against stubs synthesised from A's leaf and against A's real class files, and get the same
     * diagnostics and the same facts for B. A member of {@code res} that javac needs and a stub lacks fails this test.
     */
    @ParameterizedTest @MethodSource("digests")
    void invariant14_stubsAreSufficient(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-project");
        var work = Files.createTempDirectory("stage2-stubs");
        try {
            var files = new LinkedHashMap<String, String>();
            for (var e : Fixtures.rich().entrySet()) files.put("a/src/main/java/" + e.getKey(), e.getValue());
            Stage2Support.write(project, files);
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("a", "corp:a:1", List.of())), 2);
            var store = booted.store();
            var source = leaf(digest, store, booted.result().leaves().get("a/main"));

            // A's real class files, and the stubs of its leaf.
            var realDir = work.resolve("real");
            for (var e : Stage2Support.compile(work.resolve("a-build"), Fixtures.rich(), List.of(), List.of()).entrySet()) {
                var file = realDir.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue());
            }
            var stubDir = work.resolve("stubs");
            var kept = new HashMap<Identity, byte[]>();
            var lists = new HashMap<Identity, byte[]>();
            var cache = new Stubs.Cache() {
                @Override public byte[] list(Identity k) { return lists.get(k); }
                @Override public void putList(Identity k, byte[] value) { lists.put(k, value); }
                @Override public byte[] type(Identity stKey) { return kept.get(stKey); }
                @Override public void putType(Identity stKey, byte[] value) { kept.put(stKey, value); }
            };
            var synthesised = Stubs.stubs(digest, new ContentTree(digest), source, nodes(store), cache);
            for (var stub : synthesised) {
                var file = stubDir.resolve(stub.internalName() + ".class");
                Files.createDirectories(file.getParent());
                Files.write(file, stub.bytes());
            }
            // A second request is served from the cache, per type, and is the same bytes.
            var again = Stubs.stubs(digest, new ContentTree(digest), source, nodes(store), cache);
            assertThat(again).hasSameSizeAs(synthesised);
            for (int i = 0; i < again.size(); i++) assertThat(again.get(i).bytes()).isEqualTo(synthesised.get(i).bytes());
            assertThat(kept).as("one ST record per type").hasSize(synthesised.size());
            assertThat(Stubs.stubs(digest, new ContentTree(digest), source, nodes(store), Stubs.Cache.NONE)).hasSameSizeAs(synthesised);

            for (var user : List.of(Fixtures.userOfRich(), Fixtures.brokenUserOfRich())) {
                var real = compileAgainst(work.resolve("b-real"), user, realDir);
                var stubbed = compileAgainst(work.resolve("b-stub"), user, stubDir);
                if (user.containsKey("b/UseA.java")) assertThat(real.diagnostics).as("the good client compiles").isEmpty();
                else assertThat(real.diagnostics).as("the broken client has errors to compare").hasSizeGreaterThanOrEqualTo(6);
                assertThat(stubbed.diagnostics).as("errors against stubs").isEqualTo(real.diagnostics);
                assertThat(stubbed.facts.keySet()).containsExactlyElementsOf(real.facts.keySet());
                for (var e : real.facts.entrySet()) assertThat(stubbed.facts.get(e.getKey())).as("facts of " + e.getKey()).isEqualTo(e.getValue());
            }
        } finally { Stage2Support.delete(project); Stage2Support.delete(work); }
    }

    record Compiled(List<String> diagnostics, Map<String, List<String>> facts) { }

    private static Compiled compileAgainst(Path out, Map<String, String> sources, Path classpath) throws IOException {
        Stage2Support.delete(out);
        var src = Files.createDirectories(out.resolve("src"));
        var classes = Files.createDirectories(out.resolve("classes"));
        var options = List.of("-proc:none", "-Xlint:-options", "-d", classes.toString(), "--class-path", classpath.toString());
        var files = new ArrayList<Path>();
        for (var e : sources.entrySet()) {
            var file = src.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file);
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            compiler.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(files)).call();
        }
        var messages = new ArrayList<String>();
        // Errors only: a deprecation warning comes from the tail (the Deprecated attribute), which a stub deliberately leaves out.
        for (var d : diagnostics.getDiagnostics()) if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) messages.add(d.getKind() + " " + d.getCode() + " " + (d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName()) + ":" + d.getLineNumber() + " " + d.getMessage(null));
        var facts = new java.util.TreeMap<String, List<String>>();
        try (var walk = Files.walk(classes)) {
            for (var p : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                var name = classes.relativize(p).toString().replace('\\', '/');
                try {
                    var cf = ClassFacts.of(Sha256.INSTANCE, Files.readAllBytes(p), name.substring(0, name.length() - ".class".length()));
                    var list = new ArrayList<String>();
                    for (var f : cf.facts()) list.add(java.util.HexFormat.of().formatHex(f.m()) + "=" + java.util.HexFormat.of().formatHex(f.e()));
                    facts.put(name, list);
                } catch (ClassFacts.Fault fault) { facts.put(name, List.of("fault " + fault.getMessage())); }
            }
        }
        return new Compiled(messages, facts);
    }

    // ---- 7.3.15 ------------------------------------------------------------------------------------------------------------

    /**
     * Stable keys for proofs and results. Change a declaration in {@code common}: the {@code routeHash} of {@code server-a}'s route
     * changes, and every {@code C|} and {@code RS|} key of its files does not, because they are keyed by {@code (κ_file, leafSetExt)}.
     * Of the stubs, only the changed type's {@code ST|} record is new: every other type whose {@code oSum} did not change keeps its stub.
     */
    @ParameterizedTest @MethodSource("digests")
    void invariant15_keysOfProofsAndResultsAreStable(Digest digest) throws Exception {
        var project = multiProject();
        try {
            var before = boot(digest, multiModel(project, false), 4);
            var file = project.resolve("common/src/main/java/common/Base.java");
            Files.writeString(file, Files.readString(file).replace("public abstract int run();", "public abstract int run(); public int extra() { return 1; }"));
            var after = boot(digest, multiModel(project, false), 4);

            var was = route(digest, before, "server-a", 0);
            var is = route(digest, after, "server-a", 0);
            assertThat(is.routeHash()).as("the route of server-a changed").isNotEqualTo(was.routeHash());
            assertThat(is.leafSetExt()).as("its external part did not").isEqualTo(was.leafSetExt());
            for (var path : List.of("server-a/src/main/java/a/Server.java")) {
                var row = FileRow.decode(path, before.store().get(LocalStore.fileKey(before.projectKey(), path)), digest.width());
                var again = FileRow.decode(path, after.store().get(LocalStore.fileKey(after.projectKey(), path)), digest.width());
                assertThat(again.kappa()).isEqualTo(row.kappa());
                assertThat(LocalStore.consumerKey(again.kappa(), is.leafSetExt())).as("C| key").isEqualTo(LocalStore.consumerKey(row.kappa(), was.leafSetExt()));
                assertThat(LocalStore.resultKey(again.kappa(), is.leafSetExt())).as("RS| key").isEqualTo(LocalStore.resultKey(row.kappa(), was.leafSetExt()));
                assertThat(LocalStore.consumerKey(again.kappa(), is.routeHash())).as("keyed by the route it would have moved").isNotEqualTo(LocalStore.consumerKey(row.kappa(), was.routeHash()));
            }

            // Stubs: one type changed, so one ST| record is new. The stub of every unchanged type (server-a's, bound as a sibling by its test route) is the same record.
            var oldStubs = before.store().withPrefix("ST").keySet();
            var newStubs = new ArrayList<byte[]>();
            for (var key : after.store().withPrefix("ST").keySet()) if (!oldStubs.contains(key)) newStubs.add(key);
            assertThat(newStubs).as("ST| records new after editing one type").hasSize(1);
            assertThat(after.store().withPrefix("ST").keySet()).as("the same types have stubs, all but one under the same key").hasSameSizeAs(oldStubs);
        } finally { Stage2Support.delete(project); }
    }

    /** One file's bytes under two routes that resolve a name differently are two facts: nothing is shared by content alone. */
    @ParameterizedTest @MethodSource("digests")
    void theSameBytesResolveDifferentlyUnderDifferentRoutes(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-project");
        try {
            var text = "package use; public class User { public static final int N = ns.K.VALUE; }";
            Stage2Support.write(project, Map.of("m1/src/main/java/use/User.java", text, "m2/src/main/java/use/User.java", text));
            var p1 = Stage2Support.Dep.jar("corp:p1:1", "corp/p1/1/p1-1.jar");
            var p2 = Stage2Support.Dep.jar("corp:p2:1", "corp/p2/1/p2-1.jar");
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("m1", "corp:m1:1", List.of(p1)), new Stage2Support.Mod("m2", "corp:m2:1", List.of(p2))), 1);
            var m1 = leaf(digest, booted.store(), booted.result().leaves().get("m1/main"));
            var m2 = leaf(digest, booted.store(), booted.result().leaves().get("m2/main"));
            assertThat(m1.k()).as("the same bytes, resolved differently").isNotEqualTo(m2.k());
            assertThat(m1.r()).isNotEqualTo(m2.r());
            assertThat(booted.result().parsedFiles()).as("every file is taken through Φ_src: there is no memo").isEqualTo(2);
            var one = FileRow.decode("m1/src/main/java/use/User.java", booted.store().get(LocalStore.fileKey(booted.projectKey(), "m1/src/main/java/use/User.java")), digest.width());
            var two = FileRow.decode("m2/src/main/java/use/User.java", booted.store().get(LocalStore.fileKey(booted.projectKey(), "m2/src/main/java/use/User.java")), digest.width());
            assertThat(one.kappa()).as("one content key").isEqualTo(two.kappa());
            assertThat(one.sum()).isNotEqualTo(two.sum());
        } finally { Stage2Support.delete(project); }
    }

    /**
     * The header proof is reverse-indexed: {@code X|7|typeKey} names the files whose proof contains the type, under the external part of
     * their route, so that after an edit the files to re-check are one read away and not a scan of every file row.
     */
    @ParameterizedTest @MethodSource("digests")
    void theHeaderProofIsReverseIndexed(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-proof");
        try {
            Stage2Support.write(project, Map.of(
                    "lib/src/main/java/lib/T1.java", "package lib; public class T1 { public int a() { return 1; } }",
                    "app/src/main/java/app/UsesT1.java", "package app; public class UsesT1 { public lib.T1 field; }",
                    "app/src/main/java/app/UsesNothing.java", "package app; public class UsesNothing { public String s; }"));
            var model = Stage2Support.model(project, new Stage2Support.Mod("lib", "corp:lib:1", List.of()),
                    new Stage2Support.Mod("app", "corp:app:1", List.of(Stage2Support.Dep.module("corp:lib:1", "lib"))));
            var booted = boot(digest, model, 2);
            var ext = route(digest, booted, "app", 0).leafSetExt();
            var rows = new HashMap<String, FileRow>();
            for (var name : List.of("UsesT1", "UsesNothing")) rows.put(name, row(digest, booted, name));

            for (var row : rows.values()) {
                for (var proof : row.headerProof()) {
                    var key = new dev.jvmd.core.tree.Codec.Writer().zstr(proof.typeKey()).toBytes();
                    var stored = booted.store().get(LocalStore.reverseKey(ConsumerRecord.HEADER, key, booted.projectKey()));
                    assertThat(stored).as("X|7|" + proof.typeKey()).isNotNull();
                    assertThat(ReverseIndex.decode(stored, digest.width()).consumers()).contains(new ReverseIndex.Consumer(row.kappa(), ext));
                }
            }
            var t1 = ReverseIndex.decode(booted.store().get(LocalStore.reverseKey(ConsumerRecord.HEADER, new dev.jvmd.core.tree.Codec.Writer().zstr("lib/T1").toBytes(), booted.projectKey())), digest.width());
            assertThat(t1.consumers()).extracting(ReverseIndex.Consumer::kappa).containsExactly(rows.get("UsesT1").kappa());
            var string = ReverseIndex.decode(booted.store().get(LocalStore.reverseKey(ConsumerRecord.HEADER, new dev.jvmd.core.tree.Codec.Writer().zstr("java/lang/String").toBytes(), booted.projectKey())), digest.width());
            assertThat(string.consumers()).extracting(ReverseIndex.Consumer::kappa).contains(rows.get("UsesNothing").kappa()).doesNotContain(rows.get("UsesT1").kappa());
            assertThat(booted.store().withPrefix("X").keySet()).as("only header entries: kind 7").allMatch(k -> k[2] == 7);
        } finally { Stage2Support.delete(project); }
    }

    // ---- 7.3.16 ------------------------------------------------------------------------------------------------------------

    /** A jar MACHINE never saw is indexed on the spot: its leaf exists, the route defaults to it, no P record is written, no fault. */
    @ParameterizedTest @MethodSource("digests")
    void invariant16_aMissingJarIsIndexedNotFaulted(Digest digest) throws Exception {
        var repo = Files.createTempDirectory("stage2-late-repo");
        var project = Files.createTempDirectory("stage2-project");
        try {
            var machine = Stage2Support.jdkOnly(digest); // MACHINE is frozen here: the jar below is downloaded after it
            var late = Stage2Support.jar(repo, "corp/late/1/late-1.jar", Map.of("late/Later.java", "package late; public class Later { public int n; }"), List.of());
            assertThat(machine.get(MachineStore.pathKey("corp/late/1/late-1.jar"))).isNull();
            Stage2Support.write(project, Map.of("m/src/main/java/m/Uses.java", "package m; public class Uses { late.Later later; }"));
            var model = Stage2Support.model(project, new Stage2Support.Mod("m", "corp:m:1", List.of(Stage2Support.Dep.jar("corp:late:1", "corp/late/1/late-1.jar"))));
            var booted = boot(digest, machine, repo, model, 2);
            var store = booted.store();
            assertThat(booted.result().faults()).as("no fault from the missing P record").isEmpty();
            assertThat(booted.result().indexedOnTheSpot()).isEqualTo(1);
            var entry = (RouteEntry.Jar) route(digest, booted, "m", 0).entries().stream().filter(e -> e instanceof RouteEntry.Jar).findFirst().orElseThrow();
            assertThat(entry.defaultK()).as("the default binding is the k indexed on the spot").isNotNull();
            assertThat(store.get(MachineStore.leafKey(entry.defaultK()))).as("L|k").isNotNull();
            var l = leaf(digest, store, entry.defaultK());
            assertThat(store.get(MachineStore.nodeKey(l.oHash()))).as("its nodes").isNotNull();
            assertThat(store.get(MachineStore.pathKey("corp/late/1/late-1.jar"))).as("no P| was written").isNull();
            var leaf = leaf(digest, store, booted.result().leaves().get("m/main"));
            assertThat(leaf.factCount()).as("Uses resolved Later through the indexed jar").isEqualTo(3);
            assertThat(late).exists();
        } finally { Stage2Support.delete(repo); Stage2Support.delete(project); }
    }

    // ---- 7.3.17 ------------------------------------------------------------------------------------------------------------

    /**
     * An edit does not touch the external index: change a declaration in sibling module {@code common} and boot again. Every
     * {@code DD|} record is byte-identical; the {@code DS|} and {@code DC|} records of the routes that bind {@code common} changed;
     * a route that does not bind it has the same definer records, key for key and byte for byte.
     */
    @ParameterizedTest @MethodSource("digests")
    void invariant17_anEditDoesNotTouchTheExternalIndex(Digest digest) throws Exception {
        var project = multiProject();
        try {
            var before = boot(digest, multiModel(project, false), 4);
            var file = project.resolve("common/src/main/java/common/Base.java");
            Files.writeString(file, Files.readString(file).replace("public abstract int run();", "public abstract int run(); public int extra() { return 1; }"));
            var after = boot(digest, multiModel(project, false), 4);
            assertThat(after.result().leaves().get("common/main")).as("the edit changed common's API").isNotEqualTo(before.result().leaves().get("common/main"));

            var ddBefore = before.store().withPrefix("DD");
            var ddAfter = after.store().withPrefix("DD");
            assertThat(ddAfter.keySet()).as("the external indexes are the same set").containsExactlyElementsOf(ddBefore.keySet());
            for (var e : ddBefore.entrySet()) assertThat(ddAfter.get(e.getKey())).as("DD record").isEqualTo(e.getValue());

            for (var module : List.of("common", "server-a", "server-b", "tool")) {
                for (int scope : new int[] {0, 1}) {
                    var was = route(digest, before, module, scope);
                    var is = route(digest, after, module, scope);
                    boolean bindsCommon = module.startsWith("server-") || (module.equals("common") && scope == 1);
                    assertThat(is.leafSetExt()).as("leafSetExt of %s/%d", module, scope).isEqualTo(was.leafSetExt());
                    if (bindsCommon) {
                        assertThat(is.leafSetSib()).as("leafSetSib of %s/%d", module, scope).isNotEqualTo(was.leafSetSib());
                        assertThat(is.routeHash()).isNotEqualTo(was.routeHash());
                        assertThat(after.store().get(LocalStore.siblingKey(is.leafSetSib()))).isNotNull();
                        assertThat(after.store().get(LocalStore.conflictsKey(is.routeHash()))).isNotNull();
                        assertThat(before.store().get(LocalStore.siblingKey(is.leafSetSib()))).as("a new sibling record").isNull();
                        assertThat(before.store().get(LocalStore.conflictsKey(is.routeHash()))).as("a new conflict table").isNull();
                    } else {
                        assertThat(is.leafSetSib()).as("leafSetSib of %s/%d", module, scope).isEqualTo(was.leafSetSib());
                        assertThat(is.routeHash()).isEqualTo(was.routeHash());
                        assertThat(after.store().get(LocalStore.siblingKey(is.leafSetSib()))).isNotNull().isEqualTo(before.store().get(LocalStore.siblingKey(was.leafSetSib())));
                        assertThat(after.store().get(LocalStore.conflictsKey(is.routeHash()))).isNotNull().isEqualTo(before.store().get(LocalStore.conflictsKey(was.routeHash())));
                    }
                }
            }
        } finally { Stage2Support.delete(project); }
    }

    // ---- 7.3.18 ------------------------------------------------------------------------------------------------------------

    /**
     * The header proof decides re-compilation. Change a type in {@code lib} that no declaration header in {@code app} mentions: every
     * file's proof still holds against the new binding and {@code app}'s facts are byte-identical. Change a type that one header
     * mentions: exactly that file's proof fails.
     */
    @ParameterizedTest @MethodSource("digests")
    void invariant18_theHeaderProofDecidesRecompilation(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-proof");
        try {
            Stage2Support.write(project, Map.of(
                    "lib/src/main/java/lib/T1.java", "package lib; public class T1 { public int a() { return 1; } }",
                    "lib/src/main/java/lib/T2.java", "package lib; public class T2 { public int b() { return 1; } }",
                    "app/src/main/java/app/UsesT1.java", "package app; public class UsesT1 { public lib.T1 field; }",
                    "app/src/main/java/app/UsesNothing.java", "package app; public class UsesNothing { public String s; }",
                    "app/src/main/java/app/BodyOnly.java", "package app; public class BodyOnly { public int f() { return new lib.T2().b(); } }"));
            var model = Stage2Support.model(project, new Stage2Support.Mod("lib", "corp:lib:1", List.of()),
                    new Stage2Support.Mod("app", "corp:app:1", List.of(Stage2Support.Dep.module("corp:lib:1", "lib"))));
            var base = boot(digest, model, 2);
            var files = List.of("UsesT1", "UsesNothing", "BodyOnly");

            var proofs = proofsOf(digest, base, files);
            assertThat(proofs.get("UsesT1")).as("a header that names lib.T1").contains("lib/T1");
            assertThat(proofs.get("UsesT1")).contains("java/lang/Object");
            assertThat(proofs.get("UsesNothing")).as("a header that names only the JDK").contains("java/lang/String").doesNotContain("lib/T1", "lib/T2");
            assertThat(proofs.get("BodyOnly")).as("a body mention is not a header mention").doesNotContain("lib/T2", "lib/T1");
            for (var f : files) assertThat(holds(digest, base, base, f)).as("a proof holds against the binding it was made under: " + f).isTrue();

            // lib.T2 is mentioned by no header of app: nothing of app's proofs fails and its facts are byte-identical.
            var t2 = project.resolve("lib/src/main/java/lib/T2.java");
            Files.writeString(t2, "package lib; public class T2 { public int b() { return 1; } public int c() { return 2; } }");
            var changedT2 = boot(digest, model, 2);
            assertThat(changedT2.result().leaves().get("lib/main")).as("lib's API did change").isNotEqualTo(base.result().leaves().get("lib/main"));
            for (var f : files) assertThat(holds(digest, base, changedT2, f)).as("proof of " + f + " after changing T2").isTrue();
            assertThat(changedT2.result().leaves().get("app/main")).as("app's facts are byte-identical").isEqualTo(base.result().leaves().get("app/main"));

            // lib.T1 is mentioned by one header: exactly that file's proof fails.
            Files.writeString(t2, "package lib; public class T2 { public int b() { return 1; } }");
            Files.writeString(project.resolve("lib/src/main/java/lib/T1.java"), "package lib; public class T1 { public int a() { return 1; } public int z() { return 2; } }");
            var changedT1 = boot(digest, model, 2);
            for (var f : files) assertThat(holds(digest, base, changedT1, f)).as("proof of " + f + " after changing T1").isEqualTo(!f.equals("UsesT1"));
        } finally { Stage2Support.delete(project); }
    }

    // ---- E.4 ---------------------------------------------------------------------------------------------------------------

    /** A release javac cannot take is a substitution recorded in the descriptor, not a refusal of the boot. */
    @ParameterizedTest @MethodSource("digests")
    void aReleaseOutsideWhatJavacTakesIsClampedAndRecorded(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-release");
        try {
            Stage2Support.write(project, Map.of("old/src/main/java/o/Old.java", "package o; public class Old { public int n; }",
                    "future/src/main/java/f/Future.java", "package f; public class Future { public int n; }"));
            var roots = List.of("old/src/main/java");
            var old = new Stage2Support.Mod("old", "corp:old:1", 6, List.of(), roots, List.of(), List.of(), List.of());
            var future = new Stage2Support.Mod("future", "corp:future:1", 99, List.of(), List.of("future/src/main/java"), List.of(), List.of(), List.of());
            var booted = boot(digest, Stage2Support.model(project, old, future), 2);
            assertThat(booted.result().faults()).isEmpty();
            assertThat(leaf(digest, booted.store(), booted.result().leaves().get("old/main")).factCount()).isEqualTo(3);
            assertThat(dev.jvmd.index.layer.local.ModuleRecord.decode(booted.store().get(LocalStore.moduleKey(booted.projectKey(), "old"))).release()).isEqualTo(8);
            assertThat(dev.jvmd.index.layer.local.ModuleRecord.decode(booted.store().get(LocalStore.moduleKey(booted.projectKey(), "future"))).release()).isEqualTo(Stage2Support.FEATURE);
        } finally { Stage2Support.delete(project); }
    }

    /** Stubs are a pure function of {@code k}: a record an earlier boot wrote is read, not synthesised again, and is the same bytes. */
    @ParameterizedTest @MethodSource("digests")
    void aStubRecordAnEarlierBootWroteIsReadNotRewritten(Digest digest) throws Exception {
        var first = multiProject();
        var second = Files.createTempDirectory("stage2-second-checkout");
        try {
            Stage2Support.write(second, Fixtures.multi());
            var one = boot(digest, multiModel(first, false), 2);
            var stubs = new java.util.TreeMap<byte[], byte[]>(Arrays::compareUnsigned);
            stubs.putAll(one.store().withPrefix("S"));
            stubs.putAll(one.store().withPrefix("ST"));
            assertThat(stubs).isNotEmpty();
            // Boot the other checkout on top of the first one's store: the same sibling leaves, so the same stub records.
            var machine = one.store();
            var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 2, repository, ClassFacts::of).run(machine, ProjectModel.parse(multiModel(second, false)));
            assertThat(result.faults()).isEmpty();
            assertThat(machine.readsBeforeRoot()).contains("S", "ST");
            var after = new java.util.TreeMap<byte[], byte[]>(Arrays::compareUnsigned);
            after.putAll(machine.withPrefix("S"));
            after.putAll(machine.withPrefix("ST"));
            assertThat(after.keySet()).containsExactlyElementsOf(stubs.keySet());
            for (var e : stubs.entrySet()) assertThat(after.get(e.getKey())).isEqualTo(e.getValue());
        } finally { Stage2Support.delete(first); Stage2Support.delete(second); }
    }

    /** Two modules with one API build one leaf, whichever job gets there first, and the modules that depend on either bind it. */
    @ParameterizedTest @MethodSource("digests")
    void twoModulesWithTheSameApiShareOneLeaf(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-twins");
        try {
            var twin = "package t; public class Twin { public int n; }";
            Stage2Support.write(project, Map.of("a/src/main/java/t/Twin.java", twin, "b/src/main/java/t/Twin.java", twin,
                    "c/src/main/java/c/C.java", "package c; public class C { public t.Twin twin; }"));
            var a = Stage2Support.Dep.module("corp:a:1", "a");
            var b = Stage2Support.Dep.module("corp:b:1", "b");
            var model = Stage2Support.model(project, new Stage2Support.Mod("a", "corp:a:1", List.of()), new Stage2Support.Mod("b", "corp:b:1", List.of()),
                    new Stage2Support.Mod("c", "corp:c:1", List.of(a, b)), new Stage2Support.Mod("d", "corp:d:1", List.of(b, a)));
            for (int round = 0; round < 3; round++) {
                var booted = boot(digest, model, 8);
                assertThat(booted.result().faults()).isEmpty();
                assertThat(booted.result().leaves().get("a/main")).isEqualTo(booted.result().leaves().get("b/main"));
                assertThat(leaf(digest, booted.store(), booted.result().leaves().get("c/main")).factCount()).isEqualTo(3);
            }
        } finally { Stage2Support.delete(project); }
    }

    // ---- stub keys, X| keys, constants ---------------------------------------------------------------------------------------

    /** The stub of every type of a source leaf, as {@code internal name -> ST key}, taken through a cache that records what it is given. */
    private static Map<String, Identity> stubKeys(Digest digest, Booted booted, String leaf) {
        var store = booted.store();
        var lists = new HashMap<Identity, byte[]>();
        var cache = new Stubs.Cache() {
            @Override public byte[] list(Identity k) { return null; }
            @Override public void putList(Identity k, byte[] value) { lists.put(k, value); }
            @Override public byte[] type(Identity stKey) { return null; }
            @Override public void putType(Identity stKey, byte[] value) { }
        };
        var k = booted.result().leaves().get(leaf);
        Stubs.stubs(digest, new ContentTree(digest), leaf(digest, store, k), nodes(store), cache);
        var out = new HashMap<String, Identity>();
        for (var ref : Stubs.decodeList(lists.get(k), digest.width())) out.put(ref.internalName(), ref.stKey());
        return out;
    }

    /**
     * A method change in a member type changes the member's stub key and not its outer type's: the outer key names its members and their
     * flags, never their {@code oSum}. A change to a member's flags does change the outer key, because the outer stub's
     * {@code InnerClasses} entry carries them.
     */
    @ParameterizedTest @MethodSource("digests")
    void aMethodChangeInAMemberTypeDoesNotChangeTheOuterTypesStubKey(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-stubkey");
        try {
            var file = project.resolve("m/src/main/java/p/Outer.java");
            var model = Stage2Support.model(project, new Stage2Support.Mod("m", "corp:m:1", List.of()));
            Stage2Support.write(project, Map.of("m/src/main/java/p/Outer.java", "package p; public class Outer { public int o; public static class Inner { public int a() { return 1; } } }"));
            var base = stubKeys(digest, boot(digest, model, 1), "m/main");
            Files.writeString(file, "package p; public class Outer { public int o; public static class Inner { public int a() { return 1; } public int b() { return 2; } } }");
            var method = stubKeys(digest, boot(digest, model, 1), "m/main");
            assertThat(base).containsOnlyKeys("p/Outer", "p/Outer$Inner");
            assertThat(method.get("p/Outer$Inner")).as("Inner changed").isNotEqualTo(base.get("p/Outer$Inner"));
            assertThat(method.get("p/Outer")).as("Outer's stub key did not").isEqualTo(base.get("p/Outer"));

            Files.writeString(file, "package p; public class Outer { public int o; public static final class Inner { public int a() { return 1; } } }");
            var flags = stubKeys(digest, boot(digest, model, 1), "m/main");
            assertThat(flags.get("p/Outer")).as("a flag of the member is in the outer's InnerClasses entry").isNotEqualTo(base.get("p/Outer"));
        } finally { Stage2Support.delete(project); }
    }

    /**
     * {@code X|kind|key|projectKey}: two projects that name one type write disjoint keys, both lists are intact, and the prefix
     * {@code X|kind|key} is the cross-project read.
     */
    @ParameterizedTest @MethodSource("digests")
    void twoProjectsNamingOneTypeWriteDisjointReverseKeys(Digest digest) throws Exception {
        var first = Files.createTempDirectory("stage2-x-a");
        var second = Files.createTempDirectory("stage2-x-b");
        try {
            Stage2Support.write(first, Map.of("a/src/main/java/a/A.java", "package a; public class A { public String s; }"));
            Stage2Support.write(second, Map.of("b/src/main/java/b/B.java", "package b; public class B { public String t; }"));
            var one = boot(digest, Stage2Support.model(first, new Stage2Support.Mod("a", "corp:a:1", List.of())), 1);
            // The second project boots on the first one's store: the same machine, two LOCAL generations.
            var parsed = ProjectModel.parse(Stage2Support.model(second, new Stage2Support.Mod("b", "corp:b:1", List.of())));
            var store = one.store();
            new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, repository, ClassFacts::of).run(store, parsed);
            var otherKey = Stage2.projectKey(digest, parsed);

            var type = new dev.jvmd.core.tree.Codec.Writer().zstr("java/lang/String").toBytes();
            var prefix = LocalStore.reversePrefix(ConsumerRecord.HEADER, type);
            var found = new java.util.TreeMap<byte[], byte[]>(Arrays::compareUnsigned);
            for (var e : store.snapshot().entrySet()) if (e.getKey().length > prefix.length && Arrays.equals(Arrays.copyOf(e.getKey(), prefix.length), prefix)) found.put(e.getKey(), e.getValue());
            assertThat(found).as("one X|7|java/lang/String entry per project, under one prefix").hasSize(2);
            assertThat(found).containsKeys(LocalStore.reverseKey(ConsumerRecord.HEADER, type, one.projectKey()), LocalStore.reverseKey(ConsumerRecord.HEADER, type, otherKey));
            for (var key : found.keySet()) assertThat(key.length).as("the project key trails, fixed width").isEqualTo(prefix.length + digest.width());

            var rowA = FileRow.decode("a/src/main/java/a/A.java", store.get(LocalStore.fileKey(one.projectKey(), "a/src/main/java/a/A.java")), digest.width());
            var rowB = FileRow.decode("b/src/main/java/b/B.java", store.get(LocalStore.fileKey(otherKey, "b/src/main/java/b/B.java")), digest.width());
            var listA = ReverseIndex.decode(found.get(LocalStore.reverseKey(ConsumerRecord.HEADER, type, one.projectKey())), digest.width());
            var listB = ReverseIndex.decode(found.get(LocalStore.reverseKey(ConsumerRecord.HEADER, type, otherKey)), digest.width());
            assertThat(listA.consumers()).extracting(ReverseIndex.Consumer::kappa).containsExactly(rowA.kappa());
            assertThat(listB.consumers()).extracting(ReverseIndex.Consumer::kappa).containsExactly(rowB.kappa());
        } finally { Stage2Support.delete(first); Stage2Support.delete(second); }
    }

    /**
     * A constant initialiser is part of the proof. {@code static final int N = lib.K.VALUE} inlines {@code K}'s value into the file's
     * facts, but no {@code E} edge names {@code K}: stage 2 resolves it from the attributed initialiser (qualifier types, the owner of a
     * constant field, a static-imported constant) and the file's proof names {@code K}, indexed as kind 8. Change only {@code K.VALUE}:
     * the files that read it fail their proof and nothing else does.
     */
    @ParameterizedTest @MethodSource("digests")
    void aConstantInitialiserIsPartOfTheProof(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-constant");
        try {
            Stage2Support.write(project, Map.of(
                    "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 1; }",
                    "app/src/main/java/app/UsesConst.java", "package app; public class UsesConst { public static final int N = lib.K.VALUE; }",
                    "app/src/main/java/app/UsesImport.java", "package app; import static lib.K.VALUE; public class UsesImport { public static final int M = VALUE + 1; }",
                    "app/src/main/java/app/UsesNothing.java", "package app; public class UsesNothing { public String s; }"));
            var model = Stage2Support.model(project, new Stage2Support.Mod("lib", "corp:lib:1", List.of()),
                    new Stage2Support.Mod("app", "corp:app:1", List.of(Stage2Support.Dep.module("corp:lib:1", "lib"))));
            var base = boot(digest, model, 2);
            var files = List.of("UsesConst", "UsesImport", "UsesNothing");
            var proofs = proofsOf(digest, base, files);
            assertThat(proofs.get("UsesConst")).as("the qualifier's owner is in the proof").contains("lib/K");
            assertThat(proofs.get("UsesImport")).as("so is the owner of a static-imported constant").contains("lib/K");
            assertThat(proofs.get("UsesNothing")).doesNotContain("lib/K");

            var key = new dev.jvmd.core.tree.Codec.Writer().zstr("lib/K").toBytes();
            assertThat(base.store().get(LocalStore.reverseKey(ConsumerRecord.HEADER, key, base.projectKey()))).as("no header mentions K").isNull();
            var kind8 = ReverseIndex.decode(base.store().get(LocalStore.reverseKey(ConsumerRecord.CONSTANT, key, base.projectKey())), digest.width());
            assertThat(kind8.consumers()).extracting(ReverseIndex.Consumer::kappa).containsExactlyInAnyOrder(row(digest, base, "UsesConst").kappa(), row(digest, base, "UsesImport").kappa());

            Files.writeString(project.resolve("lib/src/main/java/lib/K.java"), "package lib; public class K { public static final int VALUE = 2; }");
            var changed = boot(digest, model, 2);
            assertThat(holds(digest, base, changed, "UsesConst")).as("the file that reads K.VALUE fails its proof").isFalse();
            assertThat(holds(digest, base, changed, "UsesImport")).isFalse();
            assertThat(holds(digest, base, changed, "UsesNothing")).as("nothing else does").isTrue();
            var before = FileRow.decode("x", base.store().get(LocalStore.fileKey(base.projectKey(), "app/src/main/java/app/UsesConst.java")), digest.width());
            assertThat(leaf(digest, changed.store(), changed.result().leaves().get("app/main")).r()).as("and the facts did change").isNotEqualTo(leaf(digest, base.store(), base.result().leaves().get("app/main")).r());
            assertThat(before.headerProof()).isNotEmpty();
        } finally { Stage2Support.delete(project); }
    }

    /**
     * Stubs emit {@code ConstantValue}: every constant field of a class file is a constant field of its stub, with the same value. (javac
     * inlines constants into a client, so a stub without them would compile a client differently; this is the test that says it does not.)
     */
    @ParameterizedTest @MethodSource("digests")
    void stubsEmitConstantValue(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-stubconst");
        try {
            var files = new LinkedHashMap<String, String>();
            for (var e : Fixtures.rich().entrySet()) files.put("a/src/main/java/" + e.getKey(), e.getValue());
            Stage2Support.write(project, files);
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("a", "corp:a:1", List.of()).withOptions("--release", String.valueOf(Stage2Support.FEATURE))), 1);
            var store = booted.store();
            var stubs = Stubs.stubs(digest, new ContentTree(digest), leaf(digest, store, booted.result().leaves().get("a/main")), nodes(store), Stubs.Cache.NONE);
            var work = Files.createTempDirectory("stage2-stubconst-work");
            try {
                var real = Stage2Support.compile(work, Fixtures.rich(), List.of(), List.of());
                var fromClass = new java.util.TreeMap<String, Object>();
                for (var e : real.entrySet()) {
                    if (e.getKey().equals("module-info.class")) continue;
                    var model = java.lang.classfile.ClassFile.of().parse(e.getValue());
                    for (var field : model.fields()) {
                        if ((field.flags().flagsMask() & java.lang.classfile.ClassFile.ACC_PRIVATE) != 0) continue;
                        var constant = field.findAttribute(java.lang.classfile.Attributes.constantValue());
                        if (constant.isPresent()) fromClass.put(model.thisClass().asInternalName() + "." + field.fieldName().stringValue(), constant.get().constant().constantValue());
                    }
                }
                var fromStub = new java.util.TreeMap<String, Object>();
                for (var stub : stubs) {
                    var model = java.lang.classfile.ClassFile.of().parse(stub.bytes());
                    for (var field : model.fields()) {
                        var constant = field.findAttribute(java.lang.classfile.Attributes.constantValue());
                        if (constant.isPresent()) fromStub.put(model.thisClass().asInternalName() + "." + field.fieldName().stringValue(), constant.get().constant().constantValue());
                    }
                }
                assertThat(fromClass).as("the fixture has constants of every kind").hasSizeGreaterThanOrEqualTo(9).containsKey("fx/Box.NAME").containsKey("fx/Box.BIG");
                assertThat(fromStub).isEqualTo(fromClass);
            } finally { Stage2Support.delete(work); }
        } finally { Stage2Support.delete(project); }
    }

    /**
     * The member part of a stub key is sorted by the unsigned bytes of {@code memberTypeKey}, not by {@code String.compareTo}: the two
     * differ for a supplementary character against a high BMP one (here U+FF21, UTF-8 {@code EF BC A1}, against U+1D400, UTF-8
     * {@code F0 9D 90 80}), and the key must be the same in every language and on every machine.
     */
    @ParameterizedTest @MethodSource("digests")
    void theMemberPartOfAStubKeyIsSortedByUnsignedBytes(Digest digest) {
        var bmp = "p/O$\uFF21";
        var supplementary = "p/O$\uD835\uDC00";
        assertThat(bmp.compareTo(supplementary)).as("String order puts the supplementary one first").isGreaterThan(0);
        var oSum = digest.hash("o".getBytes());
        var typeKey = new dev.jvmd.core.tree.Codec.Writer().zstr("p/O").toBytes();
        byte[] first = new dev.jvmd.core.tree.Codec.Writer().zstr(bmp).u16(9).toBytes();
        byte[] second = new dev.jvmd.core.tree.Codec.Writer().zstr(supplementary).u16(8).toBytes();
        var expected = digest.hash(typeKey, oSum.view(), first, second); // bytes order: EF.. before F0..
        var wrongOrder = digest.hash(typeKey, oSum.view(), second, first);
        var a = Stubs.stKey(digest, "p/O", oSum, List.of(new Stubs.Member(bmp, 9), new Stubs.Member(supplementary, 8)));
        var b = Stubs.stKey(digest, "p/O", oSum, List.of(new Stubs.Member(supplementary, 8), new Stubs.Member(bmp, 9)));
        assertThat(a).as("whatever order the members arrive in").isEqualTo(b);
        assertThat(a).isEqualTo(expected).isNotEqualTo(wrongOrder);
        assertThat(Stubs.stKey(digest, "p/O", oSum, List.of())).as("no members: exactly Digest(typeKey || oSum)").isEqualTo(digest.hash(typeKey, oSum.view()));
    }

    // ---- kind 8: everything a declaration resolved a name through outside bodies ------------------------------------------------

    /** Boots a project of modules {@code lib} and {@code app} (app depends on lib), applies one edit, boots again. */
    private record Edited(Booted before, Booted after) { }

    private static Edited edited(Digest digest, Map<String, String> files, String path, String replacement) throws Exception {
        var project = Files.createTempDirectory("stage2-kind8");
        try {
            Stage2Support.write(project, files);
            var model = Stage2Support.model(project, new Stage2Support.Mod("lib", "corp:lib:1", List.of()),
                    new Stage2Support.Mod("app", "corp:app:1", List.of(Stage2Support.Dep.module("corp:lib:1", "lib"))));
            var before = boot(digest, model, 2);
            Files.writeString(project.resolve(path), replacement);
            return new Edited(before, boot(digest, model, 2));
        } finally { Stage2Support.delete(project); }
    }

    private static void assertKind8(Digest digest, Edited e, String file, String type, boolean failsAfter) {
        assertThat(row(digest, e.before(), file).headerProof()).as("the proof of " + file + " names " + type).extracting(FileRow.Proof::typeKey).contains(type);
        var stored = e.before().store().get(LocalStore.reverseKey(ConsumerRecord.CONSTANT, new dev.jvmd.core.tree.Codec.Writer().zstr(type).toBytes(), e.before().projectKey()));
        assertThat(stored).as("X|8|" + type).isNotNull();
        assertThat(ReverseIndex.decode(stored, digest.width()).consumers()).extracting(ReverseIndex.Consumer::kappa).contains(row(digest, e.before(), file).kappa());
        assertThat(holds(digest, e.before(), e.after(), file)).as("proof of " + file + " after the change").isEqualTo(!failsAfter);
        assertThat(holds(digest, e.before(), e.after(), "UsesNothing")).as("nothing else fails").isTrue();
    }

    /** The edges of the consumer leaf: the same set of E edges before and after, because kind 8 never adds an edge. */
    private static void assertSameEdges(Digest digest, Edited e) {
        assertThat(leaf(digest, e.after().store(), e.after().result().leaves().get("app/main")).eHash()).as("the E root of app").isEqualTo(leaf(digest, e.before().store(), e.before().result().leaves().get("app/main")).eHash());
    }

    private static final Map<String, String> NOTHING = Map.of("app/src/main/java/app/UsesNothing.java", "package app; public class UsesNothing { public String s; }");

    private static Map<String, String> with(Map<String, String> a, Map<String, String> b) {
        var out = new LinkedHashMap<String, String>(a);
        out.putAll(b);
        return out;
    }

    /** (a) A final field whose initialiser does not fold today is still attributed, and becomes a constant tomorrow: the proof names its owner. */
    @ParameterizedTest @MethodSource("digests")
    void aFinalInitialiserThatDoesNotFoldYetIsInTheProof(Digest digest) throws Exception {
        var edit = edited(digest, with(NOTHING, Map.of(
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = compute(); static int compute() { return 1; } }",
                "app/src/main/java/app/UsesConst.java", "package app; public class UsesConst { public static final int N = lib.K.VALUE; }")),
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 1; static int compute() { return 1; } }");
        assertThat(leaf(digest, edit.before().store(), edit.before().result().leaves().get("app/main")).factCount()).isEqualTo(6); // 2 types, 2 constructors, N, s
        assertKind8(digest, edit, "UsesConst", "lib/K", true);
        assertSameEdges(digest, edit);
    }

    /** (b) An annotation element value: {@code @Foo(K.VALUE)} on a type. */
    @ParameterizedTest @MethodSource("digests")
    void anAnnotationElementValueIsInTheProof(Digest digest) throws Exception {
        var edit = edited(digest, with(NOTHING, Map.of(
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 1; }",
                "lib/src/main/java/lib/Foo.java", "package lib; public @interface Foo { int value(); }",
                "app/src/main/java/app/Annotated.java", "package app; @lib.Foo(lib.K.VALUE) public class Annotated { }")),
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 2; }");
        assertKind8(digest, edit, "Annotated", "lib/K", true);
        assertSameEdges(digest, edit);
    }

    /** (c) An enum constant as an annotation value, {@code @Foo(E.X)}; remove {@code X} from {@code E}. */
    @ParameterizedTest @MethodSource("digests")
    void anEnumConstantAsAnAnnotationValueIsInTheProof(Digest digest) throws Exception {
        var edit = edited(digest, with(NOTHING, Map.of(
                "lib/src/main/java/lib/E.java", "package lib; public enum E { X, Y }",
                "lib/src/main/java/lib/Foo.java", "package lib; public @interface Foo { E value(); }",
                "app/src/main/java/app/AnnotatedE.java", "package app; @lib.Foo(lib.E.X) public class AnnotatedE { }")),
                "lib/src/main/java/lib/E.java", "package lib; public enum E { Y }");
        assertKind8(digest, edit, "AnnotatedE", "lib/E", true);
        assertSameEdges(digest, edit);
    }

    /** A class literal as an annotation value, {@code @Foo(K.class)}, and a nested annotation, {@code @Outer(@Foo(K.VALUE))}. */
    @ParameterizedTest @MethodSource("digests")
    void aClassLiteralAndANestedAnnotationAreInTheProof(Digest digest) throws Exception {
        var lib = with(NOTHING, Map.of(
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 1; }",
                "lib/src/main/java/lib/Cls.java", "package lib; public @interface Cls { Class<?> value(); }",
                "lib/src/main/java/lib/Foo.java", "package lib; public @interface Foo { int value(); }",
                "lib/src/main/java/lib/Wrap.java", "package lib; public @interface Wrap { Foo value(); }",
                "app/src/main/java/app/UsesLiteral.java", "package app; @lib.Cls(lib.K.class) public class UsesLiteral { }",
                "app/src/main/java/app/UsesNested.java", "package app; @lib.Wrap(@lib.Foo(lib.K.VALUE)) public class UsesNested { }"));
        var edit = edited(digest, lib, "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 2; public int extra; }");
        assertKind8(digest, edit, "UsesLiteral", "lib/K", true);
        assertKind8(digest, edit, "UsesNested", "lib/K", true);
        assertSameEdges(digest, edit);
    }

    /** An annotation method's default, {@code int n() default K.VALUE}, is a declaration's name resolution too. */
    @ParameterizedTest @MethodSource("digests")
    void anAnnotationMethodDefaultIsInTheProof(Digest digest) throws Exception {
        var edit = edited(digest, with(NOTHING, Map.of(
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 1; }",
                "app/src/main/java/app/Cfg.java", "package app; public @interface Cfg { int n() default lib.K.VALUE; }")),
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 2; }");
        assertKind8(digest, edit, "Cfg", "lib/K", true);
        assertSameEdges(digest, edit);
    }

    /** Bodies are not declarations: a name resolved only inside a method, a lambda, an initialiser block or a non-final initialiser is not in the proof. */
    @ParameterizedTest @MethodSource("digests")
    void aNameResolvedOnlyInsideABodyIsNotInTheProof(Digest digest) throws Exception {
        var edit = edited(digest, with(NOTHING, Map.of(
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 1; }",
                "app/src/main/java/app/Bodies.java", "package app; public class Bodies { public int plain = lib.K.VALUE; static { int x = lib.K.VALUE; } public int f() { return lib.K.VALUE; } public Runnable r() { return () -> System.out.println(lib.K.VALUE); } }")),
                "lib/src/main/java/lib/K.java", "package lib; public class K { public static final int VALUE = 2; }");
        assertThat(row(digest, edit.before(), "Bodies").headerProof()).extracting(FileRow.Proof::typeKey).doesNotContain("lib/K");
        assertThat(holds(digest, edit.before(), edit.after(), "Bodies")).isTrue();
    }

    /**
     * The external definer index of one leaf set is folded once per boot, however many jobs want it at the same moment: twelve modules
     * whose routes share the JDK, on eight workers, fold it once; a second distinct external set (one jar more) is a second fold.
     */
    @ParameterizedTest @MethodSource("digests")
    void modulesSharingAnExternalLeafSetFoldItOnce(Digest digest) throws Exception {
        var project = Files.createTempDirectory("stage2-fold-once");
        try {
            var files = new LinkedHashMap<String, String>();
            var mods = new ArrayList<Stage2Support.Mod>();
            for (int i = 0; i < 12; i++) {
                files.put("m" + i + "/src/main/java/p" + i + "/C.java", "package p" + i + "; public class C { public int n; }");
                mods.add(new Stage2Support.Mod("m" + i, "corp:m" + i + ":1", List.of()));
            }
            Stage2Support.write(project, files);
            var shared = boot(digest, Stage2Support.model(project, mods.toArray(Stage2Support.Mod[]::new)), 8);
            assertThat(shared.result().timings().externalFolds()).as("one external leaf set, twelve modules, eight workers").isEqualTo(1);
            assertThat(shared.result().timings().siblingFolds()).as("sibling sets: the empty one, and one per test route's own leaf").isGreaterThanOrEqualTo(1);

            mods.set(0, new Stage2Support.Mod("m0", "corp:m0:1", List.of(Stage2Support.Dep.jar("org.example:libAB:1.4.0", Fixtures.LIB_AB_14))));
            var two = boot(digest, Stage2Support.model(project, mods.toArray(Stage2Support.Mod[]::new)), 8);
            assertThat(two.result().timings().externalFolds()).as("JDK, and JDK with one jar").isEqualTo(2);
            assertThat(two.result().distinctLeafSets()).isEqualTo(two.result().timings().externalFolds() + two.result().timings().siblingFolds());
        } finally { Stage2Support.delete(project); }
    }

    /** The type keys in each file of {@code app}'s header proof. */
    private static Map<String, List<String>> proofsOf(Digest digest, Booted booted, List<String> files) {
        var out = new HashMap<String, List<String>>();
        for (var f : files) out.put(f, row(digest, booted, f).headerProof().stream().map(FileRow.Proof::typeKey).toList());
        return out;
    }

    private static FileRow row(Digest digest, Booted booted, String file) {
        var path = "app/src/main/java/app/" + file + ".java";
        return FileRow.decode(path, booted.store().get(LocalStore.fileKey(booted.projectKey(), path)), digest.width());
    }

    /**
     * Whether every entry of {@code file}'s header proof in {@code old} still holds against the binding of {@code now}: each type is
     * resolved again, through the leaves of app's route in order, and its {@code oSum} compared. Done here with no help from stage 2.
     */
    private static boolean holds(Digest digest, Booted old, Booted now, String file) {
        var store = now.store();
        var built = new HashMap<String, Identity>();
        for (var m : now.model().modules()) built.put(m.coordinate(), now.result().leaves().get(m.name() + "/main"));
        var bound = Bind.bind(digest, route(digest, now, "app", 0).entries(), Bind.NONE, built::get, k -> leaf(digest, store, k), DISCARD);
        var leaves = new ArrayList<Identity>();
        leaves.add(now.result().leaves().get("app/main")); // the module's own types come first
        leaves.addAll(bound.sequence());
        var tree = new ContentTree(digest);
        for (var proof : row(digest, old, file).headerProof()) {
            var key = new dev.jvmd.core.tree.Codec.Writer().zstr(proof.typeKey()).toBytes();
            Identity current = null;
            for (var k : leaves) {
                var entry = tree.get(leaf(digest, store, k).oHash(), nodes(store), key);
                if (entry != null) { current = entry.h(); break; }
            }
            if (!proof.oSum().equals(current)) return false;
        }
        return true;
    }
}
