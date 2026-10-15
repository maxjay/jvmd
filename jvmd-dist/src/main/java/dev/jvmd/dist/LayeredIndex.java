package dev.jvmd.dist;

import dev.jvmd.boot.BootDecision;
import dev.jvmd.boot.provider.MavenModel;
import dev.jvmd.core.Config;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.resolver.Resolution;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The layered index in the daemon (demo plan, step 1). MACHINE is booted once at start, before READY. Then each opened Maven project
 * gets its LOCAL layer, booted in the background from the resolution its session already made. Nothing serves from these layers
 * yet; this only boots them where they will be used. {@code daemon.status} reports each one under {@code layered_index}.
 *
 * <p>Ordering:
 * <ul>
 * <li>One worker boots one project at a time, and only after MACHINE: both stages open the generation's store, and a process can
 *     hold it open once.
 * <li>A project opened again while its boot is still waiting replaces the waiting resolution.
 * <li>A project opened again while its boot runs is booted once more afterwards. The skip rule makes that cheap when nothing changed.
 * <li>Until {@link #bootMachine} runs (a daemon started by {@code main}), requests wait and nothing runs. An application built in a
 *     test therefore boots nothing unless the test asks for it.
 * </ul>
 */
final class LayeredIndex implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("dev.jvmd.dist");
    /** At most this many provider warnings per project in the status; the log has them all. */
    private static final int WARNINGS_SHOWN = 20;
    private static final boolean LOCAL_ENABLED = Boolean.parseBoolean(System.getProperty("jvmd.layered.local", "true"));

    private final Config config;
    private final Path indexDir;
    private final CompletableFuture<Void> machine = new CompletableFuture<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("jvmd-local-boot").daemon(true).factory());
    /** Hands work to the worker; once closed, work is dropped rather than failing whoever completes MACHINE. */
    private final Executor onWorker = task -> { try { worker.execute(task); } catch (RejectedExecutionException closed) { /* closing */ } };
    private final Map<Path, Project> projects = new ConcurrentHashMap<>();
    private volatile Map<String, Object> machineStatus = Map.of("state", "not_started");

    /** A project's latest requested resolution, and what became of the last boot. */
    private static final class Project {
        private Resolution next;   // guarded by this
        private boolean waiting;   // guarded by this: a boot is scheduled and has not taken next yet
        private volatile Map<String, Object> status = Map.of("state", "waiting_for_machine");
    }

    LayeredIndex(Config config) {
        this.config = config;
        this.indexDir = config.stateDir().resolve("machine");
    }

    /** Stage 1 on the calling thread; a failure is rethrown (for {@code main}, a start failure). The projects requested so far then boot. */
    void bootMachine() throws IOException {
        machineStatus = Map.of("state", "booting");
        long started = System.nanoTime();
        try {
            var result = BootDecision.machine(indexDir, config);
            var status = new LinkedHashMap<String, Object>();
            status.put("state", result.isPresent() ? "cold_booted" : "committed");
            status.put("wall_ms", (System.nanoTime() - started) / 1_000_000);
            result.ifPresent(r -> {
                status.put("root", hex(r.root().hash()));
                status.put("jars", r.distinctJars());
                status.put("faults", r.faults().size());
            });
            machineStatus = Map.copyOf(status);
            machine.complete(null);
        } catch (IOException | RuntimeException | Error failure) {
            machineStatus = Map.of("state", "failed", "error", String.valueOf(failure));
            machine.completeExceptionally(failure);
            throw failure;
        }
    }

    /**
     * Boots the LOCAL layer of the resolution's project once MACHINE is committed, and returns at once. {@code -Djvmd.layered.local=false}
     * turns this off: nothing reads LOCAL yet, and the boot of a large project takes its cores for a while.
     */
    void local(Resolution resolution) {
        if (!LOCAL_ENABLED) return;
        var root = Path.of(resolution.root()).toAbsolutePath().normalize();
        var project = projects.computeIfAbsent(root, r -> new Project());
        synchronized (project) {
            project.next = resolution;
            if (project.waiting) return;
            project.waiting = true;
            // Until this boot starts, the status says it is coming rather than showing the last one's outcome.
            if (!"booting".equals(project.status.get("state"))) project.status = Map.of("state", machine.isDone() ? "queued" : "waiting_for_machine");
        }
        machine.whenCompleteAsync((done, failure) -> {
            if (failure != null) project.status = Map.of("state", "failed", "error", "MACHINE did not boot: " + failure);
            else boot(root, project);
        }, onWorker);
    }

    private void boot(Path root, Project project) {
        Resolution resolution;
        synchronized (project) {
            resolution = project.next;
            project.next = null;
            project.waiting = false;
        }
        project.status = Map.of("state", "booting");
        long started = System.nanoTime();
        try {
            var model = MavenModel.of(resolution, config.m2Repo(), config.jdkHome());
            for (var warning : model.warnings()) LOG.log(System.Logger.Level.INFO, "{0}: {1}", root, warning);
            var result = BootDecision.local(indexDir, model.parse(), config.m2Repo());
            var status = new LinkedHashMap<String, Object>();
            status.put("state", result.isPresent() ? "cold_booted" : "current");
            status.put("wall_ms", (System.nanoTime() - started) / 1_000_000);
            result.ifPresent(r -> {
                status.put("root", hex(r.root().hash()));
                status.put("modules", r.modules());
                status.put("source_files", r.sourceFiles());
                status.put("faults", r.faults().size());
            });
            status.put("model_warnings", model.warnings().size());
            if (!model.warnings().isEmpty()) status.put("model_warnings_shown", List.copyOf(model.warnings().subList(0, Math.min(WARNINGS_SHOWN, model.warnings().size()))));
            project.status = Map.copyOf(status);
        } catch (Exception | LinkageError failure) {
            LOG.log(System.Logger.Level.WARNING, "LOCAL boot of " + root + " failed", failure);
            project.status = Map.of("state", "failed", "error", String.valueOf(failure));
        }
    }

    /** {@code {machine: {...}, projects: {root: {...}}}}, as {@code daemon.status} shows it. */
    Map<String, Object> status() {
        var byRoot = new TreeMap<String, Object>();
        projects.forEach((root, project) -> byRoot.put(root.toString(), project.status));
        return Map.of("machine", machineStatus, "projects", byRoot);
    }

    /** The first eight bytes, as the boot log lines print a root. */
    private static String hex(Identity id) {
        var out = new StringBuilder();
        for (byte b : id.view()) { if (out.length() >= 16) break; out.append(String.format("%02x", b)); }
        return out.toString();
    }

    /** Stops the worker; a boot in progress is given a few seconds to finish its writes. A LOCAL root is published last, so one cut short leaves the previous root. */
    @Override public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
