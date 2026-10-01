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
    /** Current {@link DiagnosticProjection P_diag} per unit with the content hash it belongs to. */
    private record Projection(String hash,Hash256 value) { }
    private final Map<Path,Projection> projections=new java.util.concurrent.ConcurrentHashMap<>();
    Optional<Hash256> projection(Path file){return Optional.ofNullable(projections.get(file.toAbsolutePath().normalize())).map(Projection::value);}
    /** Record the projection of a fully attributed unit. */
    void observe(Path file,String hash,Bindings.Snapshot snapshot){
        if(snapshot!=null&&snapshot.diagnosticProjection()!=null)projections.put(file.toAbsolutePath().normalize(),new Projection(hash,snapshot.diagnosticProjection()));
    }

    void attach(SemanticMemoStore store){
        attributedMemos=store;sourceNamespaces=new SourceNamespaces(store);namespaceCache.clear();
    }
    private SemanticMemoStore attributedMemos;
    private SourceNamespaces sourceNamespaces=new SourceNamespaces(null);
    private record NamespaceEntry(String hash,Object value) { }
    private final Map<Path,NamespaceEntry> namespaceCache=new java.util.concurrent.ConcurrentHashMap<>();
    private long attributedMemoRestores,attributedMemoMisses,attributedMemoRefusals;
    private final java.util.concurrent.atomic.AtomicLong attributedMemoWrites=new java.util.concurrent.atomic.AtomicLong(),
            attributedMemoFailures=new java.util.concurrent.atomic.AtomicLong();
    private final Map<String,Long> attributedMemoRefusalReasons=new TreeMap<>();
    private String lastAttributedMemoMiss="";

    /** Bump when the attributed result, its canonical encoding or its certificate rules change (§81). */
    static final SemanticMemoStore.Function ATTRIBUTED=new SemanticMemoStore.Function("attributed-diagnostics",2);

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
    private String scope(){return context().gav()+"|"+Analyzer.classpathContext(context()).scope();}
    private static Hash256 logicalContentIdentity(String hash){return CanonicalDigestWriter.digest("logical-source-content-v1",hash);}
    private static Hash256 projectionIdentity(Hash256 projection){return CanonicalDigestWriter.digest("logical-unit-projection-v1",projection);}
    /** One unit of a package directory as observed at a single moment (stats only). */
    private record PackageFile(Path file,String logical,String hash) { }
    /**
     * The units javac's source path can see for package {@code pkg}: the direct {@code .java}
     * members of {@code <root>/<pkg>} in every compiler source root, plus open documents there.
     */
    private Optional<List<PackageFile>> packageFiles(String pkg,LogicalSources logical)throws Exception{
        var result=new ArrayList<PackageFile>();
        for(Path root:context().sources()){
            Path normalized=root.toAbsolutePath().normalize(),directory=pkg.isEmpty()?normalized:normalized.resolve(pkg.replace('.','/'));
            var files=new TreeSet<Path>();
            for(Path file:analyzer.inputFiles().inventory(normalized,".java",true))if(directory.equals(file.getParent()))files.add(file);
            for(Path open:analyzer.documentsState().paths())if(directory.equals(open.getParent())&&open.toString().endsWith(".java"))files.add(open);
            for(Path file:files){
                if(!analyzer.documentsState().contains(file)&&!Files.isRegularFile(file))continue;
                var id=logical.logical(file);if(id.isEmpty())return Optional.empty();
                result.add(new PackageFile(file,id.get(),analyzer.documentsState().sourceHash(file)));
            }
        }
        return Optional.of(List.copyOf(result));
    }
    /**
     * S0 top-level type set of one package across the source roots: adding, removing or renaming a
     * top-level type in that package changes it; body edits do not. Each unit's text must still have
     * the snapshotted hash, so a later computation never binds newer content to an older snapshot.
     */
    private Optional<Hash256> packageIdentity(String pkg,List<PackageFile> files,SourceNamespaces.LanguageMode mode)throws Exception{
        var entries=new ArrayList<Object>();
        for(var file:files){
            if(file.file().getFileName().toString().equals("package-info.java")){entries.add(List.of(file.logical(),file.hash()));continue;}
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
        return Optional.of(CanonicalDigestWriter.digest("attributed-package-namespace-v1",pkg,entries));
    }
    /**
     * Established presence or absence, in the source roots, of a top-level source unit or a package
     * directory named {@code binary}: either would change how javac resolves that name.
     */
    private Hash256 absenceIdentity(String binary,LogicalSources logical){
        var present=new ArrayList<String>();
        String relative=binary.replace('.','/');
        for(Path root:context().sources()){
            Path normalized=root.toAbsolutePath().normalize(),file=normalized.resolve(relative+".java"),directory=normalized.resolve(relative);
            if(analyzer.documentsState().contains(file)||Files.isRegularFile(file))present.add("unit:"+logical.logical(file).orElse("<non-logical>"));
            if(Files.isDirectory(directory))present.add("package:"+logical.logical(directory).orElse("<non-logical>"));
        }
        return CanonicalDigestWriter.digest("source-absence-v2",binary,present);
    }
    private static final java.util.regex.Pattern PACKAGE=java.util.regex.Pattern.compile("\\bpackage\\s+([\\w.$]+)\\s*;");
    private static final java.util.regex.Pattern STAR_IMPORT=java.util.regex.Pattern.compile("\\bimport\\s+(?!static\\b)([\\w.$]+)\\s*\\.\\s*\\*\\s*;");
    /** X's own package and every non-static star-imported package. */
    private static TreeSet<String> consultedPackages(String text){
        var result=new TreeSet<String>();var own=PACKAGE.matcher(text);result.add(own.find()?own.group(1):"");
        var star=STAR_IMPORT.matcher(text);while(star.find())result.add(star.group(1));
        return result;
    }
    private static final java.util.regex.Pattern MISSING_SYMBOL=java.util.regex.Pattern.compile("symbol:\\s+(?:class|interface|variable|package)\\s+([\\w$.]+)");
    private static final java.util.regex.Pattern MISSING_PACKAGE=java.util.regex.Pattern.compile("package\\s+([\\w$.]+)\\s+does not exist");
    /**
     * NEGATIVE_RESOLUTION entries for every name X failed to resolve: unresolved type names and the
     * class, variable and package names in name-resolution diagnostics. Each searched domain outside
     * the package entries (own package and star imports, already bound) must stay absent, as must a
     * top-level package of that name.
     */
    private Optional<TreeMap<QueryProof.Key,Hash256>> negatives(String text,Bindings.Snapshot attributed,List<?> problems,Set<String> packages,LogicalSources logical){
        var simple=new TreeSet<String>(attributed.unresolvedTypeNames());var qualified=new TreeSet<String>();
        for(var problem:problems){
            var value=(CompilerPool.Problem)problem;
            if(!value.kind().equals("ERROR")||value.message()==null)continue;
            var names=new ArrayList<String>();
            if(value.code().contains("cant.resolve")){var matcher=MISSING_SYMBOL.matcher(value.message());while(matcher.find())names.add(matcher.group(1));}
            if(value.code().contains("doesnt.exist")){var matcher=MISSING_PACKAGE.matcher(value.message());while(matcher.find())names.add(matcher.group(1));}
            for(String name:names)(name.indexOf('.')<0?simple:qualified).add(name);
        }
        var result=new TreeMap<QueryProof.Key,Hash256>();
        for(String name:simple){
            if(!javax.lang.model.SourceVersion.isIdentifier(name))return refuseAttributed("negative-unproven:non-simple-name");
            var plan=NamespaceResolutionProofs.plan(text,name,null);
            if(!plan.precise())return refuseAttributed("negative-unproven:static-import");
            var domains=new TreeSet<String>(plan.domains());domains.add(name);
            for(String binary:domains){
                int split=binary.lastIndexOf('.');String pkg=split<0?"":binary.substring(0,split);
                if(!binary.equals(name)&&(packages.contains(pkg)||pkg.equals("java.lang")))continue;
                result.put(new QueryProof.Key(QueryProof.Domain.NEGATIVE_RESOLUTION,name+"@"+binary),absenceIdentity(binary,logical));
            }
        }
        for(String name:qualified){
            for(String part:name.split("\\."))if(!javax.lang.model.SourceVersion.isIdentifier(part))return refuseAttributed("negative-unproven:non-simple-name");
            result.put(new QueryProof.Key(QueryProof.Domain.NEGATIVE_RESOLUTION,name.substring(name.lastIndexOf('.')+1)+"@"+name),absenceIdentity(name,logical));
        }
        return Optional.of(result);
    }
    private java.util.concurrent.ExecutorService memoWriter;
    private final java.util.concurrent.atomic.AtomicLong pendingMemoWrites=new java.util.concurrent.atomic.AtomicLong();
    private synchronized java.util.concurrent.ExecutorService memoWriter(){
        if(memoWriter==null)memoWriter=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().name("jvmd-local-memo-writer").factory());
        return memoWriter;
    }
    /** Write every result whose SCC is final, then wait for queued LOCAL memo writes (tests, shutdown and benchmarks). */
    void awaitWrites()throws InterruptedException{
        drain(false);
        long deadline=System.nanoTime()+30_000_000_000L;
        while(pendingMemoWrites.get()>0&&System.nanoTime()<deadline)Thread.sleep(2);
    }
    /** One completed dependency of a captured result: its logical id, content and P_diag as javac saw them. */
    private record Dependency(String logical,Hash256 content,Hash256 projection) { }
    /** A result captured on the owner thread whose SCC is not yet known (W3). */
    private record Pending(SemanticMemoStore.StaticKey key,byte[] result,Map<Path,Dependency> dependencies,
                           Map<String,List<PackageFile>> packages,TreeMap<QueryProof.Key,Hash256> negatives,SourceNamespaces.LanguageMode mode) { }
    private final Map<Path,Pending> pending=new LinkedHashMap<>();
    /**
     * Capture one complete attributed result. The certificate is precise or there is no record:
     * {@code logical-unit:} P_diag for every completed dependency outside the unit's SCC,
     * {@code logical-source:} content for SCC peers, {@code package:} S0 type sets for the own and
     * star-imported packages, and negative resolutions. The record is written once the SCC is known.
     */
    void memoize(Path file,String sourceHash,Envelope envelope,FileSemanticContribution contribution,Bindings.Snapshot attributed){
        file=file.toAbsolutePath().normalize();observe(file,sourceHash,attributed);
        if(attributedMemos==null||contribution==null||!envelope.warnings().isEmpty())return;
        var projection=projection(file);if(projection.isEmpty()){refuseAttributed("projection-unavailable");return;}
        try{
            var logical=LogicalSources.of(context());
            var key=attributedStaticKey(file,sourceHash,logical);if(key.isEmpty())return;
            if(!contribution.sourceHash().equals(sourceHash))return;
            String text=analyzer.documentsState().text(file);
            if(!Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(sourceHash))return;
            var roots=context().sources().stream().map(root->root.toAbsolutePath().normalize()).toList();
            var dependencies=new TreeMap<Path,Dependency>();
            for(Path dependency:contribution.dependencies()){
                Path normalized=dependency.toAbsolutePath().normalize();if(normalized.equals(file))continue;
                if(roots.stream().noneMatch(normalized::startsWith)){refuseAttributed("non-logical-dependency");return;}
                var id=logical.logical(normalized);if(id.isEmpty()){refuseAttributed("non-logical-dependency");return;}
                var dependencyProjection=attributed.dependencyProjections().get(normalized);
                if(dependencyProjection==null){refuseAttributed("dependency-unproven");return;}
                dependencies.put(normalized,new Dependency(id.get(),logicalContentIdentity(analyzer.documentsState().sourceHash(normalized)),dependencyProjection));
            }
            var packageNames=consultedPackages(text);var packages=new TreeMap<String,List<PackageFile>>();
            for(String pkg:packageNames){
                var files=packageFiles(pkg,logical);if(files.isEmpty()){refuseAttributed("package-unproven");return;}
                packages.put(pkg,files.get());
            }
            var diagnostics=(List<?>)((Map<?,?>)envelope.result()).get("diagnostics");
            var negatives=negatives(text,attributed,diagnostics,packageNames,logical);if(negatives.isEmpty())return;
            var problems=new ArrayList<Map<String,Object>>();
            for(var problem:diagnostics){
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
            result.put("p_diag",projection.get().hex());
            result.put("exported",contribution.exportedNames().stream().sorted().toList());
            result.put("unresolved",contribution.unresolvedTargets().stream().sorted().toList());
            result.put("dependencies",dependencies.values().stream().map(Dependency::logical).sorted().toList());
            pending.put(file,new Pending(key.get(),Json.MAPPER.writeValueAsBytes(result),dependencies,packages,negatives.get(),
                    SourceNamespaces.LanguageMode.of(context().compilerOptions())));
            if(pending.size()>=PENDING_BATCH||System.nanoTime()-lastDrain>DRAIN_INTERVAL_NANOS){lastDrain=System.nanoTime();drain(false);}
        }catch(Exception failure){attributedMemoFailures.incrementAndGet();}
    }
    private static final int PENDING_BATCH=256;
    private static final long DRAIN_INTERVAL_NANOS=2_000_000_000L;
    private long lastDrain=System.nanoTime();
    /**
     * Write every pending result whose strongly connected component is final: every unit reachable
     * from it has a known dependency set. Tarjan over the reachable graph; components complete in
     * reverse topological order, so a component is final when no member and no successor reaches an
     * unknown unit. When {@code closing}, the rest are refused as {@code scc-unproven}.
     */
    void drain(boolean closing){
        if(pending.isEmpty())return;
        var roots=context()==null?List.<Path>of():context().sources().stream().map(root->root.toAbsolutePath().normalize()).toList();
        var graph=new HashMap<Path,Set<Path>>();
        java.util.function.Function<Path,Set<Path>> edges=unit->graph.computeIfAbsent(unit,current->{
            var captured=pending.get(current);if(captured!=null)return captured.dependencies().keySet();
            if(roots.stream().noneMatch(current::startsWith))return Set.of();
            var contribution=analyzer.contribution(current);if(contribution==null)return null;
            var result=new HashSet<Path>();for(Path dependency:contribution.dependencies())result.add(dependency.toAbsolutePath().normalize());
            return result;
        });
        var index=new HashMap<Path,Integer>();var low=new HashMap<Path,Integer>();var stack=new ArrayDeque<Path>();var onStack=new HashSet<Path>();
        var component=new HashMap<Path,Set<Path>>();var unknown=new HashMap<Set<Path>,Boolean>();int[] counter={0};
        for(Path start:new ArrayList<>(pending.keySet())){
            if(index.containsKey(start))continue;
            // Iterative Tarjan: each frame is a node and the iterator over its successors.
            var frames=new ArrayDeque<Map.Entry<Path,Iterator<Path>>>();
            index.put(start,counter[0]);low.put(start,counter[0]++);stack.push(start);onStack.add(start);
            var startEdges=edges.apply(start);frames.push(Map.entry(start,startEdges==null?Collections.emptyIterator():startEdges.iterator()));
            while(!frames.isEmpty()){
                var frame=frames.peek();Path node=frame.getKey();
                if(frame.getValue().hasNext()){
                    Path next=frame.getValue().next();
                    if(!index.containsKey(next)){
                        index.put(next,counter[0]);low.put(next,counter[0]++);stack.push(next);onStack.add(next);
                        var nextEdges=edges.apply(next);frames.push(Map.entry(next,nextEdges==null?Collections.emptyIterator():nextEdges.iterator()));
                    }else if(onStack.contains(next))low.put(node,Math.min(low.get(node),index.get(next)));
                    continue;
                }
                frames.pop();
                if(!frames.isEmpty()){Path parent=frames.peek().getKey();low.put(parent,Math.min(low.get(parent),low.get(node)));}
                if(low.get(node).equals(index.get(node))){
                    var members=new HashSet<Path>();Path member;
                    do{member=stack.pop();onStack.remove(member);members.add(member);}while(!member.equals(node));
                    boolean reachesUnknown=false;
                    for(Path value:members){
                        var successors=edges.apply(value);
                        if(successors==null){reachesUnknown=true;break;}
                        for(Path successor:successors)if(!members.contains(successor)&&unknown.getOrDefault(component.get(successor),true)){reachesUnknown=true;break;}
                        if(reachesUnknown)break;
                    }
                    var frozen=Set.copyOf(members);unknown.put(frozen,reachesUnknown);for(Path value:members)component.put(value,frozen);
                }
            }
        }
        for(var iterator=pending.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();var members=component.get(entry.getKey());
            if(members==null||unknown.getOrDefault(members,true)){
                if(closing){iterator.remove();refuseAttributed("scc-unproven");}
                continue;
            }
            iterator.remove();write(entry.getKey(),entry.getValue(),members);
        }
    }
    private void write(Path file,Pending captured,Set<Path> component){
        var dependencies=new TreeMap<QueryProof.Key,Hash256>(captured.negatives());
        // Every SCC peer binds its content, including peers the unit did not complete directly. Those
        // do not affect the unit's result (it read only its completed units), so binding their current
        // content only narrows reuse.
        if(component.size()>1){
            var logical=LogicalSources.of(context());
            for(Path peer:component){
                if(peer.equals(file)||captured.dependencies().containsKey(peer))continue;
                var id=logical.logical(peer);var hash=currentHash(peer);
                if(id.isEmpty()||hash.isEmpty()){refuseAttributed("scc-unproven");return;}
                dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-source:"+id.get()),logicalContentIdentity(hash.get()));
            }
        }
        for(var entry:captured.dependencies().entrySet()){
            var dependency=entry.getValue();
            if(component.contains(entry.getKey()))dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-source:"+dependency.logical()),dependency.content());
            else dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-unit:"+dependency.logical()),projectionIdentity(dependency.projection()));
        }
        var store=attributedMemos;String scope=scope();
        pendingMemoWrites.incrementAndGet();
        memoWriter().submit(()->{
            try{
                for(var entry:captured.packages().entrySet()){
                    var identity=packageIdentity(entry.getKey(),entry.getValue(),captured.mode());
                    if(identity.isEmpty()){attributedMemoFailures.incrementAndGet();return;}
                    dependencies.put(new QueryProof.Key(QueryProof.Domain.NAMESPACE,"package:"+scope+"|"+entry.getKey()),identity.get());
                }
                var certificate=new SemanticMemoStore.Certificate(new QueryProof(dependencies.entrySet().stream()
                        .map(entry->new QueryProof.Dependency(entry.getKey(),entry.getValue())).toList()));
                store.put(new SemanticMemoStore.MemoRecord(captured.key(),certificate,SemanticMemoStore.Coverage.PRECISE,
                        SemanticCompleteness.COMPLETE,SemanticMemoStore.Result.present(captured.result())));
                attributedMemoWrites.incrementAndGet();
            }catch(Exception failure){attributedMemoFailures.incrementAndGet();}
            finally{pendingMemoWrites.decrementAndGet();}
        });
    }
    /**
     * Restore a memoised attributed result after every static input matched and every certificate
     * entry is currently established equal. A {@code logical-unit:} entry compares against the
     * dependency's current P_diag, restoring that dependency first: a request waits only for its
     * dependency cone.
     */
    Envelope restore(Path path,String hash,CompilerInputs.Snapshot observed){
        path=path.toAbsolutePath().normalize();
        if(attributedMemos==null||!resolving.add(path))return null;
        try{return resolve(path,hash,observed);}finally{resolving.remove(path);}
    }
    /** Units whose restore or early-cutoff attribution is in progress on the owner thread; guards cycles. */
    private final Set<Path> resolving=new HashSet<>();
    private Envelope resolve(Path path,String hash,CompilerInputs.Snapshot observed){
        try{
            var logical=LogicalSources.of(context());
            var key=attributedStaticKey(path,hash,logical);if(key.isEmpty())return null;
            String packagePrefix="package:"+scope()+"|";
            var mode=SourceNamespaces.LanguageMode.of(context().compilerOptions());
            var lookup=attributedMemos.lookup(key.get(),dependency->{
                String value=dependency.value();
                switch(dependency.domain()){
                    case RESOLUTION_PATH -> {
                        if(value.startsWith("logical-source:")){
                            var file=logical.physical(value.substring("logical-source:".length()));
                            return file.isEmpty()?Optional.empty():currentHash(file.get()).map(AttributedMemos::logicalContentIdentity);
                        }
                        if(value.startsWith("logical-unit:")){
                            var file=logical.physical(value.substring("logical-unit:".length()));
                            return file.isEmpty()?Optional.empty():currentProjection(file.get(),observed).map(AttributedMemos::projectionIdentity);
                        }
                    }
                    case NAMESPACE -> {
                        if(value.startsWith(packagePrefix)){
                            String pkg=value.substring(packagePrefix.length());
                            var files=packageFiles(pkg,logical);
                            return files.isEmpty()?Optional.empty():packageIdentity(pkg,files.get(),mode);
                        }
                    }
                    case NEGATIVE_RESOLUTION -> {
                        int split=value.lastIndexOf('@');
                        if(split>0)return Optional.of(absenceIdentity(value.substring(split+1),logical));
                    }
                    default -> { }
                }
                return Optional.empty();
            });
            if(!(lookup instanceof SemanticMemoStore.Lookup.Hit hit)){
                attributedMemoMisses++;lastAttributedMemoMiss=((SemanticMemoStore.Lookup.Miss)lookup).reason();return null;
            }
            if(hit.record().completeness()!=SemanticCompleteness.COMPLETE
                    ||!(hit.record().result() instanceof SemanticMemoStore.Result.Present present))return null;
            var data=Json.MAPPER.readTree(present.value());
            if(!data.hasNonNull("p_diag"))return null;
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
            projections.put(path,new Projection(hash,Hash256.fromHex(data.path("p_diag").asText())));
            var envelope=new Envelope(data.path("tier").asInt(),"live",false,null,List.of(),Map.of("diagnostics",List.copyOf(problems)));
            analyzer.dependencyGraph().recordFocused(path,dependencies);analyzer.resolveContribution(contribution);
            String broad=Analyzer.broadDiagnosticStamp(observed);
            analyzer.diagnosticStore().put(path,hash,context().generation(),broad,envelope,contribution.apiFingerprint(),dependencies,contribution,broad);
            attributedMemoRestores++;
            return envelope;
        }catch(Exception failure){attributedMemoFailures.incrementAndGet();return null;}
    }
    private Optional<String> currentHash(Path file){
        try{
            if(!analyzer.documentsState().contains(file)&&!Files.isRegularFile(file))return Optional.empty();
            return Optional.of(analyzer.documentsState().sourceHash(file));
        }catch(Exception unreadable){return Optional.empty();}
    }
    /**
     * Current P_diag of a dependency (W4 early cutoff): known for its current content, established by
     * restoring it first, or else by attributing it once with javac. A dependant then compares its
     * entry against that current value, so a body edit recompiles only the edited unit.
     */
    private Optional<Hash256> currentProjection(Path file,CompilerInputs.Snapshot observed){
        file=file.toAbsolutePath().normalize();
        var hash=currentHash(file);if(hash.isEmpty())return Optional.empty();
        var known=projections.get(file);
        if(known==null||!known.hash().equals(hash.get())){
            if(restore(file,hash.get(),observed)==null&&resolving.add(file)){
                try{analyzer.diagnostics(file,analyzer.documentsState().text(file));earlyCutoffAttributions++;}
                catch(Exception failure){return Optional.empty();}
                finally{resolving.remove(file);}
            }
            known=projections.get(file);
        }
        return known!=null&&known.hash().equals(hash.get())?Optional.of(known.value()):Optional.empty();
    }
    private long earlyCutoffAttributions;
    Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("enabled",attributedMemos!=null);result.put("writes",attributedMemoWrites.get());result.put("restores",attributedMemoRestores);
        result.put("misses",attributedMemoMisses);result.put("last_miss",lastAttributedMemoMiss);result.put("refusals",attributedMemoRefusals);
        result.put("refusal_reasons",Map.copyOf(attributedMemoRefusalReasons));result.put("failures",attributedMemoFailures.get());
        result.put("pending_scc",pending.size());result.put("early_cutoff_attributions",earlyCutoffAttributions);result.put("pending_writes",pendingMemoWrites.get());
        result.put("source_namespaces",sourceNamespaces.status());
        if(attributedMemos!=null)result.put("store",attributedMemos.status());
        return Collections.unmodifiableMap(result);
    }

    @Override public void close()throws InterruptedException{
        if(context()!=null)drain(true);awaitWrites();synchronized(this){if(memoWriter!=null){memoWriter.shutdown();memoWriter=null;}}
    }
}
