package dev.jvmd.boot;

import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.boot.cold.stage2.Stage2;
import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.index.layer.local.BodiesRoot;
import dev.jvmd.index.layer.local.LocalFormat;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.core.Config;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.rocks.layer.Generation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses cold or warm for the MACHINE layer (stage 1, 5.6). The digest, boundary parameters, JDK feature, worker count and class
 * parser are decided here, once, and handed to {@link Stage1}. The generation directory name carries the digest, so changing it
 * is a new FORMAT and a cold boot, never a migration.
 */
public final class BootDecision {
    private static final System.Logger LOG = System.getLogger("dev.jvmd.boot");

    private BootDecision() { }

    /**
     * Boots MACHINE into {@code indexDir/<FORMAT>}. A generation that already has a ROOT would be a warm boot, which does not
     * exist yet: log one line and return, without touching it. Nothing on main reads this generation, so skipping is correct and
     * failing would stop every daemon restart. Warm boot replaces this branch.
     *
     * @return the boot's result, or empty if a committed generation was skipped
     */
    public static Optional<Stage1.Result> machine(Path indexDir, Config config) throws IOException {
        var digest = Sha256.INSTANCE;
        // The running JVM's feature version, not any configured JDK's. It selects multi-release entries (C.2) and is part of FORMAT.
        // A configured JDK newer than the runtime therefore faults every one of its classes on the major_version check, and its
        // multi-release selection uses the runtime's feature. Acceptable until stage 2 routes per-JDK views; written down here
        // so it is not rediscovered.
        int jdkFeature = Runtime.version().feature();
        var generation = Generation.of(indexDir, Format.of(digest, jdkFeature));
        if (generation.hasRoot()) {
            LOG.log(System.Logger.Level.INFO, "machine generation {0} committed; warm boot not implemented, skipping", generation.directory());
            return Optional.empty();
        }

        var homes = new ArrayList<Path>();
        homes.add(Path.of(System.getProperty("java.home")));
        for (var configured : new Path[] {config.jdkHome(), config.jbrHome()})
            if (configured != null && Files.isRegularFile(configured.resolve("lib/modules")) && homes.stream().noneMatch(h -> sameFile(h, configured))) homes.add(configured);

        var stage1 = new Stage1(digest, new ContentTree(digest), jdkFeature, Runtime.getRuntime().availableProcessors(), ClassFacts::of);
        try (var store = generation.create()) {
            var result = stage1.run(store, config.m2Repo(), List.copyOf(homes));
            log(result);
            return Optional.of(result);
        }
    }

    /** Headers are absent when this invocation completes bodies from an already committed LOCAL snapshot. */
    public record LocalResult(Optional<Stage2.Result> headers, Stage3.Result bodies) { }

    /**
     * Completes a cold project generation in the committed MACHINE store: headers publish LROOT, then bodies publish BROOT.
     * A matching pair is the warm-boot boundary and is left untouched. If headers committed before an interruption, Stage 3
     * resumes from that snapshot without rebuilding headers; its normal source/model/MACHINE checks still apply.
     *
     * @param repository the Maven repository root the model's locations are relative to
     * @return the completed body generation and any freshly built headers, or empty for an already complete generation
     * @throws IllegalStateException if MACHINE has not been booted
     */
    public static Optional<LocalResult> local(Path indexDir, ProjectModel model, Path repository) throws IOException {
        var digest = Sha256.INSTANCE;
        int jdkFeature = Runtime.version().feature();
        var format = Format.of(digest, jdkFeature);
        var generation = Generation.of(indexDir, format);
        var tree = new ContentTree(digest);
        int workers = Runtime.getRuntime().availableProcessors();
        var project = Stage2.projectKey(digest, model);
        // One open of the store answers everything: whether MACHINE is committed, whether this project is, and the boot itself.
        try (var store = generation.openLocal()) {
            var existing = store.get(LocalStore.localRootKey(project));
            Optional<Stage2.Result> headers = Optional.empty();
            if (existing != null && LocalRoot.formatOf(existing).equals(LocalFormat.of(format))) {
                var local = LocalRoot.decode(digest, existing);
                var bodies = store.get(LocalStore.bodiesRootKey(project));
                if (bodies != null && LocalRoot.formatOf(bodies).equals(BodiesRoot.format(local.format()))
                        && BodiesRoot.decode(bodies, digest.width()).current(local)) {
                    LOG.log(System.Logger.Level.INFO, "local and bodies generations for {0} committed; warm boot not implemented, skipping", model.root());
                    return Optional.empty();
                }
            } else {
                var result = new Stage2(digest, tree, jdkFeature, workers, repository, ClassFacts::of).run(store, model);
                log(result);
                headers = Optional.of(result);
            }
            var bodies = new Stage3(digest, tree, jdkFeature, workers, repository).run(store, model);
            log(bodies);
            return Optional.of(new LocalResult(headers, bodies));
        }
    }

    private static void log(Stage3.Result result) {
        var prefix = java.util.HexFormat.of().formatHex(result.bodies().bodiesRoot().view(), 0, 8);
        long classes = result.scopes().values().stream().mapToLong(scope -> scope.output().count()).sum();
        long errors = result.scopes().values().stream().flatMap(scope -> scope.diagnostics().stream())
                .filter(message -> message.diagnostic().kind() == 0).count();
        LOG.log(System.Logger.Level.INFO, "bodies cold boot: files={0} classes={1} errors={2} faults={3} wall_ms={4} root={5}",
                String.valueOf(result.files()), String.valueOf(classes), String.valueOf(errors), String.valueOf(result.faults().size()),
                String.valueOf(result.wallMillis()), prefix);
        if (!result.faults().isEmpty()) LOG.log(System.Logger.Level.WARNING, "bodies cold boot faults: {0}", String.join("; ", result.faults()));
    }

    private static void log(Stage2.Result r) {
        var prefix = new StringBuilder();
        for (byte b : r.root().hash().view()) { if (prefix.length() >= 16) break; prefix.append(String.format("%02x", b)); }
        LOG.log(System.Logger.Level.INFO, "local cold boot: modules={0} source_files={1} parsed_files={2} source_leaves={3} leaf_sets={4} indexed_on_the_spot={5} nodes={6} faults={7} wall_ms={8} root={9}",
                String.valueOf(r.modules()), String.valueOf(r.sourceFiles()), String.valueOf(r.parsedFiles()), String.valueOf(r.sourceLeaves()),
                String.valueOf(r.distinctLeafSets()), String.valueOf(r.indexedOnTheSpot()), String.valueOf(r.nodes()), String.valueOf(r.faults().size()), String.valueOf(r.wallMillis()), prefix);
        if (!r.faults().isEmpty()) LOG.log(System.Logger.Level.WARNING, "local cold boot faults: {0}", String.join("; ", r.faults()));
    }

    private static boolean sameFile(Path a, Path b) {
        try { return Files.isSameFile(a, b); } catch (IOException e) { return a.equals(b); }
    }

    /** D.4: one line, the only place hex appears. A fault list is written in full only when non-empty. */
    private static void log(Stage1.Result r) {
        var prefix = new StringBuilder();
        for (byte b : r.root().hash().view()) { if (prefix.length() >= 16) break; prefix.append(String.format("%02x", b)); }
        LOG.log(System.Logger.Level.INFO, "machine cold boot: locations={0} distinct_jars={1} leaves={2} nodes={3} faults={4} wall_ms={5} root={6}",
                String.valueOf(r.locations()), String.valueOf(r.distinctJars()), String.valueOf(r.leaves()), String.valueOf(r.nodes()), String.valueOf(r.faults().size()), String.valueOf(r.wallMillis()), prefix);
        if (!r.faults().isEmpty()) LOG.log(System.Logger.Level.WARNING, "machine cold boot faults: {0}", String.join("; ", r.faults()));
    }
}
