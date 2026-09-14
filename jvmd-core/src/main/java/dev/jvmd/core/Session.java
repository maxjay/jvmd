package dev.jvmd.core;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Implements 4.1 and 4.2: one platform executor and compiler state per canonical workspace. */
public final class Session implements AutoCloseable {
    private final String id;
    private final Path root;
    private final ExecutorService executor;
    private volatile Thread owner;
    private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    public Session(String id, Path root) {
        this.id = id; this.root = root;
        executor = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("jvmd-session-" + id).unstarted(() -> { owner = Thread.currentThread(); r.run(); }));
    }
    public String id() { return id; }
    public Path root() { return root; }
    public List<String> warnings() { return List.copyOf(warnings); }
    public void warn(String warning) { warnings.addIfAbsent(warning); }
    @SuppressWarnings("unchecked")
    public <T> T state(String key, Supplier<T> factory) { return (T) state.computeIfAbsent(key, _ -> factory.get()); }
    public Object state(String key) { return state.get(key); }
    public void put(String key, Object value) { state.put(key, value); }
    public <T> T execute(Callable<T> work) throws Exception {
        if (Thread.currentThread() == owner) return work.call();
        try { return executor.submit(work).get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            if (e.getCause() instanceof Error x) throw x;
            throw new IllegalStateException(e.getCause());
        }
    }
    @Override public void close() throws Exception {
        try { execute(() -> {
            Exception failure = null;
            for (Object resource : state.values()) if (resource instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception e) { failure = e; }
            }
            state.clear();
            if (failure != null) throw failure;
            return null;
        }); } finally {
            executor.shutdown();
            if (Thread.currentThread() != owner && !executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        }
    }
}
