package dev.jvmd.dist;

import dev.jvmd.analyzer.Parser;
import dev.jvmd.core.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Implements 4.1 and 12.1: composition root; user dependencies never enter this classpath. */
public final class Application implements AutoCloseable {
    private final Sessions sessions = new Sessions();
    private final Dispatcher dispatcher = new Dispatcher(sessions, new Metrics());
    private final Config config;
    public Application(Config config) {
        this.config = config;
        dispatcher.status("aot_cache", () -> AotStatus.runtime(Path.of(System.getProperty("jvmd.aot.log", config.stateDir().resolve("aot.log").toString()))));
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
    @Override public void close() throws Exception { sessions.close(); }
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
