package dev.jvmd.analyzer;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;

/**
 * Attributed LOCAL memos (architecture §68–75, §84, §87): persist and restore attributed
 * diagnostics with their detached contribution. Owned by one {@link Analyzer}; every method except
 * the background writer runs on the analyzer's owner thread.
 */
final class AttributedMemos implements AutoCloseable {
    private final Analyzer analyzer;
    AttributedMemos(Analyzer analyzer){this.analyzer=analyzer;}
    private Analyzer.Context context(){return analyzer.currentContext();}

    void attach(SemanticMemoStore store){
        attributedMemos=store;sourceNamespaces=new SourceNamespaces(store);namespaceCache.clear();
    }
    private SemanticMemoStore attributedMemos;
    private SourceNamespaces sourceNamespaces=new SourceNamespaces(null);
    private record NamespaceEntry(String hash,Object value) { }
    private final Map<Path,NamespaceEntry> namespaceCache=new java.util.concurrent.ConcurrentHashMap<>();
    private long attributedMemoRestores,attributedMemoMisses,attributedMemoRefusals;
    private final java.util.concurrent.atomic.AtomicLong attributedMemoWrites=new java.util.concurrent.atomic.AtomicLong(),
            attributedMemoFailures=new java.util.concurrent.atomic.AtomicLong(),coarseAttributedCertificates=new java.util.concurrent.atomic.AtomicLong();
    private final Map<String,Long> attributedMemoRefusalReasons=new TreeMap<>();
    private String lastAttributedMemoMiss="";

    /** Bump when the attributed result, its canonical encoding or its certificate rules change (§81). */
    static final SemanticMemoStore.Function ATTRIBUTED=new SemanticMemoStore.Function("attributed-diagnostics",1);

    private <T> Optional<T> refuseAttributed(String reason){
        attributedMemoRefusals++;attributedMemoRefusalReasons.merge(reason,1L,Long::sum);return Optional.empty();
    }
    private static boolean processorsConfigured(Analyzer.Context context){
        if(!context.binarySources().isEmpty())return true;
        for(String option:context.compilerOptions()){
            if(option.equals("-processor")||option.startsWith("-processorpath")||option.startsWith("--processor-path")
                    ||option.startsWith("--processor-module-path")||option.equals("-proc:full")||option.equals("-proc:only")
                    ||option.startsWith("-A"))return true;
        }
        return context.warnings().stream().anyMatch(warning->warning.contains("processor")||warning.contains("lombok"));
    }
    /** Platform identity from content only: the JDK location is not part of semantic meaning (§103). */
    private Hash256 platformContentIdentity()throws Exception{
        Path home=Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        var values=new ArrayList<Object>();
        for(String name:List.of("release","lib/modules","lib/ct.sym"))values.add(List.of(name,analyzer.inputFiles().hash(home.resolve(name))));
        return CanonicalDigestWriter.digest("semantic-platform-content-v1",Runtime.version().toString(),values);
    }
    /**
     * Static memo key (§70, §84): everything the in-process compiler owner holds fixed. Returns empty
     * when any ambient input cannot be bound logically; that context is simply not memoised.
     */
    private Optional<SemanticMemoStore.StaticKey> attributedStaticKey(Path file,String sourceHash,LogicalSources logical)throws Exception{
        if(attributedMemos==null||context()==null)return Optional.empty();
        if(!context().preciseSourceRoots())return refuseAttributed("imprecise-source-roots");
        if(Analyzer.hasUnprovenPathOptions(context()))return refuseAttributed("path-options");
        if(processorsConfigured(context()))return refuseAttributed("annotation-processors");
        var source=logical.logical(file);if(source.isEmpty())return refuseAttributed("non-logical-source");
        for(Path root:context().sources())if(logical.logical(root.resolve("x.java")).isEmpty())return refuseAttributed("non-logical-source-root");
        Hash256 classpath;
        if(context().classpath().isEmpty())classpath=ClasspathSequence.empty().identity();
        else{
            var sequence=analyzer.preciseClasspathSequence();
            if(sequence.isEmpty())return refuseAttributed("classpath-unproven");
            classpath=sequence.get().identity();
        }
        var classpathContext=Analyzer.classpathContext(context());
        var roots=logical.roots().stream().map(LogicalSources.Root::logical).sorted().toList();
        return Optional.of(SemanticMemoStore.StaticKey.of(ATTRIBUTED,source.get(),sourceHash,context().gav(),classpathContext.scope(),
                context().release(),context().compilerOptions(),platformContentIdentity(),classpath,roots));
    }
    private String namespaceKey(){return "source-roots:"+context().gav()+"|"+Analyzer.classpathContext(context()).scope();}
    private String rootsContentKey(){return "source-roots-content:"+context().gav()+"|"+Analyzer.classpathContext(context()).scope();}
    private static Hash256 logicalContentIdentity(String hash){return CanonicalDigestWriter.digest("logical-source-content-v1",hash);}
    /** One unit of the compiler source roots as observed at a single moment (stats only). */
    private record RootFile(Path file,String logical,String hash) { }
    /** Snapshot of every unit in the compiler source roots, or empty when any unit is not logical. */
    private Optional<List<RootFile>> rootsSnapshot(LogicalSources logical)throws Exception{
        var result=new ArrayList<RootFile>();
        for(Path root:context().sources()){
            var files=new TreeSet<Path>(analyzer.inputFiles().inventory(root.toAbsolutePath().normalize(),".java",true));
            for(Path open:analyzer.documentsState().paths())if(open.startsWith(root.toAbsolutePath().normalize())&&open.toString().endsWith(".java"))files.add(open);
            for(Path file:files){
                var id=logical.logical(file);if(id.isEmpty())return Optional.empty();
                if(!analyzer.documentsState().contains(file)&&!Files.isRegularFile(file))continue;
                result.add(new RootFile(file,id.get(),analyzer.documentsState().sourceHash(file)));
            }
        }
        return Optional.of(List.copyOf(result));
    }
    /** Coarse sufficient dependency (§75 option B): the content of every unit in the compiler source roots. */
    private static Hash256 rootsContentIdentity(List<RootFile> files){
        return CanonicalDigestWriter.digest("attributed-source-roots-content-v1",
                files.stream().map(file->List.of(file.logical(),file.hash())).toList());
    }
    /**
     * Namespace identity of the compiler source roots from S0 top-level declarations: adding, removing
     * or renaming a type anywhere changes it; body edits do not. Each unit's text must still have the
     * snapshotted hash, so a later computation can never bind newer content to an older snapshot.
     */
    private Optional<Hash256> rootsNamespaceIdentity(List<RootFile> files,SourceNamespaces.LanguageMode mode)throws Exception{
        var entries=new ArrayList<Object>();
        for(var file:files){
            String name=file.file().getFileName().toString();
            if(name.equals("module-info.java")||name.equals("package-info.java")){entries.add(List.of(file.logical(),file.hash()));continue;}
            var cached=namespaceCache.get(file.file());
            Object value;
            if(cached!=null&&cached.hash().equals(file.hash()))value=cached.value();
            else{
                String text=analyzer.documentsState().text(file.file());
                if(!Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(file.hash()))return Optional.empty();
                var namespace=sourceNamespaces.namespace(text,mode);
                value=namespace.completeness()==SemanticCompleteness.COMPLETE
                        ?List.of(namespace.completeness().name(),namespace.packageName(),namespace.topLevelTypes())
                        :List.of("partial",file.hash());
                namespaceCache.put(file.file(),new NamespaceEntry(file.hash(),value));
            }
            entries.add(List.of(file.logical(),value));
        }
        return Optional.of(CanonicalDigestWriter.digest("attributed-source-namespace-v1",entries));
    }
    /**
     * Dynamic certificate dependencies captured at result time (§71): the content identity of every
     * source in the unit's transitive dependency closure, or — when that closure is unproven — the
     * coarser content identity of all source roots (§75 option B). The S0 namespace leaf is added
     * from the same snapshot by the writer.
     */
    private Optional<TreeMap<QueryProof.Key,Hash256>> attributedDependencies(Path file,LogicalSources logical,List<RootFile> roots)throws Exception{
        var dependencies=new TreeMap<QueryProof.Key,Hash256>();
        var queue=new ArrayDeque<Path>();var seen=new HashSet<Path>();queue.add(file.toAbsolutePath().normalize());
        boolean closureComplete=true;
        while(!queue.isEmpty()&&closureComplete){
            Path current=queue.removeFirst();if(!seen.add(current))continue;
            if(!current.equals(file.toAbsolutePath().normalize())){
                if(analyzer.contribution(current)==null||analyzer.dependencyGraph().semantic().pending(current)){closureComplete=false;break;}
                var id=logical.logical(current);if(id.isEmpty())return refuseAttributed("non-logical-dependency");
                dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-source:"+id.get()),
                        logicalContentIdentity(analyzer.documentsState().sourceHash(current)));
            }
            queue.addAll(analyzer.dependencyGraph().semantic().dependencies(current));
        }
        if(!closureComplete){
            dependencies.clear();coarseAttributedCertificates.incrementAndGet();
            dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,rootsContentKey()),rootsContentIdentity(roots));
        }
        return Optional.of(dependencies);
    }
    private java.util.concurrent.ExecutorService memoWriter;
    private final java.util.concurrent.atomic.AtomicLong pendingMemoWrites=new java.util.concurrent.atomic.AtomicLong();
    private synchronized java.util.concurrent.ExecutorService memoWriter(){
        if(memoWriter==null)memoWriter=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().name("jvmd-local-memo-writer").factory());
        return memoWriter;
    }
    /** Wait for queued LOCAL memo writes (tests, shutdown and benchmarks). */
    void awaitWrites()throws InterruptedException{
        long deadline=System.nanoTime()+30_000_000_000L;
        while(pendingMemoWrites.get()>0&&System.nanoTime()<deadline)Thread.sleep(2);
    }
    /**
     * Persist one complete attributed result. Everything the certificate binds is captured now, on
     * the owner thread; the S0 namespace leaf and the write happen off the request path (§80).
     * Best effort: failure is a later miss, never an error.
     */
    void memoize(Path file,String sourceHash,Envelope envelope,FileSemanticContribution contribution){
        if(attributedMemos==null||contribution==null||!envelope.warnings().isEmpty())return;
        try{
            var logical=LogicalSources.of(context());
            var key=attributedStaticKey(file,sourceHash,logical);if(key.isEmpty())return;
            if(!contribution.sourceHash().equals(sourceHash))return;
            var roots=rootsSnapshot(logical);if(roots.isEmpty()){refuseAttributed("non-logical-source-roots");return;}
            var captured=attributedDependencies(file,logical,roots.get());if(captured.isEmpty())return;
            var dependencies=new ArrayList<String>();
            for(Path dependency:contribution.dependencies()){
                var id=logical.logical(dependency);if(id.isEmpty()){refuseAttributed("non-logical-dependency");return;}
                dependencies.add(id.get());
            }
            dependencies.sort(String::compareTo);
            var problems=new ArrayList<Map<String,Object>>();
            for(var problem:(List<?>)((Map<?,?>)envelope.result()).get("diagnostics")){
                var value=(CompilerPool.Problem)problem;
                String fileId=value.file()==null?null:Analyzer.sameFile(value.file(),file)?"self":null;
                if(value.file()!=null&&fileId==null){refuseAttributed("foreign-diagnostic-file");return;}
                var row=new LinkedHashMap<String,Object>();
                row.put("source",value.source());row.put("tier",value.tier());row.put("code",value.code());row.put("kind",value.kind());
                row.put("file",fileId);row.put("line",value.line());row.put("character",value.character());
                row.put("start",value.start());row.put("end",value.end());row.put("message",logical.toLogicalText(value.message()));
                problems.add(row);
            }
            var result=new LinkedHashMap<String,Object>();
            result.put("tier",envelope.tier());result.put("diagnostics",problems);
            result.put("api",contribution.apiFingerprint());
            result.put("exported",contribution.exportedNames().stream().sorted().toList());
            result.put("unresolved",contribution.unresolvedTargets().stream().sorted().toList());
            result.put("dependencies",dependencies);
            byte[] bytes=Json.MAPPER.writeValueAsBytes(result);
            var mode=SourceNamespaces.LanguageMode.of(context().compilerOptions());String namespaceKey=namespaceKey();
            var store=attributedMemos;var snapshot=roots.get();var certificateDependencies=captured.get();
            pendingMemoWrites.incrementAndGet();
            memoWriter().submit(()->{
                try{
                    var namespace=rootsNamespaceIdentity(snapshot,mode);
                    if(namespace.isEmpty()){attributedMemoFailures.incrementAndGet();return;}
                    certificateDependencies.put(new QueryProof.Key(QueryProof.Domain.NAMESPACE,namespaceKey),namespace.get());
                    var certificate=new SemanticMemoStore.Certificate(new QueryProof(certificateDependencies.entrySet().stream()
                            .map(entry->new QueryProof.Dependency(entry.getKey(),entry.getValue())).toList()));
                    store.put(new SemanticMemoStore.MemoRecord(key.get(),certificate,SemanticMemoStore.Coverage.COARSE,
                            SemanticCompleteness.COMPLETE,SemanticMemoStore.Result.present(bytes)));
                    attributedMemoWrites.incrementAndGet();
                }catch(Exception failure){attributedMemoFailures.incrementAndGet();}
                finally{pendingMemoWrites.decrementAndGet();}
            });
        }catch(Exception failure){attributedMemoFailures.incrementAndGet();}
    }
    /**
     * Restore a memoised attributed result after every static input matched and every certificate
     * dependency is currently established equal (§74). The restored unit re-enters the coarse
     * dependency graph, so later changes to its sources invalidate it conservatively.
     */
    Envelope restore(Path path,String hash,CompilerInputs.Snapshot observed){
        if(attributedMemos==null)return null;
        try{
            var logical=LogicalSources.of(context());
            var key=attributedStaticKey(path,hash,logical);if(key.isEmpty())return null;
            String namespaceKey=namespaceKey();
            var lookup=attributedMemos.lookup(key.get(),dependency->{
                if(dependency.domain()==QueryProof.Domain.RESOLUTION_PATH&&dependency.value().startsWith("logical-source:")){
                    var file=logical.physical(dependency.value().substring("logical-source:".length()));
                    if(file.isEmpty())return Optional.empty();
                    return Optional.of(logicalContentIdentity(analyzer.documentsState().sourceHash(file.get())));
                }
                if(dependency.domain()==QueryProof.Domain.NAMESPACE&&dependency.value().equals(namespaceKey)){
                    var roots=rootsSnapshot(logical);
                    return roots.isEmpty()?Optional.empty():rootsNamespaceIdentity(roots.get(),SourceNamespaces.LanguageMode.of(context().compilerOptions()));
                }
                if(dependency.domain()==QueryProof.Domain.RESOLUTION_PATH&&dependency.value().equals(rootsContentKey()))
                    return rootsSnapshot(logical).map(AttributedMemos::rootsContentIdentity);
                return Optional.empty();
            });
            if(!(lookup instanceof SemanticMemoStore.Lookup.Hit hit)){
                attributedMemoMisses++;lastAttributedMemoMiss=((SemanticMemoStore.Lookup.Miss)lookup).reason();return null;
            }
            if(hit.record().completeness()!=SemanticCompleteness.COMPLETE
                    ||!(hit.record().result() instanceof SemanticMemoStore.Result.Present present))return null;
            var data=Json.MAPPER.readTree(present.value());
            var dependencies=new LinkedHashSet<Path>();
            for(var value:data.path("dependencies")){
                var file=logical.physical(value.asText());if(file.isEmpty())return null;dependencies.add(file.get());
            }
            var problems=new ArrayList<CompilerPool.Problem>();
            for(var value:data.path("diagnostics")){
                String file=value.path("file").isNull()?null:path.toString();
                problems.add(new CompilerPool.Problem(value.path("source").asText(null),value.path("tier").asInt(),value.path("code").asText(null),
                        value.path("kind").asText(null),file,value.path("line").asLong(),value.path("character").asLong(),
                        value.path("start").asLong(),value.path("end").asLong(),logical.toPhysicalText(value.path("message").asText(null))));
            }
            var exported=new LinkedHashSet<String>();data.path("exported").forEach(value->exported.add(value.asText()));
            var unresolved=new LinkedHashSet<String>();data.path("unresolved").forEach(value->unresolved.add(value.asText()));
            var contribution=new FileSemanticContribution(path,hash,data.path("api").asText(),dependencies,exported,unresolved);
            var envelope=new Envelope(data.path("tier").asInt(),"live",false,null,List.of(),Map.of("diagnostics",List.copyOf(problems)));
            analyzer.dependencyGraph().recordFocused(path,dependencies);analyzer.resolveContribution(contribution);
            String broad=Analyzer.broadDiagnosticStamp(observed);
            analyzer.diagnosticStore().put(path,hash,context().generation(),broad,envelope,contribution.apiFingerprint(),dependencies,contribution,broad);
            attributedMemoRestores++;
            return envelope;
        }catch(Exception failure){attributedMemoFailures.incrementAndGet();return null;}
    }
    Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("enabled",attributedMemos!=null);result.put("writes",attributedMemoWrites.get());result.put("restores",attributedMemoRestores);
        result.put("misses",attributedMemoMisses);result.put("last_miss",lastAttributedMemoMiss);result.put("refusals",attributedMemoRefusals);
        result.put("refusal_reasons",Map.copyOf(attributedMemoRefusalReasons));result.put("failures",attributedMemoFailures.get());
        result.put("coarse_certificates",coarseAttributedCertificates.get());result.put("pending_writes",pendingMemoWrites.get());
        result.put("source_namespaces",sourceNamespaces.status());
        if(attributedMemos!=null)result.put("store",attributedMemos.status());
        return Collections.unmodifiableMap(result);
    }

    @Override public void close()throws InterruptedException{
        awaitWrites();synchronized(this){if(memoWriter!=null){memoWriter.shutdown();memoWriter=null;}}
    }
}
