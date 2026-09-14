package dev.jvmd.dist;

import dev.jvmd.analyzer.Parser;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Bindings;
import java.util.*;
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
            var manifest=p.get("manifest");
            if(manifest==null){Path file=session.root().resolve(".jvmd/workspace.json");manifest=Files.isRegularFile(file)?Json.MAPPER.readTree(file.toFile()):Json.MAPPER.createObjectNode();}
            else if(manifest.isTextual())manifest=Json.MAPPER.readTree(session.root().resolve(manifest.asText()).toFile());
            if(!manifest.isObject())throw RpcException.invalid("manifest must be an object or path");
            session.put("manifest",manifest);
            return session.execute(() -> {
                Resolution graph = Files.isRegularFile(session.root().resolve("pom.xml")) ? refresh(session) : null;
                return new Envelope(0, "live", false, null, session.warnings(), java.util.Map.of("session", session.id(),
                        "root", session.root().toString(), "classpath_entries", graph == null ? 0 : graph.classpath().size(),
                        "modules", graph == null ? 0 : graph.modules().size()));
            });
        });
        dispatcher.register("deps.graph", (s, p) -> dependencyGraph(refresh(s), p));
        dispatcher.register("symbol.atPosition",(s,p)->{
            Path path=sourcePath(s,Dispatcher.required(p,"path"));
            return analyzer(s,path).atPosition(path,Files.readString(path),Dispatcher.bounded(p,"line",0,Integer.MAX_VALUE),Dispatcher.bounded(p,"character",0,Integer.MAX_VALUE));
        });
        dispatcher.register("symbol.find",(s,p)->{
            if(p.has("path")){Path path=sourcePath(s,Dispatcher.required(p,"path"));return analyzer(s,path).atPosition(path,Files.readString(path),Dispatcher.bounded(p,"line",0,Integer.MAX_VALUE),Dispatcher.bounded(p,"character",0,Integer.MAX_VALUE));}
            String ref=Dispatcher.required(p,"name_path"),scope=p.path("scope").asText("all");if(!Set.of("workspace","deps","all").contains(scope))throw RpcException.invalid("Unknown symbol scope");
            int limit=Dispatcher.bounded(p,"limit",50,200),offset=cursor(p);boolean substring=p.path("substring").asBoolean();
            var matches=new LinkedHashMap<String,Map<String,Object>>();
            if(!scope.equals("deps"))for(var symbol:workspaceFind(s,ref,substring))matches.put(symbol.get("scip").toString(),symbol);
            if(!scope.equals("workspace")){var database=index();bindIndex(s,database);for(var symbol:database.find(ref,s.state("resolution")==null?null:s.id(),substring,offset+limit+1,0))matches.put(symbol.get("scip").toString(),symbol);}
            var kinds=new HashSet<String>();p.path("kinds").forEach(k->kinds.add(k.asText()));
            var all=matches.values().stream().filter(symbol->kinds.isEmpty()||kinds.contains(symbol.get("kind"))).toList();
            return page(2,"live","matches",all,offset,limit,s.warnings());
        });
        dispatcher.register("symbol.describe",(s,p)->describe(s,Dispatcher.required(p,"ref")));
        dispatcher.register("symbol.references",(s,p)->relationships(s,p,false));
        dispatcher.register("symbol.hierarchy",(s,p)->relationships(s,p,true));
        dispatcher.register("session.status", (s, _) -> {
            var graph = (Resolution) s.state("resolution");
            return new Envelope(0, "live", false, null, s.warnings(), java.util.Map.of("session", s.id(),
                    "root", s.root().toString(), "classpath_state", graph == null ? "unresolved" : "resolved",
                    "classpath_entries", graph == null ? 0 : graph.classpath().size(), "metrics", dispatcher.status().get("metrics"),"analyzer",s.state("analyzer")==null?Map.of("initialized",false):((Analyzer)s.state("analyzer")).status()));
        });
        dispatcher.register("symbol.overview", (s, p) -> {
            Path path = s.root().resolve(Dispatcher.required(p, "path")).normalize();
            if (!path.startsWith(s.root())) throw RpcException.invalid("Path is outside workspace");
            int offset;try{offset=Integer.parseInt(p.path("cursor").asText("0"));}catch(NumberFormatException e){throw RpcException.invalid("Invalid cursor");}
            if(offset<0)throw RpcException.invalid("Invalid cursor");
            return analyzer(s,path).overview(path,Files.readString(path),Dispatcher.bounded(p,"depth",1,10),Dispatcher.bounded(p,"limit",100,1000),offset);
        });
        dispatcher.register("diag.get",(s,p)->{
            if(p.path("verified").asBoolean()){
                var verified=new Verifier(config).verify(s.root(),(com.fasterxml.jackson.databind.JsonNode)s.state("manifest"),java.time.Duration.ofMinutes(5));
                s.put("last_verification",verified);
                if(verified.exitCode()!=0)throw new RpcException(-32004,"verify_failed",verified);
                return new Envelope(2,"verified",false,null,verified.warnings(),Map.of("diagnostics",verified.diagnostics(),"exit_code",verified.exitCode(),"elapsed_ms",verified.elapsedMillis()));
            }
            var files=new ArrayList<Path>();for(var value:p.path("paths"))files.add(sourcePath(s,value.asText()));if(files.isEmpty())files.addAll(sourceFiles(s));
            var diagnostics=new ArrayList<Object>();var warnings=new LinkedHashSet<String>();int tier=2;
            for(Path path:files){var result=analyzer(s,path).diagnostics(path,Files.readString(path));tier=Math.min(tier,result.tier());warnings.addAll(result.warnings());diagnostics.addAll((List<?>)((Map<?,?>)result.result()).get("diagnostics"));}
            return page(tier,"live","diagnostics",diagnostics,cursor(p),Dispatcher.bounded(p,"limit",200,1000),List.copyOf(warnings));
        });
    }
    public Dispatcher dispatcher() { return dispatcher; }
    public Sessions sessions() { return sessions; }
    private static Path sourcePath(Session session,String value){
        Path path=session.root().resolve(value).toAbsolutePath().normalize();if(!path.startsWith(session.root()))throw RpcException.invalid("Path is outside workspace");return path;
    }
    private static int cursor(com.fasterxml.jackson.databind.JsonNode params){
        try{int value=Integer.parseInt(params.path("cursor").asText("0"));if(value<0)throw new NumberFormatException();return value;}catch(NumberFormatException e){throw RpcException.invalid("Invalid cursor");}
    }
    private static Envelope page(int tier,String source,String key,List<?> values,int offset,int limit,List<String> warnings){
        int from=Math.min(offset,values.size()),to=Math.min(values.size(),from+limit);boolean more=to<values.size();return new Envelope(tier,source,more,more?Integer.toString(to):null,warnings,Map.of(key,List.copyOf(values.subList(from,to))));
    }
    private List<Path> sourceFiles(Session session)throws Exception{
        var graph=(Resolution)session.state("resolution");var roots=new LinkedHashSet<Path>();var files=new LinkedHashSet<Path>();
        if(graph==null)roots.add(session.root());else for(var module:graph.modules()){module.sources().forEach(p->roots.add(Path.of(p)));module.testSources().forEach(p->roots.add(Path.of(p)));}
        for(Path root:roots)if(Files.isDirectory(root))try(var paths=Files.walk(root)){paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")).sorted().forEach(files::add);}
        return List.copyOf(files);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> workspaceFind(Session session,String ref,boolean substring)throws Exception{
        var found=new LinkedHashMap<String,Map<String,Object>>();
        for(Path file:sourceFiles(session)){
            var analyzer=analyzer(session,file);int offset=0;
            do{
                var outline=analyzer.overview(file,Files.readString(file),10,1000,offset);
                for(var symbol:(List<Map<String,Object>>)((Map<?,?>)outline.result()).get("symbols"))if(symbol.get("scip")!=null&&Analyzer.matches(symbol,ref,substring))found.put(symbol.get("scip").toString(),symbol);
                if(!outline.truncated())break;offset=Integer.parseInt(outline.cursor());
            }while(true);
        }return List.copyOf(found.values());
    }
    private Envelope describe(Session session,String ref)throws Exception{
        var analyzer=(Analyzer)session.state("analyzer");
        if((ref.startsWith("maven ")||ref.startsWith("local "))&&analyzer!=null){
            var known=analyzer.known(ref);if(known.size()==1){var symbol=known.getFirst();var file=symbol.get("source_file");
                if(file!=null&&Files.isRegularFile(Path.of(file.toString()))&&symbol.get("name_start") instanceof Number position){
                    Path path=Path.of(file.toString());String text=Files.readString(path);var snapshot=analyzer(session,path).bindings(path,text,position.intValue());if(snapshot.result()!=null){var current=snapshot.result().symbols().get(ref);if(current!=null)return new Envelope(snapshot.tier(),"live",false,null,snapshot.warnings(),current);}
                }else return Envelope.of(2,"live",symbol);
            }
        }
        var local=workspaceFind(session,ref,false);
        if(local.size()==1)return Envelope.of(1,"live",local.getFirst());
        if(local.size()>1)return page(1,"live","candidates",local,0,20,List.of("ambiguous"));
        var database=index();bindIndex(session,database);var found=database.find(ref,session.state("resolution")==null?null:session.id(),false,21,0);
        if(found.size()!=1)return page(2,"index","candidates",found,0,20,found.size()>1?List.of("ambiguous"):session.warnings());
        return Envelope.of(2,"index",found.getFirst());
    }
    private Envelope relationships(Session session,com.fasterxml.jackson.databind.JsonNode params,boolean hierarchy)throws Exception{
        String ref=Dispatcher.required(params,"ref");var description=describe(session,ref);
        if(!(description.result() instanceof Map<?,?> symbol)||symbol.get("scip")==null)return description;
        String key=symbol.get("scip").toString();String direction=params.path("direction").asText(hierarchy?"up":"in");
        if(!(hierarchy?Set.of("up","down"):Set.of("in","out")).contains(direction))throw RpcException.invalid("Unknown relationship direction");
        boolean outgoing=direction.equals("out")||direction.equals("up");
        int depth=Dispatcher.bounded(params,"depth",hierarchy?3:1,20),limit=Dispatcher.bounded(params,"limit",100,1000),offset=cursor(params),tier=2;
        var allowed=new HashSet<String>();params.path("kinds").forEach(k->allowed.add(k.asText()));if(hierarchy)allowed.addAll(Set.of("extends","implements","overrides"));
        var symbols=new LinkedHashMap<String,Map<String,Object>>();var edges=new LinkedHashSet<Bindings.Edge>();var occurrences=new ArrayList<Bindings.Occurrence>();var warnings=new LinkedHashSet<String>();
        for(Path file:sourceFiles(session)){
            var snapshot=analyzer(session,file).bindings(file,Files.readString(file),null);tier=Math.min(tier,snapshot.tier());warnings.addAll(snapshot.warnings());if(snapshot.result()==null)continue;
            symbols.putAll(snapshot.result().symbols());edges.addAll(snapshot.result().edges());occurrences.addAll(snapshot.result().occurrences());
        }
        var reached=new LinkedHashSet<String>();reached.add(key);var selected=new LinkedHashSet<Bindings.Edge>();
        for(int d=0;d<depth;d++){var next=new LinkedHashSet<String>();for(var edge:edges)if((allowed.isEmpty()||allowed.contains(edge.kind()))&&reached.contains(outgoing?edge.src():edge.dst())){selected.add(edge);next.add(outgoing?edge.dst():edge.src());}if(!reached.addAll(next))break;}
        var edgeList=List.copyOf(selected);var matches=occurrences.stream().filter(o->reached.contains(o.scip())&&!o.role().equals("declaration")).toList();
        var nodes=symbols.values().stream().filter(s->reached.contains(s.get("scip"))).toList();int max=Math.max(edgeList.size(),Math.max(matches.size(),nodes.size())),to=Math.min(max,offset+limit);boolean more=to<max;
        return new Envelope(tier,"live",more,more?Integer.toString(to):null,List.copyOf(warnings),Map.of("symbols",slice(nodes,offset,limit),"edges",slice(edgeList,offset,limit),"references",slice(matches,offset,limit)));
    }
    private static <T> List<T> slice(List<T> list,int offset,int limit){return List.copyOf(list.subList(Math.min(offset,list.size()),Math.min(list.size(),offset+limit)));}
    private Analyzer analyzer(Session session,Path path)throws Exception{
        var graph=(Resolution)session.state("resolution");
        if(graph!=null)graph=refresh(session);
        String gav="local:workspace:0",release="25",generation="plain";
        List<String> options=List.of("--release","25");
        var classpath=new java.util.ArrayList<Path>();var sources=new java.util.ArrayList<Path>();var coordinates=new java.util.LinkedHashMap<String,String>();
        if(graph!=null){
            var module=graph.modules().stream().filter(m->path.startsWith(Path.of(m.directory()))).max(java.util.Comparator.comparingInt(m->m.directory().length())).orElse(graph.modules().getFirst());
            gav=module.gav();release=module.release()==null||module.release().isBlank()?"25":module.release();generation=graph.fingerprint()+":"+gav;
            boolean test=module.testSources().stream().anyMatch(root->path.startsWith(Path.of(root)));
            options=test?module.testCompilerOptions():module.compilerOptions();generation+=test?":test":":main";
            graph.classpaths().getOrDefault(gav+(test?":test":":main"),java.util.List.of()).forEach(p->classpath.add(Path.of(p)));
            module.sources().forEach(p->sources.add(Path.of(p)));if(test)module.testSources().forEach(p->sources.add(Path.of(p)));
            for(var m:graph.modules()){coordinates.put(m.directory(),m.gav());coordinates.put(Path.of(m.directory()).toUri().toString(),m.gav());}
            for(var node:graph.nodes())if(node.path()!=null&&node.winner()==null)coordinates.put(node.path(),node.gav());
        }else{sources.add(session.root());coordinates.put(session.root().toString(),gav);coordinates.put(session.root().toUri().toString(),gav);}
        var analyzer=session.state("analyzer",Analyzer::new);
        var availableIndex=index!=null&&index.isDone()&&!index.isCompletedExceptionally()?index.join():null;
        analyzer.configure(new Analyzer.Context(gav,release,java.util.List.copyOf(classpath),java.util.List.copyOf(sources),generation,java.util.Map.copyOf(coordinates),options),availableIndex,config.heapCeilingMb()*1024L*1024/Math.max(1,sessions.list().size()));
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
                    request.put("method","symbol.atPosition");request.putObject("params").put("session",session.id()).put("path",file.toString()).put("line",0).put("character",43);
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
