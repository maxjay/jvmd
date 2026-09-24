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
    private static final class AccessibilityCache {
        private static final int MAX_ENTRIES=16,MAX_MEMBER_IDS=32768;
        private final LinkedHashMap<String,Set<String>> entries=new LinkedHashMap<>(16,.75f,true);
        private long memberIds,hits,misses,evictions;
        void put(String key,Set<String> members){
            if(key==null||key.isBlank())return;
            var copy=Set.copyOf(members);var old=entries.remove(key);
            if(old!=null)memberIds-=old.size();
            entries.put(key,copy);memberIds+=copy.size();
            while((entries.size()>MAX_ENTRIES||memberIds>MAX_MEMBER_IDS)&&entries.size()>1){
                var first=entries.entrySet().iterator().next();entries.remove(first.getKey());memberIds-=first.getValue().size();evictions++;
            }
        }
        Set<String> get(String key){
            var value=entries.get(key);
            if(value==null)misses++;else hits++;
            return value;
        }
        boolean contains(String key){return key!=null&&!key.isBlank()&&entries.containsKey(key);}
        void clear(){entries.clear();memberIds=0;}
        Map<String,Object> status(){return Map.of("entries",entries.size(),"member_ids",memberIds,"hits",hits,"misses",misses,"evictions",evictions,
                "max_entries",MAX_ENTRIES,"max_member_ids",MAX_MEMBER_IDS);}
    }
    private static final class ModuleCaches {
        final LinkedHashMap<String,Envelope> outlines=new LinkedHashMap<>(16,.75f,true);
        final LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
        final Set<Path> files=new HashSet<>();
        final ResidentSemanticState semantic=new ResidentSemanticState();
        final Map<Path,DocumentSemanticCached> documentSemantics=new HashMap<>();
        final LinkedHashMap<String,SymbolDescription> descriptions=new LinkedHashMap<>(16,.75f,true);
        final AccessibilityCache accessibility=new AccessibilityCache();
        long semanticSourceEpoch=-1;
        String completionContextIdentity="";
    }
    private static final int MAX_GENERATION_FAMILIES=2;
    private final Map<String,ModuleCaches> modules=new LinkedHashMap<>();
    private final Map<String,String> generationFamilies=new HashMap<>();
    private final LinkedHashMap<String,Boolean> generationFamilyLru=new LinkedHashMap<>(4,.75f,true);
    private long retiredModuleGenerations,retiredCompilerGenerations,retiredSemanticBytes,semanticBudgetBytes;
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
    private record DocumentSemanticCached(String key,DocumentSemanticSnapshot snapshot,Map<String,String> resolutionIdentities,
                                          Map<Path,String> dependencyApis,String hierarchyApi) {
        DocumentSemanticCached { resolutionIdentities=Map.copyOf(resolutionIdentities);dependencyApis=Map.copyOf(dependencyApis);hierarchyApi=Objects.requireNonNullElse(hierarchyApi,""); }
    }
    private record Outline(List<Map<String,Object>> symbols,Set<Path> dependencies) { }
    private static final class CompletionAdvanceFailure extends Exception {
        final List<String> warnings;
        CompletionAdvanceFailure(List<String> warnings){super(String.join("; ",warnings));this.warnings=List.copyOf(warnings);}
    }
    private LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
    private final Dependencies dependencies=new Dependencies();
    private final LinkedHashMap<String,SourceText> sourceTexts=new LinkedHashMap<>(16,.75f,true);
    private long cacheHits,bindingComputations,diagnosticFilesAnalysed,diagnosticFilesReused,indexWrites,indexWriteNanos,apiFingerprintChanges,apiFingerprintUnchanged;
    private long completionRequests,residentDescriptionLoads,residentDescriptionCacheHits,residentHierarchyUnitReuses,residentHierarchyUnitBuilds;
    private List<String> residentHierarchyLastBuiltUnits=List.of();
    private long residentDeclarationFactReuses,residentDeclarationFactBuilds;
    private Context context;
    private IndexService index;
    private LiveSourceState liveSourceState;
    private DiagnosticSnapshots snapshots;
    public void persistence(Path directory){if(snapshots==null){snapshots=new DiagnosticSnapshots(directory);diagnosticStore.persistence(snapshots);snapshots.documents(documents);}}
    private long budget;
    public void configure(Context context,IndexService index,long budget)throws Exception{
        String family=generationFamily(context);generationFamilies.put(context.generation(),family);generationFamilyLru.put(family,Boolean.TRUE);
        var caches=modules.computeIfAbsent(context.generation(),_->new ModuleCaches());
        outlines=caches.outlines;focused=caches.focused;
        this.context=context;this.index=index;this.budget=budget;semanticBudgetBytes=Math.max(8L*1024*1024,budget/2);
        diagnosticStore.budget(Math.max(1024*1024,budget/8));
        String newCompletionContextIdentity=CompilerInputs.compose("completion-context-v1",
                context.gav(),context.release(),context.generation(),
                context.classpath().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.sources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.compilerOptions(),
                context.binarySources().stream().map(p->p.toAbsolutePath().normalize().toString()).sorted().toList(),
                new TreeMap<>(context.coordinates()),
                context.navigationSources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.preciseSourceRoots());
        if(!newCompletionContextIdentity.equals(caches.completionContextIdentity)){
            caches.documentSemantics.clear();caches.descriptions.clear();caches.accessibility.clear();caches.semantic.clear();caches.semanticSourceEpoch=-1;
        }
        caches.completionContextIdentity=newCompletionContextIdentity;
        compiler=compilerPools.computeIfAbsent(context.generation(),_->new CompilerPool(inputFiles));
        compiler.configure(context.generation(),context.release(),context.classpath(),context.sources(),index,budget,context.compilerOptions(),context.preciseSourceRoots());
        compiler.binarySources(context.binarySources());
        retireOldGenerationFamilies(family);
    }

    private static String generationFamily(Context context){
        String marker=":"+context.gav();int split=context.generation().indexOf(marker);
        return split>0?context.generation().substring(0,split):context.generation();
    }
    private long retainedSemanticBytes(){return modules.values().stream().mapToLong(caches->caches.semantic.estimatedBytes()).sum();}
    private void retireOldGenerationFamilies(String currentFamily)throws Exception{
        while(true){
            long retained=retainedSemanticBytes();
            boolean tooMany=generationFamilyLru.size()>MAX_GENERATION_FAMILIES;
            boolean overBudget=retained>semanticBudgetBytes;
            if(!tooMany&&!overBudget)return;
            String victim=null;
            for(String family:generationFamilyLru.keySet())if(!family.equals(currentFamily)){victim=family;break;}
            if(victim==null)return;
            retireGenerationFamily(victim);
        }
    }
    private void retireGenerationFamily(String family)throws Exception{
        var generations=new ArrayList<String>();
        for(var entry:generationFamilies.entrySet())if(entry.getValue().equals(family))generations.add(entry.getKey());
        Exception failure=null;
        for(String generation:generations){
            var caches=modules.remove(generation);
            if(caches!=null){retiredSemanticBytes+=caches.semantic.estimatedBytes();retiredModuleGenerations++;}
            var pool=compilerPools.remove(generation);
            if(pool!=null)try{pool.close();retiredCompilerGenerations++;}catch(Exception e){failure=e;}
            generationFamilies.remove(generation);
        }
        generationFamilyLru.remove(family);
        if(failure!=null)throw failure;
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
        compiler.observeSources(seen);observeResidentSourceTransitions();
    }
    private void observeResidentSourceTransitions(){
        if(liveSourceState==null||context==null)return;
        var caches=modules.get(context.generation());long current=liveSourceState.snapshot().inputEpoch();
        if(caches.semanticSourceEpoch<0){caches.semanticSourceEpoch=current;return;}
        if(current==caches.semanticSourceEpoch)return;
        var changed=liveSourceState.changedPathsSince(caches.semanticSourceEpoch);
        if(changed.isEmpty()){caches.semantic.markHierarchyUncertain();caches.documentSemantics.clear();}
        else for(Path file:changed.get()){
            if(file.getFileName()!=null&&file.getFileName().toString().equals("module-info.java"))caches.documentSemantics.clear();
            String content=liveSourceState.contentHash(file);
            if(content==null)caches.semantic.removeUnit("source:"+file.toAbsolutePath().normalize());
            else caches.semantic.markSourceStale(file.toAbsolutePath().normalize().toString(),content);
        }
        caches.semanticSourceEpoch=current;
    }
    private String classpathStamp()throws Exception{return computeClasspathStamp();}
    private CompilerInputs.Snapshot validatedInputs()throws Exception {
        var inputs=inputSnapshot();
        if(!compiler.cacheValid(inputs)){
            outlines.clear();focused.clear();
            var caches=modules.get(context.generation());
            caches.documentSemantics.clear();caches.accessibility.clear();
            // A classpath/environment replacement invalidates detached binary/JDK declaration
            // currency even when the source membership is unchanged. Preserve facts for reuse, but
            // fence every retained unit until javac re-admits it from the new environment.
            caches.semantic.markHierarchyUncertain();
            inputs=inputSnapshot();
        }
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
        path=path.toAbsolutePath().normalize();compiler.observeSources(Set.of(path));observeResidentSourceTransitions();conditionallyInvalidate(path,dependencies.changed(path,hash));
    }
    public void changed(Path path){
        path=path.toAbsolutePath().normalize();compiler.observeSources(Set.of(path));observeResidentSourceTransitions();conditionallyInvalidate(path,dependencies.changed(path));
        // An unresolved lookup has no declaration edge; an API change will invalidate unresolved diagnostic states after attribution.
        focused.entrySet().removeIf(e->e.getValue().result().diagnostics().stream().anyMatch(d->d.kind().equals("ERROR")));
        outlines.entrySet().removeIf(e->Json.MAPPER.valueToTree(e.getValue().result()).path("diagnostics").findValuesAsText("kind").contains("ERROR"));
    }
    /**
     * A source appears or disappears from the editor overlay. Preserve already-admitted semantic
     * facts and reset only this module's javac source namespace. Negative resolutions have no
     * declaration edge, so invalidate the unresolved-name posting set and its reverse closure.
     */
    public void sourceMembershipChanged(Path path)throws Exception{
        path=path.toAbsolutePath().normalize();
        compiler.documents(documents);compiler.observeSources(Set.of(path));
        var affected=new LinkedHashSet<Path>(SemanticUpdatePolicy.closure(dependencies.semantic().unresolved(),dependencies.semantic()));
        boolean present=documents.contains(path)||Files.isRegularFile(path);
        if(!present){
            var removed=dependencies.semantic().remove(path);affected.addAll(removed.reanalyze());
            semanticState().removeUnit("source:"+path);
        }
        if(!affected.isEmpty())invalidate(affected);
        compiler.resetSourceContext();
    }
    /** Broad project-model namespace reset; editor source membership must use sourceMembershipChanged. */
    public void namespaceChanged(){diagnosticStore.clear();for(var caches:modules.values()){caches.outlines.clear();caches.focused.clear();caches.documentSemantics.clear();caches.descriptions.clear();caches.accessibility.clear();caches.semantic.clear();caches.semanticSourceEpoch=-1;}dependencies.semantic().clear();for(var pool:compilerPools.values())pool.recycle();}
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
            var captured=Bindings.capture(task,units,identity,file,sourceText(file,text),true,
                    focus==null?null:focus.member().equals("declarations")?new Focusing.Span(cursor,cursor+1):new Focusing.Span(focus.start(),focus.end()),
                    semanticState()::symbol);
            if(tier==2&&focus==null)for(var unit:units)try{
                if(Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize().equals(file)){semantic[0]=SemanticFacts.sourceSnapshot(unit,captured.semanticFacts().values());break;}
            }catch(Exception ignored){}
            return captured;
        });
        diagnosticStore.inputs(observed);
        if(outcome.result()!=null&&outcome.warnings().isEmpty()){
            residentDeclarationFactReuses+=outcome.result().semanticFactReuses();
            residentDeclarationFactBuilds+=outcome.result().semanticFactBuilds();
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
                    var captured=Bindings.capture(task,List.of(unit),identity,file,new SourceText(text),true,null,semanticState()::symbol);snapshots.put(file,captured);
                    if(tier==2)semanticSnapshots.put(file,SemanticFacts.sourceSnapshot(unit,captured.semanticFacts().values()));
                }
            }
            return snapshots;
        });
        bindingComputations+=sources.size();
        if(result.result()!=null&&result.warnings().isEmpty())for(var snapshot:result.result().values()){
            residentDeclarationFactReuses+=snapshot.semanticFactReuses();
            residentDeclarationFactBuilds+=snapshot.semanticFactBuilds();
        }
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
    private String residentContextKey(Path file,String patched,int start,CompilerInputs.Snapshot inputs,boolean qualified){
        if(patched.length()>256*1024)return null;
        String patchedHash=Hashing.sha256(patched.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String namespace=qualified?"qualified":inputs.live().snapshot().state().namespace().fingerprint().value();
        return CompilerInputs.compose(qualified?"resident-qualified-v2":"resident-unqualified-v2",
                modules.get(context.generation()).completionContextIdentity,file.toString(),start,patchedHash,inputs.environment().value(),namespace);
    }

    private boolean residentSemanticUnitCurrent(String unit){
        if(unit==null||unit.isBlank())return false;
        if(unit.startsWith("source:")){
            if(liveSourceState==null)return false;
            try{
                Path file=Path.of(unit.substring("source:".length())).toAbsolutePath().normalize();
                if(!liveSourceState.accepts(file))return false;
                String current=liveSourceState.contentHash(file);
                return current!=null&&semanticState().unitCurrent(unit,current);
            }catch(Exception ignored){return false;}
        }
        return semanticState().unitCurrent(unit,null);
    }
    private boolean residentSemanticTypeCurrent(String typeId,String sourceFile){
        if(residentSemanticUnitCurrent(semanticState().unitForFact(typeId)))return true;
        if(sourceFile!=null&&!sourceFile.isBlank()&&residentSemanticUnitCurrent("source:"+sourceFile))return true;
        return typeId!=null&&!typeId.isBlank()&&residentSemanticUnitCurrent("type:"+typeId);
    }

    private String queryHierarchyApi(DocumentSemanticSnapshot.QueryContext query){
        var identities=new ArrayList<String>();var queue=new ArrayDeque<SemanticType>();addDeclaredTypes(queue,query.receiverType());var seen=new HashSet<String>();
        while(!queue.isEmpty()){
            var type=queue.removeFirst();if(!(type instanceof SemanticType.Declared declared)||!seen.add(declared.symbolId()))continue;
            identities.add(semanticState().hierarchyApi(declared.symbolId()));
        }
        if(identities.isEmpty())return "";
        identities.sort(String::compareTo);return Hashing.sha256(String.join("\n",identities).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private String accessibilityKey(DocumentSemanticSnapshot.QueryContext query){
        var caches=modules.get(context.generation());
        return CompilerInputs.compose("completion-access-v1",caches.completionContextIdentity,
                query.receiverType().identity().hex(),Objects.toString(query.receiverSymbolId(),""),
                query.staticReceiver(),query.packageName(),Objects.toString(query.enclosingTypeId(),""),
                query.staticContext(),queryHierarchyApi(query));
    }
    private DocumentSemanticSnapshot.QueryContext registerAccessibility(ModuleCaches caches,SemanticFacts.CompletionContext result){
        String key=accessibilityKey(result.query());caches.accessibility.put(key,result.accessibleMemberIds());
        return result.query().withAccessibilityKey(key);
    }
    private boolean accessibilityCurrent(DocumentSemanticSnapshot.QueryContext query){
        return modules.get(context.generation()).accessibility.contains(query.accessibilityKey());
    }
    private Set<String> accessibility(DocumentSemanticSnapshot.QueryContext query){
        return modules.get(context.generation()).accessibility.get(query.accessibilityKey());
    }

    private boolean cachedHierarchyCurrent(DocumentSemanticCached cached,DocumentSemanticSnapshot.QueryContext query)throws Exception{
        if(!context.preciseSourceRoots()||liveSourceState==null||!liveSourceState.snapshot().trusted()){
            if(!ensureHierarchySemanticCurrent(query))return false;
        }
        return Objects.equals(cached.hierarchyApi(),queryHierarchyApi(query));
    }

    private DocumentSemanticCached qualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                               String key,CompilerInputs.Snapshot observed,boolean force)throws Exception{
        return qualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,observed,force,false,0);
    }
    private DocumentSemanticCached qualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                               String key,CompilerInputs.Snapshot observed,boolean force,boolean discovered,int supersededRetries)throws Exception{
        var caches=modules.get(context.generation());int version=Objects.requireNonNullElse(documents.version(path),-1);
        String content=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var cached=caches.documentSemantics.get(path);
        if(!force&&key!=null&&cached!=null&&key.equals(cached.key())&&cached.snapshot().query(start)!=null){
            var query=cached.snapshot().query(start);var current=completionResolutionIdentities(cached.resolutionIdentities().keySet());
            if(current.equals(cached.resolutionIdentities())&&accessibilityCurrent(query)&&cachedHierarchyCurrent(cached,query)){
                var rebased=new DocumentSemanticSnapshot(path.toString(),version,content,semanticState().identity().epoch(),
                        cached.snapshot().queries());
                var reused=new DocumentSemanticCached(key,rebased,current,cached.dependencyApis(),cached.hierarchyApi());caches.documentSemantics.put(path,reused);return reused;
            }
        }
        var focus=focusing.focus(path,patched,focusCursor);
        var attributed=compiler.query(path,focus.source(),2,observed,(task,units,tier)->{
            if(tier!=2)return null;
            return SemanticFacts.qualifiedCompletion(task,units,
                    new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),
                    EditorQueries.MARKER,start,this::residentSemanticTypeCurrent);
        });
        var result=attributed.result();
        if(!attributed.warnings().isEmpty()){
            boolean superseded=attributed.warnings().stream().allMatch(w->w.startsWith("diagnostics_superseded"));
            if(superseded&&supersededRetries<1)
                return qualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,inputSnapshot(),true,discovered,supersededRetries+1);
            throw new CompletionAdvanceFailure(attributed.warnings());
        }
        if(attributed.tier()!=2)return null;
        if(result==null&&!discovered){
            compiler.discoverSourcePackages(completionDiscoveryPackages(text));compiler.resetSourceContext();
            return qualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,inputSnapshot(),true,true,supersededRetries);
        }
        if(result==null)return null;
        residentHierarchyUnitReuses+=result.hierarchyUnitsReused();residentHierarchyUnitBuilds+=result.semanticSnapshots().size();
        residentHierarchyLastBuiltUnits=result.semanticSnapshots().stream().map(SemanticSnapshot::unit).toList();
        for(var snapshot:result.semanticSnapshots())admitDetachedSemantic(snapshot);
        var query=registerAccessibility(caches,result);
        var binaries=completionNameResolutionBinaries(text,result.nameResolutionNames());
        var resolution=completionResolutionIdentities(binaries);
        var snapshot=new DocumentSemanticSnapshot(path.toString(),version,content,semanticState().identity().epoch(),
                Map.of(start,query));
        var next=new DocumentSemanticCached(key,snapshot,resolution,Map.of(),queryHierarchyApi(query));caches.documentSemantics.put(path,next);return next;
    }

    private Map<Path,String> documentDependencyApis(DocumentSemanticSnapshot.QueryContext query,Path caller)throws Exception{
        var dependenciesToCheck=new LinkedHashSet<Path>();
        for(var candidate:query.scopedCandidates())if(candidate.sourceFile()!=null)try{
            Path source=Path.of(candidate.sourceFile()).toAbsolutePath().normalize();
            if(!source.equals(caller)&&liveSourceState!=null&&liveSourceState.accepts(source))dependenciesToCheck.add(source);
        }catch(Exception ignored){}
        if(dependenciesToCheck.isEmpty())return Map.of();
        if(!ensureCompletionSemantics(dependenciesToCheck))return null;
        return completionApiFingerprints(dependenciesToCheck);
    }

    private boolean documentDependenciesCurrent(DocumentSemanticCached cached)throws Exception{
        if(cached.dependencyApis().isEmpty())return true;
        if(!ensureCompletionSemantics(cached.dependencyApis().keySet()))return false;
        return completionApiFingerprints(cached.dependencyApis().keySet()).equals(cached.dependencyApis());
    }

    private DocumentSemanticCached unqualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                                 String key,CompilerInputs.Snapshot observed,boolean force)throws Exception{
        return unqualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,observed,force,false,0);
    }
    private DocumentSemanticCached unqualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                                 String key,CompilerInputs.Snapshot observed,boolean force,boolean discovered,int supersededRetries)throws Exception{
        var caches=modules.get(context.generation());int version=Objects.requireNonNullElse(documents.version(path),-1);
        String content=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var cached=caches.documentSemantics.get(path);
        if(!force&&key!=null&&cached!=null&&key.equals(cached.key())&&cached.snapshot().query(start)!=null
                &&accessibilityCurrent(cached.snapshot().query(start))
                &&documentDependenciesCurrent(cached)&&cachedHierarchyCurrent(cached,cached.snapshot().query(start))){
            var rebased=new DocumentSemanticSnapshot(path.toString(),version,content,semanticState().identity().epoch(),
                    cached.snapshot().queries());
            var reused=new DocumentSemanticCached(key,rebased,Map.of(),cached.dependencyApis(),cached.hierarchyApi());caches.documentSemantics.put(path,reused);return reused;
        }
        var focus=focusing.focus(path,patched,focusCursor);
        var attributed=compiler.query(path,focus.source(),2,observed,(task,units,tier)->{
            if(tier!=2)return null;
            return SemanticFacts.unqualifiedCompletion(task,units,
                    new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources()),
                    EditorQueries.MARKER,start,this::residentSemanticTypeCurrent);
        });
        var result=attributed.result();
        if(!attributed.warnings().isEmpty()){
            boolean superseded=attributed.warnings().stream().allMatch(w->w.startsWith("diagnostics_superseded"));
            if(superseded&&supersededRetries<1)
                return unqualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,inputSnapshot(),true,discovered,supersededRetries+1);
            throw new CompletionAdvanceFailure(attributed.warnings());
        }
        if(attributed.tier()!=2)return null;
        if(result==null&&!discovered){
            compiler.discoverSourcePackages(completionDiscoveryPackages(text));compiler.resetSourceContext();
            return unqualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,inputSnapshot(),true,true,supersededRetries);
        }
        if(result==null)return null;
        residentHierarchyUnitReuses+=result.hierarchyUnitsReused();residentHierarchyUnitBuilds+=result.semanticSnapshots().size();
        residentHierarchyLastBuiltUnits=result.semanticSnapshots().stream().map(SemanticSnapshot::unit).toList();
        for(var snapshot:result.semanticSnapshots())admitDetachedSemantic(snapshot);
        var query=registerAccessibility(caches,result);
        var dependencyApis=documentDependencyApis(query,path);if(dependencyApis==null)return null;
        var snapshot=new DocumentSemanticSnapshot(path.toString(),version,content,semanticState().identity().epoch(),
                Map.of(start,query));
        var next=new DocumentSemanticCached(key,snapshot,Map.of(),dependencyApis,queryHierarchyApi(query));caches.documentSemantics.put(path,next);return next;
    }

    private Envelope residentQualifiedCompletion(Path path,String text,String patched,int start,int end,int focusCursor,String prefix,
                                                  int limit,int offset,String key,CompilerInputs.Snapshot observed)throws Exception{
        if(key==null)return null;
        var cached=qualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,observed,false);if(cached==null)return null;
        var query=cached.snapshot().query(start);if(query==null)return null;
        if(!(query.receiverType() instanceof SemanticType.Declared||query.receiverType() instanceof SemanticType.Intersection))return null;
        int target=(int)Math.min(Integer.MAX_VALUE,(long)offset+limit+1L);
        var rows=residentQualifiedRows(query,prefix,target);int from=Math.min(offset,rows.size()),to=Math.min(rows.size(),from+limit);
        var returned=List.copyOf(rows.subList(from,to));boolean more=rows.size()>to;
        completionRequests++;
        try(var trace=dev.jvmd.core.RequestScope.stage("completion.resident")){
            trace.cache("resident");trace.count("rows_returned",returned.size());
        }
        return new Envelope(2,"live",more,more?Integer.toString(to):null,List.of(),
                Map.of("items",returned,"range",new SourceText(text).range(start,end)));
    }

    private Envelope residentUnqualifiedCompletion(Path path,String text,String patched,int start,int end,int focusCursor,String prefix,
                                                    int limit,int offset,String key,CompilerInputs.Snapshot observed)throws Exception{
        if(key==null)return null;
        var cached=unqualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,observed,false);if(cached==null)return null;
        var query=cached.snapshot().query(start);if(query==null)return null;
        int target=(int)Math.min(Integer.MAX_VALUE,(long)offset+limit+1L);
        var rows=residentUnqualifiedRows(query,prefix,target);int from=Math.min(offset,rows.size()),to=Math.min(rows.size(),from+limit);
        var returned=List.copyOf(rows.subList(from,to));boolean more=rows.size()>to;
        completionRequests++;
        try(var trace=dev.jvmd.core.RequestScope.stage("completion.resident")){
            trace.cache("resident-scope");trace.count("rows_returned",returned.size());
        }
        return new Envelope(2,"live",more,more?Integer.toString(to):null,List.of(),
                Map.of("items",returned,"range",new SourceText(text).range(start,end)));
    }

    public Envelope completion(Path path,String text,int line,int character,int limit,int offset)throws Exception{
        try(var trace=dev.jvmd.core.RequestScope.stage("completion.materialize")){
            path=path.toAbsolutePath().normalize();
            int cursor=Documents.offset(text,new Documents.Position(line,character)),start=cursor,end=cursor;
            while(start>0&&Character.isJavaIdentifierPart(text.codePointBefore(start)))start-=Character.charCount(text.codePointBefore(start));
            while(end<text.length()&&Character.isJavaIdentifierPart(text.codePointAt(end)))end+=Character.charCount(text.codePointAt(end));
            String prefix=text.substring(start,cursor),patched=text.substring(0,start)+EditorQueries.MARKER+text.substring(end);int focusCursor=start;
            int selector=start-1;while(selector>=0&&Character.isWhitespace(text.charAt(selector)))selector--;
            boolean qualified=selector>=0&&text.charAt(selector)=='.';
            synchronizeKnownSources(path);touch(path,text);
            var observed=validatedInputs();String residentKey=residentContextKey(path,patched,start,observed,qualified);
            Envelope resident;
            try{
                resident=qualified
                        ?residentQualifiedCompletion(path,text,patched,start,end,focusCursor,prefix,limit,offset,residentKey,observed)
                        :residentUnqualifiedCompletion(path,text,patched,start,end,focusCursor,prefix,limit,offset,residentKey,observed);
            }catch(CompletionAdvanceFailure failure){
                completionRequests++;
                return new Envelope(1,"live",false,null,warnings(failure.warnings),
                        Map.of("items",List.of(),"range",new SourceText(text).range(start,end)));
            }
            if(resident!=null)return resident;

            completionRequests++;
            trace.cache("resident-unresolved");
            return new Envelope(2,"live",false,null,List.of(),
                    Map.of("items",List.of(),"range",new SourceText(text).range(start,end)));
        }
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
        if(resident!=null&&current.equals(resident.contentIdentity())&&(contribution==null||current.equals(contribution.sourceHash())))return true;
        var outcome=bindings(file,documents.text(file),null);
        resident=semanticState().unit(unit);contribution=contribution(file);
        return outcome.tier()==2&&outcome.result()!=null&&outcome.warnings().isEmpty()
                &&resident!=null&&current.equals(resident.contentIdentity())
                &&(contribution==null||current.equals(contribution.sourceHash()));
    }

    private Map<String,String> completionResolutionIdentities(Collection<String> binaries)throws Exception{
        if(binaries==null||binaries.isEmpty()||liveSourceState==null)return Map.of();
        var result=new TreeMap<String,String>();
        for(String binary:binaries){
            var source=liveSourceState.source(binary).orElse(null);
            if(source==null){result.put(binary,"<missing>");continue;}
            Path file=source.file().toAbsolutePath().normalize();
            if(!ensureSourceSemanticCurrent(file)){result.put(binary,"<missing>");continue;}
            SemanticFact declaration=semanticState().unitType("source:"+file,binary);
            String resolution=declaration==null?"<missing-declaration>":
                    declaration.kind()+"\0"+String.join(",",declaration.modifiers().stream().sorted().toList())+"\0"+Objects.toString(declaration.ownerId(),"");
            result.put(binary,file+"\0"+resolution);
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

    private Map<String,Object> residentCompletionRow(SemanticFact fact,CompletionCandidate candidate){
        return residentCompletionRow(candidate,fact.namePath());
    }
    private Map<String,Object> residentCompletionRow(CompletionCandidate candidate,String namePath){
        var value=new LinkedHashMap<String,Object>();
        value.put("scip",candidate.id());value.put("name",candidate.name());value.put("name_path",Objects.requireNonNullElse(namePath,candidate.name()));
        value.put("kind",candidate.kind());value.put("signature",candidate.structuralSignature());value.put("label",candidate.label());
        value.put("modifiers",candidate.modifiers().stream().sorted().toList());
        if(candidate.sourceFile()!=null)value.put("source_file",candidate.sourceFile());
        var parameters=new ArrayList<Map<String,Object>>();
        for(var parameter:candidate.parameters())parameters.add(Map.of("label",List.of(parameter.start(),parameter.end())));
        value.put("parameters",List.copyOf(parameters));
        return Collections.unmodifiableMap(value);
    }

    private static String inheritedMemberShape(SemanticFact fact){
        if(fact.kind().equals("method")){
            String descriptor=Objects.requireNonNullElse(fact.erasedDescriptor(),"");
            int close=descriptor.indexOf(')');
            String parameters=close>=0?descriptor.substring(0,close+1):descriptor;
            return "method\0"+fact.name()+"\0"+parameters;
        }
        if(Set.of("field","enumconst").contains(fact.kind()))return "field\0"+fact.name();
        if(fact.typeDeclaration())return "type\0"+fact.name();
        return fact.kind()+"\0"+fact.name()+"\0"+Objects.requireNonNullElse(fact.erasedDescriptor(),"");
    }

    private static final Comparator<Map<String,Object>> COMPLETION_ORDER=Comparator
            .comparing((Map<String,Object> row)->Objects.toString(row.get("name"),""))
            .thenComparing(row->Objects.toString(row.get("label"),""))
            .thenComparing(row->Objects.toString(row.get("scip"),""));
    private record HierarchyOwner(String id,Map<String,SemanticType> substitutions,int order) { }
    private record HierarchyChoice(int ownerOrder,Map<String,Object> row) { }
    private static final class HierarchyStream {
        final ResidentSemanticState.MemberCursor cursor;final Map<String,SemanticType> substitutions;final int ownerOrder;
        SemanticFact current;
        HierarchyStream(ResidentSemanticState.MemberCursor cursor,Map<String,SemanticType> substitutions,int ownerOrder){
            this.cursor=cursor;this.substitutions=substitutions;this.ownerOrder=ownerOrder;
        }
        boolean advance(){current=cursor.next();return current!=null;}
    }

    private List<HierarchyOwner> hierarchyOwners(DocumentSemanticSnapshot.QueryContext query){
        var queue=new ArrayDeque<SemanticType>();addDeclaredTypes(queue,query.receiverType());
        var seen=new HashSet<String>();var result=new ArrayList<HierarchyOwner>();
        while(!queue.isEmpty()){
            var next=queue.removeFirst();if(!(next instanceof SemanticType.Declared declared)||!seen.add(declared.symbolId()))continue;
            var owner=semanticState().symbol(declared.symbolId());if(owner==null)continue;
            var substitutions=typeSubstitutions(owner,declared);
            result.add(new HierarchyOwner(declared.symbolId(),substitutions,result.size()));
            for(var parent:owner.directSupertypes())addDeclaredTypes(queue,parent.substitute(substitutions));
        }
        return List.copyOf(result);
    }

    private List<Map<String,Object>> residentHierarchyRows(DocumentSemanticSnapshot.QueryContext query,String prefix,int target,boolean staticOnly,
                                                           Set<String> excludedIds,Set<String> shadowedFieldNames){
        if(target<=0)return List.of();
        var accessible=accessibility(query);if(accessible==null)return List.of();
        var streams=new PriorityQueue<HierarchyStream>(Comparator
                .comparing((HierarchyStream stream)->stream.current.name())
                .thenComparing(stream->stream.current.id())
                .thenComparingInt(stream->stream.ownerOrder));
        for(var owner:hierarchyOwners(query)){
            var stream=new HierarchyStream(semanticState().memberCursor(owner.id(),prefix),owner.substitutions(),owner.order());
            if(stream.advance())streams.add(stream);
        }
        var rows=new ArrayList<Map<String,Object>>(Math.min(target,64));
        while(!streams.isEmpty()&&rows.size()<target){
            String name=streams.peek().current.name();
            var choices=new HashMap<String,HierarchyChoice>();
            while(!streams.isEmpty()&&streams.peek().current.name().equals(name)){
                var stream=streams.poll();
                do{
                    var member=stream.current;
                    if(!excludedIds.contains(member.id())
                            &&!member.kind().equals("ctor")&&!member.kind().equals("package")&&!member.kind().equals("module")
                            &&(!staticOnly||member.typeDeclaration()||member.modifiers().contains("static"))
                            &&accessible.contains(member.id())
                            &&!(Set.of("field","enumconst").contains(member.kind())&&shadowedFieldNames.contains(member.name()))){
                        String shape=inheritedMemberShape(member);var row=residentCompletionRow(member,member.candidate(stream.substitutions));
                        var previous=choices.get(shape);
                        if(previous==null||stream.ownerOrder<previous.ownerOrder())choices.put(shape,new HierarchyChoice(stream.ownerOrder,row));
                    }
                }while(stream.advance()&&stream.current.name().equals(name));
                if(stream.current!=null)streams.add(stream);
            }
            var group=choices.values().stream().map(HierarchyChoice::row).sorted(COMPLETION_ORDER).toList();
            for(var row:group){if(rows.size()>=target)break;rows.add(row);}
        }
        return List.copyOf(rows);
    }
    private List<Map<String,Object>> residentQualifiedRows(DocumentSemanticSnapshot.QueryContext query,String prefix,int target){
        return residentHierarchyRows(query,prefix,target,query.staticReceiver(),Set.of(),Set.of());
    }
    private List<Map<String,Object>> residentUnqualifiedRows(DocumentSemanticSnapshot.QueryContext query,String prefix,int target){
        if(target<=0)return List.of();
        var localKinds=Set.of("local_variable","resource_variable","exception_parameter","binding_variable","parameter");
        var variableNames=new HashSet<String>();
        for(var candidate:query.scopedCandidates())
            if(candidate.name().startsWith(prefix)&&!candidate.name().equals(EditorQueries.MARKER)&&localKinds.contains(candidate.kind()))
                variableNames.add(candidate.name());

        var scopeRows=new LinkedHashMap<String,Map<String,Object>>();var seenLocalNames=new HashSet<String>();
        for(var candidate:query.scopedCandidates()){
            if(!candidate.name().startsWith(prefix)||candidate.name().equals(EditorQueries.MARKER))continue;
            boolean local=localKinds.contains(candidate.kind());
            if(local&&!seenLocalNames.add(candidate.name()))continue;
            if(!local&&Set.of("field","enumconst").contains(candidate.kind())&&variableNames.contains(candidate.name()))continue;
            if(query.staticContext()&&candidate.declaringType()!=null&&!candidate.modifiers().contains("static")
                    &&semanticState().symbol(candidate.declaringType())!=null)continue;
            scopeRows.putIfAbsent(candidate.id(),residentCompletionRow(candidate,candidate.name()));
        }
        var lexical=scopeRows.values().stream().sorted(COMPLETION_ORDER).toList();
        var hierarchy=(query.receiverType() instanceof SemanticType.Declared||query.receiverType() instanceof SemanticType.Intersection)
                ?residentHierarchyRows(query,prefix,target,query.staticContext(),scopeRows.keySet(),variableNames):List.<Map<String,Object>>of();

        var rows=new ArrayList<Map<String,Object>>(Math.min(target,64));int lexicalIndex=0,hierarchyIndex=0;
        while(rows.size()<target&&(lexicalIndex<lexical.size()||hierarchyIndex<hierarchy.size())){
            if(hierarchyIndex>=hierarchy.size()
                    ||lexicalIndex<lexical.size()&&COMPLETION_ORDER.compare(lexical.get(lexicalIndex),hierarchy.get(hierarchyIndex))<=0)
                rows.add(lexical.get(lexicalIndex++));
            else rows.add(hierarchy.get(hierarchyIndex++));
        }
        return List.copyOf(rows);
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
    private SymbolDescription loadResidentDescription(SemanticFact fact)throws Exception{
        if(fact.sourceFile()==null)return null;
        Path source;
        try{source=Path.of(fact.sourceFile()).toAbsolutePath().normalize();}
        catch(Exception invalid){return null;}
        if(!documents.contains(source)&&!Files.isRegularFile(source))return null;
        String text=documents.text(source);
        String sourceHash=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var caches=modules.get(context.generation());
        String key=fact.id()+"\0"+fact.factIdentity().hex()+"\0"+sourceHash;
        var cached=caches.descriptions.get(key);
        if(cached!=null){residentDescriptionCacheHits++;return cached;}

        String wanted=fact.id();var observed=inputSnapshot();
        var outcome=compiler.query(source,text,2,observed,(task,units,tier)->{
            if(tier!=2)return null;
            var identity=new SymbolIdentity(task,context.gav(),context.release(),this::coordinates,context.navigationSources());
            SymbolDescription[] found={null};var trees=Trees.instance(task);
            for(var unit:units){
                new TreePathScanner<Void,Void>(){
                    private void capture(Element element){
                        if(found[0]!=null||element==null)return;
                        try{if(wanted.equals(identity.scip(element)))found[0]=SemanticFacts.description(task,identity,element);}
                        catch(IllegalArgumentException unresolved){/* selected identity not present in this task */}
                    }
                    @Override public Void visitClass(ClassTree node,Void unused){capture(trees.getElement(getCurrentPath()));return found[0]==null?super.visitClass(node,unused):null;}
                    @Override public Void visitMethod(MethodTree node,Void unused){capture(trees.getElement(getCurrentPath()));return null;}
                    @Override public Void visitVariable(VariableTree node,Void unused){
                        var element=trees.getElement(getCurrentPath());if(element!=null&&element.getKind().isField())capture(element);return null;
                    }
                }.scan(unit,null);
                if(found[0]!=null)break;
            }
            return found[0];
        });
        if(outcome.result()==null||!outcome.warnings().isEmpty())return null;
        residentDescriptionLoads++;caches.descriptions.put(key,outcome.result());
        while(caches.descriptions.size()>64)caches.descriptions.remove(caches.descriptions.keySet().iterator().next());
        return outcome.result();
    }

    public Map<String,Object> residentDescription(String ref)throws Exception{
        var fact=semanticState().symbol(ref);if(fact==null)return null;
        if(fact.sourceFile()!=null&&liveSourceState!=null)try{
            Path source=Path.of(fact.sourceFile()).toAbsolutePath().normalize();
            if(liveSourceState.accepts(source)){ensureSourceSemanticCurrent(source);fact=semanticState().symbol(ref);if(fact==null)return null;}
        }catch(Exception ignored){}
        var description=loadResidentDescription(fact);var value=new LinkedHashMap<String,Object>();
        value.put("scip",fact.id());value.put("name",fact.name());value.put("name_path",fact.namePath());value.put("kind",fact.kind());
        value.put("signature",description==null?fact.structuralSignature():description.detailedSignature());value.put("resolved",true);
        value.put("modifiers",fact.modifiers().stream().sorted().toList());value.put("fqn",fact.fqn());
        if(fact.sourceFile()!=null){value.put("file",fact.sourceFile());value.put("source_file",fact.sourceFile());}
        var owner=fact.ownerId()==null?null:semanticState().symbol(fact.ownerId());
        if(owner!=null)value.put("declaring",owner.fqn()==null?owner.name():owner.fqn());
        if(description!=null){
            String doc=DocMarkdown.render(description.documentation());if(doc!=null)value.put("doc",doc);
            var location=description.declaration();
            if(location!=null){
                value.put("source_start",location.start());value.put("source_end",location.end());
                value.put("start",location.start());value.put("end",location.end());
            }
        }
        return Collections.unmodifiableMap(value);
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
        result.put("completion_requests",completionRequests);result.put("resident_description_loads",residentDescriptionLoads);result.put("resident_description_cache_hits",residentDescriptionCacheHits);
        result.put("resident_hierarchy_unit_reuses",residentHierarchyUnitReuses);result.put("resident_hierarchy_unit_builds",residentHierarchyUnitBuilds);
        result.put("resident_hierarchy_last_built_units",residentHierarchyLastBuiltUnits);
        result.put("resident_declaration_fact_reuses",residentDeclarationFactReuses);result.put("resident_declaration_fact_builds",residentDeclarationFactBuilds);
        if(context!=null){
            result.put("resident_description_cache_entries",modules.get(context.generation()).descriptions.size());
            result.put("resident_accessibility_cache",modules.get(context.generation()).accessibility.status());
        }
        long retainedSemantic=retainedSemanticBytes();
        var semanticGenerations=new LinkedHashMap<String,Object>();
        for(var entry:modules.entrySet()){
            var semanticStatus=entry.getValue().semantic.status();
            semanticGenerations.put(entry.getKey(),Map.of(
                    "family",generationFamilies.getOrDefault(entry.getKey(),entry.getKey()),
                    "estimated_bytes",entry.getValue().semantic.estimatedBytes(),
                    "facts",semanticStatus.get("semantic_facts"),
                    "units",semanticStatus.get("semantic_units")));
        }
        result.put("resident_semantic_generations",semanticGenerations);
        result.put("resident_semantic_generation_count",modules.size());
        result.put("resident_semantic_generation_families",generationFamilyLru.size());
        result.put("resident_semantic_budget_bytes",semanticBudgetBytes);
        result.put("resident_semantic_estimated_bytes",retainedSemantic);
        result.put("resident_semantic_over_budget_bytes",Math.max(0,retainedSemantic-semanticBudgetBytes));
        result.put("resident_semantic_retired_generations",retiredModuleGenerations);
        result.put("resident_semantic_retired_bytes",retiredSemanticBytes);
        result.put("compiler_retired_generations",retiredCompilerGenerations);
        var moduleStatus=new LinkedHashMap<String,Object>();for(var entry:compilerPools.entrySet())moduleStatus.put(entry.getKey(),entry.getValue().status());result.put("module_compilers",moduleStatus);return result;
    }
    @Override public void close()throws Exception{if(snapshots!=null)snapshots.close();diagnosticStore.clear();outlines.clear();focused.clear();focusing.close();sourceTexts.clear();dependencies.semantic().clear();for(var pool:compilerPools.values())pool.close();compilerPools.clear();modules.clear();generationFamilies.clear();generationFamilyLru.clear();compiler=null;}
}
