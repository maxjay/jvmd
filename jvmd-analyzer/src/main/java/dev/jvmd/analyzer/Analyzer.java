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
    public record Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings,List<Path> navigationSources,boolean preciseSourceRoots,String workspace) {
        public Context {
            workspace=Objects.requireNonNullElse(workspace,"");
        }
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings,List<Path> navigationSources,boolean preciseSourceRoots){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,binarySources,warnings,navigationSources,preciseSourceRoots,"");}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings,List<Path> navigationSources){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,binarySources,warnings,navigationSources,true,"");}
        public Context(String gav,String release,List<Path> classpath,List<Path> sources,String generation,Map<String,String> coordinates,List<String> compilerOptions,Set<Path> binarySources,List<String> warnings){this(gav,release,classpath,sources,generation,coordinates,compilerOptions,binarySources,warnings,sources,true,"");}
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
    private static final class ClasspathProofEvidence {
        long transitions,intervals,reconsidered,equal,changed,unavailable,consumersVisited,consumersChanged,consumersEqual,
                consumersFallback,broadContextInvalidations,coarseFallbacks,validatedInputReconciliations;
        List<String> lastIntervals=List.of();
        long lastReconsidered,lastEqual,lastChanged,lastUnavailable,lastConsumersVisited,lastConsumersChanged,lastConsumersEqual,
                lastConsumersFallback,lastBroadContextInvalidations;
        void record(ClasspathSearchProofs.Update update){
            transitions++;lastIntervals=update.structuralDiff().intervals().stream().map(Object::toString).toList();
            intervals+=lastIntervals.size();lastReconsidered=update.reconsidered().size();lastEqual=update.equal().size();
            lastChanged=update.changed().size();lastUnavailable=update.unavailable().size();
            reconsidered+=lastReconsidered;equal+=lastEqual;changed+=lastChanged;unavailable+=lastUnavailable;
            lastConsumersVisited=lastConsumersChanged=lastConsumersEqual=lastConsumersFallback=lastBroadContextInvalidations=0;
        }
        void propagation(SemanticUpdatePolicy.ProofPropagation value){
            lastConsumersVisited=value.recomputed().size();lastConsumersChanged=value.changed().size();
            lastConsumersEqual=value.equal().size();lastConsumersFallback=value.fallback().size();
            consumersVisited+=lastConsumersVisited;consumersChanged+=lastConsumersChanged;
            consumersEqual+=lastConsumersEqual;consumersFallback+=lastConsumersFallback;
        }
        void broadContexts(long count){lastBroadContextInvalidations=count;broadContextInvalidations+=count;}
        Map<String,Object> status(){
            return Map.ofEntries(
                    Map.entry("transitions",transitions),Map.entry("structural_intervals",intervals),
                    Map.entry("search_proofs_reconsidered",reconsidered),Map.entry("search_proofs_equal",equal),
                    Map.entry("search_proofs_changed",changed),Map.entry("search_proofs_unavailable",unavailable),
                    Map.entry("proof_consumers_visited",consumersVisited),Map.entry("proof_consumers_changed",consumersChanged),
                    Map.entry("proof_consumers_equal",consumersEqual),Map.entry("proof_consumers_fallback",consumersFallback),
                    Map.entry("broad_context_invalidations",broadContextInvalidations),Map.entry("coarse_fallbacks",coarseFallbacks),
                    Map.entry("validated_input_reconciliations",validatedInputReconciliations),
                    Map.entry("last_intervals",lastIntervals),Map.entry("last_reconsidered",lastReconsidered),
                    Map.entry("last_equal",lastEqual),Map.entry("last_changed",lastChanged),
                    Map.entry("last_unavailable",lastUnavailable),Map.entry("last_consumers_visited",lastConsumersVisited),
                    Map.entry("last_consumers_changed",lastConsumersChanged),Map.entry("last_consumers_equal",lastConsumersEqual),
                    Map.entry("last_consumers_fallback",lastConsumersFallback),
                    Map.entry("last_broad_context_invalidations",lastBroadContextInvalidations));
        }
    }

    private static final class SourceProofEvidence {
        long transitions,leavesPublished,consumersVisited,consumersChanged,consumersEqual,consumersFallback,
                coarseFiles,sourceConsumersInvalidated,preProofDependantInvalidations;
        long lastLeavesPublished,lastConsumersVisited,lastConsumersChanged,lastConsumersEqual,lastConsumersFallback,
                lastCoarseFiles,lastSourceConsumersInvalidated,lastPreProofDependantInvalidations;
        void beginMutation(int dependantInvalidations){
            lastPreProofDependantInvalidations=dependantInvalidations;
            preProofDependantInvalidations+=dependantInvalidations;
        }
        void record(int leaves,SemanticUpdatePolicy.ProofPropagation propagation,int leafEqualStops,int coarse,int sourceInvalidated){
            transitions++;lastLeavesPublished=leaves;lastConsumersVisited=propagation.recomputed().size();
            lastConsumersChanged=propagation.changed().size();lastConsumersEqual=leafEqualStops+propagation.equal().size();
            lastConsumersFallback=propagation.fallback().size();lastCoarseFiles=coarse;lastSourceConsumersInvalidated=sourceInvalidated;
            leavesPublished+=lastLeavesPublished;consumersVisited+=lastConsumersVisited;consumersChanged+=lastConsumersChanged;
            consumersEqual+=lastConsumersEqual;consumersFallback+=lastConsumersFallback;coarseFiles+=coarse;
            sourceConsumersInvalidated+=sourceInvalidated;
        }
        Map<String,Object> status(){
            return Map.ofEntries(
                    Map.entry("transitions",transitions),Map.entry("leaves_published",leavesPublished),
                    Map.entry("proof_consumers_visited",consumersVisited),Map.entry("proof_consumers_recomputed",consumersVisited),
                    Map.entry("proof_consumers_changed",consumersChanged),Map.entry("proof_consumers_stopped_equal",consumersEqual),
                    Map.entry("proof_consumers_fallback",consumersFallback),Map.entry("coarse_fallback_files",coarseFiles),
                    Map.entry("source_consumers_invalidated",sourceConsumersInvalidated),
                    Map.entry("pre_proof_dependant_invalidations",preProofDependantInvalidations),
                    Map.entry("last_leaves_published",lastLeavesPublished),Map.entry("last_proof_consumers_visited",lastConsumersVisited),
                    Map.entry("last_proof_consumers_recomputed",lastConsumersVisited),Map.entry("last_proof_consumers_changed",lastConsumersChanged),
                    Map.entry("last_proof_consumers_stopped_equal",lastConsumersEqual),
                    Map.entry("last_proof_consumers_fallback",lastConsumersFallback),Map.entry("last_coarse_fallback_files",lastCoarseFiles),
                    Map.entry("last_source_consumers_invalidated",lastSourceConsumersInvalidated),
                    Map.entry("last_pre_proof_dependant_invalidations",lastPreProofDependantInvalidations));
        }
    }

    private static final class ModuleCaches {
        final LinkedHashMap<String,Envelope> outlines=new LinkedHashMap<>(16,.75f,true);
        final LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
        final Set<Path> files=new HashSet<>();
        final ResidentSemanticState semantic=new ResidentSemanticState();
        final Map<Path,DocumentSemanticCached> documentSemantics=new HashMap<>();
        final LinkedHashMap<String,SymbolDescription> descriptions=new LinkedHashMap<>(16,.75f,true);
        final AccessibilityCache accessibility=new AccessibilityCache();
        final Map<String,IndexStore.ClasspathSearchProof> classpathSearchProofs=new TreeMap<>();
        final ClasspathProofEvidence classpathProofEvidence=new ClasspathProofEvidence();
        long semanticSourceEpoch=-1;
        String completionContextIdentity="",semanticOwnerIdentity="",platformFingerprint="";
        ClasspathSequence classpathSequence;
        boolean classpathPrecise;
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
    private record DocumentSemanticCached(String key,DocumentSemanticSnapshot snapshot) { }
    private record SemanticAdmission(SemanticDelta delta,List<SemanticFact> removed) {
        SemanticAdmission { Objects.requireNonNull(delta);removed=List.copyOf(removed); }
    }
    private record SourceLeafChanges(Map<QueryProof.Key,Hash256> changed,int consumersStoppedEqual) {
        SourceLeafChanges { changed=Collections.unmodifiableMap(new TreeMap<>(changed)); }
    }
    private record Outline(List<Map<String,Object>> symbols,Set<Path> dependencies) { }
    private static final class CompletionAdvanceFailure extends Exception {
        final List<String> warnings;
        CompletionAdvanceFailure(List<String> warnings){super(String.join("; ",warnings));this.warnings=List.copyOf(warnings);}
    }
    private LinkedHashMap<String,Cached> focused=new LinkedHashMap<>(32,.75f,true);
    private final Dependencies dependencies=new Dependencies();
    private final SourceProofEvidence sourceProofEvidence=new SourceProofEvidence();
    private final LinkedHashMap<String,SourceText> sourceTexts=new LinkedHashMap<>(16,.75f,true);
    private long cacheHits,bindingComputations,diagnosticFilesAnalysed,diagnosticFilesReused,indexWrites,indexWriteNanos,apiFingerprintChanges,apiFingerprintUnchanged;
    private long completionRequests,residentDescriptionLoads,residentDescriptionCacheHits;
    private Context context;
    private IndexService index;
    private LiveSourceState liveSourceState;
    private DiagnosticSnapshots snapshots;
    public void persistence(Path directory){if(snapshots==null){snapshots=new DiagnosticSnapshots(directory);diagnosticStore.persistence(snapshots);snapshots.documents(documents);}}
    private long budget;

    private String platformFingerprint()throws Exception{
        try(var trace=RequestScope.stage("analyzer.configure.platformFingerprint")){
            Path home=Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
            var values=new ArrayList<Object>();
            for(Path path:List.of(home.resolve("release"),home.resolve("lib/modules"),home.resolve("lib/ct.sym")))
                values.add(List.of(path.toString(),inputFiles.hash(path)));
            trace.count("files",values.size());
            return CompilerInputs.compose("semantic-platform-v1",values);
        }
    }
    private static String semanticOwnerIdentity(Context context,String platformFingerprint){
        return CompilerInputs.compose("semantic-owner-v2",
                context.gav(),context.release(),platformFingerprint,
                context.sources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.compilerOptions(),
                context.binarySources().stream().map(p->p.toAbsolutePath().normalize().toString()).sorted().toList(),
                context.navigationSources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.preciseSourceRoots(),context.workspace());
    }
    private static String broadCompletionContextIdentity(Context context){
        return CompilerInputs.compose("completion-context-v1",
                context.gav(),context.release(),context.generation(),
                context.classpath().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.sources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.compilerOptions(),
                context.binarySources().stream().map(p->p.toAbsolutePath().normalize().toString()).sorted().toList(),
                new TreeMap<>(context.coordinates()),
                context.navigationSources().stream().map(p->p.toAbsolutePath().normalize().toString()).toList(),
                context.preciseSourceRoots(),context.workspace());
    }
    private static boolean hasUnprovenPathOptions(Context context){
        if(!context.binarySources().isEmpty())return true;
        var names=Set.of("--module-path","-p","--upgrade-module-path","--class-path","-classpath","-cp",
                "--processor-path","-processorpath","--processor-module-path","--patch-module","--system",
                "--source-path","-sourcepath","--module-source-path","--boot-class-path","-bootclasspath",
                "-extdirs","-endorseddirs","-Djava.ext.dirs","-Djava.endorsed.dirs");
        for(String option:context.compilerOptions()){
            String name=option.contains("=")?option.substring(0,option.indexOf('=')):option;
            if(names.contains(name)||option.startsWith("-Xbootclasspath:")||option.startsWith("-Xbootclasspath/a:")
                    ||option.startsWith("-Xbootclasspath/p:"))return true;
        }
        return false;
    }
    private Optional<ClasspathSequence> preciseClasspathSequence(Context context,IndexService index)throws Exception{
        try(var trace=RequestScope.stage("analyzer.configure.preciseClasspathSequence")){
            if(index==null||context.workspace().isBlank()||hasUnprovenPathOptions(context)){trace.cache("unavailable");return Optional.empty();}
            Optional<ClasspathSequence> sequence;
            try(var read=RequestScope.stage("analyzer.configure.semanticClasspathSequence")){
                sequence=index.store().semanticClasspathSequence(context.workspace());
            }
            if(sequence.isEmpty()){trace.cache("missing");return Optional.empty();}
            var expected=context.classpath().stream().map(path->path.toAbsolutePath().normalize().toString()).toList();
            List<String> actual;
            try(var entries=RequestScope.stage("analyzer.configure.classpathEntries")){
                actual=sequence.get().entries().stream().map(ClasspathSequence.Entry::key).toList();
                entries.count("entries",actual.size());
            }
            trace.count("expected_entries",expected.size());trace.cache(expected.equals(actual)?"equal":"mismatch");
            return expected.equals(actual)?sequence:Optional.empty();
        }
    }
    private ModuleCaches moduleCaches(Context next,String owner){
        var direct=modules.get(next.generation());
        if(direct!=null)return direct;
        String candidateKey=null;ModuleCaches candidate=null;
        for(var entry:modules.entrySet())if(owner.equals(entry.getValue().semanticOwnerIdentity)){
            candidateKey=entry.getKey();candidate=entry.getValue();break;
        }
        if(candidate!=null){
            modules.remove(candidateKey);
            modules.put(next.generation(),candidate);
            return candidate;
        }
        var created=new ModuleCaches();modules.put(next.generation(),created);return created;
    }
    private void removeDocumentProofs(Path file,DocumentSemanticCached cached){
        if(cached!=null)for(var query:cached.snapshot().queries().values())
            dependencies.semantic().proofs().remove(documentProofConsumer(file,query.selectorOffset()));
    }
    private void clearDocumentSemantics(ModuleCaches caches){
        for(var entry:new ArrayList<>(caches.documentSemantics.entrySet()))removeDocumentProofs(entry.getKey(),entry.getValue());
        caches.documentSemantics.clear();
    }
    private void clearSemanticCaches(ModuleCaches caches){
        clearDocumentSemantics(caches);caches.descriptions.clear();caches.accessibility.clear();caches.semantic.clear();
        caches.semanticSourceEpoch=-1;caches.classpathSearchProofs.clear();caches.classpathSequence=null;caches.classpathPrecise=false;
    }
    private static SemanticUpdatePolicy.ProofConsumer documentProofConsumer(Path file,int selectorOffset){
        return new SemanticUpdatePolicy.ProofConsumer(file,"document-context:"+selectorOffset);
    }
    private static QueryProof.Key documentProofOutput(Path file,int selectorOffset){
        return new QueryProof.Key(QueryProof.Domain.DOCUMENT_SCOPE,
                "derived:"+file.toAbsolutePath().normalize()+"#"+selectorOffset);
    }
    private static SemanticUpdatePolicy.ProofConsumer sourceProofConsumer(Path file){
        return new SemanticUpdatePolicy.ProofConsumer(file,"source-semantic");
    }
    private static QueryProof.Key sourceProofOutput(Path file){
        return new QueryProof.Key(QueryProof.Domain.WORKSPACE,"source-semantic:"+file.toAbsolutePath().normalize());
    }
    private static SemanticUpdatePolicy.ProofConsumer completionRangeProofConsumer(Path file,int selectorOffset){
        return new SemanticUpdatePolicy.ProofConsumer(file,"completion-range:"+selectorOffset);
    }
    private static QueryProof.Key completionRangeProofOutput(Path file,int selectorOffset){
        return new QueryProof.Key(QueryProof.Domain.DOCUMENT_SCOPE,
                "completion-range:"+file.toAbsolutePath().normalize()+"#"+selectorOffset);
    }
    private static void addProofDependency(Map<QueryProof.Key,Hash256> values,QueryProof.Dependency dependency){
        var previous=values.putIfAbsent(dependency.key(),dependency.identity());
        if(previous!=null&&!previous.equals(dependency.identity()))
            throw new IllegalStateException("Conflicting semantic proof identity for "+dependency.key());
    }
    private boolean currentSourceFact(SemanticFact fact){
        if(fact==null||fact.sourceFile()==null||liveSourceState==null)return true;
        try{
            Path source=Path.of(fact.sourceFile()).toAbsolutePath().normalize();
            if(!liveSourceState.accepts(source))return true;
            String content=liveSourceState.contentHash(source);
            return content!=null&&semanticState().unitCurrent("source:"+source,content);
        }catch(Exception invalid){return false;}
    }
    private Optional<Hash256> maintainedNamespaceTypeIdentity(SemanticReadView view,String binary)throws Exception{
        if(liveSourceState!=null){
            var source=liveSourceState.source(binary).orElse(null);
            if(source!=null){
                Path file=source.file().toAbsolutePath().normalize();String content=liveSourceState.contentHash(file);
                if(content==null||!semanticState().unitCurrent("source:"+file,content))return Optional.empty();
            }
        }
        var symbol=view.type(binary);return symbol==null?Optional.empty():Optional.of(symbol.resolutionIdentity());
    }
    private boolean addReceiverLookupProof(Map<QueryProof.Key,Hash256> values,SemanticReadView view,
                                           String receiverType,String memberName,boolean call)throws Exception{
        var queue=new ArrayDeque<String>();queue.add(receiverType);var seen=new HashSet<String>();
        while(!queue.isEmpty()){
            String owner=queue.removeFirst();if(!seen.add(owner))continue;
            var typeIdentity=view.identity(QueryProof.Domain.EXACT_SYMBOL,owner);
            if(typeIdentity.isEmpty())return false;
            addProofDependency(values,new QueryProof.Dependency(QueryProof.Domain.EXACT_SYMBOL,owner,typeIdentity.get()));

            var memberProof=call?SemanticQueryProofs.overload(view,owner,memberName)
                    :SemanticQueryProofs.range(view,owner,memberName);
            if(memberProof.isEmpty())return false;
            for(var dependency:memberProof.get().dependencies())addProofDependency(values,dependency);

            for(String parent:view.directSupertypes(owner)){
                if(view.symbol(parent)==null)return false;
                queue.addLast(parent);
            }
        }
        return true;
    }

    private void registerSourceProof(Path file,String text,Bindings.Snapshot snapshot,FileSemanticContribution contribution)throws Exception{
        file=file.toAbsolutePath().normalize();
        var consumer=sourceProofConsumer(file);var values=new TreeMap<QueryProof.Key,Hash256>();
        var coveredFiles=new HashSet<Path>();var view=semanticReadView();
        var referencesByTarget=new HashMap<String,List<Bindings.ReferenceProof>>();
        for(var reference:snapshot.referenceProofs())
            referencesByTarget.computeIfAbsent(reference.target(),ignored->new ArrayList<>()).add(reference);
        boolean precise=liveSourceState!=null;

        for(var edge:snapshot.edges()){
            var fact=snapshot.semanticFacts().get(edge.dst());if(fact==null)continue;
            Path source=null;
            if(fact.sourceFile()!=null)try{source=Path.of(fact.sourceFile()).toAbsolutePath().normalize();}catch(Exception ignored){}
            boolean liveDependency=source!=null&&!source.equals(file)&&liveSourceState!=null&&liveSourceState.accepts(source);
            if(liveDependency){coveredFiles.add(source);if(!currentSourceFact(fact))precise=false;}
            if(!liveDependency)continue;

            var exact=view.identity(QueryProof.Domain.EXACT_SYMBOL,fact.id());
            if(exact.isEmpty())precise=false;else addProofDependency(values,
                    new QueryProof.Dependency(QueryProof.Domain.EXACT_SYMBOL,fact.id(),exact.get()));

            String owner=fact.ownerId();
            if(owner!=null&&!owner.isBlank()){
                var references=referencesByTarget.getOrDefault(fact.id(),List.of());
                boolean sourceReference=edge.kind().equals("calls")||edge.kind().equals("reads")||edge.kind().equals("writes");
                if(sourceReference){
                    if(references.isEmpty())precise=false;
                    for(var reference:references){
                        if(reference.receiverType()==null
                                ||!addReceiverLookupProof(values,view,reference.receiverType(),fact.name(),edge.kind().equals("calls")))
                            precise=false;
                    }
                }
            }else if(fact.typeDeclaration()&&(edge.kind().equals("extends")||edge.kind().equals("implements"))){
                var hierarchy=view.identity(QueryProof.Domain.HIERARCHY,fact.id());
                if(hierarchy.isEmpty())precise=false;else addProofDependency(values,
                        new QueryProof.Dependency(QueryProof.Domain.HIERARCHY,fact.id(),hierarchy.get()));
            }
        }

        for(var occurrence:snapshot.occurrences()){
            if(!occurrence.file().equals(file.toString()))continue;
            var fact=snapshot.semanticFacts().get(occurrence.scip());
            if(fact==null||!fact.typeDeclaration()||fact.sourceFile()==null)continue;
            Path source;try{source=Path.of(fact.sourceFile()).toAbsolutePath().normalize();}catch(Exception invalid){continue;}
            if(source.equals(file)||liveSourceState==null||!liveSourceState.accepts(source))continue;
            String simple=occurrence.token();
            if(!javax.lang.model.SourceVersion.isIdentifier(simple))continue;
            String binary=fact.fqn()==null||fact.fqn().isBlank()?fact.resolutionFact().symbolKey():fact.fqn();
            var plan=NamespaceResolutionProofs.plan(text,simple,binary);
            if(!plan.precise()){precise=false;continue;}
            for(var dependency:NamespaceResolutionProofs.dependencies(plan,b->maintainedNamespaceTypeIdentity(view,b)))
                addProofDependency(values,dependency);
        }

        for(String unresolved:new TreeSet<>(contribution.unresolvedTargets())){
            if(unresolved.equals("*")||!javax.lang.model.SourceVersion.isIdentifier(unresolved)){
                precise=false;continue;
            }
            var plan=NamespaceResolutionProofs.plan(text,unresolved,null);
            if(!plan.precise()){precise=false;continue;}
            for(var dependency:NamespaceResolutionProofs.dependencies(plan,b->maintainedNamespaceTypeIdentity(view,b)))
                addProofDependency(values,dependency);
        }

        for(Path dependency:contribution.dependencies()){
            Path normalized=dependency.toAbsolutePath().normalize();
            if(normalized.equals(file)||liveSourceState==null||!liveSourceState.accepts(normalized))continue;
            if(!coveredFiles.contains(normalized))precise=false;
        }

        if(!precise){
            dependencies.semantic().proofs().remove(consumer);dependencies.semantic().proofCoverage(file,false);return;
        }
        var proof=new QueryProof(values.entrySet().stream().map(entry->new QueryProof.Dependency(entry.getKey(),entry.getValue())).toList());
        dependencies.semantic().proofs().register(consumer,
                new SemanticUpdatePolicy.ProofEvaluation(proof,sourceProofOutput(file),proof.identity()));
        dependencies.semantic().proofCoverage(file,true);
    }
    private void registerCompletionRangeProof(Path file,int selectorOffset,String prefix,SemanticReadView view,
                                              CompletionContextResolver.Resolved resolved)throws Exception{
        var consumer=completionRangeProofConsumer(file,selectorOffset);
        var owners=semanticHierarchyOwners(view,resolved);
        if(owners==null||owners.isEmpty()){dependencies.semantic().proofs().remove(consumer);return;}
        var values=new TreeMap<QueryProof.Key,Hash256>();
        for(var owner:owners){
            var proof=SemanticQueryProofs.range(view,owner.symbol().id(),prefix);
            if(proof.isEmpty()){dependencies.semantic().proofs().remove(consumer);return;}
            for(var dependency:proof.get().dependencies())addProofDependency(values,dependency);
        }
        var proof=new QueryProof(values.entrySet().stream()
                .map(entry->new QueryProof.Dependency(entry.getKey(),entry.getValue())).toList());
        dependencies.semantic().proofs().register(consumer,new SemanticUpdatePolicy.ProofEvaluation(
                proof,completionRangeProofOutput(file,selectorOffset),proof.identity()));
    }

    private void registerDocumentProof(Path file,DocumentSemanticSnapshot.QueryContext query){
        if(query.proof().dependencies().isEmpty())return;
        dependencies.semantic().proofs().register(documentProofConsumer(file,query.selectorOffset()),
                new SemanticUpdatePolicy.ProofEvaluation(query.proof(),documentProofOutput(file,query.selectorOffset()),query.proof().identity()));
    }
    private Optional<SemanticUpdatePolicy.ProofEvaluation> rebaseProof(
            SemanticUpdatePolicy.ProofConsumer consumer,Map<QueryProof.Key,Hash256> leaves){
        var prior=dependencies.semantic().proofs().evaluation(consumer);if(prior.isEmpty())return Optional.empty();
        var current=new ArrayList<QueryProof.Dependency>();
        for(var dependency:prior.get().dependencies().dependencies())
            current.add(new QueryProof.Dependency(dependency.key(),leaves.getOrDefault(dependency.key(),dependency.identity())));
        var proof=new QueryProof(current);
        return Optional.of(new SemanticUpdatePolicy.ProofEvaluation(proof,prior.get().output(),proof.identity()));
    }
    private void invalidateDocumentProofConsumers(ModuleCaches caches,Collection<SemanticUpdatePolicy.ProofConsumer> consumers){
        var files=new LinkedHashSet<Path>();for(var consumer:consumers)files.add(consumer.file());
        for(Path file:files){
            var cached=caches.documentSemantics.remove(file);removeDocumentProofs(file,cached);
        }
    }
    private long invalidateBroadClasspathContexts(ModuleCaches caches){
        var files=new LinkedHashSet<Path>();
        for(var entry:caches.documentSemantics.entrySet())for(var query:entry.getValue().snapshot().queries().values())
            if(query.proof().dependencies().stream().anyMatch(dependency->
                    dependency.key().domain()==QueryProof.Domain.CLASSPATH_SEARCH
                            &&!dependency.key().value().startsWith("binary:"))){
                files.add(entry.getKey());break;
            }
        for(Path file:files){
            var cached=caches.documentSemantics.remove(file);removeDocumentProofs(file,cached);
        }
        return files.size();
    }
    private void evictChangedClasspathWinner(ModuleCaches caches,IndexStore.ClasspathSearchProof proof){
        if(proof==null||!proof.resolved())return;
        String unit=caches.semantic.unitForFact(proof.winnerScip());
        if(unit!=null&&!unit.startsWith("source:"))caches.semantic.removeUnit(unit);
    }
    private boolean reconcileClasspath(ModuleCaches caches,ClasspathSequence current,IndexService index,String workspace)throws Exception{
        try(var trace=RequestScope.stage("analyzer.configure.reconcileClasspath")){
        if(caches.classpathSequence==null){
            caches.classpathSequence=current;caches.classpathPrecise=true;return true;
        }
        var previous=caches.classpathSequence;
        if(previous.identity().equals(current.identity())){
            caches.classpathSequence=current;caches.classpathPrecise=true;return true;
        }
        var refreshed=new HashMap<String,IndexStore.ClasspathSearchProof>();
        var update=ClasspathSearchProofs.update(previous,current,caches.classpathSearchProofs.values(),binary->{
            var proof=index.store().semanticClasspathSearch(workspace,binary);
            proof.ifPresent(value->refreshed.put(binary,value));return proof;
        });
        caches.classpathProofEvidence.record(update);
        if(!update.unavailable().isEmpty()){
            caches.classpathProofEvidence.coarseFallbacks++;return false;
        }

        caches.classpathProofEvidence.broadContexts(invalidateBroadClasspathContexts(caches));
        if(!update.changed().isEmpty()){
            for(var key:update.changed().keySet()){
                String binary=key.value().startsWith("binary:")?key.value().substring("binary:".length()):key.value();
                evictChangedClasspathWinner(caches,caches.classpathSearchProofs.get(binary));
                evictChangedClasspathWinner(caches,refreshed.get(binary));
            }
            var propagation=dependencies.semantic().proofs().propagate(update.changed(),
                    consumer->rebaseProof(consumer,update.changed()));
            caches.classpathProofEvidence.propagation(propagation);
            var invalid=new LinkedHashSet<SemanticUpdatePolicy.ProofConsumer>(propagation.changed());
            invalid.addAll(propagation.fallback());
            invalidateDocumentProofConsumers(caches,invalid);
        }
        caches.classpathSearchProofs.putAll(refreshed);
        caches.classpathSequence=current;caches.classpathPrecise=true;return true;
        }
    }
    private void initializeClasspath(ModuleCaches caches,Optional<ClasspathSequence> sequence){
        caches.classpathSequence=sequence.orElse(null);caches.classpathPrecise=sequence.isPresent();
        if(sequence.isEmpty())caches.classpathSearchProofs.clear();
    }

    public void configure(Context context,IndexService index,long budget)throws Exception{
        try(var configureTrace=RequestScope.stage("analyzer.configure")){
        String family=generationFamily(context);generationFamilies.put(context.generation(),family);generationFamilyLru.put(family,Boolean.TRUE);
        String platform=platformFingerprint();
        String owner;
        try(var ownerTrace=RequestScope.stage("analyzer.configure.semanticOwnerIdentity")){owner=semanticOwnerIdentity(context,platform);}
        var caches=moduleCaches(context,owner);
        boolean hadState=!caches.completionContextIdentity.isBlank();
        boolean ownerChanged=hadState&&!owner.equals(caches.semanticOwnerIdentity);
        var currentClasspath=preciseClasspathSequence(context,index);

        this.context=context;this.index=index;this.budget=budget;semanticBudgetBytes=Math.max(8L*1024*1024,budget/2);
        outlines=caches.outlines;focused=caches.focused;
        diagnosticStore.budget(Math.max(1024*1024,budget/8));

        boolean precise=false;
        if(ownerChanged){
            clearSemanticCaches(caches);
        }else if(!hadState){
            initializeClasspath(caches,currentClasspath);precise=currentClasspath.isPresent();
        }else if(caches.classpathPrecise&&currentClasspath.isPresent()){
            precise=reconcileClasspath(caches,currentClasspath.get(),index,context.workspace());
        }

        if(hadState&&!ownerChanged&&!precise){
            String broad=broadCompletionContextIdentity(context);
            if(!broad.equals(caches.completionContextIdentity)){
                caches.classpathProofEvidence.coarseFallbacks++;clearSemanticCaches(caches);
            }
            initializeClasspath(caches,currentClasspath);
            precise=currentClasspath.isPresent();
        }
        caches.semanticOwnerIdentity=owner;caches.platformFingerprint=platform;
        caches.completionContextIdentity=precise?owner:broadCompletionContextIdentity(context);

        compiler=compilerPools.computeIfAbsent(context.generation(),_->new CompilerPool(inputFiles));
        try(var compilerTrace=RequestScope.stage("analyzer.configure.compilerPool")){
            compiler.configure(context.generation(),context.release(),context.classpath(),context.sources(),index,budget,context.compilerOptions(),context.preciseSourceRoots());
            compiler.binarySources(context.binarySources());
        }
        configureTrace.count("classpath_entries",context.classpath().size());
        retireOldGenerationFamilies(family);
        }
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
    private SemanticReadView semanticReadView(){
        var live=SemanticReadViews.resident(semanticState());
        if(index==null||context.workspace().isBlank())return live;
        return SemanticReadViews.precedence(
                live,
                SemanticReadViews.local(index.store(),context.workspace()),
                SemanticReadViews.machine(index.store(),context.workspace()),
                this::workspaceSourceOwnsBinary);
    }
    private boolean workspaceSourceOwnsBinary(String binaryName){
        return liveSourceState!=null&&binaryName!=null&&!binaryName.isBlank()
                &&liveSourceState.source(binaryName.replace((char)36,'.')).isPresent();
    }

    private List<SemanticReadView.Symbol> semanticTypes(String name)throws Exception{
        String requested=Objects.requireNonNullElse(name,"").trim();
        if(requested.isBlank())return List.of();
        var result=new LinkedHashMap<String,SemanticReadView.Symbol>();
        var residentView=SemanticReadViews.resident(semanticState());
        var resident=semanticState().type(requested);
        if(resident!=null){
            var symbol=residentView.symbol(resident.id());
            if(symbol!=null)result.put(symbol.id(),symbol);
        }
        String simple=requested.substring(Math.max(requested.lastIndexOf('.'),requested.lastIndexOf(36))+1);
        for(var fact:semanticState().typesByName(simple)){
            var symbol=residentView.symbol(fact.id());
            if(symbol!=null)result.putIfAbsent(symbol.id(),symbol);
        }
        if(index==null||context.workspace().isBlank())return List.copyOf(result.values());
        for(var layer:List.of(IndexStore.SemanticLayer.LOCAL,IndexStore.SemanticLayer.MACHINE)){
            var origin=layer==IndexStore.SemanticLayer.LOCAL?SemanticReadView.Origin.LOCAL:SemanticReadView.Origin.MACHINE;
            for(var value:index.store().semanticTypesByName(simple,context.workspace(),64,layer)){
                String fqn=value.fqn();
                if(requested.indexOf('.')>=0&&!requested.equals(fqn)&&!requested.equals(fqn.replace((char)36,'.'))
                        &&!requested.equals(value.binaryKey()))continue;
                if(workspaceSourceOwnsBinary(fqn)&&semanticState().type(fqn)==null)continue;
                var symbol=SemanticReadViews.fromIndexed(value,origin);
                result.putIfAbsent(symbol.id(),symbol);
            }
        }
        return List.copyOf(result.values());
    }
    private SemanticAdmission admitSemanticMutation(SemanticSnapshot snapshot,FileSemanticContribution contribution){
        if(snapshot==null||contribution==null)return null;
        var canonical=new SemanticSnapshot(snapshot.unit(),snapshot.sourceFile(),snapshot.contentIdentity(),snapshot.facts(),snapshot.descriptions(),
                contribution.apiFingerprint(),LiveStateTree.namespace(contribution.exportedNames()).value(),snapshot.documentationIdentity(),snapshot.dependencies());
        var delta=semanticState().diff(canonical);var removed=new ArrayList<SemanticFact>();
        for(String id:delta.removed()){var fact=semanticState().symbol(id);if(fact!=null)removed.add(fact);}
        semanticState().apply(delta);return new SemanticAdmission(delta,removed);
    }
    private void admitSemantic(SemanticSnapshot snapshot,FileSemanticContribution contribution){
        admitSemanticMutation(snapshot,contribution);
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
        if(changed.isEmpty()){
            // Lost bounded source history is an uncertainty event, not proof that every detached
            // context is semantically invalid. One resident generation fence makes retained units
            // non-current in O(1); document/query proofs observe that fence lazily through their
            // hierarchy/accessibility/resolution dependencies.
            caches.semantic.markHierarchyUncertain();
        }else for(Path file:changed.get()){
            if(file.getFileName()!=null&&file.getFileName().toString().equals("module-info.java"))caches.documentSemantics.clear();
            String content=liveSourceState.contentHash(file);
            if(content==null)caches.semantic.removeUnit("source:"+file.toAbsolutePath().normalize());
            else caches.semantic.markSourceStale(file.toAbsolutePath().normalize().toString(),content);
        }
        caches.semanticSourceEpoch=current;
    }
    private String classpathStamp()throws Exception{return computeClasspathStamp();}
    private CompilerInputs.Snapshot validatedInputs()throws Exception {
        try(var trace=RequestScope.stage("analyzer.validatedInputs")){
        var inputs=inputSnapshot();
        if(!compiler.cacheValid(inputs)){
            // Javac must fence its own environment immediately. Detached semantic/query state gets
            // a separate decision: first try the ordered classpath/search-proof transition, and
            // widen only when that transition cannot fully explain the environment change.
            outlines.clear();focused.clear();
            var caches=modules.get(context.generation());boolean precise=false;
            if(caches.classpathPrecise&&Objects.equals(caches.platformFingerprint,platformFingerprint())){
                var currentClasspath=preciseClasspathSequence(context,index);
                if(currentClasspath.isPresent()){
                    caches.classpathProofEvidence.validatedInputReconciliations++;
                    precise=reconcileClasspath(caches,currentClasspath.get(),index,context.workspace());
                }
            }
            if(!precise){
                caches.classpathProofEvidence.coarseFallbacks++;
                clearDocumentSemantics(caches);caches.accessibility.clear();
                // Unsupported environment transitions (platform/module/path options/lost precise
                // classpath coverage) retain facts physically but fence their semantic currency.
                caches.semantic.markHierarchyUncertain();
                caches.classpathSearchProofs.clear();caches.classpathSequence=null;caches.classpathPrecise=false;
            }
            inputs=inputSnapshot();
        }
        return inputs;
        }
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
    private static Hash256 unavailableProofIdentity(QueryProof.Key key){
        return CanonicalDigestWriter.digest("semantic-proof-unavailable-v1",key.domain().name(),key.value());
    }
    private static String semanticBinary(SemanticFact fact){
        if(fact==null)return "";
        String value=fact.fqn();
        if(value==null||value.isBlank())value=fact.typeDeclaration()?fact.resolutionFact().symbolKey():"";
        return Objects.requireNonNullElse(value,"").replace((char)36,'.');
    }
    private Hash256 currentSourceLeafIdentity(QueryProof.Key key)throws Exception{
        var view=semanticReadView();
        return switch(key.domain()){
            case EXACT_SYMBOL,MEMBER_RANGE,OVERLOAD_GROUP,HIERARCHY ->
                    view.identity(key.domain(),key.value()).orElseGet(()->unavailableProofIdentity(key));
            case RESOLUTION_PATH -> resolutionPathIdentity(key.value());
            case NAMESPACE -> {
                if(!key.value().startsWith("type:"))yield unavailableProofIdentity(key);
                String binary=key.value().substring("type:".length());
                Hash256 declaration=namespaceTypeIdentity(binary).orElse(null);
                yield CanonicalDigestWriter.digest("namespace-search-domain-v1",binary,declaration);
            }
            case NEGATIVE_RESOLUTION -> {
                int split=key.value().lastIndexOf('@');
                if(split<=0||split==key.value().length()-1)yield unavailableProofIdentity(key);
                String simple=key.value().substring(0,split),binary=key.value().substring(split+1);
                Hash256 declaration=namespaceTypeIdentity(binary).orElse(null);
                Hash256 domain=CanonicalDigestWriter.digest("namespace-search-domain-v1",binary,declaration);
                yield CanonicalDigestWriter.digest("negative-resolution-domain-v1",simple,binary,domain);
            }
            default -> unavailableProofIdentity(key);
        };
    }
    private SourceLeafChanges sourceMutationLeaves(SemanticAdmission admission,Path changedFile)throws Exception{
        if(admission==null||admission.delta().emptyFacts())return new SourceLeafChanges(Map.of(),0);
        var changedFacts=new ArrayList<SemanticFact>();changedFacts.addAll(admission.removed());
        changedFacts.addAll(admission.delta().changed());changedFacts.addAll(admission.delta().added());
        var changedIds=new HashSet<String>();var changedBinaries=new HashSet<String>();
        for(var fact:changedFacts){changedIds.add(fact.id());String binary=semanticBinary(fact);if(fact.typeDeclaration()&&!binary.isBlank())changedBinaries.add(binary);}
        String sourceKey="source:"+changedFile.toAbsolutePath().normalize();
        var leaves=new TreeMap<QueryProof.Key,Hash256>();
        for(var key:dependencies.semantic().proofs().dependencyKeys()){
            boolean affected=switch(key.domain()){
                case EXACT_SYMBOL -> changedIds.contains(key.value());
                case MEMBER_RANGE,OVERLOAD_GROUP -> {
                    SemanticReadView.MemberIdentityKey member;
                    try{member=SemanticReadView.parseMemberIdentityKey(key.value());}catch(IllegalArgumentException invalid){yield false;}
                    boolean exact=key.domain()==QueryProof.Domain.OVERLOAD_GROUP;
                    yield changedFacts.stream().anyMatch(fact->Objects.equals(member.ownerId(),fact.ownerId())
                            &&(exact?member.name().equals(fact.name()):fact.name().startsWith(member.name())));
                }
                // Resident hierarchy composition can update descendants after any declaration/member
                // mutation. Document-local fallback hierarchy keys are not globally addressable and
                // remain request-validated; canonical type-id keys participate in mutation propagation.
                case HIERARCHY -> !key.value().startsWith("document:");
                case NAMESPACE -> key.value().startsWith("type:")
                        &&changedBinaries.contains(key.value().substring("type:".length()).replace((char)36,'.'));
                case NEGATIVE_RESOLUTION -> {
                    int split=key.value().lastIndexOf('@');
                    yield split>0&&changedBinaries.contains(key.value().substring(split+1).replace((char)36,'.'));
                }
                case RESOLUTION_PATH -> key.value().equals(sourceKey)
                        ||key.value().startsWith("type:")
                        &&changedBinaries.contains(key.value().substring("type:".length()).replace((char)36,'.'));
                default -> false;
            };
            if(affected)leaves.put(key,currentSourceLeafIdentity(key));
        }
        return changedSourceLeaves(leaves);
    }
    private SourceLeafChanges changedSourceLeaves(Map<QueryProof.Key,Hash256> candidates){
        var changed=new TreeMap<QueryProof.Key,Hash256>();var changedConsumers=new HashSet<SemanticUpdatePolicy.ProofConsumer>();
        var equalCandidates=new HashSet<SemanticUpdatePolicy.ProofConsumer>();
        for(var entry:candidates.entrySet()){
            boolean keyChanged=false;
            for(var consumer:dependencies.semantic().proofs().consumers(entry.getKey())){
                var evaluation=dependencies.semantic().proofs().evaluation(consumer);if(evaluation.isEmpty())continue;
                var captured=evaluation.get().dependencies().identity(entry.getKey().domain(),entry.getKey().value());
                if(captured.isPresent()&&!captured.get().equals(entry.getValue())){
                    keyChanged=true;changedConsumers.add(consumer);
                }else equalCandidates.add(consumer);
            }
            if(keyChanged)changed.put(entry.getKey(),entry.getValue());
        }
        equalCandidates.removeAll(changedConsumers);
        return new SourceLeafChanges(changed,equalCandidates.size());
    }
    private String sourceBinary(Path file){
        file=file.toAbsolutePath().normalize();Path owner=null;
        for(Path root:context.sources()){
            Path normalized=root.toAbsolutePath().normalize();
            if(file.startsWith(normalized)&&(owner==null||normalized.getNameCount()>owner.getNameCount()))owner=normalized;
        }
        if(owner==null||!file.toString().endsWith(".java"))return null;
        String relative=owner.relativize(file).toString();
        return relative.substring(0,relative.length()-5).replace(java.io.File.separatorChar,'.');
    }
    private Optional<Hash256> membershipTypeIdentity(String binary,boolean present){
        if(!present)return Optional.empty();
        if(liveSourceState==null)return Optional.empty();
        var source=liveSourceState.source(binary).orElse(null);if(source==null)return Optional.empty();
        String content=Objects.requireNonNullElse(liveSourceState.contentHash(source.file()),"<unknown>");
        var resident=semanticState().type(binary);
        if(resident!=null&&semanticState().unitCurrent("source:"+source.file().toAbsolutePath().normalize(),content))
            return Optional.of(resident.resolutionIdentity());
        return Optional.of(CanonicalDigestWriter.digest("source-namespace-candidate-v1",
                binary,source.file().toAbsolutePath().normalize().toString(),content));
    }
    private SourceLeafChanges sourceMembershipLeaves(String binary,boolean present)throws Exception{
        if(binary==null||binary.isBlank())return new SourceLeafChanges(Map.of(),0);
        var leaves=new TreeMap<QueryProof.Key,Hash256>();Hash256 declaration=membershipTypeIdentity(binary,present).orElse(null);
        for(var key:dependencies.semantic().proofs().dependencyKeys()){
            switch(key.domain()){
                case NAMESPACE -> {
                    if(key.value().equals("type:"+binary))
                        leaves.put(key,CanonicalDigestWriter.digest("namespace-search-domain-v1",binary,declaration));
                }
                case NEGATIVE_RESOLUTION -> {
                    int split=key.value().lastIndexOf('@');
                    if(split>0&&key.value().substring(split+1).replace((char)36,'.').equals(binary)){
                        String simple=key.value().substring(0,split);
                        Hash256 domain=CanonicalDigestWriter.digest("namespace-search-domain-v1",binary,declaration);
                        leaves.put(key,CanonicalDigestWriter.digest("negative-resolution-domain-v1",simple,binary,domain));
                    }
                }
                case RESOLUTION_PATH -> {
                    if(key.value().equals("type:"+binary))
                        leaves.put(key,CanonicalDigestWriter.digest("document-resolution-path-v1",binary,
                                declaration==null?null:binary,declaration));
                }
                default -> {}
            }
        }
        return changedSourceLeaves(leaves);
    }
    private static SourceLeafChanges mergeSourceLeafChanges(SourceLeafChanges first,SourceLeafChanges second){
        var changed=new TreeMap<QueryProof.Key,Hash256>(first.changed());changed.putAll(second.changed());
        return new SourceLeafChanges(changed,first.consumersStoppedEqual()+second.consumersStoppedEqual());
    }
    private static boolean sourceProofConsumer(SemanticUpdatePolicy.ProofConsumer consumer){
        return consumer.id().equals("source-semantic");
    }
    private record SourcePropagation(Set<Path> affected,Set<Path> coarse,SemanticUpdatePolicy.ProofPropagation propagation) {
        SourcePropagation { affected=Set.copyOf(affected);coarse=Set.copyOf(coarse); }
    }
    private SourcePropagation propagateSourceLeaves(SourceLeafChanges leafChanges,Collection<Path> initialCoarse)throws Exception{
        var leaves=leafChanges.changed();
        var propagation=dependencies.semantic().proofs().propagate(leaves,consumer->rebaseProof(consumer,leaves));
        var queryInvalid=new LinkedHashSet<SemanticUpdatePolicy.ProofConsumer>();
        propagation.changed().stream().filter(consumer->!sourceProofConsumer(consumer)).forEach(queryInvalid::add);
        propagation.fallback().stream().filter(consumer->!sourceProofConsumer(consumer)).forEach(queryInvalid::add);
        var caches=modules.get(context.generation());if(caches!=null&&!queryInvalid.isEmpty())invalidateDocumentProofConsumers(caches,queryInvalid);

        var sourceChanged=new LinkedHashSet<Path>();
        propagation.changed().stream().filter(Analyzer::sourceProofConsumer).map(SemanticUpdatePolicy.ProofConsumer::file).forEach(sourceChanged::add);
        var sourceFallback=new LinkedHashSet<Path>();
        propagation.fallback().stream().filter(Analyzer::sourceProofConsumer).map(SemanticUpdatePolicy.ProofConsumer::file).forEach(sourceFallback::add);
        for(Path file:sourceChanged)dependencies.semantic().proofCoverage(file,false);
        for(Path file:sourceFallback)dependencies.semantic().proofCoverage(file,false);

        var coarse=new LinkedHashSet<Path>(initialCoarse);var affected=new LinkedHashSet<Path>(initialCoarse);
        affected.addAll(sourceChanged);affected.addAll(sourceFallback);
        var fallbackClosure=dependencies.semantic().coarseFallback(sourceFallback);
        affected.addAll(fallbackClosure);coarse.addAll(fallbackClosure);
        sourceProofEvidence.record(leaves.size(),propagation,leafChanges.consumersStoppedEqual(),coarse.size(),sourceChanged.size()+sourceFallback.size());
        return new SourcePropagation(affected,coarse,propagation);
    }

    private void resolveContribution(FileSemanticContribution contribution){
        try{resolveContribution(contribution,null);}
        catch(RuntimeException failure){throw failure;}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    private void resolveContribution(FileSemanticContribution contribution,SemanticAdmission admission)throws Exception{
        if(liveSourceState!=null)liveSourceState.semantic(contribution.file(),contribution.sourceHash(),contribution.apiFingerprint(),contribution.exportedNames());
        boolean pending=dependencies.semantic().pending(contribution.file());
        var result=admission==null?dependencies.semantic().resolve(contribution):dependencies.semantic().resolvePrecise(contribution);
        if(pending){if(result.apiChanged().isEmpty())apiFingerprintUnchanged++;else apiFingerprintChanges++;}

        var coarse=new LinkedHashSet<>(result.reanalyze());coarse.remove(contribution.file());
        Set<Path> affected=Set.copyOf(coarse);
        if(admission!=null)affected=propagateSourceLeaves(sourceMutationLeaves(admission,contribution.file()),coarse).affected();
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
        path=path.toAbsolutePath().normalize();compiler.observeSources(Set.of(path));observeResidentSourceTransitions();
        var affected=dependencies.changed(path,hash);sourceProofEvidence.beginMutation(Math.max(0,affected.size()-(affected.contains(path)?1:0)));
        conditionallyInvalidate(path,affected);
    }
    public void changed(Path path){
        path=path.toAbsolutePath().normalize();compiler.observeSources(Set.of(path));observeResidentSourceTransitions();
        var affected=dependencies.changed(path);sourceProofEvidence.beginMutation(Math.max(0,affected.size()-(affected.contains(path)?1:0)));
        conditionallyInvalidate(path,affected);
    }
    /**
     * A source appears or disappears from the editor/disk namespace. Membership is discovery
     * evidence: proof-covered negative/name-resolution consumers are reconsidered only from the
     * binary-name domains this path can change. Consumers without precise negative proof coverage
     * retain the conservative unresolved-file closure.
     */
    public void sourceMembershipChanged(Path path)throws Exception{
        path=path.toAbsolutePath().normalize();
        compiler.documents(documents);compiler.observeSources(Set.of(path));
        String binary=sourceBinary(path);boolean present=documents.contains(path)||Files.isRegularFile(path);
        if(liveSourceState!=null)liveSourceState.observe(path);

        var coarseRoots=new LinkedHashSet<Path>();
        for(Path unresolved:dependencies.semantic().unresolved())
            if(!dependencies.semantic().proofCovered(unresolved))coarseRoots.add(unresolved);
        var coarse=new LinkedHashSet<Path>(coarseRoots);
        coarse.addAll(dependencies.semantic().coarseFallback(coarseRoots));
        coarse.remove(path);

        SourceLeafChanges membership=sourceMembershipLeaves(binary,present);
        SourceLeafChanges semantic=new SourceLeafChanges(Map.of(),0);
        if(!present){
            String unit="source:"+path;var state=semanticState().unit(unit);var oldFacts=new ArrayList<SemanticFact>();
            if(state!=null)for(String id:state.facts().ids()){var fact=semanticState().symbol(id);if(fact!=null)oldFacts.add(fact);}
            var delta=semanticState().removeUnit(unit);
            semantic=sourceMutationLeaves(new SemanticAdmission(delta,oldFacts),path);
            var removed=dependencies.semantic().removePrecise(path);
            coarse.addAll(removed.reanalyze());coarse.remove(path);
        }

        var propagation=propagateSourceLeaves(mergeSourceLeafChanges(semantic,membership),coarse);
        if(!propagation.affected().isEmpty()){
            diagnosticStore.invalidate(propagation.affected(),DiagnosticStore.Reason.DEPENDENCY_API_CHANGED);
            invalidateCompilerCaches(propagation.affected());
        }
        compiler.resetSourceContext();
    }
    /** Broad project-model namespace reset; editor source membership must use sourceMembershipChanged. */
    public void namespaceChanged(){diagnosticStore.clear();for(var caches:modules.values()){caches.outlines.clear();caches.focused.clear();clearSemanticCaches(caches);}dependencies.semantic().clear();for(var pool:compilerPools.values())pool.recycle();}
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
            dependencies.recordFocused(path,outcome.result().dependencies());
            if(cursor==null&&outcome.tier()==2){
                var contribution=SemanticContributions.from(path,hash,outcome.result(),outcome.diagnostics());
                var admission=admitSemanticMutation(semantic[0],contribution);
                resolveContribution(contribution,admission);
                if(admission!=null)registerSourceProof(path,text,outcome.result(),contribution);
                else{dependencies.semantic().proofs().remove(sourceProofConsumer(path));dependencies.semantic().proofCoverage(path,false);}
                publishSource(path,hash,stamp,semanticPublisherContextFingerprint(observed,stamp),outcome.result(),outcome.tier());
            }
            String member=focus==null?"full":focus.member();focused.put(path+":"+hash+":"+stamp+":"+member,new Cached(path,hash,stamp,focus==null?0:focus.member().equals("declarations")?cursor:focus.start(),focus==null?text.length():focus.member().equals("declarations")?cursor+1:focus.end(),focus==null?List.of():focus.replaced(),outcome));
            while(focused.size()>32)focused.remove(focused.keySet().iterator().next());
        }return outcome;
    }
    private String semanticPublisherContextFingerprint(CompilerInputs.Snapshot observed,String broadStamp){
        var caches=modules.get(context.generation());
        if(caches==null||!caches.classpathPrecise)return broadStamp;
        return CompilerInputs.compose("semantic-publisher-context-v2",
                caches.semanticOwnerIdentity,observed.membership().value());
    }
    private void publishSource(Path file,String hash,String stamp,String semanticContext,Bindings.Snapshot snapshot,int tier)throws Exception{
        if(index==null)return;
        long started=System.nanoTime();
        var symbols=List.copyOf(snapshot.symbols().values());
        var edges=snapshot.edges().stream().map(e->new IndexService.SourceEdge(e.src(),e.dst(),e.kind())).toList();
        var contribution=dependencies.semantic().contribution(file);
        // A dependency or unresolved-target replacement must survive publisher coalescing even
        // when this file's source and exported API did not change. A precisely-covered classpath
        // mutation is excluded here because its search leaves are propagated separately.
        String semantic=Hashing.sha256(Json.MAPPER.writeValueAsBytes(List.of(hash,contribution.apiFingerprint(),semanticContext,
                contribution.dependencies().stream().map(Path::toString).sorted().toList(),
                contribution.exportedNames().stream().sorted().toList(),contribution.unresolvedTargets().stream().sorted().toList())));
        long bytes=512L+2L*Json.MAPPER.writeValueAsBytes(symbols).length+edges.size()*192L;
        index.publishSource(new SourceIndexPublisher.Delta(contribution,semantic,symbols,tier,edges,bytes,context.gav(),semanticContext));
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
        diagnosticStore.inputs(observed);
        var values=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();
        // Resolve every API first: invalidation from a later file must not erase an earlier fresh result.
        if(result.result()!=null&&result.tier()==2&&result.warnings().isEmpty())for(var entry:result.result().entrySet()){
            dependencies.recordFocused(entry.getKey(),entry.getValue().dependencies());
            var contribution=SemanticContributions.from(entry.getKey(),Hashing.sha256(sources.get(entry.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),entry.getValue(),result.diagnostics().stream().filter(p->sameFile(p.file(),entry.getKey())).toList());
            var admission=admitSemanticMutation(semanticSnapshots.get(entry.getKey()),contribution);
            resolveContribution(contribution,admission);
            if(admission!=null)registerSourceProof(entry.getKey(),sources.get(entry.getKey()),entry.getValue(),contribution);
            else{dependencies.semantic().proofs().remove(sourceProofConsumer(entry.getKey()));dependencies.semantic().proofCoverage(entry.getKey(),false);}
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
                publishSource(file,hash,stamp,semanticPublisherContextFingerprint(observed,stamp),snapshot,result.tier());
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
        return CompilerInputs.compose("resident-context-anchor-v3",
                context.gav(),context.release(),context.workspace(),file.toString(),start,qualified);
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
    private boolean residentSemanticTypeCurrent(String typeId,String binaryName){
        if(residentSemanticUnitCurrent(semanticState().unitForFact(typeId)))return true;
        if(binaryName!=null&&!binaryName.isBlank()){
            if(residentSemanticUnitCurrent(semanticState().unitForType(binaryName)))return true;
            if(liveSourceState!=null){
                var source=liveSourceState.source(binaryName).orElse(null);
                if(source!=null&&residentSemanticUnitCurrent("source:"+source.file().toAbsolutePath().normalize()))return true;
            }
        }
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

    private Hash256 documentScopeIdentity(Focusing.Result focus){
        int start=Math.max(0,Math.min(focus.start(),focus.source().length()));
        int end=Math.max(start,Math.min(focus.end(),focus.source().length()));
        return CanonicalDigestWriter.digest("document-scope-proof-v1",focus.member(),focus.source().substring(start,end));
    }
    private Hash256 documentScopeIdentity(Path path,String patched,int focusCursor)throws Exception{
        return documentScopeIdentity(focusing.focus(path,patched,focusCursor));
    }
    private Hash256 receiverProofIdentity(DocumentSemanticSnapshot.QueryContext query)throws Exception{
        Hash256 declaration=null;
        if(query.receiverSymbolId()!=null&&!query.receiverSymbolId().isBlank())
            declaration=semanticReadView().identity(QueryProof.Domain.EXACT_SYMBOL,query.receiverSymbolId()).orElse(null);
        return CanonicalDigestWriter.digest("document-receiver-proof-v1",
                query.receiverType().identity(),Objects.toString(query.receiverSymbolId(),""),
                query.staticReceiver(),declaration);
    }
    private Hash256 hierarchyProofIdentity(DocumentSemanticSnapshot.QueryContext query)throws Exception{
        if(!context.preciseSourceRoots()||liveSourceState==null||!liveSourceState.snapshot().trusted())
            if(!ensureHierarchySemanticCurrent(query))return CanonicalDigestWriter.digest("document-hierarchy-proof-v1","<unavailable>");
        return CanonicalDigestWriter.digest("document-hierarchy-proof-v1",queryHierarchyApi(query));
    }
    private QueryProof.Dependency hierarchyProofDependency(DocumentSemanticSnapshot.QueryContext query)throws Exception{
        if(query.receiverType() instanceof SemanticType.Declared declared){
            var maintained=semanticReadView().identity(QueryProof.Domain.HIERARCHY,declared.symbolId());
            if(maintained.isPresent())
                return new QueryProof.Dependency(QueryProof.Domain.HIERARCHY,declared.symbolId(),maintained.get());
        }
        String key="document:"+Objects.toString(query.receiverSymbolId(),"receiver");
        return new QueryProof.Dependency(QueryProof.Domain.HIERARCHY,key,hierarchyProofIdentity(query));
    }
    private Hash256 accessibilityProofIdentity(DocumentSemanticSnapshot.QueryContext query)throws Exception{
        Hash256 enclosing=null;
        if(query.enclosingTypeId()!=null&&!query.enclosingTypeId().isBlank())
            enclosing=semanticReadView().identity(QueryProof.Domain.EXACT_SYMBOL,query.enclosingTypeId()).orElse(null);
        return CanonicalDigestWriter.digest("document-accessibility-proof-v1",
                context.release(),context.compilerOptions(),query.receiverType().identity(),
                Objects.toString(query.receiverSymbolId(),""),query.staticReceiver(),query.packageName(),
                Objects.toString(query.enclosingTypeId(),""),enclosing,query.staticContext(),hierarchyProofIdentity(query));
    }
    private String accessibilityKey(DocumentSemanticSnapshot.QueryContext query)throws Exception{
        return accessibilityProofIdentity(query).hex();
    }
    private DocumentSemanticSnapshot.QueryContext registerAccessibility(ModuleCaches caches,SemanticFacts.CompletionContext result)throws Exception{
        String key=accessibilityKey(result.query());caches.accessibility.put(key,result.accessibleMemberIds());
        return result.query().withAccessibilityKey(key);
    }
    private boolean accessibilityCurrent(DocumentSemanticSnapshot.QueryContext query)throws Exception{
        return query.accessibilityKey().equals(accessibilityKey(query))
                &&modules.get(context.generation()).accessibility.contains(query.accessibilityKey());
    }
    private Set<String> accessibility(DocumentSemanticSnapshot.QueryContext query){
        return modules.get(context.generation()).accessibility.get(query.accessibilityKey());
    }

    private Hash256 namespaceProofIdentity(String text,CompilerInputs.Snapshot observed){
        var imports=new TreeSet<String>();
        var matcher=java.util.regex.Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$*][\\w$*]*)*)\\s*;").matcher(text);
        while(matcher.find())imports.add((matcher.group(1)==null?"":"static ")+matcher.group(2));
        var packageMatcher=java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;").matcher(text);
        String pkg=packageMatcher.find()?packageMatcher.group(1):"";
        return CanonicalDigestWriter.digest("document-namespace-proof-v1",pkg,List.copyOf(imports),
                observed.live().snapshot().state().namespace().fingerprint().value());
    }

    private static String simpleTypeName(String binary){
        String value=Objects.requireNonNullElse(binary,"").replace((char)36,'.');
        int split=value.lastIndexOf('.');return split<0?value:value.substring(split+1);
    }
    private Optional<Hash256> namespaceTypeIdentity(String binary)throws Exception{
        if(liveSourceState!=null){
            var source=liveSourceState.source(binary).orElse(null);
            if(source!=null){
                Path file=source.file().toAbsolutePath().normalize();
                if(!ensureSourceSemanticCurrent(file)){
                    String content=Objects.requireNonNullElse(liveSourceState.contentHash(file),"<missing>");
                    return Optional.of(CanonicalDigestWriter.digest("namespace-type-unavailable-v1",binary,content));
                }
            }
        }
        var symbol=semanticReadView().type(binary);
        return symbol==null?Optional.empty():Optional.of(symbol.resolutionIdentity());
    }
    private List<QueryProof.Dependency> namespaceDependencies(String text,DocumentSemanticSnapshot.QueryContext query,
                                                              Collection<String> simpleNames)throws Exception{
        if(simpleNames==null||simpleNames.isEmpty())return List.of();
        String receiver=query.receiverType() instanceof SemanticType.Declared declared?declared.name():null;
        if(receiver==null)return List.of();
        var plans=new ArrayList<NamespaceResolutionProofs.Plan>();
        var packages=new TreeSet<String>();
        for(String simple:new TreeSet<>(simpleNames)){
            if(!simpleTypeName(receiver).equals(simple))return List.of();
            var plan=NamespaceResolutionProofs.plan(text,simple,receiver);
            if(!plan.precise())return List.of();
            plans.add(plan);packages.addAll(plan.packages());
        }
        if(liveSourceState!=null&&!packages.isEmpty())liveSourceState.reconcilePackages(packages);
        var result=new ArrayList<QueryProof.Dependency>();
        for(var plan:plans)result.addAll(NamespaceResolutionProofs.dependencies(plan,this::namespaceTypeIdentity));
        return List.copyOf(result);
    }
    private QueryProof.Dependency classpathDependency(DocumentSemanticSnapshot.QueryContext query,
                                                         boolean qualified,CompilerInputs.Snapshot observed)throws Exception{
        if(qualified&&query.receiverType() instanceof SemanticType.Declared declared
                &&index!=null&&!context.workspace().isBlank()){
            var proof=index.store().semanticClasspathSearch(context.workspace(),declared.name());
            if(proof.isPresent()){
                var caches=modules.get(context.generation());
                if(caches!=null&&caches.classpathPrecise)caches.classpathSearchProofs.put(declared.name(),proof.get());
                return new QueryProof.Dependency(proof.get().key(),proof.get().identity());
            }
        }
        var key=new QueryProof.Key(QueryProof.Domain.CLASSPATH_SEARCH,
                context.workspace().isBlank()?"compiler":"workspace:"+context.workspace());
        return new QueryProof.Dependency(key,classpathProofIdentity(key,observed));
    }
    private Hash256 classpathProofIdentity(QueryProof.Key key,CompilerInputs.Snapshot observed)throws Exception{
        if(key.domain()!=QueryProof.Domain.CLASSPATH_SEARCH)throw new IllegalArgumentException("Not a classpath proof key");
        if(key.value().startsWith("binary:")&&index!=null&&!context.workspace().isBlank()){
            String binary=key.value().substring("binary:".length());
            var proof=index.store().semanticClasspathSearch(context.workspace(),binary);
            if(proof.isPresent())return proof.get().identity();
        }
        if(index!=null&&!context.workspace().isBlank()){
            var identity=index.store().semanticClasspathIdentity(context.workspace());
            if(identity.isPresent())return identity.get();
        }
        return CanonicalDigestWriter.digest("document-classpath-search-fallback-v1",
                context.release(),context.classpath().stream().map(path->path.toAbsolutePath().normalize().toString()).toList(),
                observed.environment().value());
    }
    private Hash256 resolutionPathIdentity(String key)throws Exception{
        if(key.startsWith("type:")){
            String binary=key.substring("type:".length());
            if(liveSourceState!=null){
                var source=liveSourceState.source(binary).orElse(null);
                if(source!=null){
                    Path file=source.file().toAbsolutePath().normalize();
                    if(!ensureSourceSemanticCurrent(file))
                        return CanonicalDigestWriter.digest("document-resolution-path-v1",binary,"<unavailable>");
                }
            }
            var symbol=semanticReadView().type(binary);
            return CanonicalDigestWriter.digest("document-resolution-path-v1",binary,
                    symbol==null?null:symbol.fqn(),symbol==null?null:symbol.resolutionIdentity());
        }
        if(key.startsWith("source:")){
            Path source=Path.of(key.substring("source:".length())).toAbsolutePath().normalize();
            if(!ensureCompletionSemantics(Set.of(source)))
                return CanonicalDigestWriter.digest("document-resolution-path-v1",source.toString(),"<unavailable>");
            var leaf=documents.liveState(context.sources()).leaf(source).orElse(null);
            return CanonicalDigestWriter.digest("document-resolution-path-v1",source.toString(),
                    leaf==null?"<missing>":leaf.api().value());
        }
        return CanonicalDigestWriter.digest("document-resolution-path-v1",key,"<unknown>");
    }

    private QueryProof documentContextProof(Path path,String text,Focusing.Result focus,
                                             DocumentSemanticSnapshot.QueryContext query,boolean qualified,
                                             Collection<String> namespaceNames,Collection<Path> dependencySources,
                                             CompilerInputs.Snapshot observed)throws Exception{
        var dependencies=new ArrayList<QueryProof.Dependency>();
        String queryKey=path.toAbsolutePath().normalize()+"#"+query.selectorOffset();
        dependencies.add(new QueryProof.Dependency(QueryProof.Domain.DOCUMENT_SCOPE,queryKey,documentScopeIdentity(focus)));
        dependencies.add(new QueryProof.Dependency(QueryProof.Domain.RECEIVER,"receiver",receiverProofIdentity(query)));
        if(query.receiverType() instanceof SemanticType.Declared declared){
            String resolutionKey="type:"+declared.name();
            dependencies.add(new QueryProof.Dependency(QueryProof.Domain.RESOLUTION_PATH,resolutionKey,resolutionPathIdentity(resolutionKey)));
        }
        var sourceKeys=new TreeSet<String>();
        for(Path source:dependencySources)sourceKeys.add(source.toAbsolutePath().normalize().toString());
        for(String source:sourceKeys)
            dependencies.add(new QueryProof.Dependency(QueryProof.Domain.RESOLUTION_PATH,"source:"+source,resolutionPathIdentity("source:"+source)));
        dependencies.add(hierarchyProofDependency(query));
        dependencies.add(new QueryProof.Dependency(QueryProof.Domain.ACCESSIBILITY,"context",accessibilityProofIdentity(query)));
        var namespace=namespaceDependencies(text,query,namespaceNames);
        if(namespace.isEmpty())
            dependencies.add(new QueryProof.Dependency(QueryProof.Domain.NAMESPACE,"visible",namespaceProofIdentity(text,observed)));
        else dependencies.addAll(namespace);
        dependencies.add(classpathDependency(query,qualified,observed));
        return new QueryProof(dependencies);
    }
    private QueryProof currentDocumentContextProof(Path path,String text,String patched,int focusCursor,
                                                    DocumentSemanticSnapshot snapshot,
                                                    DocumentSemanticSnapshot.QueryContext query,
                                                    CompilerInputs.Snapshot observed)throws Exception{
        String currentContent=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var dependencies=new ArrayList<QueryProof.Dependency>();
        var namespaceNames=new TreeSet<String>();boolean broadNamespace=false;
        for(var dependency:query.proof().dependencies()){
            var domain=dependency.key().domain();
            if(domain==QueryProof.Domain.NEGATIVE_RESOLUTION)continue;
            if(domain==QueryProof.Domain.NAMESPACE){
                if(dependency.key().value().startsWith("plan:"))
                    namespaceNames.add(NamespaceResolutionProofs.simpleNameFromPlanKey(dependency.key().value()));
                else if(dependency.key().value().equals("visible"))broadNamespace=true;
                continue;
            }
            Hash256 identity=switch(domain){
                case DOCUMENT_SCOPE -> currentContent.equals(snapshot.contentIdentity())
                        ?dependency.identity():documentScopeIdentity(path,patched,focusCursor);
                case RECEIVER -> receiverProofIdentity(query);
                case RESOLUTION_PATH -> resolutionPathIdentity(dependency.key().value());
                case HIERARCHY -> dependency.key().value().startsWith("document:")
                        ?hierarchyProofIdentity(query)
                        :semanticReadView().identity(QueryProof.Domain.HIERARCHY,dependency.key().value())
                                .orElseGet(()->unavailableProofIdentity(dependency.key()));
                case ACCESSIBILITY -> accessibilityProofIdentity(query);
                case CLASSPATH_SEARCH -> classpathProofIdentity(dependency.key(),observed);
                default -> dependency.identity();
            };
            dependencies.add(new QueryProof.Dependency(dependency.key(),identity));
        }
        if(!namespaceNames.isEmpty())dependencies.addAll(namespaceDependencies(text,query,namespaceNames));
        else if(broadNamespace)dependencies.add(new QueryProof.Dependency(
                QueryProof.Domain.NAMESPACE,"visible",namespaceProofIdentity(text,observed)));
        return new QueryProof(dependencies);
    }
    private boolean documentProofCurrent(Path path,String text,String patched,int focusCursor,
                                         DocumentSemanticSnapshot snapshot,DocumentSemanticSnapshot.QueryContext query,
                                         CompilerInputs.Snapshot observed)throws Exception{
        if(query.proof().dependencies().isEmpty())return false;
        if(!accessibilityCurrent(query))return false;
        return query.proof().equals(currentDocumentContextProof(path,text,patched,focusCursor,snapshot,query,observed));
    }

    private DocumentSemanticCached reusableDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                                  String key,CompilerInputs.Snapshot observed)throws Exception{
        if(key==null)return null;
        var caches=modules.get(context.generation());var cached=caches.documentSemantics.get(path);
        if(cached==null||!key.equals(cached.key())||cached.snapshot().query(start)==null)return null;
        var query=cached.snapshot().query(start);
        if(!documentProofCurrent(path,text,patched,focusCursor,cached.snapshot(),query,observed))return null;
        int version=Objects.requireNonNullElse(documents.version(path),-1);
        String content=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var rebased=new DocumentSemanticSnapshot(path.toString(),version,content,semanticState().identity().epoch(),
                cached.snapshot().queries());
        var reused=new DocumentSemanticCached(key,rebased);caches.documentSemantics.put(path,reused);return reused;
    }

    private DocumentSemanticCached qualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                               String key,CompilerInputs.Snapshot observed,boolean force)throws Exception{
        return qualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,observed,force,false,0);
    }
    private DocumentSemanticCached qualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                               String key,CompilerInputs.Snapshot observed,boolean force,boolean discovered,int supersededRetries)throws Exception{
        var caches=modules.get(context.generation());int version=Objects.requireNonNullElse(documents.version(path),-1);
        String content=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!force){
            var reused=reusableDocumentSemantic(path,text,patched,start,focusCursor,key,observed);
            if(reused!=null)return reused;
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
        for(var snapshot:result.semanticSnapshots())admitDetachedSemantic(snapshot);
        var query=registerAccessibility(caches,result);
        query=query.withProof(documentContextProof(path,text,focus,query,true,result.nameResolutionNames(),List.of(),observed));
        var snapshot=new DocumentSemanticSnapshot(path.toString(),version,content,semanticState().identity().epoch(),
                Map.of(start,query));
        var next=new DocumentSemanticCached(key,snapshot);caches.documentSemantics.put(path,next);registerDocumentProof(path,query);return next;
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

    private DocumentSemanticCached unqualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                                 String key,CompilerInputs.Snapshot observed,boolean force)throws Exception{
        return unqualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,observed,force,false,0);
    }
    private DocumentSemanticCached unqualifiedDocumentSemantic(Path path,String text,String patched,int start,int focusCursor,
                                                                 String key,CompilerInputs.Snapshot observed,boolean force,boolean discovered,int supersededRetries)throws Exception{
        var caches=modules.get(context.generation());int version=Objects.requireNonNullElse(documents.version(path),-1);
        String content=Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!force){
            var reused=reusableDocumentSemantic(path,text,patched,start,focusCursor,key,observed);
            if(reused!=null)return reused;
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
        for(var snapshot:result.semanticSnapshots())admitDetachedSemantic(snapshot);
        var query=registerAccessibility(caches,result);
        var dependencyApis=documentDependencyApis(query,path);if(dependencyApis==null)return null;
        query=query.withProof(documentContextProof(path,text,focus,query,false,List.of(),dependencyApis.keySet(),observed));
        var snapshot=new DocumentSemanticSnapshot(path.toString(),version,content,semanticState().identity().epoch(),
                Map.of(start,query));
        var next=new DocumentSemanticCached(key,snapshot);caches.documentSemantics.put(path,next);registerDocumentProof(path,query);return next;
    }

    private Envelope residentQualifiedCompletion(Path path,String text,String patched,int start,int end,int focusCursor,String prefix,
                                                  int limit,int offset,String key,CompilerInputs.Snapshot observed,boolean reuseOnly)throws Exception{
        if(key==null)return null;
        var cached=reuseOnly?reusableDocumentSemantic(path,text,patched,start,focusCursor,key,observed)
                :qualifiedDocumentSemantic(path,text,patched,start,focusCursor,key,observed,false);
        if(cached==null)return null;
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
            int cursor=Documents.offset(text,new Documents.Position(line,character));
            var probe=CompletionProbe.create(text,cursor);
            int start=probe.selectorStart(),end=probe.selectorEnd(),focusCursor=probe.focusCursor();
            String prefix=probe.prefix(),patched=probe.source();
            boolean qualified=probe.qualified();
            synchronizeKnownSources(path);touch(path,text);
            var observed=validatedInputs();String residentKey=residentContextKey(path,patched,start,observed,qualified);
            Envelope resident;
            try{
                if(qualified){
                    // Tier 0: consume an existing detached context before doing any lexical/index
                    // receiver reconstruction. Prefix edits share the same receiver anchor.
                    resident=residentQualifiedCompletion(path,text,patched,start,end,focusCursor,prefix,limit,offset,residentKey,observed,true);
                    if(resident!=null)return resident;
                    // Tier 1: establish a straightforward receiver directly from maintained facts.
                    var maintained=maintainedQualifiedCompletion(path,text,start,end,prefix,probe,limit,offset);
                    if(maintained!=null)return maintained;
                }
                resident=qualified
                        ?residentQualifiedCompletion(path,text,patched,start,end,focusCursor,prefix,limit,offset,residentKey,observed,false)
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
        value.put("editor_label",candidate.editorLabel());
        if(candidate.resolutionIdentity()!=null)value.put("resolution_identity",candidate.resolutionIdentity().hex());
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

    private static String inheritedMemberShape(SemanticReadView.Symbol fact){
        if(fact.kind().equals("method")){
            String descriptor=Objects.requireNonNullElse(fact.erasedDescriptor(),"");
            int close=descriptor.indexOf(')');
            String parameters=close>=0?descriptor.substring(0,close+1):descriptor;
            return "method\\0"+fact.name()+"\\0"+parameters;
        }
        if(Set.of("field","enumconst").contains(fact.kind()))return "field\\0"+fact.name();
        if(Set.of("class","interface","enum","record","annotation").contains(fact.kind()))return "type\\0"+fact.name();
        return fact.kind()+"\\0"+fact.name()+"\\0"+Objects.requireNonNullElse(fact.erasedDescriptor(),"");
    }

    private Map<String,SemanticType> semanticTypeSubstitutions(SemanticReadView.Symbol type,SemanticType.Declared instantiated){
        if(type.typeParameters().isEmpty()||instantiated.arguments().isEmpty())return Map.of();
        var result=new LinkedHashMap<String,SemanticType>();
        int count=Math.min(type.typeParameters().size(),instantiated.arguments().size());
        for(int i=0;i<count;i++)result.put("$T"+i,instantiated.arguments().get(i));
        return Map.copyOf(result);
    }

    private String semanticTypeLabel(SemanticType type,Map<String,String> names)throws Exception{
        return switch(type){
            case SemanticType.Primitive value -> value.name();
            case SemanticType.Declared value -> {
                String canonical=value.name();
                String display=names.get(canonical);
                if(display==null){
                    int split=Math.max(canonical.lastIndexOf('.'),canonical.lastIndexOf(36));
                    String simple=canonical.substring(split+1);
                    var matches=semanticTypes(simple).stream().map(SemanticReadView.Symbol::fqn).filter(v->!v.isBlank()).distinct().toList();
                    display=matches.size()<=1?simple:canonical.replace((char)36,'.');
                    names.put(canonical,display);
                }
                if(value.arguments().isEmpty())yield display;
                var arguments=new ArrayList<String>();
                for(var argument:value.arguments())arguments.add(semanticTypeLabel(argument,names));
                yield display+"<"+String.join(", ",arguments)+">";
            }
            case SemanticType.Variable value -> value.name();
            case SemanticType.Array value -> semanticTypeLabel(value.component(),names)+"[]";
            case SemanticType.Wildcard value -> value.extendsBound()!=null
                    ?"? extends "+semanticTypeLabel(value.extendsBound(),names)
                    :value.superBound()!=null?"? super "+semanticTypeLabel(value.superBound(),names):"?";
            case SemanticType.Intersection value -> {
                var parts=new ArrayList<String>();for(var bound:value.bounds())parts.add(semanticTypeLabel(bound,names));
                yield String.join(" & ",parts);
            }
            case SemanticType.Executable value -> value.display();
            case SemanticType.Unknown value -> value.text();
        };
    }

    private CompletionCandidate semanticCandidate(SemanticReadView.Symbol fact,Map<String,SemanticType> substitutions,
                                                  Map<String,String> typeNames)throws Exception{
        SemanticType contextual=fact.semanticType().substitute(substitutions);
        String label=fact.name()+": "+contextual.display();
        String editorLabel=fact.name()+" : "+CompletionCandidate.typeLabel(contextual,false);
        var labels=new ArrayList<CompletionCandidate.ParameterLabel>();
        if(contextual instanceof SemanticType.Executable executable){
            var value=new StringBuilder(fact.name()).append('(');
            var editor=new StringBuilder(fact.name()).append('(');
            for(int i=0;i<executable.parameters().size();i++){
                if(i>0){value.append(", ");editor.append(", ");}
                String parameter=executable.parameters().get(i).display();
                String editorParameter=CompletionCandidate.typeLabel(executable.parameters().get(i),true);
                if(fact.varargs()&&i==executable.parameters().size()-1&&parameter.endsWith("[]"))
                    parameter=parameter.substring(0,parameter.length()-2)+"...";
                if(fact.varargs()&&i==executable.parameters().size()-1&&editorParameter.endsWith("[]"))
                    editorParameter=editorParameter.substring(0,editorParameter.length()-2)+"...";
                int parameterStart=value.length();value.append(parameter);editor.append(editorParameter);
                if(i<fact.parameterNames().size()&&!fact.parameterNames().get(i).isBlank()){
                    value.append(' ').append(fact.parameterNames().get(i));
                    editor.append(' ').append(fact.parameterNames().get(i));
                }
                labels.add(new CompletionCandidate.ParameterLabel(parameterStart,value.length()));
            }
            value.append(')');editor.append(')');
            if(!fact.kind().equals("ctor")){
                value.append(": ").append(executable.returns().display());
                editor.append(" : ").append(CompletionCandidate.typeLabel(executable.returns(),true));
            }
            label=value.toString();editorLabel=editor.toString();
        }
        return new CompletionCandidate(fact.id(),fact.name(),fact.kind(),fact.signature(),fact.resolution().ownerKey(),
                fact.sourceFile(),fact.modifiers(),label,editorLabel,labels,fact.resolutionIdentity());
    }

    private record SemanticHierarchyOwner(SemanticReadView.Symbol symbol,SemanticType.Declared instantiated,
                                          Map<String,SemanticType> substitutions,int order) { }

    private List<SemanticHierarchyOwner> semanticHierarchyOwners(SemanticReadView view,
            CompletionContextResolver.Resolved resolved)throws Exception{
        var rootType=resolved.receiverType() instanceof SemanticType.Declared declared
                ?declared
                :resolved.receiver().semanticType() instanceof SemanticType.Declared declared?declared:null;
        if(rootType==null)return List.of();
        var queue=new ArrayDeque<SemanticHierarchyOwner>();
        queue.add(new SemanticHierarchyOwner(resolved.receiver(),rootType,
                semanticTypeSubstitutions(resolved.receiver(),rootType),0));
        var seen=new HashSet<String>();var result=new ArrayList<SemanticHierarchyOwner>();
        while(!queue.isEmpty()){
            var current=queue.removeFirst();if(!seen.add(current.symbol().id()))continue;
            if(view.completeness(current.symbol().id())!=SemanticCompleteness.COMPLETE)return null;
            var admitted=new SemanticHierarchyOwner(current.symbol(),current.instantiated(),current.substitutions(),result.size());
            result.add(admitted);
            for(var parent:current.symbol().directSupertypes()){
                var substituted=parent.substitute(current.substitutions());
                if(!(substituted instanceof SemanticType.Declared declared))continue;
                var matches=semanticTypes(declared.name());
                if(matches.size()!=1)continue;
                var symbol=matches.getFirst();
                queue.addLast(new SemanticHierarchyOwner(symbol,declared,semanticTypeSubstitutions(symbol,declared),0));
            }
        }
        return List.copyOf(result);
    }

    private List<Map<String,Object>> semanticQualifiedRows(SemanticReadView view,CompletionContextResolver.Resolved resolved,
                                                            String prefix,int target)throws Exception{
        if(target<=0)return List.of();
        SemanticReadView.Symbol enclosing=resolved.enclosingTypeId()==null?null:view.symbol(resolved.enclosingTypeId());
        int perOwnerBudget=Math.max(64,Math.min(4096,target*8));
        var selected=new HashMap<String,HierarchyChoice>();var typeNames=new HashMap<String,String>();
        var owners=semanticHierarchyOwners(view,resolved);if(owners==null)return null;
        for(var owner:owners){
            String cursor=null;int examined=0;
            do{
                int pageSize=Math.min(128,perOwnerBudget-examined);if(pageSize<=0)break;
                var page=view.members(owner.symbol().id(),prefix,pageSize,cursor);
                for(var member:page.symbols()){
                    examined++;
                    if(member.kind().equals("ctor")||member.kind().equals("package")||member.kind().equals("module"))continue;
                    boolean declarationType=Set.of("class","interface","enum","record","annotation").contains(member.kind());
                    if(resolved.staticReceiver()&&!member.staticMember()&&!declarationType)continue;
                    var access=CompletionContextResolver.access(member,resolved.packageName(),enclosing,resolved.enclosingTypeName());
                    if(access==CompletionContextResolver.Access.UNKNOWN)return null;
                    if(access==CompletionContextResolver.Access.DENIED)continue;
                    String shape=inheritedMemberShape(member);
                    var row=residentCompletionRow(semanticCandidate(member,owner.substitutions(),typeNames),member.name());
                    var previous=selected.get(shape);
                    if(previous==null||owner.order()<previous.ownerOrder())selected.put(shape,new HierarchyChoice(owner.order(),row));
                }
                cursor=page.cursor();
            }while(cursor!=null&&examined<perOwnerBudget);
        }
        return selected.values().stream().map(HierarchyChoice::row)
                .sorted(qualifiedCompletionOrder(resolved.staticReceiver())).limit(target).toList();
    }

    @SuppressWarnings("unchecked")
    private static boolean completionStatic(Map<String,Object> row){
        Object raw=row.get("modifiers");
        return raw instanceof Collection<?> values&&values.contains("static");
    }
    private static int instanceCompletionRank(Map<String,Object> row){
        boolean statik=completionStatic(row);
        boolean field=Set.of("field","enumconst").contains(Objects.toString(row.get("kind"),""));
        if(!statik&&field)return 0;
        if(!statik)return 1;
        if(field)return 2;
        return 3;
    }
    private static Comparator<Map<String,Object>> qualifiedCompletionOrder(boolean staticReceiver){
        return staticReceiver?COMPLETION_ORDER:
                Comparator.comparingInt(Analyzer::instanceCompletionRank).thenComparing(COMPLETION_ORDER);
    }

    private boolean moduleSensitiveCompletion(){
        for(String option:context.compilerOptions()){
            if(Set.of("--module-path","-p","--module","-m","--add-exports","--add-reads","--patch-module",
                    "--limit-modules","--upgrade-module-path").contains(option)
                    ||option.startsWith("--add-exports=")||option.startsWith("--add-reads=")
                    ||option.startsWith("--patch-module=")||option.startsWith("--module-path=")
                    ||option.startsWith("--upgrade-module-path="))return true;
        }
        return false;
    }

    private Envelope maintainedQualifiedCompletion(Path path,String text,int start,int end,String prefix,
                                                    CompletionProbe.Shape probe,int limit,int offset)throws Exception{
        if(moduleSensitiveCompletion())return null;
        var view=semanticReadView();
        var resolved=CompletionContextResolver.resolve(text,probe,view,this::semanticTypes);
        if(resolved==null)return null;
        int target=(int)Math.min(Integer.MAX_VALUE,(long)offset+limit+1L);
        var rows=semanticQualifiedRows(view,resolved,prefix,target);if(rows==null)return null;
        registerCompletionRangeProof(path,start,prefix,view,resolved);
        int from=Math.min(offset,rows.size()),to=Math.min(rows.size(),from+limit);
        var returned=List.copyOf(rows.subList(from,to));boolean more=rows.size()>to;
        completionRequests++;
        try(var trace=dev.jvmd.core.RequestScope.stage("completion.maintained")){
            trace.cache("semantic-read");trace.count("rows_returned",returned.size());
        }
        return new Envelope(2,"live",more,more?Integer.toString(to):null,warnings(List.of()),
                Map.of("items",returned,"range",new SourceText(text).range(start,end)));
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

    private enum QualifiedMemberMode { ALL, STATIC_ONLY }
    private List<Map<String,Object>> residentHierarchyRows(DocumentSemanticSnapshot.QueryContext query,String prefix,int target,QualifiedMemberMode memberMode,
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
                            &&(member.typeDeclaration()
                                ||memberMode==QualifiedMemberMode.ALL
                                ||memberMode==QualifiedMemberMode.STATIC_ONLY&&member.modifiers().contains("static"))
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
        if(query.staticReceiver())
            return residentHierarchyRows(query,prefix,target,QualifiedMemberMode.STATIC_ONLY,Set.of(),Set.of());
        int scanTarget=Math.max(target,Math.min(4096,target*8));
        return residentHierarchyRows(query,prefix,scanTarget,QualifiedMemberMode.ALL,Set.of(),Set.of()).stream()
                .sorted(qualifiedCompletionOrder(false)).limit(target).toList();
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
                ?residentHierarchyRows(query,prefix,target,
                    query.staticContext()?QualifiedMemberMode.STATIC_ONLY:QualifiedMemberMode.ALL,
                    scopeRows.keySet(),variableNames):List.<Map<String,Object>>of();

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
        value.put("resolution_identity",fact.resolutionIdentity().hex());
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
        result.put("source_proof_evidence",sourceProofEvidence.status());
        if(context!=null){
            var active=modules.get(context.generation());
            result.put("resident_description_cache_entries",active.descriptions.size());
            result.put("resident_accessibility_cache",active.accessibility.status());
            result.put("document_semantic_contexts",active.documentSemantics.size());
            result.put("semantic_proof_consumers",dependencies.semantic().proofs().size());
            result.put("classpath_proof_precise",active.classpathPrecise);
            result.put("classpath_search_proofs",active.classpathSearchProofs.size());
            result.put("classpath_proof_evidence",active.classpathProofEvidence.status());
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
