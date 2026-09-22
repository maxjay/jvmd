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
    private final ResponseBudget budgets=new ResponseBudget();
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
    /** Implements 4.9: compose core queries on the same session executor; the outer RPC owns byte paging. */
    public Envelope query(Session session,String method,JsonNode params)throws Exception{
        if(session==null||method.startsWith("daemon.")||method.startsWith("session."))throw RpcException.invalid("An in-process query requires a workspace method");
        var handler=methods.get(method);if(handler==null)throw new RpcException(-32601,"Method not found",Map.of("method",method));
        return session.execute(()->RequestScope.call(method,()->{var result=handler.call(session,params);if(result==null)throw new IllegalStateException("Handler omitted envelope");return result;}));
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
        if(!RequestScope.TRACING||RequestScope.current()!=null)return dispatchRequest(request);
        var trace=request==null?Json.MAPPER.nullNode():request.path("_jvmdTrace");
        String method=request==null?"":request.path("method").asText("");
        try{return RequestScope.traced(method,trace.path("workflow").asText(""),trace.path("invocation").asText(""),trace.path("revision").asText(""),()->dispatchRequest(request));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private ObjectNode dispatchRequest(JsonNode request) {
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
            var continuation=budgets.resume(method,params,id);
            if(continuation!=null){
                response=continuation;var value=response.has("error")?response.path("error").path("data"):response.path("result");envelope=Json.MAPPER.treeToValue(value,Envelope.class);
            }else{
                int priority=method.startsWith("document.")?0:method.equals("lsp.diagnostics")?1:method.equals("diag.get")?(params.path("paths").isEmpty()?5:3):2;
                envelope = session == null ? RequestScope.call(method,()->handler.call(null,params)) : session.execute(priority,() -> RequestScope.call(method,()->handler.call(session,params)));
                if (envelope == null) throw new IllegalStateException("Handler omitted envelope");
                try(var span=RequestScope.stage("response.encode")){response.set("result", Json.MAPPER.valueToTree(envelope));}
            }
            if(id!=null)response=budgets.enforce(response,method,params);
        } catch (RpcException e) {
            response.remove("result");
            envelope = e.data() instanceof Envelope provided?provided:Envelope.of(1, "live", e.data());
            response.set("error", Json.MAPPER.valueToTree(Map.of("code", e.code(), "message", e.getMessage(), "data", envelope)));
        } catch (Exception | AssertionError | LinkageError e) {
            response.remove("error");
            fault = true;
            System.getLogger("jvmd").log(System.Logger.Level.ERROR, "Request fault: " + method, e);
            envelope = Envelope.of(1, "live", Map.of()).warn("analyzer_fault: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            response.set("result", Json.MAPPER.valueToTree(envelope));
        }
        if(id!=null&&response.has("error"))try{response=budgets.enforce(response,method,params);}catch(Exception budget){
            envelope=Envelope.of(1,"live",Map.of("reason","Error response cannot be paged","detail",String.valueOf(budget.getMessage())));
            response.set("error",Json.MAPPER.valueToTree(Map.of("code",-32005,"message","budget_exceeded","data",envelope)));
        }
        metrics.record(method, sessionId, System.nanoTime() - start, envelope, fault);
        return id == null && validRequest ? null : response;
    }
    public static String required(JsonNode params, String key) {
        JsonNode value = params.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw RpcException.invalid("Required string: " + key);
        return value.asText();
    }
    public static int limit(JsonNode params,int fallback,int maximum){int value=bounded(params,"limit",fallback,maximum);if(value==0)throw RpcException.invalid("limit must be positive");return value;}
    public static int bounded(JsonNode params, String key, int fallback, int maximum) {
        if(params.has(key)&&(!params.path(key).isIntegralNumber()||!params.path(key).canConvertToInt()))throw RpcException.invalid(key+" must be an integer");
        int value = params.path(key).asInt(fallback);
        if (value < 0 || value > maximum) throw RpcException.invalid(key + " must be between 0 and " + maximum);
        return value;
    }
}
