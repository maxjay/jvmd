package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.ArtifactJob;
import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.boot.cold.stage1.Written;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code Defaults} (stage 2, 3.15 and step 1.3): a coordinate's default binding, the MACHINE leaf found through {@code P|location},
 * or, when MACHINE has no such record, the leaf indexed on the spot through stage 1's {@code ArtifactJob} into the shared space.
 * Nothing is written to {@code P|}: that record describes MACHINE's own commit, and when warm boot enumerates the jar it finds the
 * leaf already present and pays nothing. A location that cannot be bound has no default; the entry then binds to nothing and the
 * boot notes it.
 *
 * <p>One thread: this is step 1, and the jobs of step 2 only read what it decided.
 */
final class Defaults implements AutoCloseable {
    private final Digest digest;
    private final ContentTree tree;
    private final int jdkFeature;
    private final Path repository;
    private final Path jdkHome;
    private final LocalStore store;
    private final Written written;
    private final Stage1.Parser parser;
    private final Map<String, Identity> jars = new HashMap<>();
    private final List<FileSystem> opened = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private List<RouteEntry.Jrt> jdk;
    private int indexedOnTheSpot;

    Defaults(Digest digest, ContentTree tree, int jdkFeature, Path repository, Path jdkHome, LocalStore store, Written written, Stage1.Parser parser) {
        this.digest = digest;
        this.tree = tree;
        this.jdkFeature = jdkFeature;
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
        var found = new java.util.TreeMap<String, Identity>(Enumerate::compareNames);
        boolean missing = false;
        for (var name : names) {
            Identity k = lookup(locationName(name));
            if (k == null) missing = true; else found.put(name, k);
        }
        if (missing) {
            for (var location : Enumerate.jdk(jdkHome, digest, opened)) {
                if (found.containsKey(location.module())) continue;
                Identity k = lookup(location.name());
                if (k == null) k = indexNow(location);
                if (k == null) notes.add(location.name() + ": module could not be indexed"); else found.put(location.module(), k);
            }
        }
        var out = new ArrayList<RouteEntry.Jrt>();
        for (var e : found.entrySet()) out.add(new RouteEntry.Jrt("jrt:/" + e.getKey(), e.getKey(), e.getValue()));
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

    /** The jar entry of a dependency of the model. */
    RouteEntry.Jar jar(ProjectModel.Dependency dependency) throws IOException {
        var location = dependency.location();
        if (jars.containsKey(location)) return new RouteEntry.Jar(dependency.coordinate(), location, jars.get(location));
        Identity k = lookup(location);
        if (k == null) {
            var file = repository.resolve(location);
            if (!Files.isRegularFile(file)) notes.add(location + ": no such file under " + repository);
            else {
                k = indexNow(Enumerate.jar(location, file));
                if (k == null) notes.add(location + ": not a readable archive");
            }
        }
        jars.put(location, k);
        return new RouteEntry.Jar(dependency.coordinate(), location, k);
    }

    /** {@code P|location -> k}: null if MACHINE has no record, or its record has no leaf (an unreadable archive). */
    private Identity lookup(String location) {
        var value = store.getPath(location);
        return value == null ? null : MachineTree.decodePath(value, digest.width()).k();
    }

    private Identity indexNow(Enumerate.Location location) {
        indexedOnTheSpot++;
        return ArtifactJob.index(digest, tree, jdkFeature, written, store, parser, location).k();
    }

    @Override public void close() {
        for (var fs : opened) { try { fs.close(); } catch (IOException ignored) { /* nothing to recover */ } }
    }
}
