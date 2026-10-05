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
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
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
        Stage2Support.jar(repository, "corp/rich/1/rich-1.jar", Fixtures.rich(), List.of());
        // The same API compiled with -parameters: equal r, and a different k because the tail differs (3.5).
        Stage2Support.jar(repository, "corp/richp/1/richp-1.jar", Fixtures.rich(), List.of("-parameters"));
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
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("app", "corp:app:1", List.of())), 4);
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
                    var sorted = new ArrayList<>(bound.sequence());
                    sorted.sort((a, b) -> Arrays.compareUnsigned(a.view(), b.view()));
                    var hasher = digest.hasher();
                    for (var k : sorted) hasher.update(k.view(), 0, k.view().length);
                    var fresh = new ContentList(digest).build(elements, DISCARD);
                    assertThat(stored.r()).as("R of %s/%d", module.name(), scope).isEqualTo(r);
                    assertThat(stored.leafSet()).isEqualTo(hasher.finish());
                    assertThat(stored.routeHash()).isEqualTo(fresh.hash());
                    assertThat(bound.routeHash()).isEqualTo(stored.routeHash());
                }
            }
        } finally { Stage2Support.delete(project); }
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
        assertThat(two.leafSet()).as("and leafSet").isEqualTo(one.leafSet());

        var swapped = bind(digest, store, List.of(ab, x, richp), Bind.NONE);
        assertThat(swapped.r()).as("an API-identical leaf leaves R").isEqualTo(one.r());
        assertThat(swapped.leafSet()).isNotEqualTo(one.leafSet());
        assertThat(swapped.routeHash()).isNotEqualTo(one.routeHash());

        var tree = new ContentTree(digest);
        var before = DefinerIndex.build(digest, tree, one.sequence(), null, k -> leaf(digest, store, k), nodes(store), DISCARD);
        var after = DefinerIndex.build(digest, tree, swapped.sequence(), null, k -> leaf(digest, store, k), nodes(store), DISCARD);
        assertThat(after.disjoint().sum()).as("the disjoint index resolves every name identically").isEqualTo(before.disjoint().sum());
        assertThat(after.disjoint().hash()).as("though it stores another k").isNotEqualTo(before.disjoint().hash());
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
        var abFirst = DefinerIndex.build(digest, tree, List.of(ab, x), null, leafOf, reader, written);
        var xFirst = DefinerIndex.build(digest, tree, List.of(x, ab), null, leafOf, reader, written);

        var conflictsOne = entries(digest, abFirst.conflicts(), reader);
        var conflictsTwo = entries(digest, xFirst.conflicts(), reader);
        assertThat(conflictsOne).hasSize(1);
        assertThat(zstr(conflictsOne.get(0).key())).isEqualTo("ab/Util");
        assertThat(firstDefiner(conflictsOne.get(0), digest.width())).isEqualTo(ab);
        assertThat(firstDefiner(conflictsTwo.get(0), digest.width())).isEqualTo(x);
        assertThat(xFirst.disjoint()).as("the disjoint part is byte-identical for a permuted route").isEqualTo(abFirst.disjoint());

        // From a base by difference, adding and removing leaves, equals from nothing.
        var grown = DefinerIndex.build(digest, tree, List.of(x, ab, t), abFirst.state(), leafOf, reader, written);
        var scratch = DefinerIndex.build(digest, tree, List.of(x, ab, t), null, leafOf, reader, written);
        assertThat(grown.disjoint()).isEqualTo(scratch.disjoint());
        assertThat(grown.conflicts()).isEqualTo(scratch.conflicts());
        var shrunk = DefinerIndex.build(digest, tree, List.of(ab), grown.state(), leafOf, reader, written);
        var alone = DefinerIndex.build(digest, tree, List.of(ab), null, leafOf, reader, written);
        assertThat(shrunk.disjoint()).isEqualTo(alone.disjoint());
        assertThat(shrunk.conflicts()).as("removing x leaves ab as the only definer: no conflict left").isEqualTo(alone.conflicts());
        assertThat(entries(digest, shrunk.conflicts(), reader)).isEmpty();
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
            var booted = boot(digest, Stage2Support.model(project, new Stage2Support.Mod("rich", "corp:rich:1", List.of())), 2);
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
            assertThat(booted.store().readsBeforeRoot()).isNotEmpty().doesNotContainAnyElementsOf(List.of("MOD", "RT", "F", "DD", "DC", "C", "X", "RS", "S", "LROOT"));
            assertThat(new java.util.HashSet<>(booted.store().readsBeforeRoot())).isSubsetOf("P", "L", "N", "ROOT");
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
            assertThat(firstShared.keySet().stream().anyMatch(k -> k[0] == 'S')).as("stubs were written").isTrue();
            assertThat(firstShared.keySet().stream().anyMatch(k -> k[0] == 'D' && k[1] == 'D')).isTrue();
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
            boolean tagged = k.length > 2 && k[1] == '|' && k[0] == 'S' || k.length > 3 && k[2] == '|' && (k[0] == 'D' && (k[1] == 'D' || k[1] == 'C'));
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
            for (var stub : Stubs.stubs(new ContentTree(digest), source, nodes(store))) {
                var file = stubDir.resolve(stub.internalName() + ".class");
                Files.createDirectories(file.getParent());
                Files.write(file, stub.bytes());
            }
            assertThat(Stubs.decode(Stubs.encode(Stubs.stubs(new ContentTree(digest), source, nodes(store))))).hasSameSizeAs(Stubs.stubs(new ContentTree(digest), source, nodes(store)));

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

    /** The memo key is the route: one file's bytes in two modules that resolve a star-imported name differently are two entries and two facts. */
    @ParameterizedTest @MethodSource("digests")
    void invariant15_theMemoKeyIsTheRoute(Digest digest) throws Exception {
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
            assertThat(booted.result().memoEntries()).as("one memo entry per route").isEqualTo(2);
            assertThat(booted.result().parsedFiles()).isEqualTo(2);
            var one = FileRow.decode("m1/src/main/java/use/User.java", booted.store().get(LocalStore.fileKey(booted.projectKey(), "m1/src/main/java/use/User.java")), digest.width());
            var two = FileRow.decode("m2/src/main/java/use/User.java", booted.store().get(LocalStore.fileKey(booted.projectKey(), "m2/src/main/java/use/User.java")), digest.width());
            assertThat(one.kappa()).as("one content key").isEqualTo(two.kappa());
            assertThat(one.sum()).isNotEqualTo(two.sum());
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
}
