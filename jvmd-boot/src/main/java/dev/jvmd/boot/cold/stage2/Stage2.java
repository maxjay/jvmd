package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalFormat;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ModuleRecord;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.zip.ZipFile;

/**
 * Stage 2 (stage 2, 4 and 5.6): the LOCAL cold boot of one project. Input: the project model and a committed MACHINE. Output: the
 * LOCAL root, written last. A model fault (a cycle, a missing module, an unreadable source root) stops the boot with no root; a
 * fault of one file or one declaration is a row in the file table and never does.
 *
 * <p>Everything that shapes the result is a constructor argument with one call site ({@code BootDecision}): the digest, the tree's
 * boundary parameters, the JDK feature version, the worker count, the Maven repository root and the class parser stage 1 uses for
 * jars indexed on the spot. No LOCAL record is read before the root is written; MACHINE ({@code P|}, {@code L|}, {@code N|} and its
 * {@code ROOT}) is the committed layer below and may be.
 */
public final class Stage2 {
    /** What the boot did: the numbers of the one log line. {@code faults} are {@code path: declaration: reason}, and the classpath entries that bound to nothing. */
    public record Result(int modules, int sourceFiles, int parsedFiles, int sourceLeaves, int distinctLeafSets, int indexedOnTheSpot,
                         long nodes, List<String> faults, long wallMillis, Root root, Map<String, Identity> leaves,
                         Map<String, Identity> annotations, Timings timings) { }

    /** Where the time went, summed over jobs (so more than the wall time when jobs ran in parallel): the numbers of the cost model in 7.2. */
    public record Timings(long headerCompileMillis, long factsMillis, long definerIndexMillis, int externalFolds, int siblingFolds,
                          int conflictCacheHits, int conflictBuilds, long conflictNodeWrites, int definerCacheHits, int definerOwnerOpens, long leafSetEntriesCompared, int conflictApplies, int conflictOwnerOpens, int conflictTouched) { }

    private final Digest digest;
    private final ContentTree tree;
    private final int jdkFeature;
    private final int workers;
    private final Path repository;
    private final Stage1.Parser parser;

    public Stage2(Digest digest, ContentTree tree, int jdkFeature, int workers, Path repository, Stage1.Parser parser) {
        this.digest = digest;
        this.tree = tree;
        this.jdkFeature = jdkFeature;
        this.workers = workers;
        this.repository = repository;
        this.parser = parser;
    }

    /** {@code Digest(canonical project root path)}: names the project's records. */
    public static Identity projectKey(Digest digest, ProjectModel model) {
        Path root = Path.of(model.root()).toAbsolutePath().normalize();
        try { root = root.toRealPath(); } catch (IOException notThere) { /* the path as given */ }
        return digest.hash(root.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
    }

    /**
     * How close to the start of the boot that read it a file's modification time must be for the file to have changed again since
     * without a new time: one tick of the coarsest common file system clock (FAT's two seconds).
     */
    static final long RACY_MILLIS = 2_000;

    /**
     * Why the project's committed LOCAL root no longer describes it, or empty if it still does (demo plan A3). Until warm boot exists a
     * root is reused only when nothing it was built from changed, as far as can be seen without reading what MACHINE indexed:
     * <ul>
     * <li>the root verifies, and its FORMAT, MACHINE root and model bytes are the current ones;
     * <li>the source files are the ones it has rows for. A file whose size differs from its row's has changed. One whose modification
     *     time differs, or is within {@link #RACY_MILLIS} of the start of the boot that read it (so it could have changed again since
     *     within one tick), is hashed and compared with its row's κ; any other file is unchanged;
     * <li>no jar the root bound to nothing, because it was missing or could not be read, can now be opened.
     * </ul>
     * Not compared, so not detected; warm boot has to bind them: a jar or the JDK changed in place, since MACHINE is never re-committed;
     * the bytes of jars indexed on the spot (A7); processor paths and their configuration (A9); a file restored with another content
     * but its old size and time.
     *
     * <p>The rows compared are the root's own, read through its tree, so a row that an unfinished later boot wrote, or that belongs to a
     * file an earlier generation had, is never taken for the root's. Anything that cannot be read is a reason as well: the cold boot that
     * follows reports it if it is the project's fault. Reads the F and RT ranges of the tree, stats each source file once, and reads only
     * the files whose stamps cannot vouch for them.
     */
    public static Optional<String> stale(Digest digest, LocalStore store, ProjectModel model, String format, Path repository) {
        try {
            return check(digest, store, model, format, repository);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.of("it cannot be checked: " + unreadable);
        }
    }

    private static Optional<String> check(Digest digest, LocalStore store, ProjectModel model, String format, Path repository) throws IOException {
        var projectKey = projectKey(digest, model);
        var bytes = store.get(LocalStore.localRootKey(projectKey));
        if (bytes == null) return Optional.of("no committed root");
        // FORMAT first: the root of an older FORMAT may not decode with this codec.
        if (!LocalRoot.formatOf(bytes).equals(format)) return Optional.of("FORMAT " + LocalRoot.formatOf(bytes) + " is not " + format);
        LocalRoot root;
        try { root = LocalRoot.decode(digest, bytes); } catch (RuntimeException unreadable) { return Optional.of("the committed root does not verify"); }
        var machine = store.get(MachineStore.ROOT_KEY);
        if (machine == null || !MachineTree.decodeRoot(digest, machine).digest().equals(root.machineRoot())) return Optional.of("MACHINE changed");
        if (!digest.hash(model.bytes()).equals(root.modelHash())) return Optional.of("the project model changed");

        Function<Identity, byte[]> nodes = h -> store.get(MachineStore.nodeKey(h));
        var tree = new ContentTree(digest);
        var files = new HashMap<String, FileRow>();
        var routes = new ArrayList<Route>();
        var foreign = new ArrayList<String>();
        var filePrefix = LocalStore.fileKey(projectKey, "");
        tree.forEach(root.local().hash(), nodes, filePrefix, ContentTree.prefixEnd(filePrefix), entry -> {
            var path = dev.jvmd.core.tree.Mutf8.decode(entry.key(), filePrefix.length, entry.key().length - filePrefix.length);
            if (path.startsWith(ModuleJob.GENERATED)) return; // derived from the files around it, which this compares
            var row = committed(digest, store, entry);
            if (row == null) foreign.add("file row of " + path);
            else files.put(path, FileRow.decode(path, row, digest.width()));
        });
        var routePrefix = LocalStore.routePrefix(projectKey);
        tree.forEach(root.local().hash(), nodes, routePrefix, ContentTree.prefixEnd(routePrefix), entry -> {
            var row = committed(digest, store, entry);
            if (row == null) foreign.add("route row");
            else routes.add(Route.decode(row, digest.width()));
        });
        if (!foreign.isEmpty()) return Optional.of("the " + foreign.getFirst() + " is not the one the root committed");

        var unbound = new TreeSet<String>();
        for (var route : routes)
            for (var entry : route.entries())
                if (entry instanceof RouteEntry.Jar jar && jar.defaultK() == null) unbound.add(jar.location());
        for (var location : unbound) if (opens(repository.resolve(location))) return Optional.of(location + " can now be read");

        var stamp = store.get(LocalStore.bootStartKey(projectKey));
        // No stamp: every file is as close to the boot as can be, and is hashed.
        long started = stamp == null || stamp.length != Long.BYTES ? Long.MAX_VALUE : ByteBuffer.wrap(stamp).getLong();
        var seen = new HashSet<String>();
        for (var module : model.modules())
            for (var scope : List.of(module.main(), module.test()))
                for (var found : ModuleJob.sources(model, scope.sourceRoots())) {
                    if (!seen.add(found.path())) continue;
                    var row = files.get(found.path());
                    if (row == null) return Optional.of(found.path() + " was added");
                    if (row.size() != found.size()) return Optional.of(found.path() + " changed");
                    boolean vouched = row.mtimeNanos() == found.mtimeNanos() && found.mtimeNanos() / 1_000_000 < started - RACY_MILLIS;
                    if (!vouched && !digest.hash(Files.readAllBytes(found.file())).equals(row.kappa())) return Optional.of(found.path() + " changed");
                }
        for (var path : files.keySet()) if (!seen.contains(path)) return Optional.of(path + " was removed");
        return Optional.empty();
    }

    /** The row a tree entry names, or null if the raw row under its key is not that one. */
    private static byte[] committed(Digest digest, LocalStore store, Entry entry) {
        var row = store.get(entry.key());
        return row != null && digest.hash(row).equals(entry.h()) ? row : null;
    }

    private static boolean opens(Path jar) {
        if (!Files.isRegularFile(jar)) return false;
        try (var zip = new ZipFile(jar.toFile())) { return true; } catch (IOException unreadable) { return false; }
    }

    public Result run(LocalStore store, ProjectModel model) throws IOException {
        long started = System.nanoTime();
        long startedAt = System.currentTimeMillis(); // before any source file is statted: the skip rule's reference (see stale)
        // Step 1: model and order, one thread.
        model.validate();
        var order = Order.of(model);
        var machineRootBytes = store.get(MachineStore.ROOT_KEY);
        if (machineRootBytes == null) throw new IllegalStateException("No committed MACHINE root to build LOCAL on");
        var machineRoot = MachineTree.decodeRoot(digest, machineRootBytes).digest();
        var projectKey = projectKey(digest, model);

        try (var boot = new Boot(digest, tree, store, model, projectKey, repository)) {
            try (var defaults = new Defaults(digest, tree, jdkFeature, workers, repository, Path.of(model.jdkHome()), store, boot.written, parser)) {
                var jdk = defaults.jdk();
                boot.jdkLeaves = jdk.stream().map(RouteEntry.Jrt::k).distinct().sorted().toList();
                boot.jdkSetRoot = dev.jvmd.index.layer.local.Bind.leafSetRoot(digest, boot.jdkLeaves, boot.sink);
                boot.jdkLeafSet = boot.jdkSetRoot.hash();
                boot.sink.flush(); // publish main-thread leaf-set nodes before worker folds
                var jars = new ArrayList<ProjectModel.Dependency>();
                for (var module : model.modules())
                    for (var scope : List.of(module.main(), module.test())) for (var d : scope.dependencies()) if (d.module() == null) jars.add(d);
                defaults.prepare(jars);
                for (var module : model.modules()) {
                    for (int scope : new int[] {LocalStore.MAIN, LocalStore.TEST}) {
                        var route = new ArrayList<RouteEntry>(jdk);
                        // A test route is the module's own main leaf, then the main dependencies, then the test ones.
                        if (scope == LocalStore.TEST) route.add(new RouteEntry.Sibling(module.coordinate(), module.name()));
                        if (scope == LocalStore.TEST) addAll(route, defaults, module.main().dependencies());
                        addAll(route, defaults, module.scope(scope).dependencies());
                        var routeKey = Boot.routeKey(module.name(), scope);
                        boot.entries.put(routeKey, List.copyOf(route));
                        // This is a DAG already enforced by Order: main dependencies finish first, then own main before test.
                        // Root modules share the JDK state. No search through all earlier leaf sets is needed.
                        for (var entry : route) if (entry instanceof RouteEntry.Sibling sibling) {
                            boot.parents.put(routeKey, Boot.routeKey(sibling.module(), LocalStore.MAIN));
                            break;
                        }
                    }
                }
                boot.faults.addAll(defaults.notes());
                runInDependencyOrder(boot, order);

                // Step 3: the LOCAL tree over the records. One batch for the records, one for the tree, one for the root.
                var records = new ConcurrentSkipListMap<byte[], byte[]>(Arrays::compareUnsigned);
                records.putAll(boot.definers);
                for (var module : model.modules()) {
                    var descriptor = new ModuleRecord(module.coordinate(), effectiveRelease(module), module.moduleInfo(), module.javacOptions(),
                            module.main().sourceRoots(), module.test().sourceRoots()).encode();
                    put(store, records, LocalStore.moduleKey(projectKey, module.name()), descriptor);
                    for (int scope : new int[] {LocalStore.MAIN, LocalStore.TEST}) {
                        var route = boot.routes.get(Boot.routeKey(module.name(), scope)).encode();
                        put(store, records, LocalStore.routeKey(projectKey, module.name(), scope), route);
                        var binding = new dev.jvmd.index.layer.local.SourceLeaf(boot.built.leaf(module.name(), scope).k(), boot.built.a(module.name(), scope));
                        put(store, records, LocalStore.sourceLeafKey(projectKey, module.name(), scope), binding.encode());
                    }
                }
                for (var row : new java.util.TreeMap<>(boot.files).values()) {
                    put(store, records, LocalStore.fileKey(projectKey, row.path()), row.encode());
                    for (var dependency : dev.jvmd.index.layer.local.ReverseIndex.dependencies(row))
                        records.put(dependency.key(projectKey, row.path()), Entry.NONE);
                }
                store.flush();
                var entries = new ArrayList<Entry>(records.size());
                for (var e : records.entrySet()) entries.add(new Entry(e.getKey(), Entry.NONE, digest.hash(e.getValue())));
                var local = tree.build(entries, boot.sink);
                boot.sink.flush();

                // Step 4: sync once, then the root, last.
                store.sync();
                var format = LocalFormat.of(Format.of(digest, jdkFeature));
                store.putLocalRoot(digest, projectKey, LocalRoot.encode(digest, format, local, machineRoot, digest.hash(model.bytes())));
                store.put(LocalStore.bootStartKey(projectKey), ByteBuffer.allocate(Long.BYTES).putLong(startedAt).array());
                store.flush();

                var leaves = new java.util.TreeMap<String, Identity>();
                var annotations = new java.util.TreeMap<String, Identity>();
                for (var module : model.modules()) {
                    leaves.put(module.name() + "/main", boot.built.leaf(module.name(), LocalStore.MAIN).k());
                    leaves.put(module.name() + "/test", boot.built.leaf(module.name(), LocalStore.TEST).k());
                    annotations.put(module.name() + "/main", boot.built.a(module.name(), LocalStore.MAIN));
                    annotations.put(module.name() + "/test", boot.built.a(module.name(), LocalStore.TEST));
                }
                var faults = new ArrayList<>(boot.faults);
                java.util.Collections.sort(faults);
                return new Result(model.modules().size(), boot.sourceFiles.get(), boot.parsedFiles.get(), boot.sourceLeaves.get(),
                        boot.indexMemo.distinctLeafSets(), defaults.indexedOnTheSpot(), boot.written.count(), List.copyOf(faults),
                        (System.nanoTime() - started) / 1_000_000, local, Map.copyOf(leaves), Map.copyOf(annotations),
                        new Timings(boot.headerNanos.get() / 1_000_000, boot.factsNanos.get() / 1_000_000, boot.definerNanos.get() / 1_000_000,
                        boot.indexMemo.externalFolds(), boot.indexMemo.siblingFolds(),
                        boot.conflictCacheHits.get(), boot.conflictBuilds.get(), boot.conflictNodeWrites.get(),
                        boot.definerCacheHits.get(), boot.definerOwnerOpens.get(), boot.leafSetEntriesCompared.get(),
                        boot.conflictApplies.get(), boot.conflictOwnerOpens.get(), boot.conflictTouched.get()));
            }
        }
    }

    /** One LOCAL record: buffered in the store, and in the set the project's tree is built over. */
    private static void put(LocalStore store, Map<byte[], byte[]> records, byte[] key, byte[] value) {
        store.put(key, value);
        records.put(key, value);
    }

    private static void addAll(List<RouteEntry> route, Defaults defaults, List<ProjectModel.Dependency> dependencies) throws IOException {
        for (var d : dependencies) route.add(d.module() != null ? new RouteEntry.Sibling(d.coordinate(), d.module()) : defaults.jar(d));
    }

    /** The release javac ran at (C.1, E.4), as the module descriptor records it. */
    private int effectiveRelease(ProjectModel.Module module) {
        return HeaderCompiler.effectiveRelease(module.release(), module.javacOptions().contains("--enable-preview"), jdkFeature);
    }

    /**
     * Step 2: a module's job starts when the modules it depends on have finished; modules with nothing unbuilt in front of them run in
     * parallel on {@code workers} threads. A job failure that is not a file or declaration fault aborts the boot: no root.
     */
    private void runInDependencyOrder(Boot boot, Order order) {
        ExecutorService pool = Executors.newFixedThreadPool(workers, Thread.ofPlatform().name("jvmd-stage2-", 0).daemon(true).factory());
        try {
            var futures = new LinkedHashMap<String, CompletableFuture<Void>>();
            for (var module : order.modules()) {
                var before = order.dependencies().get(module.name()).stream().map(futures::get).toArray(CompletableFuture[]::new);
                futures.put(module.name(), CompletableFuture.allOf(before).thenRunAsync(() -> {
                    try {
                        var job = new ModuleJob(boot);
                        job.run(module, LocalStore.MAIN);
                        job.run(module, LocalStore.TEST);
                    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }, pool));
            }
            try { CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).join(); }
            catch (CompletionException failed) {
                pool.shutdownNow();
                var cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Stage 2 job failed", cause);
            }
        } finally {
            // Join every worker before the caller closes the store they write into (as in stage 1).
            pool.close();
        }
    }
}
