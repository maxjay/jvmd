package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.ArtifactJob;
import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.boot.cold.stage1.Written;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.Bind;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@code Defaults} (stage 2, 3.15 and step 1.3): a coordinate's default binding, the MACHINE leaf found through {@code P|location},
 * or, when MACHINE has no such record, the leaf indexed on the spot through stage 1's {@code ArtifactJob} into the shared space.
 * Nothing is written to {@code P|}: that record describes MACHINE's own commit, and when warm boot enumerates the jar it finds the
 * leaf already present and pays nothing. A location that cannot be bound has no default; the entry then binds to nothing and the
 * boot notes it.
 *
 * <p>Step 1 runs on the calling thread, but the locations MACHINE does not have are indexed together, on {@code workers} threads, as
 * stage 1 indexes its own: they are independent jobs. The jobs of step 2 only read what it decided.
 */
final class Defaults implements AutoCloseable {
    private final Digest digest;
    private final ContentTree tree;
    private final int jdkFeature;
    private final int workers;
    private final Path repository;
    private final Path jdkHome;
    private final LocalStore store;
    private final Written written;
    private final Stage1.Parser parser;
    private final Map<String, Bind.Leaf> jars = new HashMap<>();
    private final List<FileSystem> opened = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private List<RouteEntry.Jrt> jdk;
    private int indexedOnTheSpot;

    Defaults(Digest digest, ContentTree tree, int jdkFeature, int workers, Path repository, Path jdkHome, LocalStore store, Written written, Stage1.Parser parser) {
        this.digest = digest;
        this.tree = tree;
        this.jdkFeature = jdkFeature;
        this.workers = workers;
        this.repository = repository;
        this.jdkHome = jdkHome;
        this.store = store;
        this.written = written;
        this.parser = parser;
    }

    /** What could not be bound, as {@code location: reason}: the faults of the model's classpath, not of any source file. */
    List<String> notes() { return notes; }

    /** How many locations MACHINE did not have and this boot indexed itself. */
    int indexedOnTheSpot() { return indexedOnTheSpot; }

    /** Every module of the JDK, in module-name order, each with its leaf: the head of every route. */
    List<RouteEntry.Jrt> jdk() throws IOException {
        if (jdk != null) return jdk;
        // The module names are free; the digest of lib/modules (over a hundred megabytes) is only needed to index a module MACHINE lacks.
        var names = moduleNames();
        var found = new java.util.TreeMap<String, Bind.Leaf>(Enumerate::compareNames);
        boolean missing = false;
        for (var name : names) {
            Bind.Leaf k = lookup(locationName(name));
            if (k == null) missing = true; else found.put(name, k);
        }
        if (missing) {
            var toIndex = new ArrayList<Enumerate.Location>();
            for (var location : Enumerate.jdk(jdkHome, digest, opened)) {
                if (found.containsKey(location.module())) continue;
                Bind.Leaf k = lookup(location.name());
                if (k != null) found.put(location.module(), k); else toIndex.add(location);
            }
            var indexed = indexAll(toIndex);
            for (int i = 0; i < toIndex.size(); i++) {
                var location = toIndex.get(i);
                if (indexed.get(i) == null) notes.add(location.name() + ": module could not be indexed"); else found.put(location.module(), indexed.get(i));
            }
        }
        var out = new ArrayList<RouteEntry.Jrt>();
        for (var e : found.entrySet()) out.add(new RouteEntry.Jrt("jrt:/" + e.getKey(), e.getKey(), e.getValue().k(), e.getValue().a()));
        return jdk = List.copyOf(out);
    }

    /** {@code jrt:/<module>@<jdkHome>}: the name stage 1 gave the location (its {@code P|} key). */
    private String locationName(String module) { return "jrt:/" + module + "@" + jdkHome; }

    /** The modules of the JDK, in module-name order (unsigned bytes), from its runtime image. */
    private List<String> moduleNames() throws IOException {
        FileSystem jrt;
        if (Enumerate.sameFile(jdkHome, Path.of(System.getProperty("java.home")))) jrt = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
        else {
            jrt = java.nio.file.FileSystems.newFileSystem(java.net.URI.create("jrt:/"), Map.of("java.home", jdkHome.toString()));
            opened.add(jrt);
        }
        var names = new ArrayList<String>();
        try (var stream = Files.newDirectoryStream(jrt.getPath("/modules"))) { for (var m : stream) names.add(m.getFileName().toString()); }
        names.sort(Enumerate::compareNames);
        return names;
    }

    /**
     * Decides the default of every jar of the model at once: the ones MACHINE has are looked up, the others are indexed together on the
     * pool. {@link #jar} then answers from what was decided.
     */
    void prepare(List<ProjectModel.Dependency> dependencies) throws IOException {
        var toIndex = new ArrayList<Enumerate.Location>();
        var queued = new java.util.HashSet<String>();
        for (var dependency : dependencies) {
            var location = dependency.location();
            if (jars.containsKey(location) || queued.contains(location)) continue;
            Bind.Leaf k = lookup(location);
            if (k == null) {
                var file = repository.resolve(location);
                if (!Files.isRegularFile(file)) notes.add(location + ": no such file under " + repository);
                else { toIndex.add(Enumerate.jar(location, file)); queued.add(location); continue; }
            }
            jars.put(location, k);
        }
        var indexed = indexAll(toIndex);
        for (int i = 0; i < toIndex.size(); i++) {
            var location = toIndex.get(i).name();
            if (indexed.get(i) == null) notes.add(location + ": not a readable archive");
            jars.put(location, indexed.get(i));
        }
    }

    /** The jar entry of a dependency of the model. */
    RouteEntry.Jar jar(ProjectModel.Dependency dependency) throws IOException {
        var location = dependency.location();
        if (!jars.containsKey(location)) prepare(List.of(dependency));
        var leaf = jars.get(location);
        return new RouteEntry.Jar(dependency.coordinate(), location, leaf == null ? null : leaf.k(), leaf == null ? null : leaf.a());
    }

    /** {@code P|location -> k}: null if MACHINE has no record, or its record has no leaf (an unreadable archive). */
    private Bind.Leaf lookup(String location) {
        var value = store.get(MachineStore.pathKey(location));
        if (value == null) return null;
        var path = MachineTree.decodePath(value, digest.width());
        return path.k() == null ? null : new Bind.Leaf(path.k(), path.a());
    }

    /** The leaf of each location, in order, indexed on the pool; null for one that is not a readable archive. */
    private List<Bind.Leaf> indexAll(List<Enumerate.Location> locations) {
        indexedOnTheSpot += locations.size();
        if (locations.isEmpty()) return List.of();
        var out = new ArrayList<Bind.Leaf>(locations.size());
        int threads = Math.min(workers, locations.size());
        if (threads <= 1) {
            for (var location : locations) out.add(index(location));
            return out;
        }
        var pool = Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("jvmd-defaults-", 0).daemon(true).factory());
        try {
            var futures = new ArrayList<Future<Bind.Leaf>>(locations.size());
            for (var location : locations) futures.add(pool.submit(() -> index(location)));
            for (var future : futures) out.add(future.get());
            return out;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Indexing a location failed", failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while indexing", interrupted);
        } finally { pool.shutdownNow(); }
    }

    private Bind.Leaf index(Enumerate.Location location) {
        var indexed = ArtifactJob.index(digest, tree, jdkFeature, written, store, parser, location);
        return indexed.k() == null ? null : new Bind.Leaf(indexed.k(), indexed.a());
    }

    @Override public void close() {
        for (var fs : opened) { try { fs.close(); } catch (IOException ignored) { /* nothing to recover */ } }
    }
}
