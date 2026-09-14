package dev.jvmd.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Implements 4.1, 5 and 12.3: session dispatch, provenance, fault isolation and protocol errors. */
public final class Dispatcher {
    /** Implements 12.3: every registered method must produce a validated envelope. */
    @FunctionalInterface public interface Handler { Envelope call(Session session, JsonNode params) throws Exception; }
    private final Sessions sessions;
    private final Metrics metrics;
    private final Map<String, Handler> methods = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Object>> statusProviders = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    public Dispatcher(Sessions sessions, Metrics metrics) {
        this.sessions = sessions; this.metrics = metrics;
        register("daemon.status", (_, _) -> Envelope.of(2, "index", status()));
        register("daemon.shutdown", (_, _) -> { shutdown.set(true); return Envelope.of(2, "live", Map.of("ack", true)); });
        register("session.open", (_, p) -> {
            Session session = sessions.open(java.nio.file.Path.of(required(p, "root")));
            return Envelope.of(0, "live", Map.of("session", session.id(), "root", session.root().toString(), "classpath", java.util.List.of()));
        });
        register("session.close", (_, p) -> { sessions.close(required(p, "session")); return Envelope.of(2, "live", Map.of("ack", true)); });
        register("session.status", (s, _) -> new Envelope(0, "live", false, null, s.warnings(),
                Map.of("session", s.id(), "root", s.root().toString(), "metrics", metrics.snapshot())));
    }
    public void register(String name, Handler handler) { methods.put(name, handler); }
    public java.util.Set<String> methods() { return java.util.Set.copyOf(methods.keySet()); }
    public void status(String name, Supplier<Object> provider) { statusProviders.put(name, provider); }
    public boolean shutdownRequested() { return shutdown.get(); }
    public Map<String, Object> status() {
        var status = new java.util.LinkedHashMap<String, Object>();
        status.put("sessions", sessions.list().stream().map(s -> Map.of("session", s.id(), "root", s.root().toString())).toList());
        status.put("metrics", metrics.snapshot());
        statusProviders.forEach((name, provider) -> status.put(name, provider.get()));
        return status;
    }
    public ObjectNode dispatch(JsonNode request) {
        if (request == null) request = Json.MAPPER.nullNode();
        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        boolean validRequest = request.isObject() && request.path("jsonrpc").asText().equals("2.0")
                && request.path("method").isTextual() && !method.isEmpty()
                && (id == null || id.isTextual() || id.isNumber() || id.isNull());
        var response = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null ? Json.MAPPER.nullNode() : id);
        long start = System.nanoTime();
        JsonNode params = request.path("params");
        String sessionId = params.path("session").asText("");
        Envelope envelope;
        boolean fault = false;
        try {
            if (!validRequest)
                throw new RpcException(-32600, "Invalid Request", null);
            if (!params.isMissingNode() && !params.isObject()) throw RpcException.invalid("Params must be an object");
            Handler handler = methods.get(method);
            if (handler == null) throw new RpcException(-32601, "Method not found", Map.of("method", method));
            Session session = method.startsWith("daemon.") || method.equals("session.open") || method.equals("session.close")
                    ? null : sessions.get(required(params, "session"));
            envelope = session == null ? handler.call(null, params) : session.execute(() -> handler.call(session, params));
            if (envelope == null) throw new IllegalStateException("Handler omitted envelope");
            response.set("result", Json.MAPPER.valueToTree(envelope));
        } catch (RpcException e) {
            envelope = Envelope.of(1, "live", e.data());
            response.set("error", Json.MAPPER.valueToTree(Map.of("code", e.code(), "message", e.getMessage(), "data", envelope)));
        } catch (Exception | AssertionError | LinkageError e) {
            fault = true;
            System.getLogger("jvmd").log(System.Logger.Level.ERROR, "Request fault: " + method, e);
            envelope = Envelope.of(1, "live", Map.of()).warn("analyzer_fault: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            response.set("result", Json.MAPPER.valueToTree(envelope));
        }
        metrics.record(method, sessionId, System.nanoTime() - start, envelope, fault);
        return id == null && validRequest ? null : response;
    }
    public static String required(JsonNode params, String key) {
        JsonNode value = params.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw RpcException.invalid("Required string: " + key);
        return value.asText();
    }
    public static int bounded(JsonNode params, String key, int fallback, int maximum) {
        int value = params.path(key).asInt(fallback);
        if (value < 0 || value > maximum) throw RpcException.invalid(key + " must be between 0 and " + maximum);
        return value;
    }
}
