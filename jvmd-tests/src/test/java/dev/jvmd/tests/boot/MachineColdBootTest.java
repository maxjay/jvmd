package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.boot.cold.stage1.Entries;
import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.Sum;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The acceptance tests of stage 1 (7.3), run against an in-memory store: each invariant is a property that the design makes a
 * sum or an equality. Fixture jars are compiled here, so what a fixture contains is visible in the test.
 */
@Tag("phase-3")
class MachineColdBootTest {
    static final long T1 = 1_700_000_000_000L, T2 = T1 + 3_600_000L;
    static Path dir;
    static Path a, a2, aCopy, v1, v2, rich, multiRelease, corrupt, notAJar, empty, other;

    static Map<String, String> library(String extraMethod) {
        var sources = new LinkedHashMap<String, String>();
        sources.put("lib/Greeter.java", """
                package lib;
                public class Greeter<T extends Comparable<T>> implements java.io.Serializable {
                    public static final int VERSION = 1;
                    protected String name;
                    private int hidden;
                    public Greeter(String name) { this.name = name; }
                    public String greet(T who) { return "hi " + who; }
                    int pkg() { return 1; }
                    %s
                    public interface Listener { void on(String e) throws java.io.IOException; }
                    public static class Nested { public void run() { } }
                }
                """.formatted(extraMethod));
        sources.put("lib/Cmp.java", "package lib; public class Cmp implements Comparable<Cmp> { public int compareTo(Cmp o) { return 0; } }");
        return sources;
    }

    static Map<String, String> generated(int classes, int changed) {
        var sources = new LinkedHashMap<String, String>();
        for (int i = 0; i < classes; i++)
            sources.put("gen/C" + i + ".java", "package gen; public class C" + i + " { public String name = \"c" + i + "\"; public int m" + i
                    + "(int a) { return a + " + i + "; }" + (i == changed ? " public int extra() { return 1; }" : "") + " }");
        return sources;
    }

    @BeforeAll static void fixtures() throws IOException {
        dir = Files.createTempDirectory("stage1-fixtures");
        // Two jars with identical classes and different timestamps (so different bytes), and a byte-for-byte copy of one.
        a = BootFixtures.jar(dir, "a/a/1/a-1.jar", T1, library(""));
        a2 = BootFixtures.jar(dir, "a/a2/1/a2-1.jar", T2, library(""));
        aCopy = dir.resolve("a/copy/1/copy-1.jar");
        Files.createDirectories(aCopy.getParent());
        Files.copy(a, aCopy);
        // Two versions of one library differing in one method.
        v1 = BootFixtures.jar(dir, "v/lib/1/lib-1.jar", T1, library(""));
        v2 = BootFixtures.jar(dir, "v/lib/2/lib-2.jar", T1, library("public String shout(T who) { return who + \"!\"; }"));
        // A nested class, a record, an enum, an annotation type with meta-annotations, and a module-info.
        var rich0 = new LinkedHashMap<String, String>();
        rich0.put("module-info.java", "module rich { exports r; }");
        rich0.put("r/Outer.java", "package r; public class Outer { public class Inner { } private class Hidden { } public static class SNested { } }");
        rich0.put("r/Point.java", "package r; public record Point(int x, java.util.List<String> names) { }");
        rich0.put("r/Color.java", "package r; public enum Color { RED, GREEN; public int rank() { return ordinal(); } }");
        rich0.put("r/Marker.java", """
                package r;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD}) @Documented
                public @interface Marker { String value() default "x"; int[] nums() default {1, 2}; }
                """);
        rich = BootFixtures.jar(dir, "r/rich/1/rich-1.jar", T1, rich0);
        // A multi-release jar: version 9 replaces the base, version 99 is above any JDK.
        var base = BootFixtures.compile(Files.createTempDirectory(dir, "mr-"), Map.of("mr/M.java", "package mr; public class M { public int v() { return 8; } }"));
        var nine = BootFixtures.compile(Files.createTempDirectory(dir, "mr-"), Map.of("mr/M.java", "package mr; public class M { public int v() { return 9; } public int extra() { return 0; } }"));
        var ninetyNine = BootFixtures.compile(Files.createTempDirectory(dir, "mr-"), Map.of("mr/M.java", "package mr; public class M { public int v() { return 99; } public int never() { return 0; } }"));
        var mr = new LinkedHashMap<String, byte[]>();
        mr.put("META-INF/MANIFEST.MF", BootFixtures.text("Manifest-Version: 1.0\nMulti-Release: true\n\n"));
        mr.put("mr/M.class", base.get("mr/M.class"));
        mr.put("META-INF/versions/9/mr/M.class", nine.get("mr/M.class"));
        mr.put("META-INF/versions/99/mr/M.class", ninetyNine.get("mr/M.class"));
        multiRelease = BootFixtures.pack(dir.resolve("m/mr/1/mr-1.jar"), T1, mr);
        // One deliberately corrupt class entry, and one whose name does not match its this_class.
        var good = BootFixtures.compile(Files.createTempDirectory(dir, "bad-"), Map.of("p/A.java", "package p; public class A { public void a() { } }"));
        var bad = new LinkedHashMap<String, byte[]>();
        bad.put("p/A.class", good.get("p/A.class"));
        bad.put("p/Corrupt.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 99, 1, 2, 3});
        bad.put("p/Renamed.class", good.get("p/A.class"));
        corrupt = BootFixtures.pack(dir.resolve("c/corrupt/1/corrupt-1.jar"), T1, bad);
        notAJar = dir.resolve("c/garbage/1/garbage-1.jar");
        Files.createDirectories(notAJar.getParent());
        Files.writeString(notAJar, "this is not a zip file at all");
        empty = BootFixtures.pack(dir.resolve("e/empty/1/empty-1.jar"), T1, Map.of("README.txt", BootFixtures.text("no classes")));
        other = BootFixtures.jar(dir, "o/other/1/other-1.jar", T1, generated(30, -1));
    }

    @AfterAll static void cleanup() throws IOException {
        try (var walk = Files.walk(dir)) { for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }

    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new TestDigests.Sha3()); }

    static List<Enumerate.Location> all() throws IOException {
        var out = new ArrayList<Enumerate.Location>();
        for (var jar : List.of(a, a2, aCopy, v1, v2, rich, multiRelease, corrupt, notAJar, empty, other))
            out.add(Enumerate.jar(dir.relativize(jar).toString().replace('\\', '/'), jar));
        return out;
    }

    static Stage1.Result boot(Digest digest, int workers, List<Enumerate.Location> locations, InMemoryMachineStore store) {
        return new Stage1(digest, new ContentTree(digest), 25, workers, ClassFacts::of).run(store, locations);
    }

    static Identity zero(Digest digest) { return Sum.forWidth(digest.width()).zero(); }

    static Identity treeSum(Digest digest, InMemoryMachineStore store, Identity hash) {
        var tree = new ContentTree(digest);
        return tree.rangeSum(new Root(hash, zero(digest), 0, 0), h -> store.get(MachineStore.nodeKey(h)), null, null);
    }

    static List<MachineLeaf> leaves(Digest digest, InMemoryMachineStore store) {
        var out = new ArrayList<MachineLeaf>();
        for (var e : store.snapshot().entrySet()) if (e.getKey()[0] == 0x4C) out.add(MachineLeaf.decode(e.getValue(), digest.width()));
        return out;
    }

    // ---- 7.3 invariants ---------------------------------------------------------------------------------------------------

    @ParameterizedTest @MethodSource("digests")
    void invariants1To3_sumsComposeExactlyAtEveryLevel(Digest digest) throws Exception {
        var store = new InMemoryMachineStore();
        var result = boot(digest, 4, all(), store);
        var sums = Sum.forWidth(digest.width());
        var tree = new ContentTree(digest);
        var machine = MachineTree.decodeRoot(digest, store.root());
        tree.verify(machine.root(), h -> store.get(MachineStore.nodeKey(h)));

        // 1: the sum of the tree equals the sum of h(f) over every fact of the jar, recomputed here from the class files alone.
        for (var location : all()) {
            var path = MachineTree.decodePath(store.get(MachineStore.pathKey(location.name())), digest.width());
            if (path.k() == null) continue;
            var expected = sums.zero();
            var entries = Entries.zip(Files.readAllBytes(location.file()), 25);
            for (var item : entries.classes()) {
                try { for (var fact : ClassFacts.of(digest, entries.read(item), item.owner()).facts()) expected = sums.add(expected, fact.h()); }
                catch (ClassFacts.Fault skipped) { /* a fault contributes nothing, here and in the boot */ }
            }
            var leaf = MachineLeaf.decode(store.get(MachineStore.leafKey(path.k())), digest.width());
            assertThat(leaf.r()).as("r of %s", location.name()).isEqualTo(expected);
        }
        var total = sums.zero();
        for (var leaf : leaves(digest, store)) {
            assertThat(treeSum(digest, store, leaf.k())).as("sum(T)").isEqualTo(leaf.r());
            // 2: rekeying preserves the sum.
            assertThat(treeSum(digest, store, leaf.nHash())).as("sum(N)").isEqualTo(leaf.r());
            // 3: type sums partition the leaf sum.
            assertThat(treeSum(digest, store, leaf.oHash())).as("sum(O)").isEqualTo(leaf.r());
            total = sums.add(total, leaf.r());
            assertThat(MachineStore.leafKey(leaf.k())).isEqualTo(store.snapshot().keySet().stream().filter(k -> k[0] == 0x4C && java.util.Arrays.equals(java.util.Arrays.copyOfRange(k, 1, k.length), leaf.k().view())).findFirst().orElseThrow());
        }
        assertThat(total).as("sum(M) is the sum of every fact in every leaf").isEqualTo(machine.root().sum());
        assertThat(result.leaves()).isEqualTo(machine.root().count());
    }

    @ParameterizedTest @MethodSource("digests")
    void invariant4_theRootDoesNotDependOnJobOrderOrWorkerCount(Digest digest) throws Exception {
        var reference = new InMemoryMachineStore();
        boot(digest, 1, all(), reference);
        for (int seed = 0; seed < 4; seed++) {
            var shuffled = new ArrayList<>(all());
            Collections.shuffle(shuffled, new Random(seed));
            var store = new InMemoryMachineStore();
            boot(digest, new int[] {1, 3, 8, 16}[seed], shuffled, store);
            assertThat(store.root()).as("ROOT, seed %d", seed).isEqualTo(reference.root());
            assertThat(store.snapshot().keySet()).as("every record key, seed %d", seed).containsExactlyElementsOf(reference.snapshot().keySet());
            for (var e : reference.snapshot().entrySet()) assertThat(store.get(e.getKey())).isEqualTo(e.getValue());
        }
    }

    /**
     * Invariant 5 under L as a function of k: two jars with identical facts and different corrupt extra entries are one L, two P
     * records with different fault lists, and the second job writes no nodes.
     */
    @ParameterizedTest @MethodSource("digests") void invariant5_equalFactsAreOneLeafTwoPathRecordsWithTheirOwnFaultsAndTheSecondJobWritesNoNodes(Digest digest) throws Exception {
        var classes = BootFixtures.compile(Files.createTempDirectory(dir, "eq-"), library(""));
        var first = new LinkedHashMap<>(classes);
        first.put("x/Bad1.class", new byte[] {1, 2, 3});
        var second = new LinkedHashMap<>(classes);
        second.put("y/Bad2.class", new byte[] {4, 5, 6});
        var j1 = BootFixtures.pack(dir.resolve("eq/one/1/one-1.jar"), T1, first);
        var j2 = BootFixtures.pack(dir.resolve("eq/two/1/two-1.jar"), T2, second);
        var l1 = Enumerate.jar("eq/one/1/one-1.jar", j1);
        var l2 = Enumerate.jar("eq/two/1/two-1.jar", j2);

        var only = new InMemoryMachineStore();
        var one = boot(digest, 1, List.of(l1), only);
        var both = new InMemoryMachineStore();
        var two = boot(digest, 1, List.of(l1, l2), both);

        assertThat(two.leaves()).isEqualTo(1);
        assertThat(two.nodes()).as("nodes written for two API-identical jars").isEqualTo(one.nodes());
        assertThat(both.kinds().get("L")).isEqualTo(1L);
        assertThat(both.kinds().get("P")).isEqualTo(2L);
        var p1 = MachineTree.decodePath(both.get(MachineStore.pathKey("eq/one/1/one-1.jar")), 32);
        var p2 = MachineTree.decodePath(both.get(MachineStore.pathKey("eq/two/1/two-1.jar")), 32);
        assertThat(p1.k()).as("both locations point at one leaf key").isNotNull().isEqualTo(p2.k());
        assertThat(p1.bh()).isNotEqualTo(p2.bh());
        assertThat(p1.faults()).containsExactly("x/Bad1.class");
        assertThat(p2.faults()).containsExactly("y/Bad2.class");
        // L is the same record either way: it holds nothing per jar file.
        assertThat(both.get(MachineStore.leafKey(p1.k()))).isEqualTo(only.get(MachineStore.leafKey(p1.k())));
    }

    @ParameterizedTest @MethodSource("digests") void invariant6_aClassIsParsedOncePerDistinctJar(Digest digest) throws Exception {
        var parses = new AtomicInteger();
        var stage = new Stage1(digest, new ContentTree(digest), 25, 4, (d, bytes, owner) -> { parses.incrementAndGet(); return ClassFacts.of(d, bytes, owner); });
        var locations = List.of(Enumerate.jar("a.jar", a), Enumerate.jar("copy.jar", aCopy), Enumerate.jar("other.jar", other));
        var result = stage.run(new InMemoryMachineStore(), locations);
        int expected = classCount(a) + classCount(other); // the byte-identical copy is dropped by Seen before any entry is opened
        assertThat(parses.get()).isEqualTo(expected);
        assertThat(result.distinctJars()).isEqualTo(2);
    }

    private static int classCount(Path jar) throws IOException {
        return dev.jvmd.boot.cold.stage1.Entries.zip(Files.readAllBytes(jar), 25).classes().size();
    }

    /** An internal library published as many versions: every class repeats except one, in two artifact directories. */
    @Test void theClassMemoSkipsRepeatedClassesWithinAnArtifactDirectoryAndChangesNothingElse() throws Exception {
        var digest = Sha256.INSTANCE;
        var shared = BootFixtures.compile(Files.createTempDirectory(dir, "memo-"), generated(200, -1));
        var locations = new ArrayList<Enumerate.Location>();
        for (var artifact : List.of("corp/lib", "corp/lib2")) {
            long offset = artifact.endsWith("2") ? 86_400_000L : 0;
            for (int version = 1; version <= 6; version++) {
                var classes = new LinkedHashMap<>(shared);
                classes.putAll(BootFixtures.compile(Files.createTempDirectory(dir, "memo-"), Map.of("v/Ver.java", "package v; public class Ver { public static final int N = " + version + "; }")));
                // A different timestamp per jar (and per artifact), so no two jars are byte-identical and Seen cannot hide the repeats.
                var jar = BootFixtures.pack(dir.resolve("memo/" + artifact + "/" + version + "/lib-" + version + ".jar"), T1 + offset + version * 3_600_000L, classes);
                locations.add(Enumerate.jar("memo/" + artifact + "/" + version + "/lib-" + version + ".jar", jar));
            }
        }
        var parses = new AtomicInteger();
        var combined = new InMemoryMachineStore();
        var shuffled = new ArrayList<>(locations);
        Collections.shuffle(shuffled, new Random(11));
        new Stage1(digest, new ContentTree(digest), 25, 8, (d, b, o) -> { parses.incrementAndGet(); return ClassFacts.of(d, b, o); }).run(combined, shuffled);
        // 200 shared classes plus the 6 distinct Ver classes, per artifact directory; without the memo it would be 12 * 201 = 2412.
        assertThat(parses.get()).as("distinct class contents per artifact directory").isEqualTo(2 * (shared.size() + 6));

        // Booting each jar alone cannot hit the memo (a jar has no repeated class), so its records are what the memo must reproduce.
        var union = new java.util.TreeMap<byte[], byte[]>(java.util.Arrays::compareUnsigned);
        for (var location : locations) {
            var alone = new InMemoryMachineStore();
            boot(digest, 1, List.of(location), alone);
            for (var e : alone.snapshot().entrySet()) if (e.getKey()[0] == 0x4C || e.getKey()[0] == 0x4E) union.put(e.getKey(), e.getValue());
        }
        for (var e : combined.snapshot().entrySet()) {
            // M's own nodes depend on the whole machine; every leaf and every node under a leaf must be what each jar gave alone.
            if (e.getKey()[0] == 0x4C) assertThat(union.get(e.getKey())).as("L|k").isEqualTo(e.getValue());
        }
        var leafNodes = 0;
        for (var e : union.entrySet()) if (e.getKey()[0] == 0x4E && combined.get(e.getKey()) != null) { leafNodes++; assertThat(combined.get(e.getKey())).isEqualTo(e.getValue()); }
        assertThat(leafNodes).isGreaterThan(0);
    }

    /** A store that records how many entries had been parsed when each node reached it. */
    private static final class RecordingStore implements MachineStore {
        final InMemoryMachineStore inner = new InMemoryMachineStore();
        final AtomicInteger parsed;
        final List<Integer> arrivals = java.util.Collections.synchronizedList(new ArrayList<>());
        RecordingStore(AtomicInteger parsed) { this.parsed = parsed; }
        @Override public void write(dev.jvmd.core.tree.Node node) { arrivals.add(parsed.get()); inner.write(node); }
        @Override public void flush() { inner.flush(); }
        @Override public void putLeaf(Identity k, byte[] leaf) { inner.putLeaf(k, leaf); }
        @Override public void putAnnotationLeaf(Identity a, byte[] roots) { inner.putAnnotationLeaf(a, roots); }
        @Override public void putPath(String location, byte[] value) { inner.putPath(location, value); }
        @Override public void putRoot(byte[] value) { inner.putRoot(value); }
        @Override public void sync() { inner.sync(); }
        @Override public boolean hasRoot() { return inner.hasRoot(); }
    }

    /**
     * Invariant 7 as specified: T is written as the stream advances. For a jar with at least 4 * CAP facts, a node of T reaches
     * the sink before the last class entry has been read. This is the testable form of "job memory is independent of jar size".
     */
    @ParameterizedTest @MethodSource("digests") void invariant7_nodesOfTLeaveTheJobWhileEntriesAreStillBeingRead(Digest digest) throws Exception {
        var big = BootFixtures.jar(dir, "big/big/1/big-1.jar", T1, generated(600, -1));
        var location = Enumerate.jar("big/big/1/big-1.jar", big);
        int entries = classCount(big);
        var parsed = new AtomicInteger();
        var store = new RecordingStore(parsed);
        new Stage1(digest, new ContentTree(digest), 25, 1, (d, b, o) -> { parsed.incrementAndGet(); return ClassFacts.of(d, b, o); }).run(store, List.of(location));
        var k = MachineTree.decodePath(store.inner.get(MachineStore.pathKey("big/big/1/big-1.jar")), 32).k();
        long facts = MachineLeaf.decode(store.inner.get(MachineStore.leafKey(k)), 32).factCount();
        assertThat(facts).as("the fixture has at least 4 * CAP facts").isGreaterThanOrEqualTo(4L * ContentTree.CAP);
        assertThat(parsed.get()).isEqualTo(entries);
        assertThat(store.arrivals).isNotEmpty();
        assertThat(store.arrivals.get(0)).as("entries read when the first node arrived").isLessThan(entries);
        // A chunk closes after at most CAP facts, and every class has at least its type fact, so by CAP classes a node must have left.
        assertThat(store.arrivals.get(0)).isLessThanOrEqualTo(ContentTree.CAP);
    }

    @ParameterizedTest @MethodSource("digests") void invariant8_nothingIsReadFromTheStoreBeforeTheRootIsWritten(Digest digest) throws Exception {
        var store = new InMemoryMachineStore();
        boot(digest, 4, all(), store);
        assertThat(store.events()).doesNotContain("read");
        assertThat(store.events()).containsExactly("sync", "putRoot");
    }

    @Test void invariant9_theDigestIsAParameter() throws Exception {
        var sha256 = new InMemoryMachineStore();
        var sha3 = new InMemoryMachineStore();
        boot(Sha256.INSTANCE, 4, all(), sha256);
        var other = new TestDigests.Sha3();
        boot(other, 4, all(), sha3);
        assertThat(sha3.root()).isNotEqualTo(sha256.root());
        assertThat(Format.of(other, 25).directoryName()).isNotEqualTo(Format.of(Sha256.INSTANCE, 25).directoryName());
        // Chunk boundaries above level 0 are decided by child hashes, so node counts may differ; leaves, paths and the root do not.
        assertThat(sha3.kinds().get("L")).isEqualTo(sha256.kinds().get("L"));
        assertThat(sha3.kinds().get("P")).isEqualTo(sha256.kinds().get("P"));
    }

    /**
     * Invariant 10. Measured on this fixture (400 generated classes, one class gains a method): the share of T nodes common to
     * the two versions was 0.94 (47 of 50 nodes); the floor below is set under that. A tree this small has few chunks, so the share is lower than it would be for a real library with thousands of members.
     */
    @ParameterizedTest @MethodSource("digests") void invariant10_adjacentVersionsShareMostChunks(Digest digest) throws Exception {
        var one = BootFixtures.jar(dir, "g/gen/1/gen-1.jar", T1, generated(400, -1));
        var two = BootFixtures.jar(dir, "g/gen/2/gen-2.jar", T1, generated(400, 200));
        var first = nodeHashes(digest, one);
        var second = nodeHashes(digest, two);
        var common = new HashSet<>(first);
        common.retainAll(second);
        double shared = (double) common.size() / second.size();
        System.out.printf("phase-3-locality T nodes v1=%d v2=%d common=%d share=%.3f%n", first.size(), second.size(), common.size(), shared);
        assertThat(shared).isGreaterThan(0.9);
    }

    private static Set<Identity> nodeHashes(Digest digest, Path jar) throws Exception {
        var entries = dev.jvmd.boot.cold.stage1.Entries.zip(Files.readAllBytes(jar), 25);
        var sink = new ContentTreeTest.MapSink();
        var chunker = new ContentTree(digest).chunker(sink);
        for (var item : entries.classes()) for (var fact : ClassFacts.of(digest, entries.read(item), item.owner()).facts()) chunker.add(fact.entry());
        chunker.finish();
        return sink.nodes.keySet();
    }

    // ---- what is on disk, faults, and the rules of the appendices -----------------------------------------------------------

    @Test void onlyTheFourRecordKindsAreWrittenAndTheRootVerifies() throws Exception {
        var store = new InMemoryMachineStore();
        boot(Sha256.INSTANCE, 4, all(), store);
        assertThat(store.kinds().keySet()).containsExactlyInAnyOrder("L", "N", "P", "AL", "ROOT");
        var record = MachineTree.decodeRoot(Sha256.INSTANCE, store.root());
        assertThat(record.format()).isEqualTo(Format.of(Sha256.INSTANCE, 25).toString());
        assertThat(record.format()).isEqualTo("layout=4;digest=SHA-256;jdk=25;parser=2");
    }

    @Test void faultsAreRecordedAndNeverFatal() throws Exception {
        var store = new InMemoryMachineStore();
        var result = boot(Sha256.INSTANCE, 4, all(), store);
        var corruptName = dir.relativize(corrupt).toString().replace('\\', '/');
        assertThat(result.faults()).contains(corruptName + ": p/Corrupt.class", corruptName + ": p/Renamed.class");
        assertThat(result.faults()).anyMatch(f -> f.contains("garbage-1.jar"));
        // Faults are a fact about one file: they are in its P| record, in owner order, and not in L.
        var corruptPath = MachineTree.decodePath(store.get(MachineStore.pathKey(corruptName)), 32);
        assertThat(corruptPath.faults()).containsExactly("p/Corrupt.class", "p/Renamed.class");
        assertThat(corruptPath.k()).isNotNull();
        assertThat(MachineLeaf.decode(store.get(MachineStore.leafKey(corruptPath.k())), 32).typeCount()).isEqualTo(1);
        // A location that is not an archive: the zero identity, no leaf key, one fault naming the reason (B.5).
        var garbage = MachineTree.decodePath(store.get(MachineStore.pathKey(dir.relativize(notAJar).toString().replace('\\', '/'))), 32);
        assertThat(garbage.bh()).isEqualTo(zero(Sha256.INSTANCE));
        assertThat(garbage.k()).isNull();
        assertThat(garbage.faults()).hasSize(1);
    }

    @Test void aJarWithNoClassesIsAnEmptyApiLeaf() throws Exception {
        var store = new InMemoryMachineStore();
        boot(Sha256.INSTANCE, 1, List.of(Enumerate.jar("e.jar", empty)), store);
        var leaves = leaves(Sha256.INSTANCE, store);
        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).factCount()).isZero();
        assertThat(leaves.get(0).r()).isEqualTo(zero(Sha256.INSTANCE));
    }

    @Test void rebuildingWithDifferentDebugInfoOrBodiesIsTheSameApi() throws Exception {
        var digest = Sha256.INSTANCE;
        var plain = BootFixtures.compile(Files.createTempDirectory(dir, "dbg-"), library(""), "-g:none");
        var debug = BootFixtures.compile(Files.createTempDirectory(dir, "dbg-"), library(""), "-g");
        assertThat(plain.get("lib/Greeter.class")).isNotEqualTo(debug.get("lib/Greeter.class"));
        for (var name : plain.keySet()) {
            var owner = name.substring(0, name.length() - ".class".length());
            var x = ClassFacts.of(digest, plain.get(name), owner);
            var y = ClassFacts.of(digest, debug.get(name), owner);
            assertThat(x.facts()).hasSameSizeAs(y.facts());
            for (int i = 0; i < x.facts().size(); i++) {
                assertThat(x.facts().get(i).m()).isEqualTo(y.facts().get(i).m());
                assertThat(x.facts().get(i).res()).as("e of %s", name).isEqualTo(y.facts().get(i).res());
                assertThat(x.facts().get(i).h()).isEqualTo(y.facts().get(i).h());
            }
        }
        var bodyA = BootFixtures.compile(Files.createTempDirectory(dir, "body-"), Map.of("q/B.java", "package q; public class B { public int f(int x) { return x; } }"));
        var bodyB = BootFixtures.compile(Files.createTempDirectory(dir, "body-"), Map.of("q/B.java", "package q; public class B { public int f(int x) { return x * 2 + 1; } }"));
        var f1 = ClassFacts.of(digest, bodyA.get("q/B.class"), "q/B");
        var f2 = ClassFacts.of(digest, bodyB.get("q/B.class"), "q/B");
        assertThat(f1.facts().stream().map(f -> f.h()).toList()).isEqualTo(f2.facts().stream().map(f -> f.h()).toList());
    }

    @Test void parameterNamesChangeTheEncodingButNotTheResolutionIdentity() throws Exception {
        var digest = Sha256.INSTANCE;
        var one = BootFixtures.compile(Files.createTempDirectory(dir, "pn-"), Map.of("q/B.java", "package q; public class B { public int f(int left) { return left; } }"), "-parameters");
        var two = BootFixtures.compile(Files.createTempDirectory(dir, "pn-"), Map.of("q/B.java", "package q; public class B { public int f(int right) { return right; } }"), "-parameters");
        var x = ClassFacts.of(digest, one.get("q/B.class"), "q/B");
        var y = ClassFacts.of(digest, two.get("q/B.class"), "q/B");
        var fx = x.facts().stream().filter(f -> f.simpleName().equals("f")).findFirst().orElseThrow();
        var fy = y.facts().stream().filter(f -> f.simpleName().equals("f")).findFirst().orElseThrow();
        assertThat(fx.h()).isEqualTo(fy.h());
        assertThat(fx.res()).isEqualTo(fy.res());
        assertThat(fx.tail()).isNotEqualTo(fy.tail());
    }

    @Test void membersAreFilteredAndKeyedAsSpecified() throws Exception {
        var digest = Sha256.INSTANCE;
        var classes = BootFixtures.compile(Files.createTempDirectory(dir, "m-"), library(""));
        var greeter = ClassFacts.of(digest, classes.get("lib/Greeter.class"), "lib/Greeter");
        var names = greeter.facts().stream().map(f -> f.simpleName()).toList();
        assertThat(names).contains("Greeter", "VERSION", "name", "greet", "pkg", "<init>").doesNotContain("hidden");
        // The type fact comes first and the facts are in key order.
        assertThat(greeter.facts().get(0).simpleName()).isEqualTo("Greeter");
        for (int i = 1; i < greeter.facts().size(); i++)
            assertThat(java.util.Arrays.compareUnsigned(greeter.facts().get(i - 1).m(), greeter.facts().get(i).m())).isNegative();
        // A bridge method is not a member a consumer can resolve.
        var cmp = ClassFacts.of(digest, classes.get("lib/Cmp.class"), "lib/Cmp");
        assertThat(cmp.facts().stream().filter(f -> f.simpleName().equals("compareTo")).count()).isEqualTo(1);
        // Edges: supertypes, interfaces, field and parameter types, the enclosing type.
        var edgeTargets = greeter.edges().stream().map(e -> new String(e.key(), 0, indexOfNul(e.key()), java.nio.charset.StandardCharsets.UTF_8)).toList();
        assertThat(edgeTargets).contains("java/lang/Object", "java/io/Serializable", "java/lang/String", "java/lang/Comparable");
        var nested = ClassFacts.of(digest, classes.get("lib/Greeter$Nested.class"), "lib/Greeter$Nested");
        assertThat(nested.edges().stream().map(e -> new String(e.key(), 0, indexOfNul(e.key()), java.nio.charset.StandardCharsets.UTF_8)).toList()).contains("lib/Greeter");
    }

    private static int indexOfNul(byte[] key) { int i = 0; while (key[i] != 0) i++; return i; }

    @Test void richKindsAreAllEncoded() throws Exception {
        var digest = Sha256.INSTANCE;
        var entries = dev.jvmd.boot.cold.stage1.Entries.zip(Files.readAllBytes(rich), 25);
        var owners = new ArrayList<String>();
        for (var item : entries.classes()) {
            var facts = ClassFacts.of(digest, entries.read(item), item.owner());
            owners.add(facts.ownerKey());
            assertThat(facts.facts()).isNotEmpty();
        }
        assertThat(owners).contains("module-info", "r/Outer", "r/Outer$Inner", "r/Outer$SNested", "r/Outer$Hidden", "r/Point", "r/Color", "r/Marker");
        // Owner order is key order: Outer before Outer$Inner, even though "Outer$Inner.class" sorts before "Outer.class".
        assertThat(owners.indexOf("r/Outer")).isLessThan(owners.indexOf("r/Outer$Inner"));
    }

    @Test void multiReleaseSelectionPicksTheHighestVersionNotAboveTheJdk() throws Exception {
        var bytes = Files.readAllBytes(multiRelease);
        assertThat(dev.jvmd.boot.cold.stage1.Entries.zip(bytes, 25).classes()).singleElement()
                .satisfies(i -> { assertThat(i.owner()).isEqualTo("mr/M"); assertThat(i.path()).isEqualTo("META-INF/versions/9/mr/M.class"); });
        assertThat(dev.jvmd.boot.cold.stage1.Entries.zip(bytes, 8).classes()).singleElement().satisfies(i -> assertThat(i.path()).isEqualTo("mr/M.class"));
        assertThat(dev.jvmd.boot.cold.stage1.Entries.zip(bytes, 100).classes()).singleElement().satisfies(i -> assertThat(i.path()).isEqualTo("META-INF/versions/99/mr/M.class"));
    }

    @Test void jdkModulesAreIndexedLikeJars() throws Exception {
        var opened = new ArrayList<java.nio.file.FileSystem>();
        var modules = Enumerate.jdk(Path.of(System.getProperty("java.home")), Sha256.INSTANCE, opened).stream()
                .filter(l -> l.module().equals("java.compiler") || l.module().equals("java.logging")).toList();
        assertThat(modules).hasSize(2);
        var store = new InMemoryMachineStore();
        var result = boot(Sha256.INSTANCE, 2, modules, store);
        assertThat(result.leaves()).isEqualTo(2);
        assertThat(result.faults()).isEmpty();
        for (var leaf : leaves(Sha256.INSTANCE, store)) assertThat(leaf.factCount()).isGreaterThan(100);
        var p = store.get(MachineStore.pathKey(modules.get(0).name()));
        assertThat(p).isNotNull();
        assertThat(p[32]).as("a module location has a leaf").isEqualTo((byte) 1);
    }

    @Test void repositoryEnumerationFollowsThePathOrderRules() throws Exception {
        var root = Files.createTempDirectory(dir, "repo-");
        for (var name : List.of("b/x/1/x-1.jar", "b/x/1/x-1-sources.jar", "b/x/1/x-1-javadoc.jar", "b/x/1/x-1.pom", "a.jar", "a/y/1/y-1.jar", ".hidden/z.jar", "a/y/2/y-2.jar")) {
            Files.createDirectories(root.resolve(name).getParent());
            Files.writeString(root.resolve(name), "x");
        }
        assertThat(Enumerate.repository(root).stream().map(Enumerate.Location::name).toList())
                // "a" (a directory) sorts before "a.jar": the shorter prefix comes first, so its subtree is visited first.
                .containsExactly("a/y/1/y-1.jar", "a/y/2/y-2.jar", "a.jar", "b/x/1/x-1.jar");
    }

    @Test void theWarmPackageIsEmptyAndStage1ImportsOnlyCoreAndIndex() throws Exception {
        var root = dev.jvmd.tests.TestSupport.repo().resolve("jvmd-boot/src/main/java/dev/jvmd/boot");
        try (var walk = Files.list(root.resolve("warm"))) { assertThat(walk.map(p -> p.getFileName().toString()).toList()).containsExactly("package-info.java"); }
        try (var walk = Files.walk(root.resolve("cold/stage1"))) {
            for (var file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (var line : Files.readAllLines(file)) {
                    if (!line.startsWith("import ")) continue;
                    assertThat(line).as(file.getFileName().toString()).matches("import (static )?(java\\.|dev\\.jvmd\\.core\\.|dev\\.jvmd\\.index\\.layer\\.machine\\.|dev\\.jvmd\\.boot\\.cold\\.stage1\\.).*");
                    assertThat(line).doesNotContain("warm").doesNotContain("rocks");
                }
            }
        }
    }
}
