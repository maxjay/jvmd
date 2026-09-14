package dev.jvmd.dist;

import dev.jvmd.analyzer.Parser;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.*;
import dev.jvmd.resolver.MavenResolver;
import dev.jvmd.resolver.Resolution;
import dev.jvmd.index.IndexService;
import java.nio.file.Files;
import java.nio.file.Path;

/** Implements 4.1 and 12.1: composition root; user dependencies never enter this classpath. */
public final class Application implements AutoCloseable {
    private final Sessions sessions = new Sessions();
    private final Dispatcher dispatcher = new Dispatcher(sessions, new Metrics());
    private final Config config;
    private volatile MavenResolver resolver;
    private volatile java.util.concurrent.CompletableFuture<IndexService> index;
    public Application(Config config) {
        this.config = config;
        if (config.indexOnStart()) initializeIndex(true);
        dispatcher.status("index", () -> {
            try { return index == null ? java.util.Map.of("phase", "disabled") : index.isDone() ? index.join().status() : java.util.Map.of("phase", "starting"); }
            catch (Exception e) { return java.util.Map.of("phase", "failed", "reason", e.toString()); }
        });
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
        dispatcher.register("symbol.find", (s, p) -> {
            var database = index(); bindIndex(s, database);
            int limit = Dispatcher.bounded(p,"limit",50,200);
            String query = Dispatcher.required(p,"name_path");
            long cursor; try { cursor = Long.parseLong(p.path("cursor").asText("0")); } catch (NumberFormatException e) { throw RpcException.invalid("Invalid cursor"); }
            var found = database.find(query,s.state("resolution") == null ? null : s.id(),p.path("substring").asBoolean(),limit+1,cursor);
            boolean truncated = found.size()>limit; var page=found.subList(0,Math.min(limit,found.size()));
            return new Envelope(2,"index",truncated,truncated?page.getLast().get("id").toString():null,s.warnings(),java.util.Map.of("matches",page));
        });
        dispatcher.register("symbol.describe", (s, p) -> {
            var database=index(); bindIndex(s,database);
            var found=database.find(Dispatcher.required(p,"ref"),s.state("resolution")==null?null:s.id(),false,21,0);
            if(found.size()!=1) return new Envelope(2,"index",found.size()>20,found.size()>20?found.get(19).get("id").toString():null,
                    found.size()>1?java.util.List.of("ambiguous"):s.warnings(),java.util.Map.of("candidates",found.subList(0,Math.min(20,found.size()))));
            return Envelope.of(2,"index",found.getFirst());
        });
        dispatcher.register("session.status", (s, _) -> {
            var graph = (Resolution) s.state("resolution");
            return new Envelope(0, "live", false, null, s.warnings(), java.util.Map.of("session", s.id(),
                    "root", s.root().toString(), "classpath_state", graph == null ? "unresolved" : "resolved",
                    "classpath_entries", graph == null ? 0 : graph.classpath().size(), "metrics", dispatcher.status().get("metrics")));
        });
        dispatcher.register("symbol.overview", (s, p) -> {
            Path path = s.root().resolve(Dispatcher.required(p, "path")).normalize();
            if (!path.startsWith(s.root())) throw RpcException.invalid("Path is outside workspace");
            int offset;try{offset=Integer.parseInt(p.path("cursor").asText("0"));}catch(NumberFormatException e){throw RpcException.invalid("Invalid cursor");}
            if(offset<0)throw RpcException.invalid("Invalid cursor");
            return analyzer(s,path).overview(path,Files.readString(path),Dispatcher.bounded(p,"depth",1,10),Dispatcher.bounded(p,"limit",100,1000),offset);
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
    private Analyzer analyzer(Session session,Path path)throws Exception{
        var graph=(Resolution)session.state("resolution");
        if(graph!=null)graph=refresh(session);
        String gav="local:workspace:0",release="25",generation="plain";
        var classpath=new java.util.ArrayList<Path>();var sources=new java.util.ArrayList<Path>();var coordinates=new java.util.LinkedHashMap<String,String>();
        if(graph!=null){
            var module=graph.modules().stream().filter(m->path.startsWith(Path.of(m.directory()))).max(java.util.Comparator.comparingInt(m->m.directory().length())).orElse(graph.modules().getFirst());
            gav=module.gav();release=module.release()==null||module.release().isBlank()?"25":module.release();generation=graph.fingerprint()+":"+gav;
            boolean test=module.testSources().stream().anyMatch(root->path.startsWith(Path.of(root)));
            graph.classpaths().getOrDefault(gav+(test?":test":":main"),java.util.List.of()).forEach(p->classpath.add(Path.of(p)));
            module.sources().forEach(p->sources.add(Path.of(p)));if(test)module.testSources().forEach(p->sources.add(Path.of(p)));
            for(var m:graph.modules()){coordinates.put(m.directory(),m.gav());coordinates.put(Path.of(m.directory()).toUri().toString(),m.gav());}
            for(var node:graph.nodes())if(node.path()!=null&&node.winner()==null)coordinates.put(node.path(),node.gav());
        }else{sources.add(session.root());coordinates.put(session.root().toString(),gav);coordinates.put(session.root().toUri().toString(),gav);}
        var analyzer=session.state("analyzer",Analyzer::new);
        var availableIndex=index!=null&&index.isDone()&&!index.isCompletedExceptionally()?index.join():null;
        analyzer.configure(new Analyzer.Context(gav,release,java.util.List.copyOf(classpath),java.util.List.copyOf(sources),generation,java.util.Map.copyOf(coordinates)),availableIndex,config.heapCeilingMb()*1024L*1024/Math.max(1,sessions.list().size()));
        return analyzer;
    }
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
        if(index!=null && index.isDone() && !index.isCompletedExceptionally()) bindIndex(session,index.join());
        return graph;
    }
    private synchronized void initializeIndex(boolean scan) {
        if(index!=null)return;
        index=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try {var service=new IndexService(config.stateDir().resolve("index.db"),config.m2Repo());if(scan)service.start();return service;}
            catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        }, task -> Thread.ofVirtual().name("jvmd-index-start").start(task));
    }
    private IndexService index() { initializeIndex(false); return index.join(); }
    private void bindIndex(Session session,IndexService database)throws Exception {
        var graph=(Resolution)session.state("resolution");if(graph==null)return;
        String generation=graph.fingerprint()+":"+database.generation();
        if(generation.equals(session.state("index_generation")))return;
        var paths=graph.nodes().stream().filter(n->n.path()!=null&&n.winner()==null).map(n->new IndexService.WorkspaceArtifact(n.path(),n.scope())).toList();
        var byId=graph.nodes().stream().collect(java.util.stream.Collectors.toMap(Resolution.Node::id,Resolution.Node::gav));
        var edges=graph.edges().stream().map(e->java.util.Map.entry(byId.get(e.src()),byId.get(e.dst()))).toList();
        database.loadWorkspace(session.id(),paths,edges).forEach(session::warn);session.put("index_generation",generation);
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
        try { sessions.close(); } finally {
            try { if (resolver != null) resolver.close(); }
            finally { if(index!=null&&!index.isCompletedExceptionally())index.join().close(); }
        }
    }
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 25) throw new IllegalStateException("jvmd requires pinned JDK 25");
        Config config = Config.load();
        boolean training=args.length>0 && args[0].equals("--train");
        if(training)config=new Config(config.jdkHome(),config.jbrHome(),config.m2Repo(),config.mavenMajor(),config.idleTimeout(),config.heapCeilingMb(),false,Files.createTempDirectory("jvmd-aot-state-"),config.socket());
        var app = new Application(config);
        if (training) {
            Path fixture = Files.createTempDirectory("jvmd-training-");
            try {
                Path file = fixture.resolve("Training.java");
                Files.writeString(file, "class Training { String name; int value() { return 42; } }");
                var session = app.sessions.open(fixture);
                Files.writeString(fixture.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>dev.jvmd.training</groupId><artifactId>training</artifactId><version>1</version><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.22.2</version></dependency></dependencies></project>");
                Path wrapper=Files.createDirectories(fixture.resolve(".mvn/wrapper"));
                Files.writeString(wrapper.resolve("maven-wrapper.properties"),"distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip");
                app.refresh(session);
                var database=app.index();
                Path jackson=config.m2Repo().resolve("com/fasterxml/jackson/core/jackson-databind/2.22.2/jackson-databind-2.22.2.jar");
                database.indexJar(jackson,"com.fasterxml.jackson.core:jackson-databind:2.22.2","jar");
                database.linkEdges();
                for (int i = 0; i < 20; i++) {
                    var request = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", i).put("method", "symbol.overview");
                    request.putObject("params").put("session", session.id()).put("path", file.toString());
                    app.dispatcher.dispatch(request);
                    database.find(i%2==0?"ObjectMapper":"readValue",null,false,20,0);
                    app.refresh(session);
                }
            } finally { app.close(); try (var files = Files.walk(fixture)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); } try(var files=Files.walk(config.stateDir())){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);} }
            return;
        }
        var server = new UnixServer(config, app.dispatcher, app);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
        server.start();
        System.out.println("READY " + config.socket());
        server.await();
    }
}
