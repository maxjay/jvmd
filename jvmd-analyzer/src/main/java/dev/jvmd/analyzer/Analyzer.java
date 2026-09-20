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
    public record Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings,List<Path> navigationSources) {
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,binarySources,warnings,sources);}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,Set.of(),List.of());}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates){this(gav,release,classpath,sources,generation,coordinates,List.of("--release",release));}
    }
    private final Map<String,CompilerPool> compilerPools=new LinkedHashMap<>();
    private CompilerPool compiler;
    private static final class ModuleCaches {
        final LinkedHashMap<String,Envelope> outlines=new LinkedHashMap<>(16,.75f,true);
        final LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
        final Set<Path> files=new HashSet<>();
        CompletionCached completion;
    }
    private final Map<String,ModuleCaches> modules=new LinkedHashMap<>();
    private long classpathFingerprints;
    private final FileStateRegistry inputFiles;
    public Analyzer(){this(new FileStateRegistry());}
    /** Share only validated content identities; compiler objects remain analyzer-owned. */
    public Analyzer(FileStateRegistry inputFiles){this.inputFiles=Objects.requireNonNull(inputFiles);}
    private Documents documents=new Documents();

    private final Focusing focusing=new Focusing();
    private final DiagnosticStore diagnosticStore=new DiagnosticStore();
    private LinkedHashMap<String,Envelope> outlines=new LinkedHashMap<>(16,.75f,true);
    private record Cached(Path file,String hash,String stamp,int start,int end,List<Focusing.Span> excluded,CompilerPool.Outcome<Bindings.Snapshot> result) { }
    private record CompletionCached(String key,String prefix,CompilerPool.Outcome<List<Map<String,Object>>> result) { }
    private record Outline(List<Map<String,Object>> symbols,Set<Path> dependencies) { }
    private record PendingApi(String previous,Set<Path> affected) { PendingApi { affected=Set.copyOf(affected); } }
    private LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
    private final Dependencies dependencies=new Dependencies();
    private final LinkedHashMap<String,SourceText> sourceTexts=new LinkedHashMap<>(16,.75f,true);
    private final Map<Path,String> apiFingerprints=new HashMap<>();
    private final Map<Path,PendingApi> pendingApi=new HashMap<>();
    private final Map<Path,Set<Path>> conditionalByFile=new HashMap<>();
    private long cacheHits,bindingComputations,diagnosticFilesAnalysed,diagnosticFilesReused,indexWrites,indexWriteNanos,apiFingerprintChanges,apiFingerprintUnchanged;
    private long completionCacheHits,completionComputations;
    private Context context;
    private IndexService index;
    private DiagnosticSnapshots snapshots;
    public void persistence(Path directory){if(snapshots==null){snapshots=new DiagnosticSnapshots(directory);diagnosticStore.persistence(snapshots);snapshots.documents(documents);}}
    private long budget;
    public void configure(Context context,IndexService index,long budget)throws Exception{
        var caches=modules.computeIfAbsent(context.generation(),_->new ModuleCaches());
        outlines=caches.outlines;focused=caches.focused;
        this.context=context;this.index=index;this.budget=budget;diagnosticStore.budget(Math.max(1024*1024,budget/8));
        compiler=compilerPools.computeIfAbsent(context.generation(),_->new CompilerPool());
        compiler.configure(context.generation(),context.release(),context.classpath(),context.sources(),index,budget,context.compilerOptions());
        compiler.binarySources(context.binarySources());
    }
    public void documents(Documents documents){this.documents=documents;if(snapshots!=null)snapshots.documents(documents);compiler.documents(documents.snapshots());dependencies.documentHash(documents::hash);dependencies.fileStates(documents.fileStates());}
    private List<String> warnings(List<String> query){if(context.warnings().isEmpty())return query;var all=new LinkedHashSet<String>(context.warnings());all.addAll(query);return List.copyOf(all);}
    private String coordinates(String file){return context.coordinates().entrySet().stream().filter(e->file.startsWith(e.getKey())).max(Comparator.comparingInt(e->e.getKey().length())).map(Map.Entry::getValue).orElse(null);}
    private String classpathStamp()throws Exception{
        return RequestScope.memo(List.of(this,"classpath",context.generation()),this::computeClasspathStamp);
    }
    private String computeClasspathStamp()throws Exception{
        classpathFingerprints++;
        if(!compiler.cacheValid()){outlines.clear();focused.clear();}
        var value=new StringBuilder("diagnostics-v2:").append(Runtime.version()).append(':').append(System.getProperty("java.home")).append(':').append(context.generation()).append(':').append(context.compilerOptions());
        appendClasspathContents(value);
        // New names can resolve old failures without a previously known dependency edge.
        for(var root:context.sources()){
            value.append("\0root:").append(root);
            if(Files.isDirectory(root))for(Path file:FileInventory.matching(root,".java"))value.append("\0").append(file);
        }
        documents.paths().stream().filter(p->!Files.isRegularFile(p)&&context.sources().stream().anyMatch(p::startsWith)).sorted().forEach(p->value.append("\0buffer:").append(p));
        return Hashing.sha256(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void appendClasspathContents(StringBuilder value)throws Exception{
        for(var path:context.classpath()){
            value.append("\0").append(path);
            if(Files.isRegularFile(path))value.append(':').append(inputFiles.hash(path));
            else if(Files.isDirectory(path)){
                for(Path file:FileInventory.matching(path,".class"))value.append("\0").append(file).append(':').append(inputFiles.hash(file));
            }else value.append(":missing");
        }
    }

    private Map<Path,String> sourceIdentities()throws Exception{
        var result=new TreeMap<Path,String>();
        for(Path root:context.sources())if(Files.isDirectory(root))
            for(Path file:FileInventory.matching(root,".java"))result.put(file.toAbsolutePath().normalize(),documents.sourceHash(file));
        for(Path file:documents.paths())if(context.sources().stream().anyMatch(file::startsWith))result.put(file,documents.sourceHash(file));
        return Map.copyOf(result);
    }
    public Envelope overview(Path path,String text,int depth,int limit,int offset)throws Exception{
        touch(path,text);
        String key=path+":"+Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":"+classpathStamp()+":"+depth+":"+limit+":"+offset;
        var cached=outlines.get(key);if(cached!=null)return cached;
        var result=compiler.query(path,text,1,(task,units,tier)->new Outline(declarations(task,units,path,text,depth),Bindings.capture(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),path,sourceText(path,text),false).dependencies()));
        if(result.result()!=null)dependencies.record(path,result.result().dependencies());
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
        path=path.toAbsolutePath().normalize();pendingApi.put(path,new PendingApi(apiFingerprints.get(path),affected));diagnosticStore.invalidate(Set.of(path));
        for(Path candidate:affected){candidate=candidate.toAbsolutePath().normalize();if(candidate.equals(path))continue;var origins=new LinkedHashSet<>(conditionalByFile.getOrDefault(candidate,Set.of()));origins.add(path);conditionalByFile.put(candidate,Set.copyOf(origins));}
        invalidateCompilerCaches(affected);
    }
    private void resolveApiChange(Path path,String fingerprint){
        path=path.toAbsolutePath().normalize();apiFingerprints.put(path,fingerprint);var pending=pendingApi.remove(path);if(pending==null)return;
        boolean changed=pending.previous()==null||!pending.previous().equals(fingerprint);
        if(changed){apiFingerprintChanges++;var invalid=new LinkedHashSet<>(pending.affected());invalid.addAll(diagnosticStore.unresolvedFiles());diagnosticStore.invalidate(invalid,DiagnosticStore.Reason.DEPENDENCY_API_CHANGED);}
        else apiFingerprintUnchanged++;
        var iterator=conditionalByFile.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();var origins=new LinkedHashSet<>(entry.getValue());origins.remove(path);if(origins.isEmpty())iterator.remove();else entry.setValue(Set.copyOf(origins));}
    }
    private void invalidateConditionalIfUnresolved(Path path){
        path=path.toAbsolutePath().normalize();var origins=conditionalByFile.get(path);if(origins!=null&&origins.stream().anyMatch(pendingApi::containsKey))diagnosticStore.invalidate(Set.of(path));
    }
    private final Map<Path,Long> persistedSemanticRevisions=new HashMap<>();
    private void reconcileSemanticRevision(Path path)throws Exception{
        if(index==null)return;path=path.toAbsolutePath().normalize();long current=index.semanticRevision(path);
        Long previous=persistedSemanticRevisions.put(path,current);
        if((previous==null&&current!=0)||(previous!=null&&previous.longValue()!=current))invalidate(Set.of(path));
    }
    public String contextKey(){return context.generation();}
    /** Detached API identity used by module actors to propagate cross-module conditional invalidation. */
    public String apiFingerprint(Path path){
        path=path.toAbsolutePath().normalize();var value=apiFingerprints.get(path);
        return value==null?diagnosticStore.apiFingerprint(path):value;
    }
    /** Import a detached API identity from another isolated module actor. */
    public void resolvedApi(Path path,String fingerprint){if(fingerprint!=null)resolveApiChange(path.toAbsolutePath().normalize(),fingerprint);}
    public Set<Path> pendingPrerequisites(Path file){return conditionalByFile.getOrDefault(file.toAbsolutePath().normalize(),Set.of());}
    public void changed(Path path,String hash){
        path=path.toAbsolutePath().normalize();conditionallyInvalidate(path,dependencies.changed(path,hash));
    }
    public void changed(Path path){
        path=path.toAbsolutePath().normalize();conditionallyInvalidate(path,dependencies.changed(path));
        // An unresolved lookup has no declaration edge; an API change will invalidate unresolved diagnostic states after attribution.
        focused.entrySet().removeIf(e->e.getValue().result().diagnostics().stream().anyMatch(d->d.kind().equals("ERROR")));
        outlines.entrySet().removeIf(e->Json.MAPPER.valueToTree(e.getValue().result()).path("diagnostics").findValuesAsText("kind").contains("ERROR"));
    }
    public void namespaceChanged(){diagnosticStore.clear();for(var caches:modules.values()){caches.outlines.clear();caches.focused.clear();caches.completion=null;}apiFingerprints.clear();pendingApi.clear();conditionalByFile.clear();for(var pool:compilerPools.values())pool.recycle();}
    public CompilerPool.Outcome<Bindings.Snapshot> bindings(Path path,String text,Integer cursor)throws Exception{
        path=path.toAbsolutePath().normalize();reconcileSemanticRevision(path);touch(path,text);
        String hash=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),stamp=classpathStamp();
        for(var entry:new ArrayList<>(focused.entrySet())){
            var cached=entry.getValue();
            if(cached.file().equals(path)&&cached.hash().equals(hash)&&cached.stamp().equals(stamp)
                    &&(cursor==null?entry.getKey().endsWith(":full"):cursor>=cached.start()&&cursor<cached.end()&&cached.excluded().stream().noneMatch(span->cursor>=span.start()&&cursor<span.end()))){
                focused.get(entry.getKey());cacheHits++;return cached.result();
            }
        }
        var focus=cursor==null?null:focusing.focus(path,text,cursor);
        String source=focus==null?text:focus.source();Path file=path;bindingComputations++;
        var inputHashes=cursor==null?sourceIdentities():Map.<Path,String>of();
        var outcome=compiler.query(path,source,2,(task,units,tier)->Bindings.capture(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),file,sourceText(file,text),true,focus==null?null:focus.member().equals("declarations")?new Focusing.Span(cursor,cursor+1):new Focusing.Span(focus.start(),focus.end())));
        if(cursor==null&&!inputHashes.equals(sourceIdentities()))return new CompilerPool.Outcome<>(outcome.tier(),null,List.of(),List.of("diagnostics_superseded: source changed during analysis"));
        diagnosticStore.inputHashes(inputHashes);
        if(outcome.result()!=null&&outcome.warnings().isEmpty()){
            dependencies.record(path,outcome.result().dependencies());
            if(cursor==null){resolveApiChange(path,ApiFingerprint.of(outcome.result(),path));publishSource(path,hash,stamp,outcome.result(),outcome.tier());}
            String member=focus==null?"full":focus.member();focused.put(path+":"+hash+":"+stamp+":"+member,new Cached(path,hash,stamp,focus==null?0:focus.member().equals("declarations")?cursor:focus.start(),focus==null?text.length():focus.member().equals("declarations")?cursor+1:focus.end(),focus==null?List.of():focus.replaced(),outcome));
            while(focused.size()>32)focused.remove(focused.keySet().iterator().next());
        }return outcome;
    }
    private void publishSource(Path file,String hash,String stamp,Bindings.Snapshot snapshot,int tier)throws Exception{
        if(index==null)return;
        long started=System.nanoTime();
        var symbols=List.copyOf(snapshot.symbols().values());
        var edges=snapshot.edges().stream().map(e->new IndexService.SourceEdge(e.src(),e.dst(),e.kind())).toList();
        String api=apiFingerprints.get(file);
        String semantic=Hashing.sha256((hash+":"+api+":"+stamp).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var exports=new LinkedHashSet<String>();
        String source=file.toAbsolutePath().normalize().toString();
        for(var symbol:symbols){
            if(!source.equals(Objects.toString(symbol.get("source_file"),"")))continue;
            String kind=Objects.toString(symbol.get("kind"),"");
            if(kind.equals("local")||kind.equals("parameter"))continue;
            if(symbol.get("modifiers") instanceof Collection<?> modifiers&&modifiers.contains("private"))continue;
            for(String field:List.of("scip","fqn","binary_key","name_path")){
                String value=Objects.toString(symbol.get(field),"");if(!value.isBlank())exports.add(value);
            }
        }
        var known=new HashSet<String>(snapshot.symbols().keySet());
        for(var symbol:symbols)for(String field:List.of("scip","binary_key","fqn")){
            String value=Objects.toString(symbol.get(field),"");if(!value.isBlank())known.add(value);
        }
        var unresolved=new LinkedHashSet<String>();
        for(var edge:snapshot.edges())if(!known.contains(edge.dst()))unresolved.add(edge.dst());
        long bytes=512L+2L*Json.MAPPER.writeValueAsBytes(symbols).length+edges.size()*192L;
        index.publishSource(new SourceIndexPublisher.Delta(file,hash,semantic,symbols,tier,edges,bytes,
                context.gav(),api,stamp,snapshot.dependencies(),Set.copyOf(exports),Set.copyOf(unresolved)));
        indexWriteNanos+=System.nanoTime()-started;
    }
    public Envelope atPosition(Path path,String text,int line,int character)throws Exception{
        int offset=sourceText(path,text).offset(line,character);
        var outcome=bindings(path,text,offset);var symbol=outcome.result()==null?null:outcome.result().at(offset);
        return new Envelope(outcome.tier(),"live",false,null,warnings(outcome.warnings()),symbol==null?Map.of("resolved",false,"candidates",List.of()):symbol);
    }
    /** Validate identity before reading source text: warm diagnostics need no source bytes. */
    public Envelope cachedDiagnostics(Path path,Documents documents)throws Exception{
        path=path.toAbsolutePath().normalize();String hash=documents.sourceHash(path);
        reconcileSemanticRevision(path);touchHash(path,hash);invalidateConditionalIfUnresolved(path);
        var cached=restoreDiagnostics(path,hash,classpathStamp());
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
        if(!apiFingerprints.containsKey(path)||pendingApi.containsKey(path)){
            dependencies.record(path,state.dependencies());
            resolveApiChange(path,state.apiFingerprint());
        }
        return state.diagnostics();
    }
    public Envelope diagnostics(Path path,Documents documents)throws Exception{
        var cached=cachedDiagnostics(path,documents);
        return cached==null?diagnostics(path,documents.text(path)):cached;
    }
    /** One javac task, followed by per-file detached states; no compiler objects escape. */
    public Map<Path,CompilerPool.Outcome<Bindings.Snapshot>> bindingsBatch(Map<Path,String> sources)throws Exception{
        if(sources.isEmpty())return Map.of();
        var inputs=new ArrayList<CompilerPool.SourceInput>();
        for(var entry:sources.entrySet()){reconcileSemanticRevision(entry.getKey());touch(entry.getKey(),entry.getValue());inputs.add(new CompilerPool.SourceInput(entry.getKey(),entry.getValue()));}
        String stamp=classpathStamp();var inputHashes=sourceIdentities();
        var result=compiler.batchQuery(inputs,2,(task,units,tier)->{
            var snapshots=new LinkedHashMap<Path,Bindings.Snapshot>();
            for(var unit:units){
                Path file=Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();String text=sources.get(file);
                if(text!=null)snapshots.put(file,Bindings.capture(task,List.of(unit),new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),file,new SourceText(text),true));
            }
            return snapshots;
        });
        bindingComputations+=sources.size();
        if(!inputHashes.equals(sourceIdentities())){
            var superseded=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();for(Path file:sources.keySet())superseded.put(file,new CompilerPool.Outcome<>(1,null,List.of(),List.of("diagnostics_superseded: source changed during analysis")));return superseded;
        }
        diagnosticStore.inputHashes(inputHashes);
        var values=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
        // Resolve every API first: invalidation from a later file must not erase an earlier fresh result.
        if(result.result()!=null&&result.warnings().isEmpty())for(var entry:result.result().entrySet()){
            dependencies.record(entry.getKey(),entry.getValue().dependencies());resolveApiChange(entry.getKey(),ApiFingerprint.of(entry.getValue(),entry.getKey()));
        }
        for(var input:inputs){
            Path file=input.file();String hash=Hashing.sha256(input.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var problems=result.diagnostics().stream().filter(p->sameFile(p.file(),file)).toList();
            var envelope=new Envelope(result.tier(),"live",false,null,warnings(result.warnings()),Map.of("diagnostics",problems));
            var snapshot=result.result()==null?null:result.result().get(file);
            var outcome=new CompilerPool.Outcome<>(result.tier(),snapshot,problems,result.warnings());values.put(file,outcome);
            if(snapshot!=null&&result.warnings().isEmpty()){
                focused.put(file+":"+hash+":"+stamp+":full",new Cached(file,hash,stamp,0,input.text().length(),List.of(),outcome));
                while(focused.size()>32)focused.remove(focused.keySet().iterator().next());
                diagnosticStore.put(file,hash,context.generation(),stamp,envelope,apiFingerprints.get(file),snapshot.dependencies());
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
        path=path.toAbsolutePath().normalize();reconcileSemanticRevision(path);touch(path,text);invalidateConditionalIfUnresolved(path);
        String sourceHash=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),stamp=classpathStamp(),generation=context.generation();
        var cached=restoreDiagnostics(path,sourceHash,stamp);
        if(cached!=null){diagnosticFilesReused++;return cached;}
        long computations=bindingComputations;
        var outcome=bindings(path,text,null);
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
        if(outcome.warnings().isEmpty())diagnosticStore.put(path,sourceHash,generation,stamp,envelope,apiFingerprints.get(path),outcome.result()==null?Set.of():outcome.result().dependencies());
        return envelope;
    }
    public Envelope completion(Path path,String text,int line,int character,int limit,int offset)throws Exception{
        path=path.toAbsolutePath().normalize();
        int cursor=Documents.offset(text,new Documents.Position(line,character)),start=cursor,end=cursor;
        while(start>0&&Character.isJavaIdentifierPart(text.codePointBefore(start)))start-=Character.charCount(text.codePointBefore(start));
        while(end<text.length()&&Character.isJavaIdentifierPart(text.codePointAt(end)))end+=Character.charCount(text.codePointAt(end));
        String prefix=text.substring(start,cursor),patched=text.substring(0,start)+EditorQueries.MARKER+text.substring(end);int focusCursor=start;
        touch(path,text);
        var caches=modules.get(context.generation());String key=completionKey(path,patched,start);
        var cached=caches.completion;CompilerPool.Outcome<List<Map<String,Object>>> outcome;
        if(key!=null&&cached!=null&&key.equals(cached.key())&&prefix.startsWith(cached.prefix())){
            completionCacheHits++;outcome=cached.result();
        }else{
            // Completion may discover sources with no reverse-dependency edge yet.
            // Refresh javac's disk content cache before reading those declarations.
            completionComputations++;compiler.sourcesChanged();var focus=focusing.focus(path,patched,focusCursor);
            outcome=compiler.query(path,focus.source(),2,(task,units,tier)->EditorQueries.completion(task,units,new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),prefix));
            // Keep one detached result per module. A broader prefix recomputes candidates;
            // narrowing filters the already sorted rows without retaining javac objects.
            caches.completion=key!=null&&outcome.tier()==2&&outcome.warnings().isEmpty()&&outcome.result()!=null&&outcome.result().size()<=256
                    &&Json.MAPPER.writeValueAsBytes(outcome.result()).length<=256*1024?new CompletionCached(key,prefix,new CompilerPool.Outcome<>(2,outcome.result(),List.of(),List.of())):null;
        }
        var values=outcome.result()==null?List.<Map<String,Object>>of():outcome.result().stream().filter(row->row.get("name").toString().startsWith(prefix)).toList();int from=Math.min(offset,values.size()),to=Math.min(values.size(),from+limit);
        return new Envelope(outcome.tier(),"live",to<values.size(),to<values.size()?Integer.toString(to):null,warnings(outcome.warnings()),Map.of("items",values.subList(from,to),"range",new SourceText(text).range(start,end)));
    }
    private String completionKey(Path file,String patched,int start)throws Exception{
        if(patched.length()>256*1024)return null;
        var sources=new TreeSet<Path>();
        for(Path root:context.sources()){
            if(Files.isDirectory(root))sources.addAll(FileInventory.matching(root,".java",257));
            if(sources.size()>256)return null;
        }
        documents.paths().stream().filter(path->context.sources().stream().anyMatch(path::startsWith)).forEach(sources::add);
        if(sources.size()>256)return null;
        var stamp=new StringBuilder(context.toString()).append('\0').append(file).append(':').append(start).append(':').append(Hashing.sha256(patched.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // Detached hits need content identities, not a second compiler-manager check or
        // source inventory. A miss still validates the manager immediately before javac.
        appendClasspathContents(stamp);
        // Completion can discover members with no prior reverse-dependency edge. Validate
        // every source in this bounded context, including unsaved declarations and new names.
        for(Path source:sources)if(!source.equals(file)){
            stamp.append('\0').append(source).append(':').append(documents.sourceHash(source));
        }
        return Hashing.sha256(stamp.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public Envelope signatureHelp(Path path,String text,int line,int character)throws Exception{
        int cursor=Documents.offset(text,new Documents.Position(line,character));touch(path,text);var focus=focusing.focus(path,text,cursor);
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
        var result=new LinkedHashMap<String,Object>(compiler.status());if(snapshots!=null)result.put("persistent_snapshots",snapshots.status());result.putAll(focusing.status());result.put("outline_cache_entries",outlines.size());result.put("configured",context!=null);result.put("binding_cache_entries",focused.size());result.put("binding_cache_hits",cacheHits);result.put("binding_computations",bindingComputations);result.put("classpath_fingerprints",classpathFingerprints);result.put("diagnostic_store",diagnosticStore.status());result.put("diagnostic_files_analysed",diagnosticFilesAnalysed);result.put("diagnostic_files_reused",diagnosticFilesReused);result.put("index_record_source_calls",indexWrites);result.put("index_record_source_ms",0.0);result.put("index_publish_enqueue_ms",Math.round(indexWriteNanos/1000.0)/1000.0);if(index!=null)result.put("source_publisher",index.sourcePublisherStatus());result.put("api_fingerprint_changes",apiFingerprintChanges);result.put("api_fingerprint_unchanged",apiFingerprintUnchanged);result.put("pending_api_files",pendingApi.size());result.put("conditional_files",conditionalByFile.size());result.put("dependencies",dependencies.status());
        result.put("completion_cache_hits",completionCacheHits);result.put("completion_computations",completionComputations);
        var modules=new LinkedHashMap<String,Object>();for(var entry:compilerPools.entrySet())modules.put(entry.getKey(),entry.getValue().status());result.put("module_compilers",modules);return result;
    }
    @Override public void close()throws Exception{if(snapshots!=null)snapshots.close();diagnosticStore.clear();outlines.clear();focused.clear();focusing.close();sourceTexts.clear();apiFingerprints.clear();pendingApi.clear();conditionalByFile.clear();for(var pool:compilerPools.values())pool.close();compilerPools.clear();modules.clear();compiler=null;}
}
