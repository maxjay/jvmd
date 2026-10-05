package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stage 1 (stage 1, 5.5): the MACHINE cold boot. Input: the repository directory (and the JDK homes). Output: ROOT written, last.
 * Before ROOT is written the layer does not exist. No store read happens before that write.
 *
 * <p>Everything that shapes the result is a constructor argument with one call site ({@code BootDecision}): the digest, the
 * tree's boundary parameters, the JDK feature version, the worker count and the class parser. There is no other configuration
 * and no switch that changes what the boot does.
 */
public final class Stage1 {
    /** Φ. The one seam the tests need: counting invocations (invariant 6) and observing when entries are read (invariant 7). */
    @FunctionalInterface public interface Parser { ClassFacts parse(Digest digest, byte[] bytes, String expectedOwner) throws ClassFacts.Fault; }

    /** What the boot did: the numbers of the one log line (D.4). {@code faults} are {@code location: entry-or-reason}. */
    public record Result(int locations, int distinctJars, int leaves, long nodes, long nodesProduced, List<String> faults, long wallMillis, Root root) { }

    private final Digest digest;
    private final ContentTree tree;
    private final int jdkFeature;
    private final int workers;
    private final Parser parser;

    public Stage1(Digest digest, ContentTree tree, int jdkFeature, int workers, Parser parser) {
        this.digest = digest;
        this.tree = tree;
        this.jdkFeature = jdkFeature;
        this.workers = workers;
        this.parser = parser;
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
        var job = new ArtifactJob(digest, tree, jdkFeature, seen, leaves, written, store, parser, new ClassMemo(digest, locations));

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

        // Step 3: the machine tree. Each leaf was written by the job that won its claim.
        var all = leaves.all();
        var sink = written.through(store);
        var machine = MachineTree.build(tree, all, sink);
        sink.flush();

        // Step 4: paths (with their faults), one sync, and the root, last.
        var faults = new ArrayList<String>();
        for (var observation : seen.all()) {
            var location = observation.location();
            var unreadable = seen.unreadableReason(observation);
            if (unreadable != null) {
                // Not an archive, or not readable: the zero identity, no k, one fault naming the reason (B.5).
                store.putPath(location.name(), MachineTree.encodePath(Identity.zero(digest.width()), null, null, location.size(), location.mtimeNanos(), List.of(unreadable)));
                faults.add(location.name() + ": " + unreadable);
                continue;
            }
            var skipped = seen.skipped(observation.bh());
            store.putPath(location.name(), MachineTree.encodePath(observation.bh(), leaves.kFor(observation.bh()), leaves.aFor(observation.bh()), location.size(), location.mtimeNanos(), skipped));
            for (var entry : skipped) faults.add(location.name() + ": " + entry);
        }
        store.flush();
        store.sync();
        store.putRoot(MachineTree.encodeRoot(digest, Format.of(digest, jdkFeature), machine));

        return new Result(locations.size(), seen.distinct(), all.size(), written.count(), written.produced(), List.copyOf(faults),
                (System.nanoTime() - started) / 1_000_000, machine);
    }
}
