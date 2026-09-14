package dev.jvmd.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Implements 4.1: canonical-root session identity and lifecycle. */
public final class Sessions implements AutoCloseable {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    public synchronized Session open(Path root) throws java.io.IOException {
        Path canonical = root.toRealPath();
        if (!java.nio.file.Files.isDirectory(canonical)) throw RpcException.invalid("Root must be a directory");
        for (Session session : sessions.values()) if (session.root().equals(canonical)) return session;
        var session = new Session("s" + ids.incrementAndGet(), canonical);
        sessions.put(session.id(), session);
        return session;
    }
    public Session get(String id) {
        var session = sessions.get(id);
        if (session == null) throw new RpcException(-32001, "session_not_found", java.util.Map.of("session", id));
        return session;
    }
    public List<Session> list() { return new ArrayList<>(sessions.values()); }
    public synchronized void close(String id) throws Exception {
        Session session = sessions.remove(id);
        if (session != null) session.close();
    }
    @Override public void close() throws Exception {
        Exception failure = null;
        for (Session session : list()) try { close(session.id()); } catch (Exception e) { failure = e; }
        if (failure != null) throw failure;
    }
}
