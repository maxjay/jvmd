package dev.jvmd.analyzer;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;

/** Implements 4.2: session-owned semantic state and detached declaration snapshots. */
public final class Analyzer implements DiagnosticEngine, AutoCloseable {
    /** Implements 4.2 and 4.3: effective module classpath and source roots. */
    public record Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings,List<Path> navigationSources,boolean preciseSourceRoots) {
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings,List<Path> navigationSources){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,binarySources,warnings,navigationSources,true);}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,binarySources,warnings,sources,true);}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,Set.of(),List.of());}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates){this(gav,release,classpath,sources,generation,coordinates,List.of("--release",release));}
    }
    private final Map<String,CompilerPool> compilerPools=new LinkedHashMap<>();
    private CompilerPool compiler;
    private static final class ModuleCaches {
        final LinkedHashMap<String,Envelope> outlines=new LinkedHashMap<>(16,.75f,true);
        final LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
        final Set<Path> files=new HashSet<>();
        final ResidentSemanticState semantic=new ResidentSemanticState();
        final Map<Path,DocumentSemanticCached> documentSemantics=new HashMap<>();
        CompletionCached completion;
        long completionSourceEpoch=-1;
        boolean completionNeedsDiscoveryRefresh;
    }
    private final Map<String,ModuleCaches> modules=new LinkedHashMap<>();
    private long classpathFingerprints;
    private final FileStateRegistry inputFiles;
    public Analyzer(){this(FileStateRegistry.shared());}
    /** Share only validated content identities; compiler objects remain analyzer-owned. */
    public Analyzer(FileStateRegistry inputFiles){this.inputFiles=Objects.requireNonNull(inputFiles);this.documents=new Documents(inputFiles);}
    private Documents documents=new Documents();

    private final Focusing focusing=new Focusing();
    private final DiagnosticStore diagnosticStore=new DiagnosticStore();
    private LinkedHashMap<String,Envelope> outlines=new LinkedHashMap<>(16,.75f,true);
    private record Cached(Path file,String hash,String stamp,int start,int end,List<Focusing.Span> excluded,CompilerPool.Outcome<Bindings.Snapshot> result) { }
    private record CompletionCached(String key,String prefix,long sourceEpoch,Set<Path> dependencies,Map<Path,String> dependencyApis,
                                    CompilerPool.Outcome<List<Map<String,Object>>> result) {
        CompletionCached { dependencies=Set.copyOf(dependencies);dependencyApis=Map.copyOf(dependencyApis); }
    }
    private record CompletionValidation(CompletionCached cached,boolean apiCurrent) { }
    private record DocumentSemanticCached(String key,DocumentSemanticSnapshot snapshot,Map<String,String> resolutionIdentities) {
        DocumentSemanticCached { resolutionIdentities=Map.copyOf(resolutionIdentities); }
    }
    private record Outline(List<Map<String,Object>> symbols,Set<Path> dependencies) { }
    private LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
    private final Dependencies dependencies=new Dependencies();
    private final LinkedHashMap<String,SourceText> sourceTexts=new LinkedHashMap<>(16,.75f,true);
    private long cacheHits,bindingComputations,diagnosticFilesAnalysed,diagnosticFilesReused,indexWrites,indexWriteNanos,apiFingerprintChanges,apiFingerprintUnchanged;
    private long completionCacheHits,completionComputations,completionRequests;
    private long completionKeyNanos,completionSourceRefreshNanos,completionFocusNanos,completionQueryNanos,completionEditorNanos,
            completionCandidateNanos,completionRowNanos,completionDocNanos,completionSortNanos,completionCacheAdmissionNanos,
            completionFilterNanos,completionTotalNanos,completionCandidatesSeen,completionRowsMaterialized,completionDocLookups,
            completionGetAllMembersCalls,completionScopeTraversals,completionScipComputations,completionSignatureComputations,
            completionAsMemberOfCalls,completionSorts,completionSortInputs,completionRowsReturned,completionRowsDiscardedAfterLimit,completionResultBytes;
    private Map<String,Double> completionLastTimingMs=Map.of();private boolean completionLastCacheHit;
    private Context context;
    private String completionContextIdentity="";
    private IndexService index;
    private LiveSourceState liveSourceState;
    private DiagnosticSnapshots snapshots;
    public void persistence(Path directory){if(snapshots==null){snapshots=new DiagnosticSnapshots(directory);diagnosticStore.persistence(snapshots);snapshots.documents(documents);}}
    private long budget;
    public void configure(Context context,IndexService index,long budget)throws Exception{
        var caches=modules.computeIfAbsent(context.generation(),_->new ModuleCaches());
        outlines=caches.outlines;focused=caches.focused;
        this.context=context;this.index=index;this.budget=budget;diagnosticStore.budget(Math.max(1024*1024,budget/8));
        String newCompletionContextIdentity=CompilerInputs.compose("completion-context-v1",
                context.gav(),context.release(),context.generation(),
                context.classpath().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.sources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.compilerOptions(),
                context.binarySources().stream().map(p->p.toAbsolutePath().normalize().toString()).sorted().toList(),
                new TreeMap<>(context.coordinates()),
                context.navigationSources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.preciseSourceRoots());
        if(!newCompletionContextIdentity.equals(completionContextIdentity)){
            caches.completion=null;caches.completionSourceEpoch=-1;caches.completionNeedsDiscoveryRefresh=false;
        }
        completionContextIdentity=newCompletionContextIdentity;
        compiler=compilerPools.computeIfAbsent(context.generation(),_->new CompilerPool(inputFiles));
        compiler.configure(context.generation(),context.release(),context.classpath(),context.sources(),index,budget,context.compilerOptions(),context.preciseSourceRoots());
        compiler.binarySources(context.binarySources());
    }
    public void documents(Documents documents){this.documents=documents;if(snapshots!=null)snapshots.documents(documents);compiler.documents(documents);dependencies.documentHash(documents::hash);dependencies.fileStates(documents.fileStates());if(context!=null)liveSourceState=documents.liveState(context.sources());}
    private ResidentSemanticState semanticState(){return modules.get(context.generation()).semantic;}
    private void admitSemantic(SemanticSnapshot snapshot,FileSemanticContribution contribution){
        if(snapshot==null||contribution==null)return;
        var canonical=new SemanticSnapshot(snapshot.unit(),snapshot.sourceFile(),snapshot.contentIdentity(),snapshot.facts(),snapshot.descriptions(),
                contribution.apiFingerprint(),LiveStateTree.namespace(contribution.exportedNames()).value(),snapshot.documentationIdentity(),snapshot.dependencies());
        semanticState().admit(canonical);
    }
    private void admitDetachedSemantic(SemanticSnapshot snapshot){
        if(snapshot==null)return;
        if(snapshot.sourceFile()!=null&&liveSourceState!=null)try{
            Path source=Path.of(snapshot.sourceFile()).toAbsolutePath().normalize();
            if(liveSourceState.accepts(source)){
                var contribution=contribution(source);
                if(contribution!=null&&contribution.sourceHash().equals(snapshot.contentIdentity())){admitSemantic(snapshot,contribution);return;}
            }
        }catch(Exception ignored){}
        semanticState().admit(snapshot);
    }
    private List<String> warnings(List<String> query){if(context.warnings().isEmpty())return query;var all=new LinkedHashSet<String>(context.warnings());all.addAll(query);return List.copyOf(all);}
    private String coordinates(String file){return context.coordinates().entrySet().stream().filter(e->file.startsWith(e.getKey())).max(Comparator.comparingInt(e->e.getKey().length())).map(Map.Entry::getValue).orElse(null);}
    public CompilerInputs.Snapshot inputSnapshot()throws Exception {
        return compiler.inputSnapshot();
    }
    /** Bounded mutation observation for callers that already know the relevant source set. */
    public void observeSources(Collection<Path> paths){
        compiler.documents(documents);compiler.observeSources(paths);
    }
    public void settleSourceEvents()throws Exception{compiler.documents(documents);compiler.settleSourceEvents();}
    /** Observe this source and the source dependency graph already learned from prior attribution. */
    private void synchronizeKnownSources(Path requested)throws Exception{
        requested=requested.toAbsolutePath().normalize();
        // Analyzer-owned/session-owned documents are the sole live source owner for compiler reads.
        compiler.documents(documents);liveSourceState=documents.liveState(context.sources());
        var queue=new ArrayDeque<Path>();var seen=new LinkedHashSet<Path>();queue.add(requested);
        while(!queue.isEmpty()){
            Path file=queue.removeFirst().toAbsolutePath().normalize();
            if(!seen.add(file))continue;
            queue.addAll(dependencies.semantic().dependencies(file));
        }
        compiler.observeSources(seen);
    }
    private String classpathStamp()throws Exception{return computeClasspathStamp();}
    private CompilerInputs.Snapshot validatedInputs()throws Exception {
        var inputs=inputSnapshot();
        if(!compiler.cacheValid(inputs)){outlines.clear();focused.clear();inputs=inputSnapshot();}
        return inputs;
    }
    private String computeClasspathStamp()throws Exception {
        classpathFingerprints++;
        var inputs=validatedInputs();
        return inputs.environment().value()+":"+inputs.membership().value();
    }
    public Envelope overview(Path path,String text,int depth,int limit,int offset)throws Exception{
        synchronizeKnownSources(path);touch(path,text);
        String key=path+":"+Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":"+classpathStamp()+":"+depth+":"+limit+":"+offset;
        var cached=outlines.get(key);if(cached!=null)return cached;
        var result=compiler.query(path,text,1,(task,units,tier)->new Outline(declarations(task,units,path,text,depth),Bindings.capture(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),path,sourceText(path,text),false).dependencies()));
        if(result.result()!=null)dependencies.recordFocused(path,result.result().dependencies());
        var symbols=result.result()==null?List.<Map<String,Object>>of():result.result().symbols();
        int from=Math.min(offset,symbols.size()),to=Math.min(symbols.size(),from+limit);boolean truncated=to<symbols.size();
        var envelope=new Envelope(result.tier(),"live",truncated,truncated?Integer.toString(to):null,warnings(result.warnings()),Map.of("symbols",List.copyOf(symbols.subList(from,to)),"diagnostics",result.diagnostics()));
        if(result.warnings().isEmpty()){outlines.put(key,envelope);while(outlines.size()>16)outlines.remove(outlines.keySet().iterator().next());}
        return envelope;
    }
    private List<Map<String,Object>> declarations(JavacTask task,List<CompilationUnitTree> units,Path file,String text,int depth){
        var identity=new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources());var trees=Trees.instance(task);var docs=DocTrees.instance(task);var source=sourceText(file,text);
        var result=new ArrayList<Map<String,Object>>();
        for(var unit:units)new TreePathScanner<Void,Integer>(){
            private void add(Tree tree,Element element,int level){
                if(element==null||level>depth)return;
                int start=(int)trees.getSourcePositions().getStartPosition(unit,tree),end=(int)trees.getSourcePositions().getEndPosition(unit,tree);if(start<0||end<start)return;
                String name=identity.displayName(element);int nameStart=start,nameEnd=start;SourceText.Token token=null;
                if(tree instanceof MethodTree method){
                    int prefix=start;
                    if(method.getReturnType()!=null)prefix=Math.max(prefix,(int)trees.getSourcePositions().getEndPosition(unit,method.getReturnType()));
                    for(var type:method.getTypeParameters())prefix=Math.max(prefix,(int)trees.getSourcePositions().getEndPosition(unit,type));
                    for(var candidate:source.tokens(prefix,end))if(candidate.text().equals(name)){
                        int next=source.nextCode(candidate.end());if(next<text.length()&&(text.charAt(next)=='('||element.getKind()==ElementKind.CONSTRUCTOR&&method.getBody()!=null&&next==trees.getSourcePositions().getStartPosition(unit,method.getBody())&&text.charAt(next)=='{')){token=candidate;break;}
                    }
                }else if(tree instanceof VariableTree variable){
                    int bound=variable.getInitializer()==null?end:(int)trees.getSourcePositions().getStartPosition(unit,variable.getInitializer());token=source.named(name,start,bound,true);
                }else if(tree instanceof ClassTree type){
                    int prefix=(int)trees.getSourcePositions().getEndPosition(unit,type.getModifiers());token=source.named(name,Math.max(start,prefix),end,false);
                }
                if(token!=null){nameStart=token.start();nameEnd=token.end();}
                var row=new LinkedHashMap<String,Object>();
                row.put("name",name);row.put("kind",SymbolIdentity.kind(element));row.put("signature",identity.signature(element));try{row.put("name_path",identity.namePath(element));row.put("scip",identity.scip(element));row.put("resolved",true);}catch(IllegalArgumentException unresolved){row.put("name_path",name);row.put("scip",null);row.put("resolved",false);}row.put("gav",identity.gav(element));
                row.put("modifiers",element.getModifiers().stream().map(Object::toString).sorted().toList());row.put("file",file.toString());row.put("source_file",file.toString());row.put("line",source.position(nameStart).line()+1);row.put("character",source.position(nameStart).character());
                row.put("start",start);row.put("end",end);row.put("source_start",start);row.put("source_end",end);row.put("name_start",nameStart);row.put("name_end",nameEnd);row.put("range",source.range(start,end));row.put("name_range",source.range(nameStart,nameEnd));
                var comment=docs.getDocCommentTree(getCurrentPath());row.put("doc",comment==null?null:DocMarkdown.render(comment.toString()));
                var declaring=identity.declaring(element);row.put("fqn",declaring==null?null:identity.binaryName(declaring));row.put("declaring",declaring==null?null:declaring.getQualifiedName().toString());
                row.put("parameters",element instanceof ExecutableElement method?method.getParameters().stream().map(p->p.getSimpleName().toString()).toList():List.of());
                try{row.put("erased_descriptor",element instanceof ExecutableElement method?identity.descriptor(method):element instanceof VariableElement variable?identity.descriptor(variable.asType()):null);}catch(IllegalArgumentException unresolved){row.put("erased_descriptor",null);row.put("signature_complete",false);}
                row.put("body_start",tree instanceof MethodTree method&&method.getBody()!=null?(int)trees.getSourcePositions().getStartPosition(unit,method.getBody()):-1);row.put("body_end",tree instanceof MethodTree method&&method.getBody()!=null?(int)trees.getSourcePositions().getEndPosition(unit,method.getBody()):-1);
                result.add(Collections.unmodifiableMap(row));
            }
            @Override public Void visitClass(ClassTree node,Integer level){if(level>depth)return null;add(node,trees.getElement(getCurrentPath()),level);return super.visitClass(node,level+1);}
            @Override public Void visitMethod(MethodTree node,Integer level){add(node,trees.getElement(getCurrentPath()),level);return null;}
            @Override public Void visitVariable(VariableTree node,Integer level){add(node,trees.getElement(getCurrentPath()),level);return null;}
        }.scan(unit,0);
        return List.copyOf(result);
    }
    private SourceText sourceText(Path path,String text){
        String key=path+":"+Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));var cached=sourceTexts.get(key);if(cached!=null)return cached;
        var source=new SourceText(text);sourceTexts.put(key,source);while(sourceTexts.size()>16)sourceTexts.remove(sourceTexts.keySet().iterator().next());return source;
    }
    private void touch(Path path,String text)throws Exception{
        touchHash(path,Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private void touchHash(Path path,String hash)throws Exception{
        modules.get(context.generation()).files.add(path.toAbsolutePath().normalize());
        var changed=new LinkedHashSet<>(dependencies.observe(path,hash));
        changed.addAll(dependencies.check(path));
        if(!changed.isEmpty())invalidate(changed);
    }
    private void invalidateCompilerCaches(Set<Path> changed){
        for(var entry:modules.entrySet()){
            var caches=entry.getValue();
            caches.focused.entrySet().removeIf(e->changed.contains(e.getValue().file()));
            caches.outlines.entrySet().removeIf(e->changed.stream().anyMatch(path->e.getKey().startsWith(path+":")));
            if(!Collections.disjoint(caches.files,changed)){
                var pool=compilerPools.get(entry.getKey());
                if(changed.stream().anyMatch(path->path.getFileName().toString().equals("package-info.java")))pool.recycle();
                else pool.sourcesChanged();
            }
        }
    }
    private void invalidate(Set<Path> changed){diagnosticStore.invalidate(changed);invalidateCompilerCaches(changed);}
    private void conditionallyInvalidate(Path path,Set<Path> affected){
        diagnosticStore.invalidate(Set.of(path.toAbsolutePath().normalize()));invalidateCompilerCaches(affected);
    }
    private void resolveContribution(FileSemanticContribution contribution){
        if(liveSourceState!=null)liveSourceState.semantic(contribution.file(),contribution.sourceHash(),contribution.apiFingerprint(),contribution.exportedNames());
        boolean pending=dependencies.semantic().pending(contribution.file());
        var result=dependencies.semantic().resolve(contribution);
        if(pending){if(result.apiChanged().isEmpty())apiFingerprintUnchanged++;else apiFingerprintChanges++;}
        var affected=new LinkedHashSet<>(result.reanalyze());affected.remove(contribution.file());
        diagnosticStore.invalidate(affected,DiagnosticStore.Reason.DEPENDENCY_API_CHANGED);invalidateCompilerCaches(affected);
    }
    private void invalidateConditionalIfUnresolved(Path path){
        if(!pendingPrerequisites(path).isEmpty())diagnosticStore.invalidate(Set.of(path));
    }
    private final Map<Path,Long> persistedSemanticRevisions=new HashMap<>();
    private void reconcileSemanticRevision(Path path)throws Exception{
        if(index==null)return;path=path.toAbsolutePath().normalize();long current=index.semanticRevision(path);
        Long previous=persistedSemanticRevisions.put(path,current);
        if((previous==null&&current!=0)||(previous!=null&&previous.longValue()!=current))invalidate(Set.of(path));
    }
    public String contextKey(){return context.generation();}
    /** Detached API identity used by module actors to propagate cross-module conditional invalidation. */
    public String apiFingerprint(Path path){var value=contribution(path);return value==null?null:value.apiFingerprint();}
    public FileSemanticContribution contribution(Path path){return dependencies.semantic().contribution(path);}
    /** Only complete detached facts cross module actors. */
    public void resolvedContribution(FileSemanticContribution value){if(value!=null)resolveContribution(value);}
    public Set<Path> pendingPrerequisites(Path file){return dependencies.semantic().prerequisites(file);}
    public void changed(Path path,String hash){
        path=path.toAbsolutePath().normalize();compiler.observeSources(Set.of(path));conditionallyInvalidate(path,dependencies.changed(path,hash));
    }
    public void changed(Path path){
        path=path.toAbsolutePath().normalize();compiler.observeSources(Set.of(path));conditionallyInvalidate(path,dependencies.changed(path));
        // An unresolved lookup has no declaration edge; an API change will invalidate unresolved diagnostic states after attribution.
        focused.entrySet().removeIf(e->e.getValue().result().diagnostics().stream().anyMatch(d->d.kind().equals("ERROR")));
        outlines.entrySet().removeIf(e->Json.MAPPER.valueToTree(e.getValue().result()).path("diagnostics").findValuesAsText("kind").contains("ERROR"));
    }
    public void namespaceChanged(){diagnosticStore.clear();for(var caches:modules.values()){caches.outlines.clear();caches.focused.clear();caches.completion=null;caches.completionSourceEpoch=-1;caches.completionNeedsDiscoveryRefresh=false;caches.semantic.clear();}dependencies.semantic().clear();for(var pool:compilerPools.values())pool.recycle();}
    public CompilerPool.Outcome<Bindings.Snapshot> bindings(Path path,String text,Integer cursor)throws Exception{
        synchronizeKnownSources(path);return bindings(path,text,cursor,validatedInputs());
    }
    private CompilerPool.Outcome<Bindings.Snapshot> bindings(Path path,String text,Integer cursor,CompilerInputs.Snapshot observed)throws Exception{
        path=path.toAbsolutePath().normalize();reconcileSemanticRevision(path);touch(path,text);
        String hash=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String stamp=observed.environment().value()+":"+observed.membership().value();
        for(var entry:new ArrayList<>(focused.entrySet())){
            var cached=entry.getValue();
            if(cached.file().equals(path)&&cached.hash().equals(hash)&&cached.stamp().equals(stamp)
                    &&(cursor==null?entry.getKey().endsWith(":full"):cursor>=cached.start()&&cursor<cached.end()&&cached.excluded().stream().noneMatch(span->cursor>=span.start()&&cursor<span.end()))){
                focused.get(entry.getKey());cacheHits++;return cached.result();
            }
        }
        var focus=cursor==null?null:focusing.focus(path,text,cursor);
        String source=focus==null?text:focus.source();Path file=path;bindingComputations++;SemanticSnapshot[] semantic={null};
        var outcome=compiler.query(path,source,2,observed,(task,units,tier)->{
            var identity=new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources());
            var captured=Bindings.capture(task,units,identity,file,sourceText(file,text),true,focus==null?null:focus.member().equals("declarations")?new Focusing.Span(cursor,cursor+1):new Focusing.Span(focus.start(),focus.end()));
            if(tier==2&&focus==null)for(var unit:units)try{
                if(Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize().equals(file)){semantic[0]=SemanticFacts.sourceSnapshot(task,identity,unit);break;}
            }catch(Exception ignored){}
            return captured;
        });
        diagnosticStore.inputs(observed);
        if(outcome.result()!=null&&outcome.warnings().isEmpty()){
            dependencies.recordFocused(path,outcome.result().dependencies());
            if(cursor==null&&outcome.tier()==2){
                var contribution=SemanticContributions.from(path,hash,outcome.result(),outcome.diagnostics());
                resolveContribution(contribution);admitSemantic(semantic[0],contribution);publishSource(path,hash,stamp,outcome.result(),outcome.tier());
            }
            String member=focus==null?"full":focus.member();focused.put(path+":"+hash+":"+stamp+":"+member,new Cached(path,hash,stamp,focus==null?0:focus.member().equals("declarations")?cursor:focus.start(),focus==null?text.length():focus.member().equals("declarations")?cursor+1:focus.end(),focus==null?List.of():focus.replaced(),outcome));
            while(focused.size()>32)focused.remove(focused.keySet().iterator().next());
        }return outcome;
    }
    private void publishSource(Path file,String hash,String stamp,Bindings.Snapshot snapshot,int tier)throws Exception{
        if(index==null)return;
        long started=System.nanoTime();
        var symbols=List.copyOf(snapshot.symbols().values());
        var edges=snapshot.edges().stream().map(e->new IndexService.SourceEdge(e.src(),e.dst(),e.kind())).toList();
        var contribution=dependencies.semantic().contribution(file);
        // A dependency or unresolved-target replacement must survive publisher coalescing even
        // when this file's source and exported API did not change.
        String semantic=Hashing.sha256(Json.MAPPER.writeValueAsBytes(List.of(hash,contribution.apiFingerprint(),stamp,
                contribution.dependencies().stream().map(Path::toString).sorted().toList(),
                contribution.exportedNames().stream().sorted().toList(),contribution.unresolvedTargets().stream().sorted().toList())));
        long bytes=512L+2L*Json.MAPPER.writeValueAsBytes(symbols).length+edges.size()*192L;
        index.publishSource(new SourceIndexPublisher.Delta(contribution,semantic,symbols,tier,edges,bytes,context.gav(),stamp));
        indexWriteNanos+=System.nanoTime()-started;
    }
    public Envelope atPosition(Path path,String text,int line,int character)throws Exception{
        int offset=sourceText(path,text).offset(line,character);
        var outcome=bindings(path,text,offset);var symbol=outcome.result()==null?null:outcome.result().at(offset);
        return new Envelope(outcome.tier(),"live",false,null,warnings(outcome.warnings()),symbol==null?Map.of("resolved",false,"candidates",List.of()):symbol);
    }
    /** Validate identity before reading source text: warm diagnostics need no source bytes. */
    public Envelope cachedDiagnostics(Path path,Documents documents)throws Exception{
        synchronizeKnownSources(path);return cachedDiagnostics(path,documents,inputSnapshot());
    }
    private Envelope cachedDiagnostics(Path path,Documents documents,CompilerInputs.Snapshot observed)throws Exception{
        path=path.toAbsolutePath().normalize();
        String hash=observed.source(path).value();if(hash==null)hash=documents.sourceHash(path);
        reconcileSemanticRevision(path);touchHash(path,hash);invalidateConditionalIfUnresolved(path);
        var cached=restoreDiagnostics(path,hash,observed.environment().value()+":"+observed.membership().value());
        if(cached!=null){
            diagnosticFilesReused++;
        }
        return cached;
    }
    private Envelope restoreDiagnostics(Path path,String hash,String stamp)throws Exception{
        var state=diagnosticStore.state(path,hash,context.generation(),stamp);
        if(state==null)return null;
        // A disk snapshot can be valid again after an API is reverted. Resolve the
        // pending change using that snapshot, not the previous in-memory fingerprint.
        if(state.contribution()==null)return null;
        if(contribution(path)==null||dependencies.semantic().pending(path)){
            dependencies.recordFocused(path,state.dependencies());resolveContribution(state.contribution());
        }
        return state.diagnostics();
    }
    public Envelope diagnostics(Path path,Documents documents)throws Exception{
        synchronizeKnownSources(path);var observed=validatedInputs();var cached=cachedDiagnostics(path,documents,observed);if(cached!=null)return cached;
        return diagnostics(path,observed.text(path,documents),observed);
    }
    /** One javac task, followed by per-file detached states; no compiler objects escape. */
    public Map<Path,CompilerPool.Outcome<Bindings.Snapshot>> bindingsBatch(Map<Path,String> sources)throws Exception{
        if(sources.isEmpty())return Map.of();
        var relevant=new LinkedHashSet<Path>();
        for(Path requested:sources.keySet()){
            var queue=new ArrayDeque<Path>();queue.add(requested.toAbsolutePath().normalize());
            while(!queue.isEmpty()){Path file=queue.removeFirst().toAbsolutePath().normalize();if(relevant.add(file))queue.addAll(dependencies.semantic().dependencies(file));}
        }
        compiler.observeSources(relevant);
        var inputs=new ArrayList<CompilerPool.SourceInput>();
        for(var entry:sources.entrySet()){reconcileSemanticRevision(entry.getKey());touch(entry.getKey(),entry.getValue());inputs.add(new CompilerPool.SourceInput(entry.getKey(),entry.getValue()));}
        var observed=validatedInputs();String stamp=observed.environment().value()+":"+observed.membership().value();
        var semanticSnapshots=new LinkedHashMap<Path,SemanticSnapshot>();
        var result=compiler.batchQuery(inputs,2,observed,(task,units,tier)->{
            var snapshots=new LinkedHashMap<Path,Bindings.Snapshot>();var identity=new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources());
            for(var unit:units){
                Path file=Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();String text=sources.get(file);
                if(text!=null){
                    snapshots.put(file,Bindings.capture(task,List.of(unit),identity,file,new SourceText(text),true));
                    if(tier==2)semanticSnapshots.put(file,SemanticFacts.sourceSnapshot(task,identity,unit));
                }
            }
            return snapshots;
        });
        bindingComputations+=sources.size();
        diagnosticStore.inputs(observed);
        var values=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
        // Resolve every API first: invalidation from a later file must not erase an earlier fresh result.
        if(result.result()!=null&&result.tier()==2&&result.warnings().isEmpty())for(var entry:result.result().entrySet()){
            dependencies.recordFocused(entry.getKey(),entry.getValue().dependencies());
            var contribution=SemanticContributions.from(entry.getKey(),Hashing.sha256(sources.get(entry.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),entry.getValue(),result.diagnostics().stream().filter(p->sameFile(p.file(),entry.getKey())).toList());
            resolveContribution(contribution);admitSemantic(semanticSnapshots.get(entry.getKey()),contribution);
        }
        for(var input:inputs){
            Path file=input.file();String hash=Hashing.sha256(input.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var problems=result.diagnostics().stream().filter(p->sameFile(p.file(),file)).toList();
            var envelope=new Envelope(result.tier(),"live",false,null,warnings(result.warnings()),Map.of("diagnostics",problems));
            var snapshot=result.result()==null?null:result.result().get(file);
            var outcome=new CompilerPool.Outcome<>(result.tier(),snapshot,problems,result.warnings());values.put(file,outcome);
            if(snapshot!=null&&result.tier()==2&&result.warnings().isEmpty()){
                focused.put(file+":"+hash+":"+stamp+":full",new Cached(file,hash,stamp,0,input.text().length(),List.of(),outcome));
                while(focused.size()>32)focused.remove(focused.keySet().iterator().next());
                diagnosticStore.put(file,hash,context.generation(),stamp,envelope,apiFingerprint(file),snapshot.dependencies(),contribution(file));
                publishSource(file,hash,stamp,snapshot,result.tier());
            }
        }
        return values;
    }
    public Map<Path,Envelope> diagnosticsBatch(Map<Path,String> sources)throws Exception{
        var results=bindingsBatch(sources);diagnosticFilesAnalysed+=sources.size();
        var values=new LinkedHashMap<Path,Envelope>();
        results.forEach((file,result)->values.put(file,new Envelope(result.tier(),"live",false,null,warnings(result.warnings()),Map.of("diagnostics",result.diagnostics()))));
        return values;
    }
    private static boolean sameFile(String source,Path file){
        if(source==null)return true;
        try{return (source.startsWith("file:")?Path.of(java.net.URI.create(source)):Path.of(source)).toAbsolutePath().normalize().equals(file);}
        catch(Exception invalid){return false;}
    }
    public Envelope diagnostics(Path path,String text)throws Exception{
        synchronizeKnownSources(path);return diagnostics(path,text,validatedInputs());
    }
    private Envelope diagnostics(Path path,String text,CompilerInputs.Snapshot observed)throws Exception{
        path=path.toAbsolutePath().normalize();reconcileSemanticRevision(path);touch(path,text);invalidateConditionalIfUnresolved(path);
        String sourceHash=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),stamp=observed.environment().value()+":"+observed.membership().value(),generation=context.generation();
        var cached=restoreDiagnostics(path,sourceHash,stamp);
        if(cached!=null){diagnosticFilesReused++;return cached;}
        long computations=bindingComputations;
        var outcome=bindings(path,text,null,observed);
        if(bindingComputations>computations)diagnosticFilesAnalysed++;else diagnosticFilesReused++;
        var warnings=new LinkedHashSet<String>(warnings(outcome.warnings()));
        if(outcome.result()!=null)for(var problem:outcome.diagnostics())if(problem.kind().equals("ERROR")){
            for(var occurrence:outcome.result().occurrences())if(occurrence.end()>=problem.start()&&occurrence.start()<=Math.max(problem.start(),problem.end())){
                var symbol=outcome.result().symbols().get(occurrence.scip());if(symbol==null||symbol.get("source_file")==null)continue;
                String gav=Objects.toString(symbol.get("gav"),context.gav());if(!gav.equals(context.gav()))warnings.add("originates: "+gav);
            }
            // An unresolved selected member can erase its own occurrence; its declaring source remains a dependency.
            if(warnings.stream().noneMatch(w->w.startsWith("originates:"))&&problem.code().contains("cant.resolve"))for(Path dependency:outcome.result().dependencies()){
                String gav=coordinates(dependency.toString());if(gav!=null&&!gav.equals(context.gav()))warnings.add("originates: "+gav);
            }
        }
        var envelope=new Envelope(outcome.tier(),"live",false,null,List.copyOf(warnings),Map.of("diagnostics",outcome.diagnostics()));
        if(outcome.warnings().isEmpty())diagnosticStore.put(path,sourceHash,generation,stamp,envelope,apiFingerprint(path),outcome.result()==null?Set.of():outcome.result().dependencies(),outcome.tier()==2?contribution(path):null);
        return envelope;
    }
    public Envelope completion(Path path,String text,int line,int character,int limit,int offset)throws Exception{
        try(var trace=dev.jvmd.core.RequestScope.stage("completion.materialize")){
        long requestStarted=System.nanoTime(),keyNanos=0,sourceRefreshNanos=0,focusNanos=0,queryNanos=0,cacheAdmissionNanos=0,filterNanos=0;
        var profile=new EditorQueries.CompletionTiming();boolean cacheHit=false;
        path=path.toAbsolutePath().normalize();
        int cursor=Documents.offset(text,new Documents.Position(line,character)),start=cursor,end=cursor;
        while(start>0&&Character.isJavaIdentifierPart(text.codePointBefore(start)))start-=Character.charCount(text.codePointBefore(start));
        while(end<text.length()&&Character.isJavaIdentifierPart(text.codePointAt(end)))end+=Character.charCount(text.codePointAt(end));
        String prefix=text.substring(start,cursor),patched=text.substring(0,start)+EditorQueries.MARKER+text.substring(end);int focusCursor=start;
        int selector=start-1;while(selector>=0&&Character.isWhitespace(text.charAt(selector)))selector--;
        boolean qualified=selector>=0&&text.charAt(selector)=='.';
        synchronizeKnownSources(path);touch(path,text);
        var caches=modules.get(context.generation());
        if(caches.completionNeedsDiscoveryRefresh){
            // An unresolved receiver can leave javac's pooled scope tied to the old source namespace.
            // Reconcile only packages this caller can name, then discard that task context.
            compiler.discoverSourcePackages(completionDiscoveryPackages(text));
            compiler.resetSourceContext();
            caches.completionSourceEpoch=-1;
            caches.completionNeedsDiscoveryRefresh=false;
        }
        var cached=caches.completion;boolean cachedApiCurrent=true;
        if(cached!=null){
            var validation=refreshCompletionDependencies(path,cached);
            cached=validation==null?null:validation.cached();cachedApiCurrent=validation!=null&&validation.apiCurrent();caches.completion=cached;
        }
        long sourceEpoch=compiler.sourceStateGeneration();
        long phaseStarted=System.nanoTime();var observed=inputSnapshot();String key=completionKey(path,patched,start,qualified,observed);keyNanos=System.nanoTime()-phaseStarted;
        CompilerPool.Outcome<List<Map<String,Object>>> outcome;
        if(key!=null&&cached!=null&&cachedApiCurrent&&key.equals(cached.key())&&prefix.startsWith(cached.prefix())){
            completionCacheHits++;cacheHit=true;outcome=cached.result();
        }else{
            completionComputations++;phaseStarted=System.nanoTime();
            if(caches.completionSourceEpoch>=0&&caches.completionSourceEpoch!=sourceEpoch)compiler.recycle();
            else compiler.sourcesChanged();
            sourceRefreshNanos=System.nanoTime()-phaseStarted;
            phaseStarted=System.nanoTime();var focus=focusing.focus(path,patched,focusCursor);focusNanos=System.nanoTime()-phaseStarted;
            phaseStarted=System.nanoTime();
            var attributed=compiler.query(path,focus.source(),2,observed,(task,units,tier)->EditorQueries.completionResult(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),prefix,profile));
            var completionResult=attributed.result();
            outcome=new CompilerPool.Outcome<>(attributed.tier(),completionResult==null?null:completionResult.items(),attributed.diagnostics(),attributed.warnings());
            queryNanos=System.nanoTime()-phaseStarted;
            phaseStarted=System.nanoTime();
            caches.completionNeedsDiscoveryRefresh=outcome.result()==null||outcome.result().isEmpty();
            var live=documents.liveState(context.sources());
            caches.completionSourceEpoch=live.snapshot().inputEpoch();
            caches.completion=null;
            if(key!=null&&outcome.tier()==2&&outcome.warnings().isEmpty()&&outcome.result()!=null&&!outcome.result().isEmpty()
                    &&outcome.result().size()<=256&&Json.MAPPER.writeValueAsBytes(outcome.result()).length<=256*1024){
                var completionDependencies=completionDependencies(path,text,completionResult);
                if(ensureCompletionSemantics(completionDependencies)){
                    dependencies.recordFocused(path,completionDependencies);
                    String admittedKey=completionKey(path,patched,start,qualified,observed);
                    caches.completion=new CompletionCached(admittedKey,prefix,live.snapshot().inputEpoch(),completionDependencies,
                            completionApiFingerprints(completionDependencies),
                            new CompilerPool.Outcome<>(2,outcome.result(),List.of(),List.of()));
                    caches.completionSourceEpoch=live.snapshot().inputEpoch();
                }
            }
            cacheAdmissionNanos=System.nanoTime()-phaseStarted;
        }
        phaseStarted=System.nanoTime();var values=outcome.result()==null?List.<Map<String,Object>>of():outcome.result().stream().filter(row->row.get("name").toString().startsWith(prefix)).toList();int from=Math.min(offset,values.size()),to=Math.min(values.size(),from+limit);var returned=values.subList(from,to);long resultBytes=Json.MAPPER.writeValueAsBytes(returned).length;filterNanos=System.nanoTime()-phaseStarted;
        long totalNanos=System.nanoTime()-requestStarted;
        completionRequests++;completionKeyNanos+=keyNanos;completionSourceRefreshNanos+=sourceRefreshNanos;completionFocusNanos+=focusNanos;completionQueryNanos+=queryNanos;
        completionEditorNanos+=profile.totalNanos();completionCandidateNanos+=profile.candidateNanos();completionRowNanos+=profile.rowNanos();completionDocNanos+=profile.docNanos();
        completionSortNanos+=profile.sortNanos();completionCacheAdmissionNanos+=cacheAdmissionNanos;completionFilterNanos+=filterNanos;completionTotalNanos+=totalNanos;
        completionCandidatesSeen+=profile.candidates();completionRowsMaterialized+=profile.rows();completionDocLookups+=profile.docs();
        completionGetAllMembersCalls+=profile.getAllMembersCalls();completionScopeTraversals+=profile.scopeTraversals();completionScipComputations+=profile.scipComputations();
        completionSignatureComputations+=profile.signatureComputations();completionAsMemberOfCalls+=profile.asMemberOfCalls();completionSorts+=profile.sorts();completionSortInputs+=profile.sortInputs();
        completionRowsReturned+=returned.size();completionRowsDiscardedAfterLimit+=Math.max(0,values.size()-to);completionResultBytes+=resultBytes;completionLastCacheHit=cacheHit;
        trace.cache(cacheHit?"prefix-hit":"recompute");trace.count("candidates_examined",profile.candidates());trace.count("rows_built",profile.rows());trace.count("documentation_lookups",profile.docs());
        trace.count("get_all_members_calls",profile.getAllMembersCalls());trace.count("scope_traversals",profile.scopeTraversals());trace.count("as_member_of_calls",profile.asMemberOfCalls());
        completionLastTimingMs=Map.ofEntries(
                Map.entry("key",millis(keyNanos)),Map.entry("source_refresh",millis(sourceRefreshNanos)),Map.entry("focus",millis(focusNanos)),
                Map.entry("compiler_query",millis(queryNanos)),Map.entry("editor_total",millis(profile.totalNanos())),Map.entry("candidate_discovery",millis(profile.candidateNanos())),
                Map.entry("row_materialization",millis(profile.rowNanos())),Map.entry("documentation",millis(profile.docNanos())),Map.entry("sort",millis(profile.sortNanos())),
                Map.entry("cache_admission",millis(cacheAdmissionNanos)),Map.entry("filter",millis(filterNanos)),Map.entry("total",millis(totalNanos)));
        return new Envelope(outcome.tier(),"live",to<values.size(),to<values.size()?Integer.toString(to):null,warnings(outcome.warnings()),Map.of("items",returned,"range",new SourceText(text).range(start,end)));
        }
    }
    private CompletionValidation refreshCompletionDependencies(Path caller,CompletionCached cached)throws Exception{
        var live=documents.liveState(context.sources());
        live.observe(cached.dependencies());
        var changed=live.changedPathsSince(cached.sourceEpoch());
        if(changed.isEmpty())return null; // journal no longer proves the delta
        if(changed.get().isEmpty())return new CompletionValidation(cached,true);
        boolean apiCurrent=true;
        for(Path dependency:changed.get()){
            if(dependency.equals(caller)||!cached.dependencies().contains(dependency))continue;
            var leaf=live.leaf(dependency).orElse(null);
            if(leaf==null){apiCurrent=false;continue;}
            if(!leaf.semanticsCurrent()){
                var result=bindings(dependency,documents.text(dependency),null);
                if(result.tier()!=2||result.result()==null||!result.warnings().isEmpty())return null;
                leaf=live.leaf(dependency).orElse(null);
                if(leaf==null||!leaf.semanticsCurrent())return null;
            }
            if(!Objects.equals(cached.dependencyApis().get(dependency),leaf.api().value()))apiCurrent=false;
        }
        var refreshed=new CompletionCached(cached.key(),cached.prefix(),live.snapshot().inputEpoch(),cached.dependencies(),cached.dependencyApis(),cached.result());
        return new CompletionValidation(refreshed,apiCurrent);
    }
    private Map<Path,String> completionApiFingerprints(Set<Path> dependenciesToCheck){
        var live=documents.liveState(context.sources());var result=new LinkedHashMap<Path,String>();
        for(Path dependency:dependenciesToCheck){
            var leaf=live.leaf(dependency).orElse(null);
            if(leaf==null||!leaf.semanticsCurrent())throw new IllegalStateException("Completion dependency semantics are not current: "+dependency);
            result.put(dependency,leaf.api().value());
        }
        return Map.copyOf(result);
    }
    private Set<Path> completionDependencies(Path caller,String text,EditorQueries.CompletionResult completion){
        var result=new TreeSet<Path>(Comparator.comparing(Path::toString));var live=documents.liveState(context.sources());
        if(completion==null)return Set.of();
        for(Path dependency:completion.semanticDependencies()){
            Path file=dependency.toAbsolutePath().normalize();
            if(!file.equals(caller)&&live.accepts(file))result.add(file);
        }
        for(String binary:completionNameResolutionBinaries(text,completion.nameResolutionNames())){
            live.source(binary).map(LiveSourceState.Source::file).filter(file->!file.equals(caller)).ifPresent(result::add);
        }
        return Set.copyOf(result);
    }
    private static Set<String> completionNameResolutionBinaries(String text,Collection<String> simpleNames){
        if(simpleNames==null||simpleNames.isEmpty())return Set.of();
        var names=new LinkedHashSet<>(simpleNames);var result=new LinkedHashSet<String>();
        var packageMatch=java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;").matcher(text);
        String current=packageMatch.find()?packageMatch.group(1):"";
        for(String name:names)result.add(current.isEmpty()?name:current+"."+name);
        var imports=java.util.regex.Pattern.compile("\\bimport\\s+(static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)*)\\s*;").matcher(text);
        while(imports.find()){
            boolean statik=imports.group(1)!=null;String imported=imports.group(2);
            if(statik){
                if(imported.endsWith(".*"))result.add(imported.substring(0,imported.length()-2));
                else{
                    int cut=imported.lastIndexOf('.');String member=cut<0?imported:imported.substring(cut+1);
                    if(cut>=0&&names.contains(member))result.add(imported.substring(0,cut));
                }
            }else if(imported.endsWith(".*")){
                String pkg=imported.substring(0,imported.length()-2);
                for(String name:names)result.add(pkg+"."+name);
            }else{
                int cut=imported.lastIndexOf('.');String simple=cut<0?imported:imported.substring(cut+1);
                if(names.contains(simple))result.add(imported);
            }
        }
        return Set.copyOf(result);
    }
    private boolean ensureCompletionSemantics(Set<Path> dependenciesToCheck)throws Exception{
        var live=documents.liveState(context.sources());live.observe(dependenciesToCheck);
        for(Path dependency:dependenciesToCheck){
            String current=live.contentHash(dependency);if(current==null)return false;
            var contribution=contribution(dependency);
            if(contribution==null||!current.equals(contribution.sourceHash())){
                var result=bindings(dependency,documents.text(dependency),null);
                if(result.tier()!=2||result.result()==null||!result.warnings().isEmpty())return false;
            }
        }
        return true;
    }
    private boolean ensureSourceSemanticCurrent(Path requested)throws Exception{
        Path file=requested.toAbsolutePath().normalize();
        if(liveSourceState==null||!liveSourceState.accepts(file))return true;
        compiler.observeSources(Set.of(file));
        String current=liveSourceState.contentHash(file);
        String unit="source:"+file;
        if(current==null){semanticState().removeUnit(unit);return false;}
        var resident=semanticState().unit(unit);var contribution=contribution(file);
        if(resident!=null&&current.equals(resident.contentIdentity())&&contribution!=null&&current.equals(contribution.sourceHash()))return true;
        var outcome=bindings(file,documents.text(file),null);
        resident=semanticState().unit(unit);contribution=contribution(file);
        return outcome.tier()==2&&outcome.result()!=null&&outcome.warnings().isEmpty()
                &&resident!=null&&current.equals(resident.contentIdentity())
                &&contribution!=null&&current.equals(contribution.sourceHash());
    }

    private Map<String,String> completionResolutionIdentities(Collection<String> binaries)throws Exception{
        if(binaries==null||binaries.isEmpty()||liveSourceState==null)return Map.of();
        var result=new TreeMap<String,String>();
        for(String binary:binaries){
            var source=liveSourceState.source(binary).orElse(null);
            if(source==null){result.put(binary,"<missing>");continue;}
            Path file=source.file().toAbsolutePath().normalize();
            if(!ensureSourceSemanticCurrent(file)){result.put(binary,"<missing>");continue;}
            var contribution=contribution(file);
            var unit=semanticState().unit("source:"+file);
            String api=contribution!=null?contribution.apiFingerprint():unit==null?"":unit.apiIdentity();
            result.put(binary,file+"\0"+api);
        }
        return Map.copyOf(result);
    }

    private static void addDeclaredTypes(ArrayDeque<SemanticType> queue,SemanticType type){
        if(type instanceof SemanticType.Declared declared)queue.addLast(declared);
        else if(type instanceof SemanticType.Intersection intersection)intersection.bounds().forEach(value->addDeclaredTypes(queue,value));
        else if(type instanceof SemanticType.Variable variable)queue.addLast(variable);
    }

    private Map<String,SemanticType> typeSubstitutions(SemanticFact type,SemanticType.Declared instantiated){
        if(type.typeParameters().isEmpty()||instantiated.arguments().isEmpty())return Map.of();
        var result=new LinkedHashMap<String,SemanticType>();
        int count=Math.min(type.typeParameters().size(),instantiated.arguments().size());
        for(int i=0;i<count;i++)result.put(type.typeParameters().get(i),instantiated.arguments().get(i));
        return Map.copyOf(result);
    }

    private boolean ensureHierarchySemanticCurrent(DocumentSemanticSnapshot.QueryContext query)throws Exception{
        var queue=new ArrayDeque<SemanticType>();addDeclaredTypes(queue,query.receiverType());
        var seen=new HashSet<String>();var sources=new HashSet<Path>();
        while(!queue.isEmpty()){
            var next=queue.removeFirst();
            if(!(next instanceof SemanticType.Declared declared))continue;
            if(!seen.add(declared.symbolId()))continue;
            var fact=semanticState().symbol(declared.symbolId());if(fact==null)return false;
            if(fact.sourceFile()!=null)try{
                Path source=Path.of(fact.sourceFile()).toAbsolutePath().normalize();
                if(liveSourceState!=null&&liveSourceState.accepts(source)&&sources.add(source)&&!ensureSourceSemanticCurrent(source))return false;
            }catch(Exception ignored){}
            fact=semanticState().symbol(declared.symbolId());if(fact==null)return false;
            var substitutions=typeSubstitutions(fact,declared);
            for(var parent:fact.directSupertypes())addDeclaredTypes(queue,parent.substitute(substitutions));
        }
        return true;
    }

    private String semanticNestRoot(String typeId){
        String current=typeId,last=typeId;var seen=new HashSet<String>();
        while(current!=null&&seen.add(current)){
            var fact=semanticState().symbol(current);if(fact==null||!fact.typeDeclaration())break;
            last=current;
            String owner=fact.ownerId();
            if(owner==null||semanticState().symbol(owner)==null||!semanticState().symbol(owner).typeDeclaration())break;
            current=owner;
        }
        return last;
    }

    private boolean semanticSubtype(String subtype,String supertype){
        if(subtype==null||supertype==null)return false;if(subtype.equals(supertype))return true;
        var queue=new ArrayDeque<String>();var seen=new HashSet<String>();queue.add(subtype);
        while(!queue.isEmpty()){
            String id=queue.removeFirst();if(!seen.add(id))continue;
            var fact=semanticState().symbol(id);if(fact==null)continue;
            for(var parent:fact.directSupertypes()){
                var declared=parent instanceof SemanticType.Declared value?value:null;
                if(declared==null)continue;
                if(declared.symbolId().equals(supertype))return true;queue.addLast(declared.symbolId());
            }
        }
        return false;
    }

    private boolean residentAccessible(SemanticFact member,DocumentSemanticSnapshot.QueryContext query){
        var modifiers=member.modifiers();String callerPackage=query.packageName(),memberPackage=member.packageName();
        if(modifiers.contains("public"))return true;
        if(modifiers.contains("private")){
            if(query.enclosingTypeId()==null||member.ownerId()==null)return false;
            return Objects.equals(semanticNestRoot(query.enclosingTypeId()),semanticNestRoot(member.ownerId()));
        }
        if(Objects.equals(callerPackage,memberPackage))return true;
        if(!modifiers.contains("protected")||query.enclosingTypeId()==null||member.ownerId()==null)return false;
        if(!semanticSubtype(query.enclosingTypeId(),member.ownerId()))return false;
        return modifiers.contains("static")||query.receiverSymbolId()==null||semanticSubtype(query.receiverSymbolId(),query.enclosingTypeId());
    }

    private Map<String,Object> residentCompletionRow(SemanticFact fact,CompletionCandidate candidate){
        var value=new LinkedHashMap<String,Object>();
        value.put("scip",candidate.id());value.put("name",candidate.name());value.put("name_path",fact.namePath());
        value.put("kind",candidate.kind());value.put("signature",candidate.structuralSignature());value.put("label",candidate.label());
        value.put("modifiers",candidate.modifiers().stream().sorted().toList());
        if(candidate.sourceFile()!=null)value.put("source_file",candidate.sourceFile());
        var parameters=new ArrayList<Map<String,Object>>();
        for(var parameter:candidate.parameters())parameters.add(Map.of("label",List.of(parameter.start(),parameter.end())));
        value.put("parameters",List.copyOf(parameters));
        return Collections.unmodifiableMap(value);
    }

    private List<Map<String,Object>> residentQualifiedRows(DocumentSemanticSnapshot.QueryContext query,String prefix,int target){
        if(target<=0)return List.of();
        var queue=new ArrayDeque<SemanticType>();addDeclaredTypes(queue,query.receiverType());
        var seenTypes=new HashSet<String>();var rows=new LinkedHashMap<String,Map<String,Object>>();
        while(!queue.isEmpty()&&rows.size()<target*8){
            var next=queue.removeFirst();if(!(next instanceof SemanticType.Declared declared)||!seenTypes.add(declared.symbolId()))continue;
            var owner=semanticState().symbol(declared.symbolId());if(owner==null)continue;
            var substitutions=typeSubstitutions(owner,declared);
            for(var member:semanticState().members(declared.symbolId(),prefix,target)){
                if(member.kind().equals("ctor")||member.kind().equals("package")||member.kind().equals("module"))continue;
                if(query.staticReceiver()&&!member.typeDeclaration()&&!member.modifiers().contains("static"))continue;
                if(!residentAccessible(member,query))continue;
                rows.putIfAbsent(member.id(),residentCompletionRow(member,member.candidate(substitutions)));
            }
            for(var parent:owner.directSupertypes())addDeclaredTypes(queue,parent.substitute(substitutions));
        }
        return rows.values().stream().sorted(Comparator.comparing(row->row.get("label").toString())).limit(target).toList();
    }

    private static Set<String> completionDiscoveryPackages(String text){
        var result=new LinkedHashSet<String>();var packageMatch=java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;").matcher(text);
        String current=packageMatch.find()?packageMatch.group(1):"";result.add(current);
        var imports=java.util.regex.Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)*)\\s*;").matcher(text);
        while(imports.find()){
            boolean statik=imports.group(1)!=null;String name=imports.group(2);
            if(name.endsWith(".*"))name=name.substring(0,name.length()-2);
            int cut=name.lastIndexOf('.');
            if(statik&&cut>=0){name=name.substring(0,cut);cut=name.lastIndexOf('.');}
            if(cut>=0)result.add(name.substring(0,cut));else if(!statik)result.add("");
        }
        // Fully-qualified type references can name a source package without an import.
        var qualifiedTypes=java.util.regex.Pattern.compile("\\b((?:[A-Za-z_$][\\w$]*\\.)+)([A-Z_$][\\w$]*)\\b").matcher(text);
        while(qualifiedTypes.find())result.add(qualifiedTypes.group(1).substring(0,qualifiedTypes.group(1).length()-1));
        return Set.copyOf(result);
    }
    private static double millis(long nanos){return Math.round(nanos/1000.0)/1000.0;}
    private String completionKey(Path file,String patched,int start,boolean qualified,CompilerInputs.Snapshot inputs){
        if(patched.length()>256*1024)return null;
        var state=inputs.live().snapshot().state();String patchedHash=Hashing.sha256(patched.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String namespace=qualified?"qualified":state.namespace().fingerprint().value();
        return CompilerInputs.compose("completion-v3",completionContextIdentity,file.toString(),start,patchedHash,inputs.environment().value(),
                state.membership().fingerprint().value(),namespace);
    }

    public Envelope signatureHelp(Path path,String text,int line,int character)throws Exception{
        synchronizeKnownSources(path);int cursor=Documents.offset(text,new Documents.Position(line,character));touch(path,text);var focus=focusing.focus(path,text,cursor);
        var outcome=compiler.query(path,focus.source(),2,(task,units,tier)->EditorQueries.signatures(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),cursor));
        return new Envelope(outcome.tier(),"live",false,null,warnings(outcome.warnings()),outcome.result()==null?Map.of("signatures",List.of()):outcome.result());
    }
    public Envelope semanticTokens(Path path,String text,int limit,int offset)throws Exception{
        var outcome=bindings(path,text,null);var tokens=new TreeMap<Integer,Bindings.Occurrence>();
        if(outcome.result()!=null)for(var occurrence:outcome.result().occurrences())if(occurrence.file().equals(path.toString()))tokens.merge(occurrence.start(),occurrence,(first,next)->"instantiates".equals(first.role())?next:first);
        var values=List.copyOf(tokens.values());int from=Math.min(offset,values.size()),to=Math.min(values.size(),from+limit);var data=new ArrayList<Integer>();
        int previousLine=from==0?0:values.get(from-1).range().start().line(),previousCharacter=from==0?0:values.get(from-1).range().start().character();
        for(var occurrence:values.subList(from,to)){
            var symbol=outcome.result().symbols().get(occurrence.scip());int type=switch(Objects.toString(symbol.get("kind"),"")){case "package"->0;case "class","record","ctor"->1;case "interface"->2;case "enum"->3;case "type_parameter"->4;case "parameter"->5;case "field"->7;case "enumconst"->8;case "method"->9;case "annotation"->10;default->6;};
            @SuppressWarnings("unchecked") var modifiers=(List<String>)symbol.getOrDefault("modifiers",List.of());int flags="declaration".equals(occurrence.role())?1:0;if(modifiers.contains("static"))flags|=2;if(modifiers.contains("final"))flags|=4;if(modifiers.contains("abstract"))flags|=8;if("writes".equals(occurrence.role()))flags|=32;
            int line=occurrence.range().start().line(),character=occurrence.range().start().character();data.add(line-previousLine);data.add(line==previousLine?character-previousCharacter:character);data.add(occurrence.end()-occurrence.start());data.add(type);data.add(flags);previousLine=line;previousCharacter=character;
        }
        return new Envelope(outcome.tier(),"live",to<values.size(),to<values.size()?Integer.toString(to):null,warnings(outcome.warnings()),Map.of("data",data,"resultId",Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    public List<Map<String,Object>> known(String ref){
        var found=new LinkedHashMap<String,Map<String,Object>>();
        for(var cached:focused.values())if(cached.result().result()!=null)for(var symbol:cached.result().result().symbols().values())if(matches(symbol,ref,false))found.put(symbol.get("scip").toString(),symbol);
        return List.copyOf(found.values());
    }
    public static boolean matches(Map<String,Object> symbol,String ref,boolean substring){
        if(substring)return Objects.toString(symbol.get("name_path"),"").contains(ref)||Objects.toString(symbol.get("name"),"").contains(ref);
        return NamePath.parse(ref).matches(symbol);
    }
    public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>(compiler.status());if(snapshots!=null)result.put("persistent_snapshots",snapshots.status());result.putAll(focusing.status());result.put("outline_cache_entries",outlines.size());result.put("configured",context!=null);result.put("binding_cache_entries",focused.size());result.put("binding_cache_hits",cacheHits);result.put("binding_computations",bindingComputations);result.put("classpath_fingerprints",classpathFingerprints);result.put("diagnostic_store",diagnosticStore.status());result.put("diagnostic_files_analysed",diagnosticFilesAnalysed);result.put("diagnostic_files_reused",diagnosticFilesReused);result.put("index_record_source_calls",indexWrites);result.put("index_record_source_ms",0.0);result.put("index_publish_enqueue_ms",Math.round(indexWriteNanos/1000.0)/1000.0);if(index!=null)result.put("source_publisher",index.sourcePublisherStatus());result.put("api_fingerprint_changes",apiFingerprintChanges);result.put("api_fingerprint_unchanged",apiFingerprintUnchanged);result.put("pending_api_files",dependencies.semantic().pendingCount());result.put("conditional_files",dependencies.semantic().conditionalCount());result.put("dependencies",dependencies.status());
        if(liveSourceState!=null)result.put("live_source_state",liveSourceState.status());
        if(context!=null)result.put("resident_semantic_state",semanticState().status());
        result.put("completion_cache_hits",completionCacheHits);result.put("completion_computations",completionComputations);result.put("completion_requests",completionRequests);
        result.put("completion_candidates_seen",completionCandidatesSeen);result.put("completion_rows_materialized",completionRowsMaterialized);result.put("completion_doc_lookups",completionDocLookups);
        result.put("completion_get_all_members_calls",completionGetAllMembersCalls);result.put("completion_scope_traversals",completionScopeTraversals);
        result.put("completion_scip_computations",completionScipComputations);result.put("completion_signature_computations",completionSignatureComputations);
        result.put("completion_as_member_of_calls",completionAsMemberOfCalls);result.put("completion_sort_count",completionSorts);result.put("completion_sort_input_size",completionSortInputs);
        result.put("completion_rows_returned",completionRowsReturned);result.put("completion_rows_discarded_after_limit",completionRowsDiscardedAfterLimit);result.put("completion_result_bytes",completionResultBytes);
        result.put("completion_last_cache_hit",completionLastCacheHit);result.put("completion_last_timing_ms",completionLastTimingMs);
        result.put("completion_timing_ms",Map.ofEntries(
                Map.entry("key",millis(completionKeyNanos)),Map.entry("source_refresh",millis(completionSourceRefreshNanos)),Map.entry("focus",millis(completionFocusNanos)),
                Map.entry("compiler_query",millis(completionQueryNanos)),Map.entry("editor_total",millis(completionEditorNanos)),Map.entry("candidate_discovery",millis(completionCandidateNanos)),
                Map.entry("row_materialization",millis(completionRowNanos)),Map.entry("documentation",millis(completionDocNanos)),Map.entry("sort",millis(completionSortNanos)),
                Map.entry("cache_admission",millis(completionCacheAdmissionNanos)),Map.entry("filter",millis(completionFilterNanos)),Map.entry("total",millis(completionTotalNanos))));
        var modules=new LinkedHashMap<String,Object>();for(var entry:compilerPools.entrySet())modules.put(entry.getKey(),entry.getValue().status());result.put("module_compilers",modules);return result;
    }
    @Override public void close()throws Exception{if(snapshots!=null)snapshots.close();diagnosticStore.clear();outlines.clear();focused.clear();focusing.close();sourceTexts.clear();dependencies.semantic().clear();for(var pool:compilerPools.values())pool.close();compilerPools.clear();modules.clear();compiler=null;}
}
