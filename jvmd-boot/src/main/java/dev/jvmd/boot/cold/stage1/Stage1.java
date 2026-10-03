package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stage 1 (stage 1, 5.5): the MACHINE cold boot. Input: the repository directory (and the JDK homes). Output: ROOT written, last.
 * Before ROOT is written the layer does not exist. No store read happens before that write.
 *
 * <p>Everything that shapes the result is a constructor argument with one call site ({@code BootDecision}): the digest, the
 * tree's boundary parameters, the JDK feature version and the worker count. There is no other configuration.
 */
public final class Stage1 {
    /** Φ. A seam so a test can count invocations (invariant 6); production uses {@link ClassFacts#of}. */
    @FunctionalInterface public interface Parser { ClassFacts parse(Digest digest, byte[] bytes, String expectedOwner) throws ClassFacts.Fault; }

    /** What the boot did: the numbers of the one log line (D.4). */
    public record Result(int locations, int distinctJars, int leaves, long nodes, long nodesProduced, List<String> faults, long wallMillis, Root root) { }

    private final Digest digest;
    private final ContentTree tree;
    private final int jdkFeature;
    private final int workers;
    private final Parser parser;

    private final boolean classMemo;

    /** Class memo off: the plain design. */
    public Stage1(Digest digest, ContentTree tree, int jdkFeature, int workers) { this(digest, tree, jdkFeature, workers, ClassFacts::of, false); }

    /** @param classMemo skip re-parsing class files that repeat byte for byte within an artifact directory (3.8); output is identical either way */
    public Stage1(Digest digest, ContentTree tree, int jdkFeature, int workers, boolean classMemo) { this(digest, tree, jdkFeature, workers, ClassFacts::of, classMemo); }

    public Stage1(Digest digest, ContentTree tree, int jdkFeature, int workers, Parser parser) { this(digest, tree, jdkFeature, workers, parser, false); }

    public Stage1(Digest digest, ContentTree tree, int jdkFeature, int workers, Parser parser, boolean classMemo) {
        this.digest = digest;
        this.tree = tree;
        this.jdkFeature = jdkFeature;
        this.workers = workers;
        this.parser = parser;
        this.classMemo = classMemo;
    }

    /** Enumerates the repository and then the JDK homes, and boots into {@code store}. */
    public Result run(MachineStore store, Path repository, List<Path> jdkHomes) throws IOException {
        var opened = new ArrayList<FileSystem>();
        try {
            var locations = new ArrayList<>(Enumerate.repository(repository));
            for (var home : jdkHomes) locations.addAll(Enumerate.jdk(home, digest, opened));
            return run(store, locations);
        } finally {
            for (var fs : opened) { try { fs.close(); } catch (IOException ignored) { /* nothing to recover */ } }
        }
    }

    /** Boots over an explicit location list, in this order. The result does not depend on the order or on the worker count. */
    public Result run(MachineStore store, List<Enumerate.Location> locations) {
        long started = System.nanoTime();
        var seen = new Seen();
        var leaves = new Leaves();
        var written = new Written();
        var faults = new ConcurrentLinkedQueue<String>();
        var job = new ArtifactJob(digest, tree, jdkFeature, seen, leaves, written, store, parser, faults, classMemo ? new ClassMemo(locations) : null);

        // Step 2: P workers, jobs started in enumeration order (a FIFO queue). A failure anywhere aborts the boot: no ROOT.
        ExecutorService pool = Executors.newFixedThreadPool(workers, Thread.ofPlatform().name("jvmd-stage1-", 0).daemon(true).factory());
        try {
            var futures = new ArrayList<Future<?>>(locations.size());
            for (var location : locations) futures.add(pool.submit(() -> job.run(location)));
            for (var future : futures) {
                try { future.get(); }
                catch (ExecutionException failed) {
                    pool.shutdownNow();
                    var cause = failed.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException("Stage 1 job failed", cause);
                }
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Stage 1 interrupted", interrupted);
        } finally { pool.shutdown(); }

        // Step 3: the machine tree.
        leaves.resolvePending();
        var all = leaves.all();
        var sink = written.through(store);
        var machine = MachineTree.build(tree, all, sink);
        for (var leaf : all) store.putLeaf(leaf.k(), leaf.encode());
        sink.flush();

        // Step 4: paths, one sync, and the root, last.
        for (var observation : seen.all()) {
            var location = observation.location();
            var k = leaves.kFor(observation.bh());
            store.putPath(location.name(), MachineTree.encodePath(observation.bh(), k, location.size(), location.mtimeNanos()));
        }
        store.flush();
        store.sync();
        store.putRoot(MachineTree.encodeRoot(digest, Format.of(digest, jdkFeature), machine));

        var allFaults = new ArrayList<>(faults);
        for (var leaf : all) for (var fault : leaf.faults()) allFaults.add(fault);
        allFaults.sort(Enumerate::compareNames);
        return new Result(locations.size(), seen.distinct(), all.size(), written.count(), written.produced(), List.copyOf(allFaults),
                (System.nanoTime() - started) / 1_000_000, machine);
    }
}
