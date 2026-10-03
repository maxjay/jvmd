package dev.jvmd.boot;

import dev.jvmd.boot.cold.stage1.Stage1;
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
