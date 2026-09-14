package dev.jvmd.dist;

import dev.jvmd.analyzer.Parser;
import dev.jvmd.core.*;
import dev.jvmd.resolver.MavenResolver;
import dev.jvmd.resolver.Resolution;
import java.nio.file.Files;
import java.nio.file.Path;

/** Implements 4.1 and 12.1: composition root; user dependencies never enter this classpath. */
public final class Application implements AutoCloseable {
    private final Sessions sessions = new Sessions();
    private final Dispatcher dispatcher = new Dispatcher(sessions, new Metrics());
    private final Config config;
    private volatile MavenResolver resolver;
    public Application(Config config) {
        this.config = config;
        dispatcher.status("aot_cache", () -> AotStatus.runtime(Path.of(System.getProperty("jvmd.aot.log", config.stateDir().resolve("aot.log").toString()))));
        dispatcher.status("resolver", () -> resolver == null ? java.util.Map.of("maven_major", config.mavenMajor(), "initialized", false) : resolver.status());
        dispatcher.register("session.open", (_, p) -> {
            var session = sessions.open(Path.of(Dispatcher.required(p, "root")));
            return session.execute(() -> {
                Resolution graph = Files.isRegularFile(session.root().resolve("pom.xml")) ? refresh(session) : null;
                return new Envelope(0, "live", false, null, session.warnings(), java.util.Map.of("session", session.id(),
                        "root", session.root().toString(), "classpath_entries", graph == null ? 0 : graph.classpath().size(),
                        "modules", graph == null ? 0 : graph.modules().size()));
            });
        });
        dispatcher.register("deps.graph", (s, p) -> dependencyGraph(refresh(s), p));
        dispatcher.register("session.status", (s, _) -> {
            var graph = (Resolution) s.state("resolution");
            return new Envelope(0, "live", false, null, s.warnings(), java.util.Map.of("session", s.id(),
                    "root", s.root().toString(), "classpath_state", graph == null ? "unresolved" : "resolved",
                    "classpath_entries", graph == null ? 0 : graph.classpath().size(), "metrics", dispatcher.status().get("metrics")));
        });
        dispatcher.register("symbol.overview", (s, p) -> {
            Path path = s.root().resolve(Dispatcher.required(p, "path")).normalize();
            if (!path.startsWith(s.root())) throw RpcException.invalid("Path is outside workspace");
            Parser parser = s.state("parser", Parser::new);
            return parser.overview(path, Files.readString(path), Dispatcher.bounded(p, "depth", 1, 10), Dispatcher.bounded(p, "limit", 100, 1000));
        });
        dispatcher.register("diag.get", (s, p) -> {
            if (p.path("verified").asBoolean()) throw new RpcException(-32003, "unsupported_capability", java.util.Map.of("capability", "verified diagnostics"));
            var diagnostics = new java.util.ArrayList<Object>();
            for (var value : p.path("paths")) {
                Path path = s.root().resolve(value.asText()).normalize();
                if (!path.startsWith(s.root())) throw RpcException.invalid("Path is outside workspace");
                var result = s.state("parser", Parser::new).overview(path, Files.readString(path), 0, 0);
                diagnostics.addAll((java.util.List<?>) ((java.util.Map<?, ?>) result.result()).get("diagnostics"));
            }
            return Envelope.of(0, "live", java.util.Map.of("diagnostics", diagnostics));
        });
    }
    public Dispatcher dispatcher() { return dispatcher; }
    public Sessions sessions() { return sessions; }
    private synchronized MavenResolver resolver() {
        if (resolver == null) resolver = new MavenResolver(config);
        return resolver;
    }
    private Resolution refresh(Session session) throws Exception {
        Resolution graph = resolver().resolve(session.root());
        var previous = (Resolution) session.state("resolution");
        if (previous == null || !previous.fingerprint().equals(graph.fingerprint())) {
            var oldPaths = previous == null ? java.util.List.<String>of() : previous.classpath();
            var newPaths = graph.classpath();
            session.put("classpath_diff", java.util.Map.of("added", newPaths.stream().filter(p -> !oldPaths.contains(p)).toList(),
                    "removed", oldPaths.stream().filter(p -> !newPaths.contains(p)).toList()));
            session.put("classpath_generation", graph.fingerprint());
        }
        session.put("resolution", graph);
        graph.warnings().forEach(session::warn);
        return graph;
    }
    private static Envelope dependencyGraph(Resolution graph, com.fasterxml.jackson.databind.JsonNode params) {
        int depth = Dispatcher.bounded(params, "depth", 2, 20), limit = Dispatcher.bounded(params, "limit", 50, 200);
        String scope = params.path("scope").asText("all");
        if (!java.util.List.of("all", "compile", "runtime", "test", "provided").contains(scope)) throw RpcException.invalid("Unknown dependency scope");
        var reach = new java.util.LinkedHashSet<String>();
        var roots = graph.modules().stream().map(Resolution.Module::gav).collect(java.util.stream.Collectors.toSet());
        for (var node : graph.nodes()) if (roots.contains(node.gav()) && node.extension().equals("pom")) reach.add(node.id());
        for (int d = 0; d < depth; d++) {
            var next = new java.util.LinkedHashSet<>(reach);
            for (var edge : graph.edges()) if (reach.contains(edge.src()) && (scope.equals("all") || scope.equals(edge.scope()))) next.add(edge.dst());
            if (next.equals(reach)) break;
            reach = next;
        }
        var selected = reach;
        var nodes = graph.nodes().stream().filter(n -> selected.contains(n.id())).toList();
        var edges = graph.edges().stream().filter(e -> selected.contains(e.src()) && selected.contains(e.dst())).toList();
        boolean truncated = nodes.size() > limit || edges.size() > limit;
        return new Envelope(2, "index", truncated, truncated ? Integer.toString(limit) : null, graph.warnings(),
                java.util.Map.of("nodes", nodes.subList(0, Math.min(limit, nodes.size())), "edges", edges.subList(0, Math.min(limit, edges.size())),
                        "fingerprint", graph.fingerprint(), "cached", graph.cached()));
    }
    @Override public void close() throws Exception {
        try { sessions.close(); } finally { if (resolver != null) resolver.close(); }
    }
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 25) throw new IllegalStateException("jvmd requires pinned JDK 25");
        Config config = Config.load();
        var app = new Application(config);
        if (args.length > 0 && args[0].equals("--train")) {
            Path fixture = Files.createTempDirectory("jvmd-training-");
            try {
                Path file = fixture.resolve("Training.java");
                Files.writeString(file, "class Training { String name; int value() { return 42; } }");
                var session = app.sessions.open(fixture);
                for (int i = 0; i < 20; i++) {
                    var request = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", i).put("method", "symbol.overview");
                    request.putObject("params").put("session", session.id()).put("path", file.toString());
                    app.dispatcher.dispatch(request);
                }
            } finally { app.close(); try (var files = Files.walk(fixture)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); } }
            return;
        }
        var server = new UnixServer(config, app.dispatcher, app);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
        server.start();
        System.out.println("READY " + config.socket());
        server.await();
    }
}
