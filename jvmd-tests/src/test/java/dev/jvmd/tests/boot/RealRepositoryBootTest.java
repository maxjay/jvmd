package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.boot.cold.stage1.Entries;
import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Node;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in (rules 7 and 8 of section 10, D.3): stage 1 over a real repository. Run with
 * {@code -Djvmd.boot.realM2=true} (the default ~/.m2/repository) or a path, and {@code -DexcludedGroups=}. It prints the boot
 * summary, the B sweep, the chunk sharing between adjacent versions, and the class-memo evidence; it asserts only that a boot
 * ends in a verifiable ROOT.
 */
@Tag("benchmark")
class RealRepositoryBootTest {
    /** Counts what reaches the store, so the sweep can report bytes written. */
    private static final class Counting implements MachineStore {
        final InMemoryMachineStore inner = new InMemoryMachineStore();
        final AtomicLong nodeBytes = new AtomicLong();
        @Override public void write(Node node) { nodeBytes.addAndGet(node.bytes().length); inner.write(node); }
        @Override public void flush() { inner.flush(); }
        @Override public void putLeaf(Identity k, byte[] leaf) { inner.putLeaf(k, leaf); }
        @Override public void putAnnotationLeaf(Identity a, byte[] roots) { inner.putAnnotationLeaf(a, roots); }
        @Override public void putPath(String location, byte[] value) { inner.putPath(location, value); }
        @Override public void putRoot(byte[] value) { inner.putRoot(value); }
        @Override public void sync() { inner.sync(); }
        @Override public boolean hasRoot() { return inner.hasRoot(); }
    }

    private static Path repository() {
        String property = System.getProperty("jvmd.boot.realM2");
        Assumptions.assumeTrue(property != null, "set -Djvmd.boot.realM2=true (or a repository path) to run");
        return property.equals("true") ? Path.of(System.getProperty("user.home"), ".m2/repository") : Path.of(property);
    }

    @Test void coldBootOverARealRepositorySweepingB() throws Exception {
        var repo = repository();
        var digest = Sha256.INSTANCE;
        var locations = Enumerate.repository(repo);
        System.out.printf("real-repository %s: %d jar locations%n", repo, locations.size());
        for (int b : new int[] {16, 32, 64}) {
            var store = new Counting();
            var phi = new java.util.concurrent.atomic.AtomicLong();
            var started = System.nanoTime();
            var result = new Stage1(digest, new ContentTree(digest, b, 4 * b), 25, Runtime.getRuntime().availableProcessors(), (d, bytes, owner) -> { phi.incrementAndGet(); return ClassFacts.of(d, bytes, owner); }).run(store, locations);
            System.out.printf("real-repository B=%d: leaves=%d distinct_jars=%d nodes=%d produced=%d shared_by_equal_nodes=%.3f node_bytes=%d faults=%d phi_calls=%d wall_ms=%d%n",
                    b, result.leaves(), result.distinctJars(), result.nodes(), result.nodesProduced(),
                    1.0 - (double) result.nodes() / Math.max(1, result.nodesProduced()), store.nodeBytes.get(), result.faults().size(), phi.get(), (System.nanoTime() - started) / 1_000_000);
            assertThat(store.inner.root()).isNotNull();
        }
    }

    @Test void adjacentVersionsShareChunksAndTheClassMemoHitRate() throws Exception {
        var repo = repository();
        var digest = Sha256.INSTANCE;
        // Group jars by artifact directory: .../<artifact>/<version>/<file>.jar -> .../<artifact>
        var byArtifact = new TreeMap<String, List<Enumerate.Location>>();
        for (var location : Enumerate.repository(repo)) {
            var path = Path.of(location.name());
            if (path.getNameCount() < 3) continue;
            byArtifact.computeIfAbsent(path.getParent().getParent().toString(), k -> new ArrayList<>()).add(location);
        }
        long entries = 0, distinct = 0;
        for (int b : new int[] {16, 32, 64}) {
            var shares = new ArrayList<Double>();
            for (var group : byArtifact.values()) {
                var seen = new HashSet<Identity>();
                Set<Identity> previous = null;
                for (var location : group) {
                    var bytes = Files.readAllBytes(location.file());
                    Entries zip;
                    try { zip = Entries.zip(bytes, 25); } catch (java.io.IOException unreadable) { continue; }
                    var sink = new ContentTreeTest.MapSink();
                    var chunker = new ContentTree(digest, b, 4 * b).chunker(sink);
                    for (var item : zip.classes()) {
                        byte[] classBytes;
                        try { classBytes = zip.read(item); } catch (java.io.IOException e) { continue; }
                        if (b == 32) { entries++; if (seen.add(digest.hash(classBytes))) distinct++; }
                        try { for (var fact : ClassFacts.of(digest, classBytes, item.owner()).facts()) chunker.add(fact.entry()); } catch (ClassFacts.Fault ignored) { /* counted as a fault by the boot */ }
                    }
                    chunker.finish();
                    var nodes = sink.nodes.keySet();
                    if (previous != null && nodes.size() > 4) {
                        var common = new HashSet<>(previous);
                        common.retainAll(nodes);
                        shares.add((double) common.size() / nodes.size());
                    }
                    previous = nodes;
                }
            }
            double mean = shares.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            System.out.printf("real-repository B=%d adjacent versions: pairs=%d mean_shared_T_nodes=%.3f%n", b, shares.size(), mean);
        }
        System.out.printf("real-repository class memo evidence: class_entries=%d distinct_class_contents_per_artifact_dir=%d hit_rate=%.3f%n",
                entries, distinct, entries == 0 ? 0.0 : 1.0 - (double) distinct / entries);
    }
}
