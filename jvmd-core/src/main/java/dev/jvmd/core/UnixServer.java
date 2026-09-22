package dev.jvmd.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Implements 4.1: a single private Unix socket daemon, virtual-thread connections and idle expiry. */
public final class UnixServer implements AutoCloseable {
    private final Config config;
    private final Dispatcher dispatcher;
    private final AutoCloseable resources;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean idleArmed = new AtomicBoolean();
    private final AtomicInteger active = new AtomicInteger();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("jvmd-idle").factory());
    private volatile long lastActivity = System.nanoTime();
    private ServerSocketChannel server;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean bound;
    public UnixServer(Config config, Dispatcher dispatcher, AutoCloseable resources) {
        this.config = config; this.dispatcher = dispatcher; this.resources = resources;
    }
    public void start() throws IOException {
        Files.createDirectories(config.socket().getParent());
        lockChannel = FileChannel.open(config.socket().resolveSibling(config.socket().getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException e) { lockChannel.close(); throw new IOException("Daemon already running", e); }
        if (lock == null) { lockChannel.close(); throw new IOException("Daemon already running"); }
        if (Files.exists(config.socket())) {
            boolean alive;
            try (var probe = SocketChannel.open(StandardProtocolFamily.UNIX)) { probe.connect(UnixDomainSocketAddress.of(config.socket())); alive = true; }
            catch (java.net.ConnectException e) { alive = false; }
            if (alive) { lock.release(); lockChannel.close(); throw new IOException("Socket already in use"); }
            Files.delete(config.socket());
        }
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(config.socket()));
        bound = true;
        Files.setPosixFilePermissions(config.socket(), PosixFilePermissions.fromString("rw-------"));
        connections.submit(() -> {
            while (!closed.get()) try {
                SocketChannel client = server.accept();
                clients.add(client);
                connections.submit(() -> serve(client));
            } catch (IOException e) { if (!closed.get()) System.getLogger("jvmd").log(System.Logger.Level.WARNING, "Accept failed", e); }
        });
    }
    public void ready() {
        if(closed.get()||!idleArmed.compareAndSet(false,true))return;
        lastActivity=System.nanoTime();
        long interval = Math.max(10, Math.min(1000, config.idleTimeout().toMillis() / 4));
        timer.scheduleWithFixedDelay(() -> {
            if (active.get() == 0 && System.nanoTime() - lastActivity > config.idleTimeout().toNanos()) close();
        }, interval, interval, TimeUnit.MILLISECONDS);
    }
    private void serve(SocketChannel client) {
        try (client; var input = Channels.newInputStream(client); var output = Channels.newOutputStream(client)) {
            for (byte[] body; (body = Framing.read(input)) != null;) {
                lastActivity = System.nanoTime(); active.incrementAndGet();
                try {
                    com.fasterxml.jackson.databind.JsonNode response;
                    try { response = dispatcher.dispatch(Json.MAPPER.readTree(body)); }
                    catch (JsonProcessingException e) {
                        var error = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
                        error.putNull("id");
                        error.set("error", Json.MAPPER.valueToTree(Map.of("code", -32700, "message", "Parse error", "data", Envelope.of(1, "live", Map.of()))));
                        response = error;
                    }
                    if (response != null) Framing.write(output, Json.MAPPER.writeValueAsBytes(response));
                } finally { lastActivity = System.nanoTime(); active.decrementAndGet(); }
                if (dispatcher.shutdownRequested()) { close(); break; }
            }
        } catch (IOException e) {
            if (!closed.get()) System.getLogger("jvmd").log(System.Logger.Level.DEBUG, "Connection ended", e);
        } finally { clients.remove(client); }
    }
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException { return stopped.await(timeout, unit); }
    public void await() throws InterruptedException { stopped.await(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) {
            try { stopped.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return;
        }
        timer.shutdownNow();
        try {
            if (server != null) server.close();
            for (var client : clients) try { client.close(); } catch (IOException ignored) { }
            resources.close();
        } catch (Exception e) { System.getLogger("jvmd").log(System.Logger.Level.ERROR, "Shutdown failed", e); }
        finally {
            connections.shutdown();
            try { if (bound) Files.deleteIfExists(config.socket()); } catch (IOException ignored) { }
            try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
            try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
            stopped.countDown();
        }
    }
}
