package dev.jvmd.tests.boot;

import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Node;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in controlled Stage 1 measurement. Run this identical harness at base and head. */
@Tag("benchmark")
class Stage1Measurement {
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new TestDigests.Sha3()); }
    static final class Counts {
        final AtomicLong produced = new AtomicLong(), bytes = new AtomicLong();
        final Set<Identity> unique = ConcurrentHashMap.newKeySet();
        void add(Node node) { produced.incrementAndGet(); if (unique.add(node.hash())) bytes.addAndGet(node.bytes().length); }
    }

    static final class Store implements MachineStore {
        final Map<Identity, byte[]> nodes = new ConcurrentHashMap<>(), leaves = new ConcurrentHashMap<>(), annotations = new ConcurrentHashMap<>();
        final Map<String, byte[]> paths = new ConcurrentHashMap<>();
        final Map<String, Counts> byTree = new ConcurrentHashMap<>();
        byte[] root;
        @Override public void write(Node node) { nodes.put(node.hash(), node.bytes()); }
        @Override public void nodeBuilt(String tree, Node node) { byTree.computeIfAbsent(tree, _ -> new Counts()).add(node); }
        @Override public void putLeaf(Identity k, byte[] bytes) { leaves.put(k, bytes); }
        // This method also compiles against the instrumented LAYOUT 3 base, where AL does not exist.
        public void putAnnotationLeaf(Identity a, byte[] bytes) { annotations.put(a, bytes); }
        @Override public void putPath(String path, byte[] bytes) { paths.put(path, bytes); }
        @Override public void putRoot(byte[] bytes) { root = bytes; }
        @Override public void flush() { }
        @Override public void sync() { }
        @Override public boolean hasRoot() { return root != null; }
    }

    @ParameterizedTest @MethodSource("digests")
    void sameRepositoryAndJdk(Digest digest) throws Exception {
        String repository = System.getProperty("jvmd.measure.repository");
        Assumptions.assumeTrue(repository != null, "set jvmd.measure.repository to opt in");
        int workers = Integer.getInteger("jvmd.measure.workers", 4);
        var opened = new ArrayList<java.nio.file.FileSystem>();
        try {
            var locations = new ArrayList<>(Enumerate.repository(Path.of(repository)));
            var jdk = Path.of(System.getProperty("java.home"));
            locations.addAll(Enumerate.jdk(jdk, digest, opened));
            locations.sort((a, b) -> Enumerate.compareNames(a.name(), b.name()));
            var inventory = inventory(digest, locations);
            var jdkIdentity = hash(digest, jdk.resolve("lib/modules"));
            var store = new Store();
            var phi = new AtomicLong();
            long baselineHeap = usedHeap();
            var peak = new AtomicLong(baselineHeap);
            var sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
            var sampler = Thread.ofPlatform().daemon().start(() -> {
                while (sampling.get()) {
                    peak.accumulateAndGet(usedHeap(), Math::max);
                    try { Thread.sleep(20); } catch (InterruptedException stopped) { return; }
                }
            });
            Stage1.Result result;
            try {
                result = new Stage1(digest, new ContentTree(digest), Runtime.version().feature(), workers,
                        (d, bytes, owner) -> { phi.incrementAndGet(); return ClassFacts.of(d, bytes, owner); }).run(store, locations);
            } finally { sampling.set(false); sampler.interrupt(); sampler.join(); }
            assertThat(inventory(digest, locations)).as("no input changed during the boot").isEqualTo(inventory);
            assertThat(hash(digest, jdk.resolve("lib/modules"))).isEqualTo(jdkIdentity);
            assertThat(store.byTree).doesNotContainKey("unlabelled");
            new ContentTree(digest).verify(MachineTree.decodeRoot(digest, store.root).root(), store.nodes::get);
            var report = new StringBuilder();
            report.append("revision=").append(System.getProperty("jvmd.measure.revision", "working-tree")).append('\n');
            report.append("digest=").append(digest.name()).append(" runtime=").append(Runtime.version()).append(" workers=").append(workers).append('\n');
            report.append("repository=").append(repository).append(" jdk=").append(jdk).append('\n');
            report.append("inventory=Digest(sorted(zstr location || bh)) ").append(java.util.HexFormat.of().formatHex(inventory.view())).append(" jdk_image=").append(java.util.HexFormat.of().formatHex(jdkIdentity.view())).append('\n');
            report.append("locations=").append(result.locations()).append(" distinct_bytes=").append(result.distinctJars()).append(" leaves=").append(result.leaves()).append('\n');
            report.append("wall_ms=").append(result.wallMillis()).append(" phi_calls=").append(phi).append(" faults=").append(result.faults().size()).append('\n');
            report.append("nodes_written=").append(result.nodes()).append(" nodes_produced=").append(result.nodesProduced()).append(" node_bytes=")
                    .append(store.nodes.values().stream().mapToLong(b -> b.length).sum()).append(" annotation_records=").append(store.annotations.size()).append('\n');
            report.append("sampled_heap_baseline=").append(baselineHeap).append(" sampled_heap_peak=").append(peak).append('\n');
            for (var e : new TreeMap<>(store.byTree).entrySet()) report.append("tree=").append(e.getKey()).append(" produced=").append(e.getValue().produced)
                    .append(" unique=").append(e.getValue().unique.size()).append(" unique_bytes=").append(e.getValue().bytes).append('\n');
            report.append("fault_entries=\n");
            result.faults().stream().sorted().forEach(f -> report.append(f).append('\n'));
            var target = Path.of("target", "stage1-measurement-" + digest.name() + ".txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
            System.out.print(report);
        } finally { for (var fs : opened) fs.close(); }
    }

    private static Identity inventory(Digest digest, List<Enumerate.Location> locations) throws Exception {
        var out = new Codec.Writer();
        for (var location : locations) out.zstr(location.name()).id(location.isModule() ? location.bh() : hash(digest, location.file()));
        return digest.hash(out.toBytes());
    }

    private static Identity hash(Digest digest, Path path) throws Exception {
        var h = digest.hasher();
        try (var in = Files.newInputStream(path)) {
            var buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) >= 0; ) if (n > 0) h.update(buffer, 0, n);
        }
        return h.finish();
    }

    private static long usedHeap() { var runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
}
