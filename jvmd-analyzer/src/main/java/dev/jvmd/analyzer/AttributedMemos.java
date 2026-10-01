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
    private record NamespaceEntry(String hash,SourceNamespaces.LanguageMode mode,Object value) { }
    private final Map<Path,NamespaceEntry> namespaceCache=new java.util.concurrent.ConcurrentHashMap<>();
    private long attributedMemoRestores,attributedMemoMisses,attributedMemoRefusals;
    private final java.util.concurrent.atomic.AtomicLong attributedMemoWrites=new java.util.concurrent.atomic.AtomicLong(),
            attributedMemoFailures=new java.util.concurrent.atomic.AtomicLong();
    private final Map<String,Long> attributedMemoRefusalReasons=new TreeMap<>(),missReasons=new TreeMap<>();
    private String lastAttributedMemoMiss="";

    /** Bump when the attributed result, its canonical encoding or its certificate rules change (§81). */
    static final SemanticMemoStore.Function ATTRIBUTED=new SemanticMemoStore.Function("attributed-diagnostics",6);

    private <T> Optional<T> refuseAttributed(String reason){
        attributedMemoRefusals++;attributedMemoRefusalReasons.merge(reason,1L,Long::sum);return Optional.empty();
    }
    /** Platform identity from content only: the JDK location is not part of semantic meaning (§103). */
    private Object platformIdentity()throws Exception{
        String configured=analyzer.platformIdentity();
        if(configured==null)throw new IllegalStateException("analyzer is not configured");
        return configured;
    }
    /**
     * Static memo key (§70, §84): everything the in-process compiler owner holds fixed. Returns empty
     * when any ambient input cannot be bound logically; that context is simply not memoised.
     */
    private Optional<SemanticMemoStore.StaticKey> attributedStaticKey(Path file,String sourceHash,LogicalSources logical)throws Exception{
        if(attributedMemos==null||context()==null)return Optional.empty();
        if(!context().preciseSourceRoots())return refuseAttributed("imprecise-source-roots");
        var source=logical.logical(file);if(source.isEmpty())return refuseAttributed("non-logical-source");
        for(Path root:context().sources())if(logical.logical(root.resolve("x.java")).isEmpty())return refuseAttributed("non-logical-source-root");
        StaticInputs.Binding statics=shared("static-inputs",()->staticInputs(logical));
        if(statics.refused())return refuseAttributed(statics.refusal());
        var classpathContext=Analyzer.classpathContext(context());
        var roots=logical.roots().stream().map(LogicalSources.Root::logical).sorted().toList();
        return Optional.of(SemanticMemoStore.StaticKey.of(ATTRIBUTED,source.get(),sourceHash,context().gav(),classpathContext.scope(),
                context().release(),statics.value(),platformIdentity(),roots,contextWarnings()));
    }
    private List<String> contextWarnings(){return List.copyOf(new LinkedHashSet<>(context().warnings()));}
    /** The analyzer's own processor class output: its units are bound one by one through their binary P_diag. */
    private boolean ownProcessorOutput(Path entry){
        String normalized=entry.toAbsolutePath().normalize().toString();
        String role=context().coordinates().get("role:"+normalized);
        return role!=null&&role.startsWith("processor-classes")&&context().gav().equals(context().coordinates().get(normalized));
    }
    /**
     * W6 static inputs: compiler options with path options as logical slots, the annotation processing
     * binding, the classpath as logical slots with content identities, the units hidden from the
     * source path in favour of processor class output, and whether a {@code lombok.config} exists
     * above the reactor root (such a file has no logical identity).
     */
    private StaticInputs.Binding staticInputs(LogicalSources logical)throws Exception{
        var inputs=new StaticInputs(analyzer.inputFiles(),context().coordinates(),context().gav());
        var options=inputs.options(context().compilerOptions());if(options.refused())return options;
        var processors=inputs.processors(context().processing(),context().compilerOptions());if(processors.refused())return processors;
        var classpath=inputs.slots(context().classpath().stream().filter(entry->!ownProcessorOutput(entry)).toList());
        if(classpath.refused())return classpath;
        var hidden=new TreeSet<String>();
        for(Path unit:context().binarySources()){
            var id=logical.logical(unit);if(id.isEmpty())return StaticInputs.Binding.refuse("non-logical-binary-source");hidden.add(id.get());
        }
        boolean outsideConfig=false;
        if(lombok()){var top=reactorRoot();if(top==null)return StaticInputs.Binding.refuse("lombok-config-unbound");
            for(Path current=top.getParent();current!=null;current=current.getParent())if(Files.exists(current.resolve("lombok.config")))outsideConfig=true;
            if(outsideConfig)return StaticInputs.Binding.refuse("lombok-config-outside-reactor");}
        return StaticInputs.Binding.of(List.of(options.value(),processors.value(),classpath.value(),List.copyOf(hidden)));
    }
    private boolean lombok(){
        var processing=context().processing();
        return processing.enabled()&&processing.mode().equals("full");
    }
    private boolean jpaXml()throws Exception{
        var processing=context().processing();if(!processing.enabled())return false;
        for(String processor:StaticInputs.processorClasses(processing.path(),processing.names()))
            if(ProcessorAllowlist.entry(processor).map(entry->entry.input()==ProcessorAllowlist.ExtraInput.JPA_XML).orElse(false))return true;
        return false;
    }
    /**
     * P1 (corrective pass): everything derived from the configured context alone, derived once per
     * context instance. {@code Analyzer.configure} replaces the context on any configuration change
     * (module selection, generated roots, processor settings), which is the only invalidator. Before,
     * the coordinate table was walked for every memo, dependency entry and {@code config:} lookup.
     */
    private record Configured(Analyzer.Context context,Path reactorRoot,LogicalSources logical,List<Path> roots,List<Path> perClassDirectories,
                              Map<Path,Boolean> foreign) { }
    private volatile Configured configured;
    private final java.util.concurrent.atomic.AtomicLong configDerivations=new java.util.concurrent.atomic.AtomicLong();
    private Configured configured(){
        var current=context();var value=configured;
        if(value!=null&&value.context()==current)return value;
        configDerivations.incrementAndGet();
        var roots=current.sources().stream().map(root->root.toAbsolutePath().normalize()).toList();
        var directories=current.classpath().stream().map(entry->entry.toAbsolutePath().normalize())
                .filter(entry->StaticInputs.perClass(entry,current.coordinates(),current.gav())).toList();
        value=new Configured(current,deriveReactorRoot(current,roots),LogicalSources.of(current),roots,directories,new java.util.concurrent.ConcurrentHashMap<>());
        configured=value;return value;
    }
    private LogicalSources logical(){return configured().logical();}
    private Path reactorRoot(){return configured().reactorRoot();}
    /** The outermost module directory of the reactor: the shortest coordinates directory that contains a source root. */
    private static Path deriveReactorRoot(Analyzer.Context context,List<Path> roots){
        Path best=null;
        for(var entry:context.coordinates().entrySet()){
            if(entry.getKey().contains("://")||entry.getKey().startsWith("role:"))continue;
            Path candidate;try{candidate=Path.of(entry.getKey()).toAbsolutePath().normalize();}catch(Exception invalid){continue;}
            boolean contains=false;for(Path root:roots)if(root.startsWith(candidate)&&!root.equals(candidate))contains=true;
            if(contains&&(best==null||candidate.getNameCount()<best.getNameCount()))best=candidate;
        }
        return best;
    }
    /**
     * The declared processor resource files of one unit: {@code lombok.config} in every directory from
     * the unit's up to the reactor root, and the module's JPA XML mappings. Empty when unbound.
     */
    private Optional<List<Path>> resourceFiles(Path file,LogicalSources logical,Path top)throws Exception{
        var files=new ArrayList<Path>();
        if(lombok())for(Path current=file.getParent();current!=null&&current.startsWith(top);current=current.getParent())files.add(current.resolve("lombok.config"));
        if(jpaXml()){
            var root=logical.roots().stream().filter(candidate->file.startsWith(candidate.path())).findFirst().orElse(null);
            if(root==null)return Optional.empty();
            Path module=root.path();for(int i=0;i<Path.of(root.role()).getNameCount()&&module!=null;i++)module=module.getParent();
            if(module==null)return Optional.empty();
            files.add(module.resolve("src/main/resources/META-INF/persistence.xml"));files.add(module.resolve("src/main/resources/META-INF/orm.xml"));
        }
        return Optional.of(files);
    }
    /** File stamp: absent, or kind, size, modification and change time and file key. Unreadable throws. */
    private static String stamp(Path path)throws ObservationFaults.Unavailable{
        var value=ObservationFaults.attributes(path);if(value==null)return "absent";
        Object ctime;try{ctime=Files.getAttribute(path,"unix:ctime");}catch(Exception unsupported){ctime="";}
        return value.isRegularFile()+"|"+value.size()+"|"+value.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS)+"|"+ctime+"|"+value.fileKey();
    }
    /**
     * C4: called on the owner thread immediately before a compiler transaction for {@code file}. The
     * processor reads its resource files inside the transaction, but they are not part of the live input
     * state the transaction fence checks, so their stamps are recorded here. A capture binds a resource
     * only if its stamp is unchanged since then: the processor read exactly the bytes being bound.
     */
    void beforeTransaction(Path file,CompilerInputs.Snapshot observed){
        if(attributedMemos==null||observed==null||context()==null||!(lombok()||jpaXmlQuietly()))return;
        file=file.toAbsolutePath().normalize();
        var stamps=new HashMap<Path,String>();
        try{
            Path top=reactorRoot();if(top==null)return;
            var files=resourceFiles(file,logical(),top);if(files.isEmpty())return;
            for(Path config:files.get())stamps.put(config,stamp(config));
        }catch(Exception unobservable){return;}
        resourceStamps.put(file,new ResourceStamps(observed,Map.copyOf(stamps)));
    }
    private boolean jpaXmlQuietly(){try{return jpaXml();}catch(Exception unknown){return true;}}
    private record ResourceStamps(CompilerInputs.Snapshot observed,Map<Path,String> stamps) { }
    private final Map<Path,ResourceStamps> resourceStamps=new HashMap<>();
    /**
     * Declared processor resources of one unit as {@code config:<gav>|<path from the reactor root>}:
     * {@code lombok.config} in every directory from the unit's up to the reactor root (content, or
     * established absence), and the module's JPA XML mappings for the Hibernate metamodel.
     */
    private Optional<TreeMap<QueryProof.Key,Hash256>> processorResources(Path file,LogicalSources logical,CompilerInputs.Snapshot observed)throws Exception{
        var result=new TreeMap<QueryProof.Key,Hash256>();
        if(!lombok()&&!jpaXml())return Optional.of(result);
        Path top=reactorRoot();if(top==null)return refuseAttributed("processor-resources-unbound");
        String topGav=context().coordinates().get(top.toString());if(topGav==null)return refuseAttributed("processor-resources-unbound");
        var files=resourceFiles(file,logical,top);if(files.isEmpty())return refuseAttributed("processor-resources-unbound");
        var before=resourceStamps.remove(file);
        if(before==null||before.observed()!=observed)return refuseAttributed("processor-resource-unproven");
        for(Path config:files.get()){
            String recorded=before.stamps().get(config);
            if(recorded==null||!recorded.equals(stamp(config)))return refuseAttributed("processor-resource-superseded");
            result.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"config:"+topGav+"|"+top.relativize(config).toString().replace(java.io.File.separatorChar,'/')),configIdentity(config));
        }
        return Optional.of(result);
    }
    private Hash256 configIdentity(Path config)throws Exception{
        return shared("config:"+config,()->{
            ObservationFaults.check(config);
            String hash=analyzer.inputFiles().hash(config);
            return CanonicalDigestWriter.digest("processor-resource-v1","missing".equals(hash)?"<absent>":hash);
        });
    }
    /** Binary name of a unit from its source-root-relative path. */
    private static String binaryName(String logical){
        String relative=logical.substring(logical.lastIndexOf('|')+1);
        return relative.substring(0,relative.length()-".java".length()).replace('/','.');
    }
    /**
     * Binary P_diag of a class as javac resolves it on the classpath, from one classpath-only task
     * per context and classpath environment: units Lombok hides from the source path, and classes
     * read from other reactor modules' class directories. Capture and restore use this same reader.
     * The dependant's own (pooled) task does not give a stable element model for class-file types: a
     * reused javac context can drop a nested member class. The environment identity covers every
     * class file under the classpath directories, so a rebuilt directory gets a fresh reader, and a
     * record is only captured when its task's environment is still current.
     */
    private Optional<Hash256> binaryProjection(String binary,CompilerInputs.Snapshot observed)throws Exception{
        var key=List.of(context().classpath(),context().compilerOptions(),observed.environment());
        synchronized(this){
            if(!key.equals(binaryProjectionsKey)){
                if(binaryProjections!=null)binaryProjections.close();
                binaryProjections=new BinaryProjections(context().classpath(),context().compilerOptions());binaryProjectionsKey=key;
            }
            return binaryProjections.projection(binary);
        }
    }
    private Optional<Hash256> binaryProjection(Path file,LogicalSources logical,CompilerInputs.Snapshot observed)throws Exception{
        var id=logical.logical(file);if(id.isEmpty()||!id.get().endsWith(".java"))return Optional.empty();
        return binaryProjection(binaryName(id.get()),observed);
    }
    /** Other reactor modules' class directories on this context's classpath, in classpath order. */
    private List<Path> perClassDirectories(){return configured().perClassDirectories();}
    /** Location-free name of a class directory slot. */
    private String slotName(Path directory){
        String role=context().coordinates().get("role:"+directory);
        return (role!=null?role:"reactor")+":"+context().coordinates().get(directory.toString())+(role!=null?"":"|"+directory.getFileName());
    }
    /**
     * Top-level class names of one package in other reactor modules' class directories: what a star
     * import or the unit's own package can newly resolve to. Nested and anonymous class files
     * ({@code $}) are covered by their top-level class's P_diag.
     */
    private Hash256 classPackageIdentity(String pkg)throws Exception{
        // A listing failure propagates (UNKNOWN); only an absent folder is an empty slot.
        var parts=new ArrayList<String>();
        for(Path directory:perClassDirectories()){
            Path folder=pkg.isEmpty()?directory:directory.resolve(pkg.replace('.','/'));
            if(!ObservationFaults.directory(folder))continue;
            var names=new TreeSet<String>();
            try(var listing=Files.list(folder)){
                listing.map(path->path.getFileName().toString()).filter(name->name.endsWith(".class")&&!name.contains("$")).forEach(names::add);
            }
            parts.add(slotName(directory)+"="+String.join(",",names));
        }
        // Which top-level names a package holds, per slot; classpath order is not part of it (shadowing
        // between directories is decided by the reactor-class entries of the classes actually used).
        return CanonicalDigestWriter.digest("reactor-class-package-v2",pkg,List.copyOf(new TreeSet<>(parts)));
    }
    /**
     * Certificate entries for classes the unit completed from other reactor modules' class
     * directories, and for its consulted packages there. Empty when a completed class cannot be bound.
     */
    private Optional<TreeMap<QueryProof.Key,Hash256>> reactorClasses(Bindings.Snapshot attributed,Set<String> packages,CompilerInputs.Snapshot observed)throws Exception{
        var result=new TreeMap<QueryProof.Key,Hash256>();var directories=perClassDirectories();
        if(directories.isEmpty())return Optional.of(result);
        var classpath=context().classpath().stream().map(entry->entry.toAbsolutePath().normalize()).toList();
        for(var entry:attributed.classDirectoryTypes().entrySet()){
            var directory=classpath.stream().filter(entry.getValue()::startsWith).findFirst();
            if(directory.isEmpty()){refuseAttributed("class-directory-unbound");return Optional.empty();}
            if(!directories.contains(directory.get()))continue;
            var projection=binaryProjection(entry.getKey(),observed);
            if(projection.isEmpty()){refuseAttributed("reactor-class-unreadable");return Optional.empty();}
            result.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"reactor-class:"+entry.getKey()),projectionIdentity(projection.get()));
        }
        for(String pkg:packages)result.put(new QueryProof.Key(QueryProof.Domain.NAMESPACE,"class-package:"+pkg),classPackageIdentity(pkg));
        return Optional.of(result);
    }
    private BinaryProjections binaryProjections;private Object binaryProjectionsKey;
    /**
     * Shared snapshot of one observation epoch (W5): every unit validated while the compiler inputs
     * snapshot is unchanged shares one roots inventory, one hash per file, one identity per package
     * and one set of static inputs. Captures (memoize) use the epoch of their own compiler transaction and end with its fence.
     */
    private static final class Epoch {
        final Object key;final Object context;final Map<Path,NavigableSet<Path>> members=new HashMap<>();final Map<Path,Optional<String>> hashes=new HashMap<>();
        final Map<String,Object> values=new HashMap<>();
        Epoch(Object key,Object context){this.key=key;this.context=context;}
    }
    private Epoch epoch,lastEpoch;
    /** The epoch of one observed input snapshot under one configured context (a reconfiguration may reuse the snapshot object). */
    private Epoch epochOf(CompilerInputs.Snapshot observed){
        if(lastEpoch==null||lastEpoch.key!=observed||lastEpoch.context!=context())lastEpoch=new Epoch(observed,context());
        return lastEpoch;
    }
    @FunctionalInterface private interface Computation<T> { T compute()throws Exception; }
    @SuppressWarnings("unchecked")
    private <T> T shared(String key,Computation<T> computation)throws Exception{
        if(epoch==null)return computation.compute();
        if(epoch.values.containsKey(key))return (T)epoch.values.get(key);
        T value=computation.compute();epoch.values.put(key,value);return value;
    }
    /**
     * {@code .java} members of one compiler source root, once per epoch. Inside an epoch they come
     * from the observed live source state (reconciled at start, refreshed by watch events and
     * per-request observation), so validation does no directory work of its own.
     */
    private NavigableSet<Path> members(Path root)throws Exception{
        if(epoch!=null){var cached=epoch.members.get(root);if(cached!=null)return cached;}
        ObservationFaults.check(root);
        NavigableSet<Path> result;
        // Live membership only while the live state is trusted (no overflow or unreconciled change);
        // otherwise the directories are observed directly. A failed listing is UNKNOWN and propagates.
        if(epoch!=null&&epoch.key instanceof CompilerInputs.Snapshot observed&&observed.live()!=null&&observed.trusted())result=liveMembers(root,observed);
        else{
            var listed=new TreeSet<Path>();
            try{listed.addAll(analyzer.inputFiles().inventory(root,".java",true));}
            catch(java.io.IOException failure){throw new ObservationFaults.Unavailable("source root unreadable: "+root,failure);}
            result=listed;
        }
        TreeSet<Path> overlays=null;
        for(Path open:analyzer.documentsState().paths())if(open.startsWith(root)&&open.toString().endsWith(".java")&&!result.contains(open)){
            if(overlays==null)overlays=new TreeSet<>(result);overlays.add(open);
        }
        var value=Collections.unmodifiableNavigableSet(overlays!=null?overlays:result);
        if(epoch!=null)epoch.members.put(root,value);
        return value;
    }
    /**
     * B2: the {@code .java} members of one root under the live source owner, maintained across input
     * snapshots. Built once per root and live owner; a later snapshot applies only the paths the live
     * journal reports changed since the previous one (a rebuild only when the journal cannot answer).
     */
    private record LiveMembers(LiveSourceState live,long observation,Map<Path,TreeSet<Path>> roots) { }
    private LiveMembers liveMembers;
    private long membershipEnumerations,membershipUpdates;
    private NavigableSet<Path> liveMembers(Path root,CompilerInputs.Snapshot observed){
        var live=observed.live();long observation=observed.observation();var current=liveMembers;
        if(current==null||current.live()!=live)current=new LiveMembers(live,observation,new HashMap<>());
        else if(current.observation()!=observation){
            var changed=observation>current.observation()?live.changedPathsSince(current.observation()):Optional.<Set<Path>>empty();
            if(changed.isPresent()){
                for(var entry:current.roots().entrySet())for(Path file:changed.get()){
                    if(!file.startsWith(entry.getKey())||!file.toString().endsWith(".java"))continue;
                    membershipUpdates++;
                    if(live.contentHash(file)!=null)entry.getValue().add(file);else entry.getValue().remove(file);
                }
                current=new LiveMembers(live,observation,current.roots());
            }else current=new LiveMembers(live,observation,new HashMap<>());
        }
        liveMembers=current;
        var set=current.roots().get(root);
        if(set==null){
            membershipEnumerations++;set=new TreeSet<>();
            for(Path file:live.paths())if(file.startsWith(root)&&file.toString().endsWith(".java"))set.add(file);
            current.roots().put(root,set);
        }
        return set;
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
        for(Path normalized:configured().roots()){
            Path directory=pkg.isEmpty()?normalized:normalized.resolve(pkg.replace('.','/'));
            // Paths sharing the directory's string prefix are contiguous; siblings such as "p.x" sort among them.
            for(Path file:members(normalized).tailSet(directory,false)){
                if(!file.toString().startsWith(directory.toString()))break;
                if(!directory.equals(file.getParent()))continue;
                var hash=currentHash(file);if(hash.isEmpty())continue;
                var id=logical.logical(file);if(id.isEmpty())return Optional.empty();
                result.add(new PackageFile(file,id.get(),hash.get()));
            }
        }
        return Optional.of(List.copyOf(result));
    }
    /**
     * S0 top-level type set of one package across the source roots: adding, removing or renaming a
     * top-level type in that package changes it; body edits do not. Each unit's text must still have
     * the snapshotted hash, so a later computation never binds newer content to an older snapshot.
     */
    /** B2: a package's S0 identity, built at most once per package and epoch, for captures and restores alike. */
    private Optional<Hash256> currentPackageIdentity(String pkg,LogicalSources logical,SourceNamespaces.LanguageMode mode)throws Exception{
        return shared("package:"+pkg+"|"+mode,()->{
            packageIdentityBuilds.incrementAndGet();
            var files=packageFiles(pkg,logical);
            return files.isEmpty()?Optional.<Hash256>empty():packageIdentity(pkg,files.get(),mode);
        });
    }
    private final java.util.concurrent.atomic.AtomicLong packageIdentityBuilds=new java.util.concurrent.atomic.AtomicLong();
    private Optional<Hash256> packageIdentity(String pkg,List<PackageFile> files,SourceNamespaces.LanguageMode mode)throws Exception{
        var entries=new ArrayList<Object>();
        for(var file:files){
            if(file.file().getFileName().toString().equals("package-info.java")){entries.add(List.of(file.logical(),file.hash()));continue;}
            var cached=namespaceCache.get(file.file());
            // An S0 result depends on the content and the language mode only (B2).
            Object value;
            if(cached!=null&&cached.hash().equals(file.hash())&&cached.mode().equals(mode))value=cached.value();
            else{
                String text=analyzer.documentsState().text(file.file());
                if(!Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(file.hash()))return Optional.empty();
                var namespace=sourceNamespaces.namespace(text,mode);
                value=namespace.completeness()==SemanticCompleteness.COMPLETE
                        ?List.of(namespace.completeness().name(),namespace.packageName(),namespace.topLevelTypes())
                        :List.of("partial",file.hash());
                namespaceCache.put(file.file(),new NamespaceEntry(file.hash(),mode,value));
            }
            entries.add(List.of(file.logical(),value));
        }
        return Optional.of(CanonicalDigestWriter.digest("attributed-package-namespace-v1",pkg,entries));
    }
    /**
     * Established presence or absence, in the source roots, of a top-level source unit or a package
     * directory named {@code binary}: either would change how javac resolves that name.
     */
    /**
     * Whether a type or a package named {@code binary} exists anywhere javac looks: the compiler
     * source roots and other reactor modules' class directories (archives and the platform are in the
     * static key). Only existence can turn an unresolved name into a resolved one; where a type that
     * exists lives is bound by the dependency entries of the units that use it, so locations and
     * classpath order are not part of this identity.
     */
    private Hash256 absenceIdentity(String binary,LogicalSources logical)throws Exception{
        boolean type=false,pkg=false;
        String relative=binary.replace('.','/');
        for(Path normalized:configured().roots()){
            Path file=normalized.resolve(relative+".java"),directory=normalized.resolve(relative);
            // A root whose members cannot be listed is UNKNOWN (C3): the failure propagates, never an identity.
            NavigableSet<Path> members=members(normalized);
            if(members.contains(file))type=true;
            // A package exists for javac's source path when its directory holds sources.
            for(Path member:members.tailSet(directory,false)){
                if(!member.toString().startsWith(directory.toString()))break;
                if(member.startsWith(directory)){pkg=true;break;}
            }
        }
        for(Path directory:perClassDirectories()){
            if(ObservationFaults.regularFile(directory.resolve(relative+".class")))type=true;
            if(ObservationFaults.directory(directory.resolve(relative)))pkg=true;
        }
        return CanonicalDigestWriter.digest("source-absence-v5",binary,type,pkg);
    }
    /**
     * NEGATIVE_RESOLUTION entries for every name X failed to resolve: unresolved type names and the
     * class, variable and package names in name-resolution diagnostics. Each searched domain outside
     * the package entries (own package and star imports, already bound) must stay absent, as must a
     * top-level package of that name.
     */
    /**
     * Bindings that make a negative resolution precise despite static imports: a name could newly
     * resolve to a static member or member type of a statically imported class, so each such class
     * must be bound. A source unit of these roots is bound by its P_diag entry (it must be a recorded
     * dependency); a class of another module's class directory by its {@code reactor-class:} entry;
     * any other class comes from an archive or the platform (static key), and an absence entry binds
     * a source or class-directory copy appearing later. Every dotted prefix is checked, because a
     * nested class's top-level unit is not known from the text. Empty when a class is not bound.
     */
    private Optional<TreeMap<QueryProof.Key,Hash256>> staticImportBindings(NamespaceResolutionProofs.Header header,Set<Path> dependencies,Bindings.Snapshot attributed,LogicalSources logical)throws Exception{
        var result=new TreeMap<QueryProof.Key,Hash256>();
        var roots=configured().roots();var directories=perClassDirectories();
        for(String type:header.staticImportTypes()){
            var parts=type.split("\\.");
            for(int length=2;length<=parts.length;length++){
                String binary=String.join(".",Arrays.copyOf(parts,length)),relative=binary.replace('.','/');
                Path source=null;
                for(Path root:roots)if(ObservationFaults.regularFile(root.resolve(relative+".java"))){source=root.resolve(relative+".java");break;}
                if(source!=null){if(!dependencies.contains(source))return Optional.empty();continue;}
                boolean classFile=false;
                for(Path directory:directories)if(ObservationFaults.regularFile(directory.resolve(relative+".class"))){classFile=true;break;}
                if(classFile){
                    if(!attributed.classDirectoryTypes().containsKey(binary))return Optional.empty();continue;
                }
                result.put(new QueryProof.Key(QueryProof.Domain.NEGATIVE_RESOLUTION,parts[length-1]+"@"+binary),absenceIdentity(binary,logical));
            }
        }
        return Optional.of(result);
    }
    private Optional<TreeMap<QueryProof.Key,Hash256>> negatives(NamespaceResolutionProofs.Header header,Bindings.Snapshot attributed,List<?> problems,Set<String> packages,LogicalSources logical,Set<Path> dependencies)throws Exception{
        var simple=new TreeSet<String>(attributed.unresolvedTypeNames());var qualified=new TreeSet<String>();
        for(var problem:problems){
            var value=(CompilerPool.Problem)problem;
            if(!value.kind().equals("ERROR")||value.code()==null)continue;
            if(!value.code().contains("cant.resolve")&&!value.code().contains("doesnt.exist"))continue;
            // Names come from javac's structured diagnostic arguments, never from its rendered message.
            // A cant.resolve for a method or constructor names no type or package, so it adds nothing;
            // a doesnt.exist without its package argument cannot be bound.
            if(value.names().isEmpty()){
                if(value.code().contains("doesnt.exist"))return refuseAttributed("negative-unproven:unstructured-diagnostic");
                continue;
            }
            for(String name:value.names())(name.indexOf('.')<0?simple:qualified).add(name);
        }
        var result=new TreeMap<QueryProof.Key,Hash256>();
        for(String name:simple){
            if(!javax.lang.model.SourceVersion.isIdentifier(name))return refuseAttributed("negative-unproven:non-simple-name");
            var plan=NamespaceResolutionProofs.plan(header,name,null);
            // With no resolved winner, a plan is imprecise only because of static imports.
            if(!plan.precise()){
                var bound=staticImportBindings(header,dependencies,attributed,logical);
                if(bound.isEmpty())return refuseAttributed("negative-unproven:static-import");
                result.putAll(bound.get());
            }
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
    /** {@code binary}: javac completed the unit from a class file, so its entry is the binary view. */
    private record Dependency(String logical,Hash256 content,Hash256 projection,boolean binary) { }
    /** A result captured on the owner thread whose SCC is not yet known (W3). */
    private record Pending(SemanticMemoStore.StaticKey key,byte[] result,Map<Path,Dependency> dependencies,
                           Map<String,Hash256> packages,TreeMap<QueryProof.Key,Hash256> negatives,SourceNamespaces.LanguageMode mode) { }
    private final Map<Path,Pending> pending=new LinkedHashMap<>();
    /** Dependency sets of units captured or restored this session, for SCC computation after their contribution is invalidated. */
    private final Map<Path,Set<Path>> knownDependencies=new HashMap<>();
    /**
     * Capture one complete attributed result. The certificate is precise or there is no record:
     * {@code logical-unit:} P_diag for every completed dependency outside the unit's SCC,
     * {@code logical-source:} content for SCC peers, {@code package:} S0 type sets for the own and
     * star-imported packages, and negative resolutions. The record is written once the SCC is known.
     */
    void memoize(Path file,String sourceHash,Envelope envelope,FileSemanticContribution contribution,Bindings.Snapshot attributed,CompilerInputs.Snapshot observed){
        // C4: a capture observes the same input frontier the compiler transaction was validated
        // against (its live snapshot), never later disk or editor state, and ends with a strict fence.
        var saved=epoch;
        if(observed!=null){epoch=epochOf(observed);}else epoch=null;
        try{ObservationFaults.beforeCapture(file);capture(file.toAbsolutePath().normalize(),sourceHash,envelope,contribution,attributed,observed);}finally{epoch=saved;}
    }
    private void capture(Path file,String sourceHash,Envelope envelope,FileSemanticContribution contribution,Bindings.Snapshot attributed,CompilerInputs.Snapshot observed){
        observe(file,sourceHash,attributed);
        // Known even when this unit's own record is refused or its contribution was already
        // invalidated: other units' SCCs may pass through it.
        if(attributed!=null){
            var known=new HashSet<Path>();for(Path dependency:attributed.dependencies())known.add(dependency.toAbsolutePath().normalize());
            known(file,Set.copyOf(known));
        }
        if(attributedMemos==null||contribution==null){if(attributedMemos!=null)refuseAttributed("contribution-unavailable");return;}
        // Context warnings (processor fidelity notes) belong to the context and are part of the static
        // key; only warnings about this query (faults, superseded inputs, originating modules) block a record.
        var queryWarnings=new ArrayList<>(envelope.warnings());queryWarnings.removeAll(context().warnings());
        // "originates: <gav>" names the module an error's symbol comes from: a function of the
        // diagnostics and the bound inputs, so it is part of the result. Any other query warning
        // (a fault, superseded inputs) means the result is not a complete answer.
        var resultWarnings=queryWarnings.stream().filter(warning->warning.startsWith("originates: ")).distinct().sorted().toList();
        queryWarnings.removeAll(resultWarnings);
        if(!queryWarnings.isEmpty()){refuseAttributed("query-warning:"+queryWarnings.get(0).replaceAll("[:\\s].*$",""));return;}
        if(context().warnings().stream().anyMatch(warning->warning.startsWith("unsaved_processor_inputs"))){refuseAttributed("unsaved-processor-inputs");return;}
        var projection=projection(file);if(projection.isEmpty()){refuseAttributed("projection-unavailable");return;}
        try{
            var logical=logical();
            var key=attributedStaticKey(file,sourceHash,logical);if(key.isEmpty())return;
            if(!contribution.sourceHash().equals(sourceHash)){refuseAttributed("contribution-superseded");return;}
            String text=analyzer.documentsState().text(file);
            if(!Hashing.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(sourceHash)){refuseAttributed("source-superseded");return;}
            var roots=configured().roots();
            var dependencies=new TreeMap<Path,Dependency>();
            for(Path dependency:contribution.dependencies()){
                Path normalized=dependency.toAbsolutePath().normalize();if(normalized.equals(file))continue;
                // javac's source path is the compiler source roots: a unit outside them was read from the
                // classpath (another module's class output), which the static key binds as a slot.
                if(roots.stream().noneMatch(normalized::startsWith))continue;
                var id=logical.logical(normalized);if(id.isEmpty()){refuseAttributed("non-logical-dependency");return;}
                boolean binary=attributed.binaryDependencies().contains(normalized);
                var dependencyProjection=binary?binaryProjection(normalized,logical,observed).orElse(null):attributed.dependencyProjections().get(normalized);
                if(dependencyProjection==null){refuseAttributed("dependency-unproven");return;}
                var content=currentHash(normalized);if(content.isEmpty()){refuseAttributed("dependency-unproven");return;}
                dependencies.put(normalized,new Dependency(id.get(),logicalContentIdentity(content.get()),dependencyProjection,binary));
            }
            // The package and imports javac used for this result, from its own syntax tree (never the raw text).
            var header=attributed.header();
            if(header==null){refuseAttributed("header-unavailable");return;}
            if(!header.complete()){refuseAttributed("header-unsupported");return;}
            var packageNames=new TreeSet<String>(header.consultedPackages());var packages=new TreeMap<String,Hash256>();
            var mode=SourceNamespaces.LanguageMode.of(context().compilerOptions());
            for(String pkg:packageNames){
                var identity=currentPackageIdentity(pkg,logical,mode);if(identity.isEmpty()){refuseAttributed("package-unproven");return;}
                packages.put(pkg,identity.get());
            }
            var diagnostics=(List<?>)((Map<?,?>)envelope.result()).get("diagnostics");
            var negatives=negatives(header,attributed,diagnostics,packageNames,logical,dependencies.keySet());if(negatives.isEmpty())return;
            var resources=processorResources(file,logical,observed);if(resources.isEmpty())return;negatives.get().putAll(resources.get());
            var reactor=reactorClasses(attributed,packageNames,observed);if(reactor.isEmpty())return;negatives.get().putAll(reactor.get());
            var problems=new ArrayList<Map<String,Object>>();
            for(var problem:diagnostics){
                var value=(CompilerPool.Problem)problem;
                // Keep the exact form javac reported (a file URI, or the plain path when it had no source object).
                String fileId=value.file()==null?null:value.file().equals(file.toString())?"self-path":Analyzer.sameFile(value.file(),file)?"self":null;
                if(value.file()!=null&&fileId==null){refuseAttributed("foreign-diagnostic-file");return;}
                var row=new LinkedHashMap<String,Object>();
                row.put("source",value.source());row.put("tier",value.tier());row.put("code",value.code());row.put("kind",value.kind());
                row.put("file",fileId);row.put("line",value.line());row.put("character",value.character());
                row.put("start",value.start());row.put("end",value.end());row.put("message",logical.toLogicalText(value.message()));
                problems.add(row);
            }
            var result=new LinkedHashMap<String,Object>();
            result.put("tier",envelope.tier());result.put("diagnostics",problems);result.put("warnings",resultWarnings);
            result.put("api",contribution.apiFingerprint());
            result.put("p_diag",projection.get().hex());
            result.put("exported",contribution.exportedNames().stream().sorted().toList());
            result.put("unresolved",contribution.unresolvedTargets().stream().sorted().toList());
            result.put("dependencies",dependencies.values().stream().map(Dependency::logical).sorted().toList());
            // Every value above was read from the transaction's live state. If that state moved on (an
            // edit, a membership change, or A -> B -> A, which the input epoch also detects), the
            // evidence may not be what javac read: no record.
            if(observed==null||!observed.transactionCurrent()){refuseAttributed("inputs-superseded");return;}
            pending.put(file,new Pending(key.get(),Json.MAPPER.writeValueAsBytes(result),dependencies,packages,negatives.get(),
                    SourceNamespaces.LanguageMode.of(context().compilerOptions())));
            if(pending.size()>=drainAt||System.nanoTime()-lastDrain>drainInterval){lastDrain=System.nanoTime();scheduledDrain();}
        }catch(ObservationFaults.Unavailable unknown){refuseAttributed("observation-unavailable");lastFailure=unknown.toString();}
        catch(Exception failure){attributedMemoFailures.incrementAndGet();lastFailure=failure.toString();}
    }
    private volatile String lastFailure="";
    /** Whether a unit belongs to another module's source root; once per unit and context. */
    private boolean foreignModule(Path unit,LogicalSources logical){
        if(logical==null)return false;
        return configured().foreign().computeIfAbsent(unit,current->{var id=logical.logical(current);return id.isPresent()&&!id.get().startsWith(context().gav()+"|");});
    }
    private static final int PENDING_BATCH=256;
    private static final long DRAIN_INTERVAL_NANOS=2_000_000_000L,MAX_DRAIN_INTERVAL_NANOS=60_000_000_000L;
    private long lastDrain=System.nanoTime(),drainInterval=DRAIN_INTERVAL_NANOS;private int drainAt=PENDING_BATCH;private long unproductiveDrains;
    /**
     * Drains triggered by captures back off while they write nothing. During cold admission many pending
     * units still reach units not yet attributed, so their components cannot be final; draining them again
     * every 256 captures or 2 s re-traversed the whole unsettled region for no result (43% of a cold 5,000-unit
     * session's allocation). After an unproductive drain the next one waits until the pending set doubles or
     * the interval doubles (at most 60 s); a productive drain resets both. Close and awaitWrites still drain
     * everything, so what is written is unchanged; only when.
     */
    private void scheduledDrain(){
        int before=pending.size();drain(false);
        if(pending.size()<before){drainAt=PENDING_BATCH;drainInterval=DRAIN_INTERVAL_NANOS;}
        else{unproductiveDrains++;drainAt=Math.max(PENDING_BATCH,2*pending.size());drainInterval=Math.min(MAX_DRAIN_INTERVAL_NANOS,2*drainInterval);}
    }
    /**
     * P3 (corrective pass): components already found final are settled. A later drain does not traverse
     * a settled region again; it takes the recorded component as final. A component is settled only when
     * every successor outside it is settled or a fixed sink (outside the compiler roots, or another
     * module), so everything a settled node reaches is settled or a sink. Any change of a node's
     * dependency set ({@link #dependenciesObserved}, {@link #known}) unsettles that node and, through the
     * reverse edges, every settled node that reaches it: merges, splits and edges to formerly unknown
     * nodes are re-examined. A context change clears the settled state.
     */
    private final Map<Path,Set<Path>> settled=new HashMap<>(),settledEdges=new HashMap<>(),settledReverse=new HashMap<>();
    private Object settledContext;
    private long sccDrains,sccVertexVisits,sccEdgeVisits,sccSettledReuses,sccInvalidations;
    private volatile Path unknownSample;
    /** Record a unit's dependency set (captured, written or restored). */
    private void known(Path file,Set<Path> dependencies){knownDependencies.put(file,dependencies);dependenciesObserved(file,dependencies);}
    /** Every dependency-set observation of {@code file} (Analyzer.resolveContribution, capture, restore). */
    void dependenciesObserved(Path file,Collection<Path> dependencies){
        file=file.toAbsolutePath().normalize();var at=settledEdges.get(file);if(at==null||context()==null)return;
        if(!at.equals(relevant(file,dependencies)))unsettle(file);
    }
    /** The edges that matter for SCCs: in the compiler roots (others are sinks) and not the unit itself. */
    private Set<Path> relevant(Path file,Collection<Path> dependencies){
        var roots=configured().roots();var result=new HashSet<Path>();
        for(Path dependency:dependencies){Path normalized=dependency.toAbsolutePath().normalize();if(!normalized.equals(file)&&roots.stream().anyMatch(normalized::startsWith))result.add(normalized);}
        return result;
    }
    private void unsettle(Path changed){
        var queue=new ArrayDeque<Path>();queue.add(changed);
        while(!queue.isEmpty()){
            Path node=queue.poll();var edges=settledEdges.remove(node);
            if(edges==null)continue;
            sccInvalidations++;
            var component=settled.remove(node);
            if(component!=null)for(Path peer:component)if(settledEdges.containsKey(peer))queue.add(peer);
            for(Path successor:edges){var reverse=settledReverse.get(successor);if(reverse!=null){reverse.remove(node);if(reverse.isEmpty())settledReverse.remove(successor);}}
            var reachers=settledReverse.get(node);if(reachers!=null)queue.addAll(reachers);
        }
    }
    private void settle(Set<Path> component,Map<Path,Set<Path>> edgesOf){
        for(Path member:component){
            var edges=Set.copyOf(relevant(member,edgesOf.get(member)));
            settled.put(member,component);settledEdges.put(member,edges);
            for(Path successor:edges)settledReverse.computeIfAbsent(successor,ignored->new HashSet<>()).add(member);
        }
    }
    /**
     * Write every pending result whose strongly connected component is final: every unit reachable
     * from it has a known dependency set. Iterative Tarjan from the pending units; components complete
     * in reverse topological order, so a component is final when no member and no successor reaches an
     * unknown unit. Settled regions are not entered (P3). When {@code closing}, the rest are refused as
     * {@code scc-unproven}.
     */
    void drain(boolean closing){
        if(pending.isEmpty())return;
        sccDrains++;
        if(settledContext!=context()){settled.clear();settledEdges.clear();settledReverse.clear();settledContext=context();}
        var roots=context()==null?List.<Path>of():configured().roots();
        var logical=context()==null?null:logical();
        var graph=new HashMap<Path,Set<Path>>();var sinks=new HashSet<Path>();
        java.util.function.Function<Path,Set<Path>> edges=unit->graph.computeIfAbsent(unit,current->{
            var captured=pending.get(current);if(captured!=null)return captured.dependencies().keySet();
            if(roots.stream().noneMatch(current::startsWith)){sinks.add(current);return Set.of();}
            // Maven reactor modules form a DAG: a unit of another module's source root cannot reach back
            // into this module, so it is a sink for this module's SCCs.
            if(foreignModule(current,logical)){sinks.add(current);return Set.of();}
            var contribution=analyzer.contribution(current);
            if(contribution==null){var known=knownDependencies.get(current);if(known!=null)return known;unknownSample=current;return null;}
            var result=new HashSet<Path>();for(Path dependency:contribution.dependencies())result.add(dependency.toAbsolutePath().normalize());
            return result;
        });
        var index=new HashMap<Path,Integer>();var low=new HashMap<Path,Integer>();var stack=new ArrayDeque<Path>();var onStack=new HashSet<Path>();
        // Per node: its component and whether that component is final.
        var component=new HashMap<Path,Set<Path>>();var isFinal=new HashMap<Path,Boolean>();int[] counter={0};
        java.util.function.Predicate<Path> reuseSettled=node->{
            var known=settled.get(node);if(known==null||pending.containsKey(node)&&!pendingMatchesSettled(node))return false;
            index.put(node,-1);for(Path member:known){component.put(member,known);isFinal.put(member,true);}
            sccSettledReuses++;return true;
        };
        for(Path start:new ArrayList<>(pending.keySet())){
            if(index.containsKey(start)||reuseSettled.test(start))continue;
            // Iterative Tarjan: each frame is a node and the iterator over its successors.
            var frames=new ArrayDeque<Map.Entry<Path,Iterator<Path>>>();
            index.put(start,counter[0]);low.put(start,counter[0]++);stack.push(start);onStack.add(start);sccVertexVisits++;
            var startEdges=edges.apply(start);frames.push(Map.entry(start,startEdges==null?Collections.emptyIterator():startEdges.iterator()));
            while(!frames.isEmpty()){
                var frame=frames.peek();Path node=frame.getKey();
                if(frame.getValue().hasNext()){
                    Path next=frame.getValue().next();sccEdgeVisits++;
                    if(!index.containsKey(next)){
                        if(reuseSettled.test(next))continue;
                        index.put(next,counter[0]);low.put(next,counter[0]++);stack.push(next);onStack.add(next);sccVertexVisits++;
                        var nextEdges=edges.apply(next);frames.push(Map.entry(next,nextEdges==null?Collections.emptyIterator():nextEdges.iterator()));
                    }else if(onStack.contains(next))low.put(node,Math.min(low.get(node),index.get(next)));
                    continue;
                }
                frames.pop();
                if(!frames.isEmpty()){Path parent=frames.peek().getKey();low.put(parent,Math.min(low.get(parent),low.get(node)));}
                if(low.get(node).equals(index.get(node))){
                    var members=new HashSet<Path>();Path member;
                    do{member=stack.pop();onStack.remove(member);members.add(member);}while(!member.equals(node));
                    boolean reachesUnknown=false,settleable=true;
                    for(Path value:members){
                        var successors=edges.apply(value);
                        if(successors==null){reachesUnknown=true;break;}
                        for(Path successor:successors){
                            if(members.contains(successor))continue;
                            if(!isFinal.getOrDefault(successor,false)){reachesUnknown=true;break;}
                            if(!sinks.contains(successor)&&!settled.containsKey(successor))settleable=false;
                        }
                        if(reachesUnknown)break;
                    }
                    var frozen=Set.copyOf(members);for(Path value:members){component.put(value,frozen);isFinal.put(value,!reachesUnknown);}
                    if(!reachesUnknown&&settleable){var edgesOf=new HashMap<Path,Set<Path>>();for(Path value:members)edgesOf.put(value,edges.apply(value));settle(frozen,edgesOf);}
                }
            }
        }
        for(var iterator=pending.entrySet().iterator();iterator.hasNext();){
            var entry=iterator.next();var members=component.get(entry.getKey());
            if(members==null||!isFinal.getOrDefault(entry.getKey(),false)){
                if(closing){iterator.remove();refuseAttributed("scc-unproven");}
                continue;
            }
            iterator.remove();known(entry.getKey(),Set.copyOf(entry.getValue().dependencies().keySet()));write(entry.getKey(),entry.getValue(),members);
        }
    }
    /** A pending unit may reuse its settled component only if its captured dependencies are those it was settled with. */
    private boolean pendingMatchesSettled(Path node){
        var at=settledEdges.get(node);return at!=null&&at.equals(relevant(node,pending.get(node).dependencies().keySet()));
    }
    private void write(Path file,Pending captured,Set<Path> component){
        var dependencies=new TreeMap<QueryProof.Key,Hash256>(captured.negatives());
        // Every SCC peer binds its content, including peers the unit did not complete directly. Those
        // do not affect the unit's result (it read only its completed units), so binding their current
        // content only narrows reuse.
        if(component.size()>1){
            var logical=logical();
            for(Path peer:component){
                if(peer.equals(file)||captured.dependencies().containsKey(peer))continue;
                var id=logical.logical(peer);Optional<String> hash;
                try{hash=currentHash(peer);}catch(ObservationFaults.Unavailable unknown){refuseAttributed("observation-unavailable");return;}
                if(id.isEmpty()||hash.isEmpty()){refuseAttributed("scc-unproven");return;}
                dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-source:"+id.get()),logicalContentIdentity(hash.get()));
            }
        }
        for(var entry:captured.dependencies().entrySet()){
            var dependency=entry.getValue();
            if(component.contains(entry.getKey()))dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-source:"+dependency.logical()),dependency.content());
            else dependencies.put(new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,(dependency.binary()?"logical-binary:":"logical-unit:")+dependency.logical()),projectionIdentity(dependency.projection()));
        }
        var store=attributedMemos;String scope=scope();
        pendingMemoWrites.incrementAndGet();
        memoWriter().submit(()->{
            try{
                // Package identities were established on the owner thread at capture, from the transaction's
                // own snapshot; the writer only encodes and stores (A3).
                for(var entry:captured.packages().entrySet())
                    dependencies.put(new QueryProof.Key(QueryProof.Domain.NAMESPACE,"package:"+scope+"|"+entry.getKey()),entry.getValue());
                var certificate=new SemanticMemoStore.Certificate(new QueryProof(dependencies.entrySet().stream()
                        .map(entry->new QueryProof.Dependency(entry.getKey(),entry.getValue())).toList()));
                store.put(new SemanticMemoStore.MemoRecord(captured.key(),certificate,SemanticMemoStore.Coverage.PRECISE,
                        SemanticCompleteness.COMPLETE,SemanticMemoStore.Result.present(captured.result())));
                attributedMemoWrites.incrementAndGet();
            }catch(Exception failure){attributedMemoFailures.incrementAndGet();lastFailure="write: "+failure;}
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
        var saved=epoch;
        epoch=epochOf(observed);
        try{return resolve(path,hash,observed);}finally{resolving.remove(path);epoch=saved;}
    }
    /** Units whose restore or early-cutoff attribution is in progress on the owner thread; guards cycles. */
    private final Set<Path> resolving=new HashSet<>();
    private Envelope resolve(Path path,String hash,CompilerInputs.Snapshot observed){
        try{
            var logical=logical();
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
                        if(value.startsWith("config:")){
                            Path top=reactorRoot();String prefix=top==null?null:"config:"+context().coordinates().get(top.toString())+"|";
                            if(prefix==null||!value.startsWith(prefix)||value.contains(".."))return Optional.empty();
                            return Optional.of(configIdentity(top.resolve(value.substring(prefix.length()))));
                        }
                        if(value.startsWith("logical-binary:")){
                            var file=logical.physical(value.substring("logical-binary:".length()));
                            return file.isEmpty()?Optional.empty():shared("binary:"+file.get(),()->binaryProjection(file.get(),logical,observed)).map(AttributedMemos::projectionIdentity);
                        }
                        if(value.startsWith("reactor-class:")){
                            String binary=value.substring("reactor-class:".length());
                            return shared("reactor-class:"+binary,()->binaryProjection(binary,observed)).map(AttributedMemos::projectionIdentity);
                        }
                        if(value.startsWith("logical-unit:")){
                            var file=logical.physical(value.substring("logical-unit:".length()));
                            return file.isEmpty()?Optional.empty():currentProjection(file.get(),observed).map(AttributedMemos::projectionIdentity);
                        }
                    }
                    case NAMESPACE -> {
                        if(value.startsWith(packagePrefix)){
                            String pkg=value.substring(packagePrefix.length());
                            return currentPackageIdentity(pkg,logical,mode);
                        }
                        if(value.startsWith("class-package:")){
                            String pkg=value.substring("class-package:".length());
                            return Optional.of(shared("class-package:"+pkg,()->classPackageIdentity(pkg)));
                        }
                    }
                    case NEGATIVE_RESOLUTION -> {
                        int split=value.lastIndexOf('@');
                        if(split>0){String binary=value.substring(split+1);return Optional.of(shared("absent:"+binary,()->absenceIdentity(binary,logical)));}
                    }
                    default -> { }
                }
                return Optional.empty();
            });
            if(!(lookup instanceof SemanticMemoStore.Lookup.Hit hit)){
                attributedMemoMisses++;lastAttributedMemoMiss=((SemanticMemoStore.Lookup.Miss)lookup).reason();
                // Group by reason and key kind (the key's own value is in last_miss).
                String reason=lastAttributedMemoMiss.replaceAll("^((?:stale|unknown)-dependency:[A-Z_]+:[a-z-]+):.*$","$1");
                missReasons.merge(reason,1L,Long::sum);return null;
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
                String file=value.path("file").isNull()?null:value.path("file").asText().equals("self-path")?path.toString():path.toUri().toString();
                problems.add(new CompilerPool.Problem(value.path("source").asText(null),value.path("tier").asInt(),value.path("code").asText(null),
                        value.path("kind").asText(null),file,value.path("line").asLong(),value.path("character").asLong(),
                        value.path("start").asLong(),value.path("end").asLong(),logical.toPhysicalText(value.path("message").asText(null))));
            }
            var exported=new LinkedHashSet<String>();data.path("exported").forEach(value->exported.add(value.asText()));
            var unresolved=new LinkedHashSet<String>();data.path("unresolved").forEach(value->unresolved.add(value.asText()));
            var contribution=new FileSemanticContribution(path,hash,data.path("api").asText(),dependencies,exported,unresolved);
            projections.put(path,new Projection(hash,Hash256.fromHex(data.path("p_diag").asText())));
            var warnings=new ArrayList<>(contextWarnings());data.path("warnings").forEach(value->warnings.add(value.asText()));
            var envelope=new Envelope(data.path("tier").asInt(),"live",false,null,List.copyOf(warnings),Map.of("diagnostics",List.copyOf(problems)));
            analyzer.dependencyGraph().recordFocused(path,dependencies);analyzer.resolveContribution(contribution);known(path,Set.copyOf(dependencies));
            String broad=Analyzer.broadDiagnosticStamp(observed);
            analyzer.diagnosticStore().put(path,hash,context().generation(),broad,envelope,contribution.apiFingerprint(),dependencies,contribution);
            attributedMemoRestores++;
            return envelope;
        }catch(ObservationFaults.Unavailable unknown){
            attributedMemoMisses++;lastAttributedMemoMiss="observation-unavailable";missReasons.merge("observation-unavailable",1L,Long::sum);lastFailure="restore: "+unknown;return null;
        }catch(Exception failure){attributedMemoFailures.incrementAndGet();lastFailure="restore: "+failure;return null;}
    }
    /** Current content hash; empty only for an established absence. An unreadable file is UNKNOWN and throws. */
    private Optional<String> currentHash(Path file)throws ObservationFaults.Unavailable{
        if(epoch!=null){var cached=epoch.hashes.get(file);if(cached!=null)return cached;}
        ObservationFaults.check(file);
        Optional<String> value;
        String live=epoch!=null&&epoch.key instanceof CompilerInputs.Snapshot observed&&observed.live()!=null&&observed.live().accepts(file)
                ?observed.live().contentHash(file):null;
        if(live!=null){value=Optional.of(live);epoch.hashes.put(file,value);return value;}
        try{
            String hash=analyzer.documentsState().sourceHash(file);
            value="missing".equals(hash)?Optional.empty():Optional.of(hash);
        }catch(Exception unreadable){throw new ObservationFaults.Unavailable("source unreadable: "+file,unreadable);}
        if(epoch!=null)epoch.hashes.put(file,value);
        return value;
    }
    /**
     * Current P_diag of a dependency (W4 early cutoff): known for its current content, established by
     * restoring it first, or else by attributing it once with javac. A dependant then compares its
     * entry against that current value, so a body edit recompiles only the edited unit.
     */
    private Optional<Hash256> currentProjection(Path file,CompilerInputs.Snapshot observed){
        file=file.toAbsolutePath().normalize();
        Optional<String> hash;try{hash=currentHash(file);}catch(ObservationFaults.Unavailable unknown){return Optional.empty();}
        if(hash.isEmpty())return Optional.empty();
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
        result.put("pending_scc",pending.size());result.put("last_failure",lastFailure);result.put("scc_unknown_sample",unknownSample==null?"":unknownSample.toString());
        result.put("scc",Map.of("unproductive_drains",unproductiveDrains,"drains",sccDrains,"vertex_visits",sccVertexVisits,"edge_visits",sccEdgeVisits,"settled_reuses",sccSettledReuses,"invalidations",sccInvalidations,"settled",settled.size()));result.put("miss_reasons",Map.copyOf(missReasons));result.put("early_cutoff_attributions",earlyCutoffAttributions);result.put("pending_writes",pendingMemoWrites.get());
        result.put("config_derivations",configDerivations.get());
        result.put("package_identity_builds",packageIdentityBuilds.get());result.put("membership_enumerations",membershipEnumerations);result.put("membership_updates",membershipUpdates);
        result.put("source_namespaces",sourceNamespaces.status());
        if(attributedMemos!=null)result.put("store",attributedMemos.status());
        return Collections.unmodifiableMap(result);
    }

    @Override public void close()throws InterruptedException{
        if(context()!=null)drain(true);awaitWrites();synchronized(this){if(memoWriter!=null){memoWriter.shutdown();memoWriter=null;}if(binaryProjections!=null){binaryProjections.close();binaryProjections=null;binaryProjectionsKey=null;}}
    }
}
