package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.Written;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.Stubs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

/**
 * Everything the jobs of one boot share and nothing that outlives it (stage 2, 2.6): {@code Built}, {@code Written},
 * {@code FileMemo}, {@code IndexMemo}, the records to be written, the stub directories javac reads sibling modules from, and the
 * counters of the log line. None of it is written except through the store.
 */
final class Boot implements AutoCloseable {
    final Digest digest;
    final ContentTree tree;
    final LocalStore store;
    final ProjectModel model;
    final Identity projectKey;
    final Path repository;
    final Written written = new Written();
    final NodeSink sink;
    final Built built = new Built();
    final FileMemo fileMemo = new FileMemo();
    final IndexMemo indexMemo = new IndexMemo();
    /** The routes as step 1 resolved them: {@code module \0 scope -> entries}. */
    final Map<String, List<RouteEntry>> entries = new ConcurrentHashMap<>();
    /** The bound routes as the jobs wrote them. */
    final Map<String, Route> routes = new ConcurrentHashMap<>();
    final Map<String, FileRow> files = new ConcurrentHashMap<>();
    /** {@code DD|} and {@code DC|} records this boot wrote: they are part of the LOCAL tree of the project that used them. */
    final ConcurrentSkipListMap<byte[], byte[]> definers = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
    final ConcurrentLinkedQueue<String> faults = new ConcurrentLinkedQueue<>();
    final AtomicInteger sourceFiles = new AtomicInteger(), parsedFiles = new AtomicInteger(), sourceLeaves = new AtomicInteger(), compiledFiles = new AtomicInteger();
    /** Summed over jobs, so with several workers they exceed the wall time: header compilation (parse, enter, member completion), Φ_src, and definer indexes. */
    final java.util.concurrent.atomic.AtomicLong headerNanos = new java.util.concurrent.atomic.AtomicLong(), factsNanos = new java.util.concurrent.atomic.AtomicLong(),
            definerNanos = new java.util.concurrent.atomic.AtomicLong();
    private final ConcurrentHashMap<Identity, MachineLeaf> leaves = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, CompletableFuture<Void>> leafDone = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, Path> stubDirs = new ConcurrentHashMap<>();
    private volatile Path stubRoot;

    Boot(Digest digest, ContentTree tree, LocalStore store, ProjectModel model, Identity projectKey, Path repository) {
        this.digest = digest;
        this.tree = tree;
        this.store = store;
        this.model = model;
        this.projectKey = projectKey;
        this.repository = repository;
        this.sink = written.through(store);
    }

    static String routeKey(String module, int scope) { return module + "\0" + scope; }

    /** {@code L|k}: a MACHINE read. A leaf this boot built is already committed when its job registered it. */
    MachineLeaf leaf(Identity k) {
        return leaves.computeIfAbsent(k, key -> {
            var bytes = store.getLeaf(key);
            if (bytes == null) throw new IllegalStateException("No leaf for a bound key");
            return MachineLeaf.decode(bytes, digest.width());
        });
    }

    /** {@code N|hash}: a MACHINE read. */
    byte[] node(Identity hash) {
        var bytes = store.getNode(hash);
        if (bytes == null) throw new IllegalStateException("No node for a stored hash");
        return bytes;
    }

    /** The future of one leaf key: the job that claimed it completes it once {@code L|k} and its nodes are committed; others wait on it. */
    CompletableFuture<Void> leafDone(Identity k) { return leafDone.computeIfAbsent(k, key -> new CompletableFuture<>()); }

    /** A directory of the stub class files of leaf {@code k}, made on first use (3.14). Also written as {@code S|k}, shared across projects. */
    Path stubDir(Identity k) {
        return stubDirs.computeIfAbsent(k, key -> {
            try {
                var stubs = Stubs.stubs(tree, leaf(key), this::node);
                store.putStub(key, Stubs.encode(stubs));
                if (stubRoot == null) synchronized (this) { if (stubRoot == null) stubRoot = Files.createTempDirectory("jvmd-stubs-"); }
                var dir = Files.createTempDirectory(stubRoot, "s");
                for (var stub : stubs) {
                    var file = dir.resolve(stub.internalName() + ".class");
                    Files.createDirectories(file.getParent());
                    Files.write(file, stub.bytes());
                }
                return dir;
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @Override public void close() {
        var root = stubRoot;
        if (root == null) return;
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) { /* a temporary directory the OS will collect */ }
    }
}
