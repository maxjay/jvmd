package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.LocalFormat;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ModuleRecord;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Stage 2 (stage 2, 4 and 5.6): the LOCAL cold boot of one project. Input: the project model and a committed MACHINE. Output: the
 * LOCAL root, written last. A model fault (a cycle, a missing module, an unreadable source root) stops the boot with no root; a
 * fault of one file or one declaration is a row in the file table and never does.
 *
 * <p>Everything that shapes the result is a constructor argument with one call site ({@code BootDecision}): the digest, the tree's
 * boundary parameters, the JDK feature version, the worker count, the Maven repository root and the class parser stage 1 uses for
 * jars indexed on the spot. Before publication it reads MACHINE and shared derivations/capabilities listed in {@link LocalStore};
 * it does not read a previous project's mutable file, route, configuration or domain records to construct the new root.
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

    public Result run(LocalStore store, ProjectModel model) throws IOException {
        long started = System.nanoTime();
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
                for (var module : model.modules()) {
                    for (var scope : List.of(module.main(), module.test())) for (var d : scope.dependencies()) if (d.module() == null) jars.add(d);
                    jars.addAll(module.processing().path());
                }
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

                // Global processor history can advance in another project/body generation without mutating this LOCAL snapshot.
                for (var observation : boot.processorCapabilities.entrySet())
                    store.observeProcessor(observation.getKey().path(), observation.getKey().name(), observation.getValue());

                // Step 3: the LOCAL tree over the records. One batch for the records, one for the tree, one for the root.
                var records = new ConcurrentSkipListMap<byte[], byte[]>(Arrays::compareUnsigned);
                records.putAll(boot.definers);
                for (var record : boot.processingRecords.entrySet()) {
                    // Content-addressed derivations were already deduplicated by GeneratedOutputs.
                    if (record.getKey()[0] == 'G') records.put(record.getKey(), record.getValue());
                    else put(store, records, record.getKey(), record.getValue());
                }
                for (var module : model.modules()) {
                    var descriptor = new ModuleRecord(module.coordinate(), effectiveRelease(module), module.moduleInfo(), module.javacOptions(),
                            module.main().sourceRoots(), module.test().sourceRoots(), module.processing()).encode();
                    put(store, records, LocalStore.moduleKey(projectKey, module.name()), descriptor);
                    for (int scope : new int[] {LocalStore.MAIN, LocalStore.TEST}) {
                        var route = boot.routes.get(Boot.routeKey(module.name(), scope)).encode();
                        put(store, records, LocalStore.routeKey(projectKey, module.name(), scope), route);
                        var binding = new dev.jvmd.index.layer.local.SourceLeaf(boot.built.leaf(module.name(), scope).k(), boot.built.a(module.name(), scope));
                        put(store, records, LocalStore.sourceLeafKey(projectKey, module.name(), scope), binding.encode());
                    }
                }
                for (var file : new java.util.TreeMap<>(boot.files).entrySet()) {
                    var row = file.getValue();
                    put(store, records, LocalStore.fileKey(projectKey, file.getKey()), row.encode());
                    for (var dependency : dev.jvmd.index.layer.local.ReverseIndex.dependencies(row))
                        records.put(dependency.key(projectKey, file.getKey()), Entry.NONE);
                }
                for (var diagnostics : new java.util.TreeMap<>(boot.headerDiagnostics).entrySet())
                    put(store, records, LocalStore.headerDiagnosticsKey(projectKey, diagnostics.getKey()), diagnostics.getValue().encode());
                store.flush();
                var entries = new ArrayList<Entry>(records.size());
                var values=new java.util.HashSet<Identity>();
                for (var e : records.entrySet()) {
                    var hash=digest.hash(e.getValue());
                    entries.add(new Entry(e.getKey(), Entry.NONE, hash));
                    if(values.add(hash))store.put(LocalStore.bodyValueKey(hash),e.getValue());
                }
                store.flush();
                var local = tree.build(entries, boot.sink);
                boot.sink.flush();

                // Step 4: sync once, then the root, last.
                store.sync();
                var format = LocalFormat.of(Format.of(digest, jdkFeature));
                store.putLocalRoot(digest, projectKey, LocalRoot.encode(digest, format, local, machineRoot, digest.hash(model.bytes())));

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
        } finally { pool.shutdown(); }
    }
}
