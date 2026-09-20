package dev.jvmd.dist;

import dev.jvmd.analyzer.Parser;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Bindings;
import dev.jvmd.analyzer.CompilerPool;
import dev.jvmd.analyzer.DiagnosticEngine;
import java.util.*;
import dev.jvmd.core.*;
import dev.jvmd.resolver.MavenResolver;
import dev.jvmd.resolver.Resolution;
import dev.jvmd.index.IndexService;
import dev.jvmd.index.ArtifactGenerationSink;
import java.nio.file.Files;
import java.nio.file.Path;

/** Implements 4.1 and 12.1: composition root; user dependencies never enter this classpath. */
public final class Application implements AutoCloseable {
    private final Sessions sessions = new Sessions();
    private final Dispatcher dispatcher = new Dispatcher(sessions, new Metrics());
    private final Config config;
    private final FileStateRegistry classpathFiles=new FileStateRegistry();
    private volatile MavenResolver resolver;
    private volatile dev.jvmd.runtime.JavaRuntime.Selection debuggeeRuntime;
    private volatile java.util.concurrent.CompletableFuture<IndexService> index;
    private static final Set<String> COMPLETION_TYPE_KINDS=Set.of("class","interface","enum","record","annotation");
    private record TypeCompletionCache(String generation,String prefix,List<Map<String,Object>> rows,boolean complete) { }
    public Application(Config config) {
        this.config = config;
        if (config.indexOnStart()) initializeIndex(true);
        dispatcher.status("index", () -> {
            try { return index == null ? java.util.Map.of("phase", "disabled") : index.isDone() ? index.join().status() : java.util.Map.of("phase", "starting"); }
            catch (Exception e) { return java.util.Map.of("phase", "failed", "reason", e.toString()); }
        });
        dispatcher.status("aot_cache", () -> AotStatus.runtime(Path.of(System.getProperty("jvmd.aot.log", config.stateDir().resolve("aot.log").toString()))));
        dispatcher.status("resolver", () -> resolver == null ? java.util.Map.of("maven_major", config.mavenMajor(), "initialized", false) : resolver.status());
        dispatcher.status("classpath_files",classpathFiles::status);
        dispatcher.register("session.open", (_, p) -> {
            var session = sessions.open(Path.of(Dispatcher.required(p, "root")));
            var manifest=p.get("manifest");
            if(manifest==null){Path file=session.root().resolve(".jvmd/workspace.json");manifest=Files.isRegularFile(file)?Json.MAPPER.readTree(file.toFile()):Json.MAPPER.createObjectNode();}
            else if(manifest.isTextual())manifest=Json.MAPPER.readTree(session.root().resolve(manifest.asText()).toFile());
            if(!manifest.isObject())throw RpcException.invalid("manifest must be an object or path");
            session.put("manifest",manifest);
            session.put("workspace_manifest",WorkspaceManifest.read(session.root(),manifest));
            return session.execute(() -> {
                Resolution graph = workspace(session).roots().stream().anyMatch(root->Files.isRegularFile(root.resolve("pom.xml"))) ? refresh(session) : null;
                return new Envelope(0, "live", false, null, session.warnings(), java.util.Map.of("session", session.id(),
                        "root", session.root().toString(), "classpath_entries", graph == null ? 0 : graph.classpath().size(),
                        "modules", graph == null ? 0 : graph.modules().size()));
            });
        });
        dispatcher.register("mcp.tools",(_,_) -> Envelope.of(2,"index",Map.of("catalog",dev.jvmd.mcp.McpTools.catalog())));
        dispatcher.register("mcp.invoke",(s,p)->dev.jvmd.mcp.McpTools.invoke(dispatcher,s.id(),p));
        dispatcher.register("document.open",(s,p)->document(s,p,"open"));
        dispatcher.register("document.change",(s,p)->document(s,p,"change"));
        dispatcher.register("document.close",(s,p)->document(s,p,"close"));
        dispatcher.register("run.start",this::run);
        dispatcher.register("debug.op",(session,params)->runs(session).operation(Dispatcher.required(params,"run_session"),Dispatcher.required(params,"op"),params.path("args")));
        dispatcher.register("deps.graph", (s, p) -> dependencyGraph(refresh(s), p));
        dispatcher.register("symbol.atPosition",(s,p)->{
            Path path=sourcePath(s,Dispatcher.required(p,"path"));
            return analyzer(s,path).atPosition(path,documents(s).text(path),Dispatcher.bounded(p,"line",0,Integer.MAX_VALUE),Dispatcher.bounded(p,"character",0,Integer.MAX_VALUE));
        });
        dispatcher.register("symbol.find",(s,p)->{
            if(p.has("path")){Path path=sourcePath(s,Dispatcher.required(p,"path"));return analyzer(s,path).atPosition(path,documents(s).text(path),Dispatcher.bounded(p,"line",0,Integer.MAX_VALUE),Dispatcher.bounded(p,"character",0,Integer.MAX_VALUE));}
            String ref=Dispatcher.required(p,"name_path"),scope=p.path("scope").asText("workspace");if(!Set.of("workspace","deps","all").contains(scope))throw RpcException.invalid("Unknown symbol scope");
            int limit=Dispatcher.limit(p,50,200);boolean substring=p.path("substring").asBoolean();
            IndexService searchIndex=null;if(!scope.equals("workspace")){searchIndex=index();prepareIndex(s,searchIndex);}
            var kinds=new HashSet<String>();p.path("kinds").forEach(k->kinds.add(k.asText()));int depth=Dispatcher.bounded(p,"depth",0,10);
            String continuation=p.path("cursor").asText("0");
            if(scope.equals("deps")&&depth==0&&(continuation.equals("0")||continuation.startsWith("index:"))){
                long after=0;
                if(!continuation.equals("0"))try{after=Long.parseLong(continuation.substring(6));if(after<=0)throw new NumberFormatException();}
                catch(NumberFormatException invalid){throw RpcException.invalid("Invalid index cursor");}
                var found=searchIndex.find(ref,s.state("resolution")==null?null:s.id(),substring,limit+1,after,kinds);
                boolean more=found.size()>limit;int end=Math.min(limit,found.size());var rows=new ArrayList<Map<String,Object>>();
                for(var symbol:found.subList(0,end))rows.add(findResult(symbol,p.path("include_body").asBoolean()));
                return new Envelope(2,"index",more,more?"index:"+found.get(end-1).get("id"):null,s.warnings(),Map.of("matches",List.copyOf(rows)));
            }
            int offset=cursor(p);
            var matches=new LinkedHashMap<String,Map<String,Object>>();
            int needed=Math.addExact(Math.addExact(offset,limit),1);
            if(!scope.equals("deps"))for(var parent:workspaceFind(s,ref,substring)) {
                expandFind(s,parent,scope,searchIndex,depth,kinds,needed,matches);
                if(matches.size()>=needed)break;
            }
            if(!scope.equals("workspace")&&matches.size()<needed) {
                long after=0;
                while(matches.size()<needed) {
                    int batch=Math.min(128,needed-matches.size());
                    var parents=searchIndex.find(ref,s.state("resolution")==null?null:s.id(),substring,batch,after,depth>0?Set.of():kinds);
                    if(parents.isEmpty())break;
                    for(var parent:parents) {
                        after=((Number)parent.get("id")).longValue();
                        expandFind(s,parent,scope,searchIndex,depth,kinds,needed,matches);
                        if(matches.size()>=needed)break;
                    }
                    if(parents.size()<batch)break;
                }
            }
            var all=new ArrayList<Map<String,Object>>();
            for(var symbol:matches.values())if(kinds.isEmpty()||kinds.contains(symbol.get("kind"))){
                all.add(findResult(symbol,p.path("include_body").asBoolean()&&all.size()>=offset&&all.size()<offset+limit));
            }
            return page(scope.equals("deps")?2:1,scope.equals("deps")?"index":"live","matches",all,offset,limit,s.warnings());
        });
        dispatcher.register("symbol.completion",this::completion);
        dispatcher.register("symbol.signatureHelp",(s,p)->{Path path=sourcePath(s,Dispatcher.required(p,"path"));return analyzer(s,path).signatureHelp(path,documents(s).text(path),Dispatcher.bounded(p,"line",0,Integer.MAX_VALUE),Dispatcher.bounded(p,"character",0,Integer.MAX_VALUE));});
        dispatcher.register("symbol.semanticTokens",(s,p)->{Path path=sourcePath(s,Dispatcher.required(p,"path"));return analyzer(s,path).semanticTokens(path,documents(s).text(path),Dispatcher.limit(p,2000,10000),cursor(p));});
        dispatcher.register("symbol.occurrences",this::occurrences);
        dispatcher.register("lsp.request",(s,p)->dev.jvmd.lsp.LspFacade.request(dispatcher,s,documents(s),p));
        dispatcher.register("lsp.diagnostics",(s,p)->dev.jvmd.lsp.LspFacade.diagnostics(dispatcher,s,documents(s),p));
        dispatcher.register("symbol.describe",this::describeDocumented);
        dispatcher.register("edit.replaceBody",(s,p)->editSymbol(s,p,"body"));
        dispatcher.register("edit.insert",(s,p)->editSymbol(s,p,"insert"));
        dispatcher.register("edit.rename",this::renameSymbol);
        dispatcher.register("edit.text",this::editText);
        dispatcher.register("symbol.references",(s,p)->relationships(s,p,false));
        dispatcher.register("symbol.hierarchy",(s,p)->relationships(s,p,true));
        dispatcher.register("session.status", (s, _) -> {
            var graph=(Resolution)s.state("resolution");var result=new LinkedHashMap<String,Object>();
            result.put("shared_classpath_files",classpathFiles.status());
            result.put("diagnostics",s.state("diagnostics")==null?Map.of("initialized",false):diagnostics(s).status());result.put("file_states",documents(s).fileStates().status());result.put("analysis_contexts",s.state("analysis_contexts")==null?Map.of():((WorkspaceContextManager)s.state("analysis_contexts")).status());
            result.put("workspace_bindings",s.state("workspace_bindings")==null?Map.of("initialized",false):((WorkspaceBindings)s.state("workspace_bindings")).status());result.put("documents",documents(s).status());result.put("session",s.id());result.put("root",s.root().toString());result.put("classpath_state",graph==null?"unresolved":"resolved");result.put("classpath_entries",graph==null?0:graph.classpath().size());result.put("overlay",graph==null?Map.of():overlay(s,graph).status());result.put("metrics",dispatcher.status().get("metrics"));result.put("annotation_processing",s.state("processors")==null?Map.of("initialized",false):((AnnotationProcessing)s.state("processors")).status());
            var actorRegistry=(ModuleAnalyzerRegistry)s.state("diagnostic_actors");var interactiveAnalyzer=(Analyzer)s.state("analyzer");
            result.put("analyzer",actorRegistry!=null?actorRegistry.analyzerStatus(interactiveAnalyzer==null?Map.of():interactiveAnalyzer.status()):interactiveAnalyzer==null?Map.of("initialized",false):interactiveAnalyzer.status());
            result.put("module_actors",actorRegistry==null?Map.of("initialized",false):actorRegistry.status());result.put("runs",s.state("runs")==null?List.of():runs(s).status());result.put("index",index==null?Map.of("phase","disabled"):index.isDone()&&!index.isCompletedExceptionally()?index.join().status():Map.of("phase","starting"));result.put("capabilities",Map.of("analysis_tiers",List.of(0,1,2),"mcp_tools",14,"runtime",true));
            return new Envelope(0,"live",false,null,s.warnings(),result);
        });
        dispatcher.register("symbol.overview",this::overview);
        dispatcher.register("diag.get",(s,p)->{
            if(p.path("verified").asBoolean()){
                if(dirty(s))throw new RpcException(-32003,"unsupported_capability",Map.of("capability","verified","reason","Save editor changes before verifying the on-disk build"));
                var verified=new Verifier(config).verify(s.root(),(com.fasterxml.jackson.databind.JsonNode)s.state("manifest"),java.time.Duration.ofMinutes(5));
                s.put("last_verification",verified);
                if(verified.exitCode()!=0)throw new RpcException(-32004,"verify_failed",verified);
                return new Envelope(2,"verified",false,null,verified.warnings(),Map.of("diagnostics",verified.diagnostics(),"exit_code",verified.exitCode(),"elapsed_ms",verified.elapsedMillis()));
            }
            RequestScope.memo(List.of(s,"analysis-resolution"),()->s.state("resolution")==null?null:refresh(s));
            var files=new ArrayList<Path>();for(var value:p.path("paths"))files.add(sourcePath(s,value.asText()));boolean whole=files.isEmpty();if(whole)files.addAll(sourceFiles(s));
            return diagnostics(s).get(files,whole,cursor(p),Dispatcher.limit(p,200,1000),s.warnings());
        });
    }
    @SuppressWarnings("unchecked")
    private Envelope completion(Session session,com.fasterxml.jackson.databind.JsonNode params)throws Exception{
        Path path=sourcePath(session,Dispatcher.required(params,"path"));String text=documents(session).text(path);
        int line=Dispatcher.bounded(params,"line",0,Integer.MAX_VALUE),character=Dispatcher.bounded(params,"character",0,Integer.MAX_VALUE);
        int limit=Dispatcher.limit(params,100,1000),offset=cursor(params),cursorOffset=Documents.offset(text,new Documents.Position(line,character));
        int start=cursorOffset;while(start>0&&Character.isJavaIdentifierPart(text.codePointBefore(start)))start-=Character.charCount(text.codePointBefore(start));
        String prefix=text.substring(start,cursorOffset);
        int previous=start-1;while(previous>=0&&Character.isWhitespace(text.charAt(previous)))previous--;
        boolean qualified=previous>=0&&text.charAt(previous)=='.';
        if(prefix.length()<2||qualified||session.state("resolution")==null)
            return analyzer(session,path).completion(path,text,line,character,limit,offset);

        // One full live page lets us merge and paginate deterministically. Extremely broad
        // live scopes retain the old behavior rather than silently dropping live candidates.
        var live=analyzer(session,path).completion(path,text,line,character,1000,0);
        if(live.truncated())return analyzer(session,path).completion(path,text,line,character,limit,offset);
        var liveResult=(Map<String,Object>)live.result();
        var liveItems=(List<Map<String,Object>>)liveResult.getOrDefault("items",List.of());

        IndexService searchIndex=index();bindIndex(session,searchIndex);
        String generation=Objects.toString(session.state("index_generation"),"");
        var cached=(TypeCompletionCache)session.state("completion_type_cache");
        List<Map<String,Object>> indexed;
        if(cached!=null&&cached.complete()&&cached.generation().equals(generation)&&prefix.startsWith(cached.prefix())){
            indexed=cached.rows().stream().filter(row->Objects.toString(row.get("name"),"").startsWith(prefix)).toList();
        }else{
            var found=searchIndex.findNamePrefix(prefix,session.id(),257,COMPLETION_TYPE_KINDS);
            boolean complete=found.size()<257;indexed=List.copyOf(found.subList(0,Math.min(256,found.size())));
            session.put("completion_type_cache",new TypeCompletionCache(generation,prefix,indexed,complete));
        }

        String packageName=sourcePackage(text);var imported=sourceImports(text);
        var liveNames=new HashSet<String>();for(var row:liveItems)liveNames.add(Objects.toString(row.get("name"),""));
        var types=new LinkedHashMap<String,Map<String,Object>>();
        for(var symbol:indexed){
            String name=Objects.toString(symbol.get("name"),""),fqn=Objects.toString(symbol.get("fqn"),"").replace('$','.');
            if(name.isBlank()||fqn.isBlank()||liveNames.contains(name)||!name.startsWith(prefix))continue;
            int split=fqn.lastIndexOf('.');String candidatePackage=split<0?"":fqn.substring(0,split);
            int flags=symbol.get("flags") instanceof Number value?value.intValue():0;
            if(!candidatePackage.equals(packageName)&&(flags&1)==0)continue;
            var row=new LinkedHashMap<String,Object>();
            for(String key:List.of("scip","name","name_path","kind","signature","fqn"))if(symbol.get(key)!=null)row.put(key,symbol.get(key));
            row.put("label",fqn);row.put("doc",dev.jvmd.index.DocMarkdown.summary((String)symbol.get("doc")));
            if(needsImport(fqn,packageName,imported))row.put("import",fqn);
            types.putIfAbsent(fqn,Collections.unmodifiableMap(row));
        }
        var typeRows=new ArrayList<Map<String,Object>>(types.values());typeRows.sort(Comparator.comparing((Map<String,Object> row)->Objects.toString(row.get("name"),"")).thenComparing(row->Objects.toString(row.get("fqn"),"")));
        var all=new ArrayList<Map<String,Object>>(liveItems.size()+typeRows.size());all.addAll(liveItems);all.addAll(typeRows);
        int from=Math.min(offset,all.size()),to=Math.min(all.size(),from+limit);boolean more=to<all.size();
        return new Envelope(live.tier(),"live",more,more?Integer.toString(to):null,live.warnings(),
                Map.of("items",List.copyOf(all.subList(from,to)),"range",liveResult.get("range")));
    }

    private static String sourcePackage(String text){
        var match=java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;").matcher(text);
        return match.find()?match.group(1):"";
    }
    private static Set<String> sourceImports(String text){
        var result=new HashSet<String>();
        var match=java.util.regex.Pattern.compile("(?m)^\\s*import\\s+(?!static\\s+)([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)*)\\s*;").matcher(text);
        while(match.find())result.add(match.group(1));return Set.copyOf(result);
    }
    private static boolean needsImport(String fqn,String packageName,Set<String> imports){
        int split=fqn.lastIndexOf('.');String candidatePackage=split<0?"":fqn.substring(0,split);
        return !candidatePackage.equals(packageName)&&!candidatePackage.equals("java.lang")&&!imports.contains(fqn)&&!imports.contains(candidatePackage+".*");
    }

    private WorkspaceAnalysisCoordinator diagnostics(Session session){
        var actors=diagnosticActors(session);
        return session.state("diagnostics",()->new WorkspaceAnalysisCoordinator(documents(session),file->diagnosticAnalyzer(session,file),session::yieldInteractive,file->externalDiagnostics(session,file),actors.parallelism()));
    }
    private ModuleAnalyzerRegistry diagnosticActors(Session session){return session.state("diagnostic_actors",()->new ModuleAnalyzerRegistry(classpathFiles));}
    private DiagnosticEngine diagnosticAnalyzer(Session session,Path path)throws Exception{
        var graph=RequestScope.memo(List.of(session,"analysis-resolution"),()->session.state("resolution")==null?null:refresh(session));
        var contexts=session.state("analysis_contexts",WorkspaceContextManager::new);
        var context=contexts.context(path,graph,file->createAnalyzerContext(session,file,graph));
        var availableIndex=index!=null&&index.isDone()&&!index.isCompletedExceptionally()?index.join():null;
        long totalBudget=config.heapCeilingMb()*1024L*1024/Math.max(1,sessions.list().size());
        Path persistence=config.stateDir().resolve("diagnostics-v2").resolve(Hashing.sha256(session.root().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return diagnosticActors(session).engine(WorkspaceContextManager.key(path,graph),context,availableIndex,totalBudget,documents(session),persistence);
    }
    private WorkspaceAnalysisCoordinator.ExternalResult externalDiagnostics(Session session,Path file)throws Exception{
        var graph=(Resolution)session.state("resolution");if(graph==null)return null;
        var module=WorkspaceContextManager.owner(file,graph);
        boolean test=module.testSources().stream().anyMatch(root->file.startsWith(Path.of(root)));
        var settings=test?module.testProcessing():module.processing();if(!settings.lombok())return null;
        // The isolated worker consumes a saved module snapshot. Unsaved editor state must keep using
        // the resident analyser until a future worker can materialize authoritative buffers.
        if(documents(session).dirty(Path.of(module.directory())))return null;
        var output=(AnnotationProcessing.Output)session.state("apt:"+module.gav()+(test?":test":":main"));
        if(output==null||!output.diagnosticFidelity().equals("full_lombok_external"))return null;
        String wanted=file.toAbsolutePath().normalize().toUri().toString();
        var diagnostics=output.diagnostics().stream().filter(problem->problem.file().equals(wanted)).map(problem->
                new dev.jvmd.analyzer.CompilerPool.Problem("external-javac",2,problem.code(),problem.kind(),problem.file(),
                        problem.line(),problem.character(),-1,-1,problem.message())).toList();
        return new WorkspaceAnalysisCoordinator.ExternalResult(diagnostics,output.diagnosticFidelity());
    }
    public Dispatcher dispatcher() { return dispatcher; }
    public Sessions sessions() { return sessions; }
    private static WorkspaceManifest workspace(Session session){return session.state("workspace_manifest",()->new WorkspaceManifest(List.of(session.root()),true));}
    private static dev.jvmd.resolver.WorkspaceOverlay overlay(Session session,Resolution graph){
        var old=(dev.jvmd.resolver.WorkspaceOverlay)session.state("overlay");
        if(old==null||!graph.fingerprint().equals(session.state("overlay_generation"))){old=new dev.jvmd.resolver.WorkspaceOverlay(graph.modules(),workspace(session).ignoreVersions());session.put("overlay",old);session.put("overlay_generation",graph.fingerprint());}
        return old;
    }
    private static Path sourcePath(Session session,String value){return workspace(session).resolve(session.root(),value);}

    public static Documents documents(Session session){return session.state("documents",Documents::new);}
    private static boolean dirty(Session session)throws Exception{for(Path root:workspace(session).roots())if(documents(session).dirty(root))return true;return false;}
    private Envelope document(Session session,com.fasterxml.jackson.databind.JsonNode params,String operation)throws Exception{
        Path file=sourcePath(session,Dispatcher.required(params,"path"));if(!file.toString().endsWith(".java"))throw RpcException.invalid("Document must be Java source");
        var documents=documents(session);
        if(operation.equals("close"))documents.close(file);
        else{
            var version=params.path("version");if(!version.isIntegralNumber()||!version.canConvertToInt())throw RpcException.invalid("Document version must be an integer");
            if(operation.equals("open")){if(!params.path("text").isTextual())throw RpcException.invalid("Document text is required");documents.open(file,params.path("text").asText(),version.intValue());}
            else{
                if(!params.path("changes").isArray())throw RpcException.invalid("Document changes must be an array");var changes=new ArrayList<Documents.Change>();
                for(var value:params.path("changes")){if(!value.path("text").isTextual())throw RpcException.invalid("Change text is required");Documents.Range range=null;
                    if(value.hasNonNull("range"))range=Json.MAPPER.treeToValue(value.path("range"),Documents.Range.class);changes.add(new Documents.Change(range,value.path("text").asText()));}
                documents.change(file,version.intValue(),changes);
            }
        }
        var analyzer=(Analyzer)session.state("analyzer");if(analyzer!=null){analyzer.changed(file);if(!Files.isRegularFile(file)&&(operation.equals("open")||operation.equals("close")))analyzer.namespaceChanged();}
        session.put("last_verification",Map.of("stale",true));
        return Envelope.of(0,"live",Map.of("path",file.toString(),"open",documents.contains(file),"generation",documents.generation()));
    }

    private static int cursor(com.fasterxml.jackson.databind.JsonNode params){
        try{int value=Integer.parseInt(params.path("cursor").asText("0"));if(value<0)throw new NumberFormatException();return value;}catch(NumberFormatException e){throw RpcException.invalid("Invalid cursor");}
    }
    private static Map<String,Object> findResult(Map<String,Object> symbol,boolean body)throws Exception{
        var value=new LinkedHashMap<>(symbol);value.put("doc",dev.jvmd.index.DocMarkdown.summary((String)symbol.get("doc")));
        return body?new LinkedHashMap<>(dev.jvmd.index.Documentation.withBody(value)):value;
    }
    private static Envelope page(int tier,String source,String key,List<?> values,int offset,int limit,List<String> warnings){
        int from=Math.min(offset,values.size()),to=Math.min(values.size(),from+limit);boolean more=to<values.size();return new Envelope(tier,source,more,more?Integer.toString(to):null,warnings,Map.of(key,List.copyOf(values.subList(from,to))));
    }
    private List<Path> sourceFiles(Session session)throws Exception{
        var graph=(Resolution)session.state("resolution");var roots=new LinkedHashSet<Path>();var files=new LinkedHashSet<Path>();
        if(graph==null)roots.addAll(workspace(session).roots());else for(var module:graph.modules()){module.sources().forEach(p->roots.add(Path.of(p)));module.testSources().forEach(p->roots.add(Path.of(p)));}
        // Background snapshot writers can rename unrelated temporary files during discovery.
        for(Path root:roots)if(Files.isDirectory(root))files.addAll(FileInventory.matching(root,".java"));
        documents(session).paths().stream().filter(workspace(session)::contains).sorted().forEach(files::add);
        return List.copyOf(files);
    }
    private WorkspaceBindings.Snapshot workspaceBindings(Session session,boolean load)throws Exception{
        var graph=(Resolution)session.state("resolution");if(graph!=null)graph=refresh(session);
        var classpath=new LinkedHashSet<Path>();
        if(graph!=null){graph.classpath().forEach(path->classpath.add(Path.of(path)));for(var module:graph.modules()){
            classpath.add(Path.of(module.classes()));classpath.add(Path.of(module.testClasses()));
            for(boolean test:List.of(false,true)){
                (test?module.testProcessing():module.processing()).path().forEach(path->classpath.add(Path.of(path)));
                if(session.state("apt:"+module.gav()+(test?":test":":main")) instanceof AnnotationProcessing.Output output)classpath.addAll(output.classpath());
            }
        }}
        var cache=session.state("workspace_bindings",()->new WorkspaceBindings(classpathFiles));String generation=graph==null?"plain":graph.fingerprint();
        if(!load)return cache.peek(sourceFiles(session),List.copyOf(classpath),documents(session),generation);
        Resolution currentGraph=graph;
        return cache.getBatch(()->sourceFiles(session),List.copyOf(classpath),documents(session),generation,(long)config.heapCeilingMb()*1024*1024/Math.max(1,sessions.list().size())/4,files->{
            var groups=new LinkedHashMap<String,LinkedHashMap<Path,String>>();
            for(var entry:files.entrySet())groups.computeIfAbsent(WorkspaceContextManager.key(entry.getKey(),currentGraph),_->new LinkedHashMap<>()).put(entry.getKey(),entry.getValue());
            var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
            for(var group:groups.values()){
                var batch=new LinkedHashMap<Path,String>();long characters=0;
                for(var entry:group.entrySet()){
                    if(!batch.isEmpty()&&(batch.size()>=32||characters+entry.getValue().length()>1024*1024)){
                        results.putAll(analyzer(session,batch.keySet().iterator().next()).bindingsBatch(batch));batch.clear();characters=0;
                    }
                    batch.put(entry.getKey(),entry.getValue());characters+=entry.getValue().length();
                }
                if(!batch.isEmpty())results.putAll(analyzer(session,batch.keySet().iterator().next()).bindingsBatch(batch));
            }
            return results;
        });
    }
    @SuppressWarnings("unchecked")
    private Envelope overview(Session session,com.fasterxml.jackson.databind.JsonNode params)throws Exception{
        boolean byPath=params.has("path");if(byPath==params.has("package"))throw RpcException.invalid("overview needs either path or package");
        int depth=Dispatcher.bounded(params,"depth",1,10),limit=Dispatcher.limit(params,100,1000),offset=cursor(params);
        Path path=byPath?sourcePath(session,Dispatcher.required(params,"path")):null;
        if(path!=null&&(Files.isRegularFile(path)||documents(session).contains(path)))return analyzer(session,path).overview(path,documents(session).text(path),depth,limit,offset);
        String wanted=byPath?"":Dispatcher.required(params,"package");
        if(!byPath){var parsed=NamePath.parse(wanted);if(parsed.identity()||parsed.parameters()!=null||wanted.contains("/"))throw RpcException.invalid("Invalid package");}
        var symbols=new ArrayList<Map<String,Object>>();var warnings=new LinkedHashSet<String>();int tier=1;
        for(Path file:sourceFiles(session)){
            if(path!=null&&!file.startsWith(path))continue;int page=0;
            do{
                var outline=analyzer(session,file).overview(file,documents(session).text(file),depth,1000,page);tier=Math.min(tier,outline.tier());warnings.addAll(outline.warnings());
                for(var symbol:(List<Map<String,Object>>)((Map<?,?>)outline.result()).get("symbols")){
                    String fqn=Objects.toString(symbol.get("fqn"),"");int last=fqn.lastIndexOf('.');String pkg=last<0?"":fqn.substring(0,last);
                    if(byPath||pkg.equals(wanted))symbols.add(symbol);
                }
                if(!outline.truncated())break;page=Integer.parseInt(outline.cursor());
            }while(true);
        }return page(tier,"live","symbols",symbols,offset,limit,List.copyOf(warnings));
    }
    @SuppressWarnings("unchecked")
    private void expandFind(Session session,Map<String,Object> parent,String scope,IndexService database,int depth,Set<String> kinds,
                            int needed,LinkedHashMap<String,Map<String,Object>> matches)throws Exception {
        if(kinds.isEmpty()||kinds.contains(parent.get("kind")))matches.putIfAbsent(parent.get("scip").toString(),parent);
        if(depth==0||matches.size()>=needed)return;
        String path=Objects.toString(parent.get("name_path"),"");if(path.isEmpty())return;
        int parentDepth=(int)path.chars().filter(c->c=='/').count();
        if(!scope.equals("deps"))for(var child:workspaceFind(session,path+"/",true)) {
            String candidate=Objects.toString(child.get("name_path"),"");
            if(candidate.startsWith(path+"/")&&candidate.chars().filter(c->c=='/').count()-parentDepth<=depth&&(kinds.isEmpty()||kinds.contains(child.get("kind"))))
                matches.putIfAbsent(child.get("scip").toString(),child);
            if(matches.size()>=needed)return;
        }
        if(!scope.equals("workspace")) {
            long after=0;
            while(matches.size()<needed) {
                var children=database.descendants(path,session.state("resolution")==null?null:session.id(),depth,128,after,kinds);
                if(children.isEmpty())break;
                for(var child:children) {
                    after=((Number)child.get("id")).longValue();matches.putIfAbsent(child.get("scip").toString(),child);
                    if(matches.size()>=needed)return;
                }
                if(children.size()<128)break;
            }
        }
    }
    private List<Map<String,Object>> workspaceFind(Session session,String ref,boolean substring)throws Exception{
        var found=new LinkedHashMap<String,Map<String,Object>>();
        if(session.state("workspace_bindings")!=null){var cached=workspaceBindings(session,false);if(cached!=null&&cached.diagnostics().stream().noneMatch(d->d.kind().equals("ERROR"))){
            var candidates=ref.contains(")/")?cached.symbols():cached.declarations();
            return candidates.values().stream().filter(symbol->Analyzer.matches(symbol,ref,substring)).toList();
        }}
        if(ref.contains(")/")){
            for(Path file:sourceFiles(session)){var snapshot=analyzer(session,file).bindings(file,documents(session).text(file),null);if(snapshot.result()!=null)for(var symbol:snapshot.result().symbols().values())if(Analyzer.matches(symbol,ref,substring))found.put(symbol.get("scip").toString(),symbol);}
            return List.copyOf(found.values());
        }
        for(Path file:sourceFiles(session)){
            var analyzer=analyzer(session,file);int offset=0;var declarations=new ArrayList<Map<String,Object>>();
            do{
                var outline=analyzer.overview(file,documents(session).text(file),10,1000,offset);
                for(var symbol:(List<Map<String,Object>>)((Map<?,?>)outline.result()).get("symbols")){declarations.add(symbol);if(symbol.get("scip")!=null&&Analyzer.matches(symbol,ref,substring))found.put(symbol.get("scip").toString(),symbol);}
                if(!outline.truncated())break;offset=Integer.parseInt(outline.cursor());
            }while(true);
            if(index!=null&&index.isDone()&&!index.isCompletedExceptionally())index.join().recordSource(file,Hashing.sha256(documents(session).text(file).getBytes(java.nio.charset.StandardCharsets.UTF_8)),declarations,1,List.of());
        }return List.copyOf(found.values());
    }
    private Envelope describe(Session session,String ref)throws Exception{
        return describe(session,ref,null);
    }
    private Envelope describe(Session session,String ref,WorkspaceBindings.Snapshot validated)throws Exception{
        if(validated!=null){var symbol=validated.symbols().get(ref);if(symbol!=null)return new Envelope(validated.tier(),"live",false,null,validated.warnings(),symbol);}
        var analyzer=(Analyzer)session.state("analyzer");
        if((ref.startsWith("maven ")||ref.startsWith("local "))&&analyzer!=null){
            var known=analyzer.known(ref);if(known.size()==1){var symbol=known.getFirst();var file=symbol.get("source_file");
                if(file!=null&&session.state("workspace_bindings")!=null){
                    var workspace=workspaceBindings(session,false);var cached=workspace==null?null:workspace.symbols().get(ref);
                    if(cached!=null&&Objects.equals(cached.get("source_file"),file)&&cached.get("name_start") instanceof Number)
                        return new Envelope(workspace.tier(),"live",false,null,workspace.warnings(),cached);
                }
                if(file!=null&&(Files.isRegularFile(Path.of(file.toString()))||documents(session).contains(Path.of(file.toString())))&&symbol.get("name_start") instanceof Number position){
                    Path path=Path.of(file.toString());
                    String text=documents(session).text(path);var snapshot=analyzer(session,path).bindings(path,text,position.intValue());if(snapshot.result()!=null){var current=snapshot.result().symbols().get(ref);if(current!=null)return new Envelope(snapshot.tier(),"live",false,null,snapshot.warnings(),current);}
                }else return Envelope.of(2,"live",symbol);
            }
        }
        var local=workspaceFind(session,ref,false);
        if(local.size()==1)return Envelope.of(1,"live",local.getFirst());
        if(local.size()>1)return page(1,"live","candidates",local,0,20,List.of("ambiguous"));
        var database=index();prepareIndex(session,database);var found=database.find(ref,session.state("resolution")==null?null:session.id(),false,21,0);
        if(found.size()!=1)return page(2,"index","candidates",found,0,20,found.size()>1?List.of("ambiguous"):session.warnings());
        return Envelope.of(2,"index",found.getFirst());
    }
    private Envelope describeDocumented(Session session,com.fasterxml.jackson.databind.JsonNode params)throws Exception{
        int depth=Dispatcher.bounded(params,"doc_depth",0,10),limit=Dispatcher.limit(params,50,200),offset=cursor(params);
        String detail=params.path("detail").asText("summary");if(!Set.of("summary","full").contains(detail))throw RpcException.invalid("Unknown documentation detail");
        var base=describe(session,Dispatcher.required(params,"ref"));
        if(!(base.result() instanceof Map<?,?> raw)||raw.get("scip")==null)return base;
        var symbol=new LinkedHashMap<String,Object>();for(var entry:raw.entrySet())symbol.put(entry.getKey().toString(),entry.getValue());
        if(!symbol.containsKey("id")&&depth==0){
            if(detail.equals("summary"))symbol.put("doc",dev.jvmd.index.DocMarkdown.summary((String)symbol.get("doc")));symbol.put("closure",List.of());
            return new Envelope(base.tier(),base.source(),base.truncated(),base.cursor(),base.warnings(),symbol);
        }
        var database=index();String workspace=session.state("resolution")==null?null:session.id();
        if(!symbol.containsKey("id")){
            prepareIndex(session,database);var indexed=database.find(symbol.get("scip").toString(),workspace,false,2,0);
            if(indexed.size()==1){var current=new LinkedHashMap<>(indexed.getFirst());current.putAll(symbol);symbol=current;}
        }
        var docs=session.state("documentation",()->new dev.jvmd.index.Documentation(database,config.jdkHome()));
        var result=docs.describe(symbol,workspace,detail,depth,limit,offset);var warnings=new LinkedHashSet<>(base.warnings());warnings.addAll(result.warnings());
        return new Envelope(Math.min(base.tier(),result.tier()),base.source(),result.truncated(),result.cursor(),List.copyOf(warnings),result.result());
    }
    private static int number(Map<?,?> symbol,String key){
        if(!(symbol.get(key) instanceof Number value)||value.intValue()<0)throw RpcException.invalid("Symbol has no editable "+key);return value.intValue();
    }
    private Path editable(Session session,Map<?,?> symbol){
        Object location=symbol.get("source_file");if(location==null||location.toString().startsWith("jar:"))throw RpcException.invalid("Symbol has no editable workspace source");
        return sourcePath(session,location.toString());
    }
    private Envelope editSymbol(Session session,com.fasterxml.jackson.databind.JsonNode params,String operation)throws Exception{
        var description=describe(session,Dispatcher.required(params,"ref"));
        if(!(description.result() instanceof Map<?,?> symbol)||symbol.get("scip")==null)return description;
        Path file=editable(session,symbol);String text=documents(session).text(file);int start,end;String replacement;
        if(operation.equals("body")){
            if(!Set.of("method","ctor").contains(symbol.get("kind")))throw RpcException.invalid("replace_body requires a method or constructor");
            start=number(symbol,"body_start");end=number(symbol,"body_end");replacement=Dispatcher.required(params,"body").strip();
            if(!replacement.startsWith("{"))replacement="{\n"+replacement+"\n}";
        }else{
            String position=Dispatcher.required(params,"position");replacement=Dispatcher.required(params,"code");
            switch(position){
                case "before"->{start=number(symbol,"source_start");replacement+="\n";}
                case "after"->{start=number(symbol,"source_end");replacement="\n"+replacement;}
                case "into"->{
                    if(Set.of("class","interface","enum","record","annotation").contains(symbol.get("kind"))){start=number(symbol,"source_end")-1;if(start>=text.length()||text.charAt(start)!='}')throw RpcException.invalid("Type has no complete closing brace");}
                    else if(Set.of("method","ctor").contains(symbol.get("kind")))start=number(symbol,"body_end")-1;
                    else throw RpcException.invalid("insert into requires a type or member body");
                    replacement="\n"+replacement+"\n";
                }
                default->throw RpcException.invalid("position must be before, after or into");
            }end=start;
        }
        return finishEdit(session,TextEdits.prepare(List.of(new TextEdits.Edit(file,start,end,replacement)),Map.of(),documents(session).snapshots()),params.path("dry_run").asBoolean());
    }
    private Envelope editText(Session session,com.fasterxml.jackson.databind.JsonNode params)throws Exception{
        var values=params.path("text_edits");if(!values.isArray()||values.isEmpty())throw RpcException.invalid("text_edits must be a nonempty array");
        var edits=new ArrayList<TextEdits.Edit>();var texts=new HashMap<Path,String>();
        for(var value:values){
            Path file=sourcePath(session,Dispatcher.required(value,"path"));String text=texts.computeIfAbsent(file,path->{try{return documents(session).text(path);}catch(Exception e){throw new IllegalArgumentException(e);}});
            int start,end;
            if(value.has("range")){var source=new dev.jvmd.analyzer.SourceText(text);var range=value.path("range");start=source.offset(Dispatcher.bounded(range.path("start"),"line",0,Integer.MAX_VALUE),Dispatcher.bounded(range.path("start"),"character",0,Integer.MAX_VALUE));end=source.offset(Dispatcher.bounded(range.path("end"),"line",0,Integer.MAX_VALUE),Dispatcher.bounded(range.path("end"),"character",0,Integer.MAX_VALUE));}
            else{if(!value.has("start")||!value.has("end"))throw RpcException.invalid("Each edit needs a range or start/end offsets");start=Dispatcher.bounded(value,"start",0,Integer.MAX_VALUE);end=Dispatcher.bounded(value,"end",0,Integer.MAX_VALUE);}
            if(!value.path("new_text").isTextual())throw RpcException.invalid("Each edit needs new_text");
            if(value.has("sha256")&&!value.path("sha256").asText().equals(Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))))throw RpcException.invalid("Source hash changed: "+file);
            edits.add(new TextEdits.Edit(file,start,end,value.path("new_text").asText()));
        }
        return finishEdit(session,TextEdits.prepare(edits,Map.of(),texts),params.path("dry_run").asBoolean());
    }
    private Envelope renameSymbol(Session session,com.fasterxml.jackson.databind.JsonNode params)throws Exception{
        String newName=Dispatcher.required(params,"new_name");
        if(!javax.lang.model.SourceVersion.isIdentifier(newName)||javax.lang.model.SourceVersion.isKeyword(newName)||Set.of("var","yield","record","sealed","permits").contains(newName))throw RpcException.invalid("new_name must be a Java identifier");
        var description=describe(session,Dispatcher.required(params,"ref"));
        if(!(description.result() instanceof Map<?,?> target)||target.get("scip")==null)return description;
        editable(session,target);
        if(newName.equals(target.get("name")))return Envelope.of(2,"live",Map.of("applied",false,"changes",List.of(),"diagnostics",List.of(),"verified",false));
        var snapshot=workspaceBindings(session,true);
        if(snapshot.tier()<2||!snapshot.warnings().isEmpty()||snapshot.diagnostics().stream().anyMatch(d->d.kind().equals("ERROR")))throw new RpcException(-32003,"unsupported_capability",Map.of("capability","rename","reason","Resolve compiler errors before renaming","diagnostics",snapshot.diagnostics()));
        var symbols=snapshot.symbols();var occurrences=snapshot.occurrences();var edges=snapshot.edges();
        String key=target.get("scip").toString();var family=new LinkedHashSet<String>();family.add(key);boolean type=Set.of("class","interface","enum","annotation","record").contains(target.get("kind"));
        if(target.get("kind").equals("ctor"))throw RpcException.invalid("Rename the declaring type to rename its constructors");
        if(type)for(var symbol:symbols.values())if("ctor".equals(symbol.get("kind"))&&Objects.equals(symbol.get("fqn"),target.get("fqn")))family.add(symbol.get("scip").toString());
        if(target.get("kind").equals("method")){
            boolean changed;do{changed=false;for(var edge:edges)if(edge.kind().equals("overrides")&&(family.contains(edge.src())||family.contains(edge.dst()))){changed|=family.add(edge.src());changed|=family.add(edge.dst());}}while(changed);
            for(String member:family){var symbol=symbols.get(member);if(symbol==null)throw RpcException.invalid("Override declaration is unavailable: "+member);editable(session,symbol);}
        }
        var edits=new LinkedHashMap<String,TextEdits.Edit>();var renames=new LinkedHashMap<Path,Path>();
        var imported=new HashMap<String,Set<String>>();for(var occurrence:occurrences)if(occurrence.importSite()!=null)imported.computeIfAbsent(occurrence.file()+":"+occurrence.start(),_->new HashSet<>()).add(occurrence.scip());
        for(var occurrence:occurrences)if(family.contains(occurrence.scip())){
            Path file=sourcePath(session,occurrence.file());var site=occurrence.importSite();
            boolean remaining=site!=null&&!family.containsAll(imported.get(occurrence.file()+":"+occurrence.start()));
            if(remaining){
                String text=documents(session).text(file),newline=text.contains("\r\n")?"\r\n":"\n";
                edits.putIfAbsent(file+":import:"+site.start(),new TextEdits.Edit(file,site.end(),site.end(),newline+"import static "+site.qualifier()+"."+newName+";"+newline));
            }else edits.putIfAbsent(file+":"+occurrence.start(),new TextEdits.Edit(file,occurrence.start(),occurrence.end(),newName));
        }
        if(edits.isEmpty())throw RpcException.invalid("No resolved source occurrences for the rename");
        if(type){Path file=editable(session,target);String old=target.get("name").toString();if(!target.get("name_path").toString().contains("/")&&file.getFileName().toString().equals(old+".java")&&!newName.equals(old))renames.put(file,file.resolveSibling(newName+".java"));}
        return finishEdit(session,TextEdits.prepare(List.copyOf(edits.values()),renames,documents(session).snapshots()),params.path("dry_run").asBoolean());
    }
    @SuppressWarnings("unchecked")
    private Envelope finishEdit(Session session,TextEdits.Plan plan,boolean dryRun)throws Exception{
        if(dryRun)return Envelope.of(2,"live",Map.of("applied",false,"changes",plan.edits(),"diagnostics",List.of(),"verified",false));
        for(var change:plan.edits())if(documents(session).contains(Path.of(change.get("path").toString())))throw new RpcException(-32003,"unsupported_capability",Map.of("capability","edit","reason","This file is open in an editor; apply its dry-run edit plan through the editor"));
        TextEdits.apply(plan);
        var existing=(Analyzer)session.state("analyzer");if(existing!=null)for(var change:plan.edits()){existing.changed(Path.of(change.get("path").toString()));if(change.get("new_path")!=null)existing.changed(Path.of(change.get("new_path").toString()));}
        session.put("last_verification",Map.of("stale",true));
        var diagnostics=new ArrayList<Object>();var members=new ArrayList<Map<String,Object>>();var warnings=new LinkedHashSet<String>();int tier=2;
        for(Path file:plan.files()){
            String text=Files.readString(file);var analyzer=analyzer(session,file);var declarations=new ArrayList<Map<String,Object>>();int offset=0;
            do{var outline=analyzer.overview(file,text,10,1000,offset);declarations.addAll((List<Map<String,Object>>)((Map<?,?>)outline.result()).get("symbols"));if(!outline.truncated())break;offset=Integer.parseInt(outline.cursor());}while(true);
            var selected=new LinkedHashSet<Map<String,Object>>();
            for(int[] touched:plan.touched(file)){
                while(touched[0]<touched[1]&&Character.isWhitespace(text.charAt(touched[0])))touched[0]++;
                while(touched[1]>touched[0]&&Character.isWhitespace(text.charAt(touched[1]-1)))touched[1]--;
                var enclosing=declarations.stream().filter(s->s.get("source_start") instanceof Number start&&s.get("source_end") instanceof Number end&&start.intValue()<=touched[0]&&end.intValue()>=touched[1]).min(Comparator.comparingInt(s->((Number)s.get("source_end")).intValue()-((Number)s.get("source_start")).intValue()));
                if(enclosing.isPresent())selected.add(enclosing.get());
            }
            var result=analyzer.diagnostics(file,text);tier=Math.min(tier,result.tier());warnings.addAll(result.warnings());
            for(var problem:(List<dev.jvmd.analyzer.CompilerPool.Problem>)((Map<?,?>)result.result()).get("diagnostics"))
                if(selected.isEmpty()||problem.start()<0||selected.stream().anyMatch(s->problem.start()>=((Number)s.get("source_start")).longValue()&&problem.start()<=((Number)s.get("source_end")).longValue()))diagnostics.add(problem);
            for(var member:selected){var row=new LinkedHashMap<String,Object>();row.put("path",file.toString());row.put("scip",member.get("scip"));row.put("range",member.get("range"));members.add(row);}
        }
        return new Envelope(tier,"live",false,null,List.copyOf(warnings),Map.of("applied",true,"changes",plan.edits(),"changed_files",plan.files().stream().map(Path::toString).toList(),"members",members,"diagnostics",diagnostics,"verified",false));
    }
    private Envelope occurrences(Session session,com.fasterxml.jackson.databind.JsonNode params)throws Exception{
        String ref=Dispatcher.required(params,"ref");var snapshot=workspaceBindings(session,true);var description=describe(session,ref,snapshot);if(!(description.result() instanceof Map<?,?> symbol)||symbol.get("scip")==null)return description;
        var found=snapshot.occurrences().stream().filter(o->o.scip().equals(symbol.get("scip"))&&(params.path("include_declaration").asBoolean()||!o.role().equals("declaration"))).toList();
        return page(snapshot.tier(),"live","occurrences",found,cursor(params),Dispatcher.limit(params,1000,10000),snapshot.warnings());
    }
    private Envelope relationships(Session session,com.fasterxml.jackson.databind.JsonNode params,boolean hierarchy)throws Exception{
        String ref=Dispatcher.required(params,"ref");var snapshot=workspaceBindings(session,true);var description=describe(session,ref,snapshot);
        if(!(description.result() instanceof Map<?,?> symbol)||symbol.get("scip")==null)return description;
        String key=symbol.get("scip").toString();String direction=params.path("direction").asText(hierarchy?"up":"in");
        if(!(hierarchy?Set.of("up","down"):Set.of("in","out")).contains(direction))throw RpcException.invalid("Unknown relationship direction");
        boolean outgoing=direction.equals("out")||direction.equals("up");
        int depth=Dispatcher.bounded(params,"depth",hierarchy?3:1,20),limit=Dispatcher.limit(params,100,1000),offset=cursor(params),tier=2;
        var allowed=new HashSet<String>();params.path("kinds").forEach(k->allowed.add(k.asText()));if(hierarchy)allowed.addAll(Set.of("extends","implements","overrides"));else if(allowed.isEmpty())allowed.addAll(Set.of("calls","reads","writes","instantiates"));
        tier=Math.min(tier,snapshot.tier());
        var symbols=new LinkedHashMap<String,Map<String,Object>>();var warnings=new LinkedHashSet<>(snapshot.warnings());
        var root=new LinkedHashMap<String,Object>();for(var entry:symbol.entrySet())root.put(entry.getKey().toString(),entry.getValue());symbols.put(key,snapshot.symbols().getOrDefault(key,root));
        var database=index();bindIndex(session,database);
        if(!(session.state("indexed_workspace_bindings") instanceof java.lang.ref.WeakReference<?> prior)||prior.get()!=snapshot){prepareIndex(session,database);session.put("indexed_workspace_bindings",new java.lang.ref.WeakReference<>(snapshot));}
        var code=session.state("code_pass",()->new dev.jvmd.index.CodePass(database));
        var reached=new LinkedHashSet<String>();reached.add(key);var selected=new LinkedHashSet<Bindings.Edge>();var frontier=new LinkedHashSet<String>();frontier.add(key);
        for(int d=0;d<depth;d++){
            var edges=new LinkedHashSet<>(snapshot.adjacent(frontier,outgoing));
            var inputs=frontier.stream().map(symbols::get).filter(Objects::nonNull).toList();String filter=session.state("resolution")==null?null:session.id();
            var expansion=hierarchy?code.hierarchy(inputs,outgoing,filter):code.expand(inputs,outgoing,allowed,filter);
            expansion.symbols().forEach(node->{String identity=node.get("scip").toString();symbols.putIfAbsent(identity,snapshot.symbols().getOrDefault(identity,node));});
            for(var edge:expansion.edges())edges.add(new Bindings.Edge(edge.src(),edge.dst(),edge.kind()));warnings.addAll(expansion.warnings());
            var next=new LinkedHashSet<String>();
            for(var edge:edges)if(allowed.contains(edge.kind())&&frontier.contains(outgoing?edge.src():edge.dst())){
                selected.add(edge);String target=outgoing?edge.dst():edge.src();var node=snapshot.symbols().get(target);if(node!=null)symbols.putIfAbsent(target,node);
                if(!reached.contains(target))next.add(target);
            }
            if(next.isEmpty())break;reached.addAll(next);frontier=next;
        }
        var edgeList=List.copyOf(selected);var matches=snapshot.references(selected);
        var nodes=reached.stream().map(symbols::get).filter(Objects::nonNull).toList();int max=Math.max(edgeList.size(),Math.max(matches.size(),nodes.size())),to=Math.min(max,offset+limit);boolean more=to<max;
        return new Envelope(tier,"live",more,more?Integer.toString(to):null,List.copyOf(warnings),Map.of("symbols",slice(nodes,offset,limit),"edges",slice(edgeList,offset,limit),"references",slice(matches,offset,limit)));
    }
    private static <T> List<T> slice(List<T> list,int offset,int limit){return List.copyOf(list.subList(Math.min(offset,list.size()),Math.min(list.size(),offset+limit)));}
    private Analyzer analyzer(Session session,Path path)throws Exception{
        var graph=RequestScope.memo(List.of(session,"analysis-resolution"),()->session.state("resolution")==null?null:refresh(session));
        var contexts=session.state("analysis_contexts",WorkspaceContextManager::new);
        var context=contexts.context(path,graph,file->createAnalyzerContext(session,file,graph));
        var analyzer=session.state("analyzer",()->new Analyzer(classpathFiles));
        var availableIndex=index!=null&&index.isDone()&&!index.isCompletedExceptionally()?index.join():null;
        analyzer.configure(context,availableIndex,config.heapCeilingMb()*1024L*1024/Math.max(1,sessions.list().size()));
        analyzer.documents(documents(session));
        analyzer.persistence(config.stateDir().resolve("diagnostics-v2").resolve(Hashing.sha256(session.root().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return analyzer;
    }
    private Analyzer.Context createAnalyzerContext(Session session,Path path,Resolution graph)throws Exception{
        String gav="local:workspace:0",release="25",generation="plain";
        List<String> options=List.of("--release","25");
        var classpath=new java.util.ArrayList<Path>();var sources=new java.util.ArrayList<Path>();var coordinates=new java.util.LinkedHashMap<String,String>();var binarySources=new LinkedHashSet<Path>();var processorWarnings=new LinkedHashSet<String>();var navigationSources=new LinkedHashSet<Path>();
        if(graph!=null){
            var module=WorkspaceContextManager.owner(path,graph);
            gav=module.gav();release=module.release()==null||module.release().isBlank()?"25":module.release();generation=graph.fingerprint()+":"+gav;
            boolean test=module.testSources().stream().anyMatch(root->path.startsWith(Path.of(root)));
            options=test?module.testCompilerOptions():module.compilerOptions();generation+=test?":test":":main";
            graph.classpaths().getOrDefault(gav+(test?":test":":main"),java.util.List.of()).forEach(p->classpath.add(Path.of(p)));
            module.sources().forEach(p->sources.add(Path.of(p)));if(test)module.testSources().forEach(p->sources.add(Path.of(p)));
            for(var dependency:overlay(session,graph).dependencies(graph,gav,test)){
                boolean sourceOnly=overlay(session,graph).requiresSource(dependency)||documents(session).dirty(Path.of(dependency.directory()));generation+=":"+dependency.gav()+":"+sourceOnly;
                if(sourceOnly)dependency.sources().forEach(p->sources.add(Path.of(p)));classpath.add(Path.of(dependency.classes()));
                // A built dependency uses its API; source changes (including preserved mtimes) switch to SOURCE_PATH.
                coordinates.put(dependency.classes(),dependency.gav());
                var generated=prepareProcessing(session,dependency,false,graph);
                if(generated!=null){classpath.addAll(0,generated.classpath());sources.addAll(generated.sourceRoots());binarySources.addAll(generated.binarySources());processorWarnings.addAll(generated.warnings());generation+=":"+generated.fingerprint();}
            }
            var processing=prepareProcessing(session,module,false,graph);
            if(processing!=null){classpath.addAll(0,processing.classpath());sources.addAll(0,processing.sourceRoots());binarySources.addAll(processing.binarySources());processorWarnings.addAll(processing.warnings());generation+=":"+processing.fingerprint();coordinates.put(processing.sourceRoots().getFirst().toString(),gav);}
            if(test){var testOutput=prepareProcessing(session,module,true,graph);if(testOutput!=null){classpath.addAll(0,testOutput.classpath());sources.addAll(0,testOutput.sourceRoots());binarySources.addAll(testOutput.binarySources());processorWarnings.addAll(testOutput.warnings());generation+=":"+testOutput.fingerprint();coordinates.put(testOutput.sourceRoots().getFirst().toString(),gav);}}
            for(var m:graph.modules()){
                for(String source:java.util.stream.Stream.concat(m.sources().stream(),m.testSources().stream()).toList()){
                    navigationSources.add(Path.of(source));coordinates.putIfAbsent(source,m.gav());coordinates.putIfAbsent(Path.of(source).toUri().toString(),m.gav());
                }
                coordinates.put(m.directory(),m.gav());coordinates.put(Path.of(m.directory()).toUri().toString(),m.gav());
            }
            // Shared build-helper roots take the identity of the module whose compiler context owns this query.
            for(String source:java.util.stream.Stream.concat(module.sources().stream(),module.testSources().stream()).toList()){
                coordinates.put(source,gav);coordinates.put(Path.of(source).toUri().toString(),gav);
            }
            for(var node:graph.nodes())if(node.path()!=null&&node.winner()==null)coordinates.put(node.path(),node.gav());
        }else{sources.addAll(workspace(session).roots());for(Path root:workspace(session).roots()){coordinates.put(root.toString(),gav);coordinates.put(root.toUri().toString(),gav);}}
        if(dirty(session)&&graph!=null&&graph.modules().stream().anyMatch(m->m.processing().enabled()||m.testProcessing().enabled()))processorWarnings.add("unsaved_processor_inputs: generated APIs reflect the last saved processor inputs");
        navigationSources.addAll(sources);
        return new Analyzer.Context(gav,release,List.copyOf(classpath),List.copyOf(sources),generation,Map.copyOf(coordinates),options,Set.copyOf(binarySources),List.copyOf(processorWarnings),List.copyOf(navigationSources),graph!=null);
    }

    private AnnotationProcessing.Output prepareProcessing(Session session,Resolution.Module module,boolean test,Resolution graph)throws Exception{
        var settings=test?module.testProcessing():module.processing();
        if(settings.lombok())session.warn("lombok_reduced_fidelity: generated member bodies and positions are unavailable");
        if(!settings.enabled())return null;
        var processor=session.state("processors",()->new AnnotationProcessing(config));
        // Only original source roots are processor inputs; prior generated files are never fed back into Filer.
        var roots=(test?module.testSources():module.sources()).stream().filter(p->!p.equals(settings.generatedDirectory())&&!p.contains("/generated-sources")&&!p.contains("/generated-test-sources")).map(Path::of).toList();
        var classpath=new ArrayList<Path>(graph.classpaths().getOrDefault(module.gav()+(test?":test":":main"),List.of()).stream().map(Path::of).toList());
        if(test&&session.state("apt:"+module.gav()+":main") instanceof AnnotationProcessing.Output main)classpath.addAll(0,main.classpath());
        var result=processor.prepare(new AnnotationProcessing.Request(module.gav()+(test?":test":":main"),Path.of(module.directory()),roots,classpath,settings.path().stream().map(Path::of).toList(),settings.names(),test?module.testCompilerOptions():module.compilerOptions(),settings.lombok()),java.time.Duration.ofSeconds(60));
        session.put("apt:"+module.gav()+(test?":test":":main"),result);result.warnings().forEach(session::warn);return result;
    }
    private static dev.jvmd.runtime.RunManager runs(Session session){return session.state("runs",()->new dev.jvmd.runtime.RunManager(session.id()));}
    private Envelope run(Session session,com.fasterxml.jackson.databind.JsonNode params)throws Exception{
        if(dirty(session))throw new RpcException(-32003,"unsupported_capability",Map.of("capability","run","reason","Save editor changes before compiling a run"));
        String target=Dispatcher.required(params,"target");var graph=(Resolution)session.state("resolution");if(graph!=null)graph=refresh(session);
        var matches=workspaceFind(session,target,false).stream().filter(s->Set.of("class","record","enum","method").contains(s.get("kind"))).toList();
        if(matches.size()!=1)return page(1,"live","candidates",matches,0,20,matches.size()>1?List.of("ambiguous"):List.of("No workspace main class matched the target"));
        var symbol=matches.getFirst();String main=Objects.toString(symbol.get("fqn"),symbol.get("name_path").toString().replace('/','$'));
        Path source=Path.of(symbol.get("source_file").toString());var sourceRoots=new LinkedHashSet<Path>();var classpath=new LinkedHashSet<Path>();var coordinates=new LinkedHashMap<Path,String>();var targets=new ArrayList<dev.jvmd.runtime.RunManager.Target>();List<String> options;
        Path output;
        if(graph==null){
            output=config.stateDir().resolve("run-classes").resolve(session.id());sourceRoots.addAll(workspace(session).roots());options=List.of("--release","25");classpath.add(output);coordinates.put(session.root(),"local:workspace:0");
            var compiled=dev.jvmd.runtime.RuntimeCompiler.compile(config.jdkHome(),session.root(),List.of(source),List.copyOf(classpath),List.copyOf(sourceRoots),options,java.time.Duration.ofSeconds(60));dev.jvmd.runtime.RuntimeCompiler.publish(compiled,output);
            targets.add(new dev.jvmd.runtime.RunManager.Target(session.root(),List.copyOf(sourceRoots),List.copyOf(classpath),options,output));
        }else{
            var module=graph.modules().stream().filter(m->source.startsWith(Path.of(m.directory()))).max(Comparator.comparingInt(m->m.directory().length())).orElseThrow();output=Path.of(module.classes());options=runtimeCompilerOptions(module);
            compileRuntimeModule(session,graph,module,new HashSet<>(),new HashSet<>());
            classpath.add(output);graph.classpaths().getOrDefault(module.gav()+":runtime",graph.classpaths().getOrDefault(module.gav()+":main",List.of())).forEach(path->classpath.add(Path.of(path)));
            for(var local:graph.modules()){
                var roots=local.sources().stream().map(Path::of).toList();sourceRoots.addAll(roots);coordinates.put(Path.of(local.directory()),local.gav());
                var compilePath=new LinkedHashSet<Path>();compilePath.add(Path.of(local.classes()));graph.classpaths().getOrDefault(local.gav()+":main",List.of()).forEach(path->compilePath.add(Path.of(path)));overlay(session,graph).dependencies(graph,local.gav(),false).forEach(m->compilePath.add(Path.of(m.classes())));
                targets.add(new dev.jvmd.runtime.RunManager.Target(Path.of(local.directory()),roots,List.copyOf(compilePath),runtimeCompilerOptions(local),Path.of(local.classes())));
            }
            for(var node:graph.nodes())if(node.path()!=null&&node.winner()==null)coordinates.put(Path.of(node.path()),node.gav());
        }
        var arguments=new ArrayList<String>();for(var argument:params.path("args")){if(!argument.isTextual())throw RpcException.invalid("Run arguments must be strings");arguments.add(argument.asText());}if(arguments.size()>1000)throw RpcException.invalid("Too many application arguments");
        var runtime=debuggeeRuntime();runtime.warnings().forEach(session::warn);
        var launch=new dev.jvmd.runtime.DebugSession.Launch(runtime.home(),session.root(),List.copyOf(classpath),main,List.copyOf(arguments),params.path("debug").asBoolean(),runtime.options());
        var request=new dev.jvmd.runtime.RunManager.Request(launch,new dev.jvmd.runtime.SourceLookup(List.copyOf(sourceRoots),coordinates),config.jdkHome(),List.copyOf(sourceRoots),options,output,List.copyOf(targets));
        return runs(session).start(request);
    }
    private static List<String> runtimeCompilerOptions(Resolution.Module module){
        var options=new ArrayList<>(module.compilerOptions());var processor=module.processing();
        if(processor.enabled()){
            options.add("-proc:full");if(!processor.path().isEmpty())options.addAll(List.of("-processorpath",String.join(java.io.File.pathSeparator,processor.path())));if(!processor.names().isEmpty())options.addAll(List.of("-processor",String.join(",",processor.names())));
        }return List.copyOf(options);
    }
    private void compileRuntimeModule(Session session,Resolution graph,Resolution.Module module,Set<String> finished,Set<String> visiting)throws Exception{
        if(finished.contains(module.gav()))return;if(!visiting.add(module.gav()))throw RpcException.invalid("Runtime module dependency cycle: "+module.gav());
        var dependencies=overlay(session,graph).dependencies(graph,module.gav(),false);for(var dependency:dependencies)compileRuntimeModule(session,graph,dependency,finished,visiting);
        if(!module.packaging().equals("pom")&&overlay(session,graph).requiresSource(module)){
            var roots=module.sources().stream().filter(path->!module.processing().enabled()||!path.contains("/generated-sources")).map(Path::of).toList();var files=new ArrayList<Path>();
            for(Path root:roots)if(Files.isDirectory(root))files.addAll(FileInventory.matching(root,".java"));
            if(!files.isEmpty()){
                var classpath=new LinkedHashSet<Path>();classpath.add(Path.of(module.classes()));graph.classpaths().getOrDefault(module.gav()+":main",List.of()).forEach(path->classpath.add(Path.of(path)));dependencies.forEach(m->classpath.add(Path.of(m.classes())));
                var compiled=dev.jvmd.runtime.RuntimeCompiler.compile(config.jdkHome(),Path.of(module.directory()),files,List.copyOf(classpath),roots,runtimeCompilerOptions(module),java.time.Duration.ofSeconds(60));dev.jvmd.runtime.RuntimeCompiler.publish(compiled,Path.of(module.classes()));
            }
        }visiting.remove(module.gav());finished.add(module.gav());
    }
    private synchronized dev.jvmd.runtime.JavaRuntime.Selection debuggeeRuntime()throws Exception{
        if(debuggeeRuntime==null)debuggeeRuntime=dev.jvmd.runtime.JavaRuntime.select(config.jdkHome(),config.jbrHome(),config.hotswapAgent());return debuggeeRuntime;
    }
    private synchronized MavenResolver resolver() {
        if (resolver == null) resolver = new MavenResolver(config);
        return resolver;
    }
    private Resolution refresh(Session session) throws Exception {
        Resolution graph = resolver().resolveWorkspace(session.root(),workspace(session).roots(),workspace(session).ignoreVersions());
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
        if(graph.modules().stream().anyMatch(m->m.processing().lombok()||m.testProcessing().lombok()))session.warn("lombok_reduced_fidelity: generated member bodies and positions are unavailable");
        if(index!=null && index.isDone() && !index.isCompletedExceptionally()) bindIndex(session,index.join());
        return graph;
    }
    private synchronized void initializeIndex(boolean scan) {
        if(index!=null)return;
        index=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            ArtifactGenerationSink generations=null;
            try {
                long defaultBudgetMb=Math.max(8L,Math.min(128L,config.heapCeilingMb()/8L));
                long budgetMb=Long.getLong("jvmd.index.generation_budget_mb",defaultBudgetMb);
                if(budgetMb<1)throw new IllegalArgumentException("jvmd.index.generation_budget_mb must be positive");
                generations=ArtifactGenerationSink.open(config.stateDir().resolve("index-v2"),Math.multiplyExact(budgetMb,1024L*1024L));
                var service=new IndexService(config.stateDir().resolve("index.db"),config.m2Repo(),generations);
                if(scan)service.start();
                return service;
            } catch(Exception e){
                if(generations!=null)try{generations.close();}catch(Exception close){e.addSuppressed(close);}
                throw new java.util.concurrent.CompletionException(e);
            }
        }, task -> Thread.ofVirtual().name("jvmd-index-start").start(task));
    }
    private IndexService index() { initializeIndex(false); return index.join(); }
    private void bindIndex(Session session,IndexService database)throws Exception {
        var graph=(Resolution)session.state("resolution");if(graph==null)return;
        for(var module:graph.modules()){
            var roots=new ArrayList<Path>();module.sources().forEach(p->roots.add(Path.of(p)));module.testSources().forEach(p->roots.add(Path.of(p)));
            database.registerLocal(new IndexService.LocalModule(Path.of(module.directory()),module.gav(),roots,List.of(Path.of(module.classes()),Path.of(module.testClasses()))));
        }
        String generation=graph.fingerprint()+":"+database.generation();
        if(generation.equals(session.state("index_generation")))return;

        // A resolved workspace can introduce artifacts after the background repository scan.
        // Publish those signatures before exposing the workspace; unchanged releases reuse
        // their existing generations and SNAPSHOTs retain the normal content check.
        for(var node:graph.nodes())if(node.path()!=null&&node.winner()==null&&node.extension().equals("jar")){
            Path path=Path.of(node.path());if(Files.isRegularFile(path))database.indexJar(path,node.gav(),"jar");
        }
        generation=graph.fingerprint()+":"+database.generation();

        var openDocuments=documents(session).snapshots();
        String jdkFingerprint=Runtime.version()+"|"+config.jdkHome().toAbsolutePath().normalize();
        for(var module:graph.modules()){
            configureModuleIndexState(database,module,false,graph,openDocuments,jdkFingerprint);
            configureModuleIndexState(database,module,true,graph,openDocuments,jdkFingerprint);
        }

        var paths=new ArrayList<>(graph.nodes().stream().filter(n->n.path()!=null&&n.winner()==null).map(n->new IndexService.WorkspaceArtifact(n.path(),n.scope())).toList());
        for(var module:graph.modules())paths.add(new IndexService.WorkspaceArtifact(module.directory(),"local"));
        var byId=graph.nodes().stream().collect(java.util.stream.Collectors.toMap(Resolution.Node::id,Resolution.Node::gav));
        var edges=graph.edges().stream().map(e->java.util.Map.entry(byId.get(e.src()),byId.get(e.dst()))).toList();
        database.loadWorkspace(session.id(),paths,edges).forEach(session::warn);session.put("index_generation",generation);
    }
    private void prepareIndex(Session session,IndexService database)throws Exception{
        bindIndex(session,database);var graph=(Resolution)session.state("resolution");
        if(graph!=null)for(var module:graph.modules())database.refreshLocal(Path.of(module.directory()));
        bindIndex(session,database);
    }
    private static Envelope dependencyGraph(Resolution graph, com.fasterxml.jackson.databind.JsonNode params) {
        int depth = Dispatcher.bounded(params, "depth", 2, 20), limit = Dispatcher.limit(params, 50, 200), offset=cursor(params);
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
        boolean truncated = Math.max(nodes.size(),edges.size()) > offset+limit;
        return new Envelope(2, "index", truncated, truncated ? Integer.toString(offset+limit) : null, graph.warnings(),
                java.util.Map.of("nodes", slice(nodes,offset,limit), "edges", slice(edges,offset,limit),
                        "fingerprint", graph.fingerprint(), "cached", graph.cached()));
    }

    private void configureModuleIndexState(IndexService database,Resolution.Module module,boolean test,Resolution graph,
                                           Map<Path,String> openDocuments,String jdkFingerprint)throws Exception{
        var roots=(test?module.testSources():module.sources()).stream().map(Path::of).map(path->path.toAbsolutePath().normalize()).toList();
        if(roots.isEmpty())return;
        var overlays=new LinkedHashMap<Path,String>();
        openDocuments.forEach((path,text)->{if(roots.stream().anyMatch(path::startsWith))overlays.put(path,text);});
        var processing=test?module.testProcessing():module.processing();
        var processors=new ArrayList<String>();processors.addAll(processing.path());processors.addAll(processing.names());
        if(processing.lombok())processors.add("lombok");
        var generated=new LinkedHashMap<String,String>();
        if(processing.generatedDirectory()!=null&&!processing.generatedDirectory().isBlank()){
            Path directory=Path.of(processing.generatedDirectory()).toAbsolutePath().normalize();
            generated.put(directory.toString(),fingerprintDirectory(directory));
        }
        String key=module.gav()+(test?":test":":main");
        var classpath=graph.classpaths().getOrDefault(key,List.of());
        var options=test?module.testCompilerOptions():module.compilerOptions();
        database.configureModuleState(new ArtifactGenerationSink.ModuleStateInput(module.directory()+"|"+key,roots,Map.copyOf(overlays),options,
                List.copyOf(processors),Map.copyOf(generated),classpath,jdkFingerprint+"|release="+module.release()));
    }

    private static String fingerprintDirectory(Path directory)throws Exception{
        if(!Files.isDirectory(directory))return "missing";
        return IndexService.directoryHash(directory);
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
                // Train platform services used only by the native Maven 4 bundle as well.
                // Custom bundle classes retain their isolated loader at runtime.
                Files.writeString(wrapper.resolve("maven-wrapper.properties"),"distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/4.0.0-rc-6/apache-maven-4.0.0-rc-6-bin.zip");
                var nativeConfig=new Config(config.jdkHome(),null,config.m2Repo(),4,config.idleTimeout(),config.heapCeilingMb(),false,config.stateDir().resolve("maven4"),config.socket());
                try(var nativeResolver=new MavenResolver(nativeConfig)){nativeResolver.resolve(fixture);nativeResolver.resolve(fixture);}
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
