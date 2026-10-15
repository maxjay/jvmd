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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The layered index in the daemon (demo plan, step 1). MACHINE is booted once at start, before READY. Then each opened Maven project
 * gets its LOCAL layer, booted in the background from the resolution its session already made. Nothing serves from these layers yet;
 * this only boots them where they will be used. {@code daemon.status} reports each one under {@code layered_index}.
 *
 * <p>Ordering:
 * <ul>
 * <li>One worker boots one project at a time, and only after MACHINE: both stages open the generation's store, and a process can hold
 *     it open once.
 * <li>A project opened again while its boot is still waiting replaces the waiting resolution.
 * <li>A project opened again while its boot runs is booted once more afterwards. The skip rule makes that cheap when nothing changed.
 * <li>Until {@link #bootMachine} runs (a daemon started by {@code main}), requests wait and nothing runs. An application built in a
 *     test therefore boots nothing unless the test asks for it.
 * </ul>
 * A boot uses half the cores, so the daemon keeps answering while it runs. Closing cancels it: no module job starts after that, the
 * running ones end at their next scope, and no root is written.
 */
final class LayeredIndex implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("dev.jvmd.dist");
    /** At most this many provider warnings per project in the status; the log has them all. */
    private static final int WARNINGS_SHOWN = 20;
    /** How long closing waits for a cancelled boot's running module jobs, which javac does not interrupt. */
    private static final long CLOSE_WAIT_SECONDS = 60;

    private final Config config;
    private final Path indexDir;
    /** {@code -Djvmd.layered.local=false} turns LOCAL boots off: nothing reads LOCAL yet. */
    private final boolean localEnabled = Boolean.parseBoolean(System.getProperty("jvmd.layered.local", "true"));
    private final int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private final CompletableFuture<Void> machine = new CompletableFuture<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("jvmd-local-boot").daemon(true).factory());
    /** Hands work to the worker; once closed, work is dropped rather than failing whoever completes MACHINE. */
    private final Executor onWorker = task -> { try { worker.execute(task); } catch (RejectedExecutionException closed) { /* closing */ } };
    private final Map<Path, Project> projects = new ConcurrentHashMap<>();
    private volatile Map<String, Object> machineStatus = Map.of("state", "not_started");
    /** The thread booting MACHINE (main's), and when it has finished: closing cancels the boot and waits for it. Guarded by this. */
    private Thread machineThread;
    private boolean closing;
    private final java.util.concurrent.CountDownLatch machineEnded = new java.util.concurrent.CountDownLatch(1);

    /** A project's latest requested resolution, and what became of the last boot. Its fields are guarded by itself. */
    private static final class Project {
        private Resolution next;
        /** A boot is scheduled and has not taken {@link #next} yet. */
        private boolean waiting;
        private Map<String, Object> status = Map.of("state", "waiting_for_machine");
    }

    LayeredIndex(Config config) {
        this.config = config;
        this.indexDir = config.stateDir().resolve("machine");
    }

    /** Stage 1 on the calling thread; a failure is rethrown (for {@code main}, a start failure). The projects requested so far then boot. */
    void bootMachine() throws IOException {
        synchronized (this) {
            if (closing) throw new CancellationException("the daemon is closing");
            if (machineThread != null) throw new IllegalStateException("MACHINE is booted once");
            machineThread = Thread.currentThread();
        }
        try { bootMachineOnThisThread(); } finally { machineEnded.countDown(); }
    }

    private void bootMachineOnThisThread() throws IOException {
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
            boolean cancelled;
            synchronized (this) { cancelled = closing; }
            if (cancelled) {
                // Closing interrupted the boot (stage 1 starts no further job, ends the running ones and writes no ROOT).
                Thread.interrupted();
                machineStatus = Map.of("state", "cancelled");
                var cancellation = new CancellationException("MACHINE boot cancelled: the daemon is closing");
                machine.completeExceptionally(cancellation);
                throw cancellation;
            }
            machineStatus = Map.of("state", "failed", "error", String.valueOf(failure));
            machine.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Boots the LOCAL layer of the resolution's project once MACHINE is committed, and returns at once. */
    void local(Resolution resolution) {
        if (!localEnabled) return;
        var root = Path.of(resolution.root()).toAbsolutePath().normalize();
        var project = projects.computeIfAbsent(root, r -> new Project());
        synchronized (project) {
            project.next = resolution;
            if (project.waiting) return;
            project.waiting = true;
            if (!"booting".equals(project.status.get("state"))) project.status = waiting(project.status);
        }
        machine.whenCompleteAsync((done, failure) -> {
            if (failure == null) { boot(root, project); return; }
            synchronized (project) {
                project.waiting = false;
                project.status = Map.of("state", "failed", "error", "MACHINE did not boot: " + failure);
            }
        }, onWorker);
    }

    private void boot(Path root, Project project) {
        Resolution resolution;
        synchronized (project) {
            resolution = project.next;
            project.next = null;
            project.waiting = false;
            project.status = Map.of("state", "booting");
        }
        long started = System.nanoTime();
        Map<String, Object> outcome;
        try {
            var model = MavenModel.of(resolution, config.m2Repo(), config.jdkHome());
            for (var warning : model.warnings()) LOG.log(System.Logger.Level.INFO, "{0}: {1}", root, warning);
            var result = BootDecision.local(indexDir, model.parse(), config.m2Repo(), workers);
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
            outcome = Map.copyOf(status);
        } catch (CancellationException cancelled) {
            outcome = Map.of("state", "cancelled");
        } catch (Throwable failure) {
            // Anything, an Error too: a status left at "booting" would never change.
            LOG.log(System.Logger.Level.WARNING, "LOCAL boot of " + root + " failed", failure);
            outcome = Map.of("state", "failed", "error", String.valueOf(failure));
            if (failure instanceof VirtualMachineError fatal) { publish(project, outcome); throw fatal; }
        }
        publish(project, outcome);
    }

    /** The outcome of a boot; if another is already waiting for this project, it says so and keeps the outcome as the last one. */
    private void publish(Project project, Map<String, Object> outcome) {
        synchronized (project) { project.status = project.waiting ? waiting(outcome) : outcome; }
    }

    private Map<String, Object> waiting(Map<String, Object> last) {
        var state = machine.isDone() ? "queued" : "waiting_for_machine";
        return last.containsKey("state") && !last.get("state").equals(state) && !last.get("state").equals("waiting_for_machine")
                ? Map.of("state", state, "last", last) : Map.of("state", state);
    }

    /** {@code {machine: {...}, projects: {root: {...}}}}, as {@code daemon.status} shows it. */
    Map<String, Object> status() {
        var byRoot = new TreeMap<String, Object>();
        projects.forEach((root, project) -> { synchronized (project) { byRoot.put(root.toString(), project.status); } });
        return Map.of("machine", machineStatus, "projects", byRoot);
    }

    /** The first eight bytes, as the boot log lines print a root. */
    private static String hex(Identity id) {
        var out = new StringBuilder();
        for (byte b : id.view()) { if (out.length() >= 16) break; out.append(String.format("%02x", b)); }
        return out.toString();
    }

    /**
     * Cancels a running boot, MACHINE's or a LOCAL one, and waits for it to end, so the store is closed before the daemon goes on
     * closing and releases its lock: a daemon asked to shut down while it boots writes nothing after that. A root is published last, so
     * a boot cut short leaves the previous one.
     */
    @Override public void close() {
        Thread booting;
        synchronized (this) {
            closing = true;
            booting = machineThread;
        }
        if (booting != null && machineEnded.getCount() > 0) booting.interrupt();
        worker.shutdownNow();
        try {
            if (booting != null && !machineEnded.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS))
                LOG.log(System.Logger.Level.WARNING, "the MACHINE boot did not end within {0} s of closing", CLOSE_WAIT_SECONDS);
            if (!worker.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS))
                LOG.log(System.Logger.Level.WARNING, "a LOCAL boot did not end within {0} s of closing", CLOSE_WAIT_SECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
