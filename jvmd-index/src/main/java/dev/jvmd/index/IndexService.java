package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.JarFile;

/** Implements 4.4: machine-global eager skeleton/docs indexing, content invalidation and queries. */
public final class IndexService implements AutoCloseable {
    /** Implements 4.4: persisted artifact identity and fast-path file stamps. */
    public record Artifact(long id,String gav,String kind,String sha256,String path,long size,long mtime,boolean hasDocs,boolean hasCodeEdges,boolean hasSignatureEdges) { }
    /** Implements 4.4: one workspace's filtered artifact membership. */
    public record WorkspaceArtifact(String path,String scope) { }
    private final SqliteIndexStore sqliteStore;
    private final IndexStore store;
    private final IndexDatabase database;
    private final ArtifactGenerationSink generationSink;
    private final Path repository;
    private final SourceIndexPublisher sourcePublisher=new SourceIndexPublisher(this::recordSource,32L*1024*1024);
    public void publishSource(SourceIndexPublisher.Delta delta){sourcePublisher.enqueue(delta);}
    public long semanticRevision(Path file)throws Exception{return generationSink.semanticRevision(file);}
    public Map<String,Object> sourcePublisherStatus(){return sourcePublisher.status();}
    private final ExecutorService readers=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())),Thread.ofVirtual().name("jvmd-index-reader-",0).factory());
    private final ScheduledExecutorService scanner=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jvmd-index-scan").factory());
    private final AtomicLong scanned=new AtomicLong(),indexed=new AtomicLong(),reused=new AtomicLong(),hashed=new AtomicLong(),faults=new AtomicLong();
    private final AtomicLong scans=new AtomicLong(),scanNanos=new AtomicLong(),discoveryNanos=new AtomicLong(),hashNanos=new AtomicLong(),
            parseNanos=new AtomicLong(),storageNanos=new AtomicLong(),docsNanos=new AtomicLong(),linkNanos=new AtomicLong(),
            queryCalls=new AtomicLong(),queryNanos=new AtomicLong(),workspaceResolutionCalls=new AtomicLong(),workspaceResolutionNanos=new AtomicLong(),
            shadowComparisons=new AtomicLong(),shadowMismatches=new AtomicLong(),shadowSkipped=new AtomicLong();
    private final ConcurrentHashMap<String,String> activeArtifacts=new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> warnings=new ConcurrentLinkedDeque<>();
    private volatile String phase="idle";
    private volatile long total;
    private volatile boolean closed;
    public IndexService(Path database,Path repository) throws Exception {
        this(database,repository,ArtifactGenerationSink.none());
    }
    public IndexService(Path database,Path repository,ArtifactGenerationSink generationSink) throws Exception {
        this.sqliteStore=new SqliteIndexStore(database);this.store=sqliteStore;this.database=sqliteStore.database();
        this.repository=repository.toAbsolutePath().normalize();this.generationSink=Objects.requireNonNull(generationSink);
    }
    public IndexDatabase database(){return database;}
    public IndexStore store(){return store;}
    public long generation(){return indexed.get();}
    public void start(){
        long initialDelaySeconds=Long.getLong("jvmd.index.scan.initial_delay_seconds",2L);
        if(initialDelaySeconds<0)throw new IllegalArgumentException("jvmd.index.scan.initial_delay_seconds must be non-negative");
        scanner.scheduleWithFixedDelay(()->{try{scan();}catch(Exception e){warn("index_scan_fault: "+e);}},
                initialDelaySeconds,60,TimeUnit.SECONDS);
    }
    public synchronized void scan() throws Exception {
        if(closed||!Files.isDirectory(repository))return;
        long start=System.nanoTime();scans.incrementAndGet();phase="discovering";
        long discoveryStarted=System.nanoTime();
        List<Path> jars;try(var files=Files.walk(repository)){jars=files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")&&!p.getFileName().toString().endsWith("-javadoc.jar")).sorted().toList();}
        discoveryNanos.addAndGet(System.nanoTime()-discoveryStarted);
        total=jars.size();scanned.set(0);phase="skeletons";
        long inventoryGeneration=generationSink.beginScan();
        var skeletonComplete=new AtomicBoolean(true);
        var jobs=new ArrayList<Future<?>>();
        for(var jar:jars)if(!jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{
            try{indexJar(jar,gav(jar),"jar",inventoryGeneration);}
            catch(Exception|LinkageError e){skeletonComplete.set(false);warn("artifact_fault: "+jar+": "+e);}
            finally{scanned.incrementAndGet();}
        }));
        for(var job:jobs)job.get();
        jobs.clear();phase="docs";long docsStarted=System.nanoTime();var docsComplete=new AtomicBoolean(true);
        for(var jar:jars)if(jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{
            try{indexSources(jar);}
            catch(Exception|LinkageError e){docsComplete.set(false);warn("source_fault: "+jar+": "+e);}
            finally{scanned.incrementAndGet();}
        }));
        for(var job:jobs)job.get();docsNanos.addAndGet(System.nanoTime()-docsStarted);
        if(skeletonComplete.get()&&docsComplete.get()){
            generationSink.completeScan(inventoryGeneration);
            if(store.reconcilePaths(repository,Set.copyOf(jars)))indexed.incrementAndGet();
        }
        phase="linking";long linkStarted=System.nanoTime();linkEdges();linkNanos.addAndGet(System.nanoTime()-linkStarted);phase="ready";
        long elapsed=System.nanoTime()-start;scanNanos.addAndGet(elapsed);
        System.getLogger("dev.jvmd.index").log(System.Logger.Level.INFO,"index scan: {0} artifacts in {1} ms",jars.size(),elapsed/1_000_000);
    }
    private void warn(String warning){faults.incrementAndGet();warnings.add(warning);while(warnings.size()>50)warnings.poll();System.getLogger("dev.jvmd.index").log(System.Logger.Level.WARNING,warning);}
    public Map<String,Object> status() throws Exception {
        var result=new LinkedHashMap<String,Object>();result.putAll(store.counts());result.put("source_publisher",sourcePublisher.status());
        result.put("phase",phase);result.put("total",total);result.put("scanned",scanned.get());result.put("indexed",indexed.get());
        result.put("reused",reused.get());result.put("hashes",hashed.get());result.put("faults",faults.get());result.put("warnings",List.copyOf(warnings));
        result.put("active_artifacts",Map.copyOf(activeArtifacts));result.put("store",store.status());result.put("generation_sink",generationSink.status());
        result.put("read_backend",System.getProperty("jvmd.index.read.backend","shadow"));
        var timings=new LinkedHashMap<String,Object>();
        timings.put("scans",scans.get());timings.put("scan_ms",millis(scanNanos.get()));timings.put("discovery_ms",millis(discoveryNanos.get()));
        timings.put("hash_ms",millis(hashNanos.get()));timings.put("parse_ms",millis(parseNanos.get()));timings.put("storage_ms",millis(storageNanos.get()));
        timings.put("docs_ms",millis(docsNanos.get()));timings.put("link_ms",millis(linkNanos.get()));
        timings.put("query_calls",queryCalls.get());timings.put("query_ms",millis(queryNanos.get()));
        timings.put("workspace_resolution_calls",workspaceResolutionCalls.get());timings.put("workspace_resolution_ms",millis(workspaceResolutionNanos.get()));
        result.put("timings",Map.copyOf(timings));
        result.put("shadow_validation",Map.of("comparisons",shadowComparisons.get(),"mismatches",shadowMismatches.get(),"skipped",shadowSkipped.get()));
        return result;
    }
    private static double millis(long nanos){return Math.round(nanos/1000.0)/1000.0;}
    private void active(Path path,String operation){activeArtifacts.put(location(path),operation);}
    public String gav(Path path){
        Path relative=repository.relativize(path.toAbsolutePath().normalize());int n=relative.getNameCount();
        if(n<4)return "local:"+path.getFileName()+":0";
        return relative.subpath(0,n-3).toString().replace(java.io.File.separatorChar,'.')+":"+relative.getName(n-3)+":"+relative.getName(n-2);
    }
    private static String location(Path path){return path.getFileSystem().provider().getScheme().equals("file")?path.toAbsolutePath().normalize().toString():path.toUri().toString();}
    public Artifact artifact(Path path) throws Exception {
        var value=store.artifact(path);return value==null?null:new Artifact(value.id(),value.gav(),value.kind(),value.sha256(),value.path(),
                value.size(),value.mtime(),value.hasDocs(),value.hasCodeEdges(),value.hasSignatureEdges());
    }
    public long indexJar(Path path,String gav,String kind) throws Exception{return indexJar(path,gav,kind,0L);}
    private long indexJar(Path path,String gav,String kind,long inventoryGeneration) throws Exception {
        path=path.toAbsolutePath().normalize();Path tracked=path;active(path,"stat");
        try(var permit=generationSink.acquireArtifact(path)){
            var previous=artifact(path);
            var stamp=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);long size=stamp.size(),mtime=stamp.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            if(previous!=null&&previous.hasSignatureEdges()&&!gav.contains("SNAPSHOT")&&!kind.equals("local")&&previous.size()==size&&previous.mtime()==mtime){
                ensureGeneration(path,kind,ArtifactIndexFormat.key(previous.sha256(),"signatures"));
                if(inventoryGeneration>0){
                    var key=ArtifactIndexFormat.key(previous.sha256(),"signatures");
                    generationSink.observe(inventoryGeneration,new IndexStore.ArtifactInput(new ArtifactContext(gav,kind,location(path)),key,size,mtime));
                }
                reused.incrementAndGet();return previous.id();
            }
            active(path,"hash");long hashStarted=System.nanoTime();verifyChecksum(path);String hash=Files.isDirectory(path)?directoryHash(path):Hashing.sha256(path);hashNanos.addAndGet(System.nanoTime()-hashStarted);hashed.incrementAndGet();
            if(previous!=null&&previous.hasSignatureEdges()&&previous.sha256().equals(hash)){
                ensureGeneration(path,kind,ArtifactIndexFormat.key(hash,kind.equals("local")?"local-signatures":"signatures"));
                store.publishPath(path,previous.id(),size,mtime);
                if(inventoryGeneration>0){
                    var key=ArtifactIndexFormat.key(hash,kind.equals("local")?"local-signatures":"signatures");
                    generationSink.observe(inventoryGeneration,new IndexStore.ArtifactInput(new ArtifactContext(gav,kind,location(path)),key,size,mtime));
                }
                reused.incrementAndGet();return previous.id();
            }
            active(path,"parse");long parseStarted=System.nanoTime();var content=new BinaryReader().read(path,kind.equals("local"));parseNanos.addAndGet(System.nanoTime()-parseStarted);content.warnings().forEach(this::warn);
            active(path,"storage");long storageStarted=System.nanoTime();
            var key=ArtifactIndexFormat.key(hash,kind.equals("local")?"local-signatures":"signatures");
            var input=new IndexStore.ArtifactInput(new ArtifactContext(gav,kind,location(path)),key,size,mtime);
            var facts=ArtifactIndexFormat.from(content,key);var classReferences=CodeReader.classReferences(content.models().values());
            generationSink.publish(facts,classReferences);
            if(inventoryGeneration>0)generationSink.observe(inventoryGeneration,input);
            long id=store.publishBinary(input,facts,classReferences);
            storageNanos.addAndGet(System.nanoTime()-storageStarted);indexed.incrementAndGet();return id;
        }finally{activeArtifacts.remove(location(tracked));}
    }
    private void ensureGeneration(Path path,String kind,ArtifactIndexFormat.Key key)throws Exception{
        if(generationSink.contains(key))return;
        active(path,"generation-backfill");long started=System.nanoTime();
        var content=new BinaryReader().read(path,kind.equals("local"));
        parseNanos.addAndGet(System.nanoTime()-started);content.warnings().forEach(this::warn);
        started=System.nanoTime();
        generationSink.publish(ArtifactIndexFormat.from(content,key),CodeReader.classReferences(content.models().values()));
        storageNanos.addAndGet(System.nanoTime()-started);indexed.incrementAndGet();
    }
    synchronized void storeCode(long artifact,String gav,String hash,Path path,BinaryReader.Content content,List<BinaryReader.Edge> edges)throws Exception{
        var key=ArtifactIndexFormat.key(hash,"code");
        var facts=ArtifactIndexFormat.from(content,key,edges);
        var classReferences=CodeReader.classReferences(content.models().values());
        generationSink.publish(facts,classReferences);
        store.publishCode(artifact,new ArtifactContext(gav,"jar",location(path)),facts,classReferences);
        indexed.incrementAndGet();
    }
    /** Implements 4.4 and phase 6: a module's source and binary inputs, independent of Maven objects. */
    public record LocalModule(Path directory,String gav,List<Path> sources,List<Path> outputs) {
        public LocalModule { directory=directory.toAbsolutePath().normalize();sources=sources.stream().map(p->p.toAbsolutePath().normalize()).distinct().toList();outputs=outputs.stream().map(p->p.toAbsolutePath().normalize()).distinct().toList(); }
    }
    private final LocalArtifacts locals=new LocalArtifacts(this);
    public void registerLocal(LocalModule module){if(locals.register(module))readers.submit(()->{try{locals.refresh(module.directory());}catch(Exception e){warn("local_artifact_fault: "+module.directory()+": "+e);}});}
    public long refreshLocal(Path directory)throws Exception{return locals.refresh(directory);}
    public void refreshLocalWorkspace(String workspace)throws Exception{locals.refreshWorkspace(workspace);}
    long replaceLocal(LocalModule module,String hash,long size,long mtime,BinaryReader.Content content,
                      Map<String,Map<String,Object>> sourceData)throws Exception{
        var key=ArtifactIndexFormat.key(hash,"local-signatures");
        var facts=ArtifactIndexFormat.from(content,key);
        var classReferences=CodeReader.classReferences(content.models().values());
        generationSink.publish(facts,classReferences);
        long id=store.publishArtifact(new IndexStore.ArtifactInput(
                new ArtifactContext(module.gav(),"local",location(module.directory())),key,size,mtime),
                facts,classReferences,sourceData);
        indexed.incrementAndGet();return id;
    }
    /** Implements 4.4: detached source relationships, never compiler-owned trees. */
    public record SourceEdge(String src,String dst,String kind) { }
    private void recordSource(SourceIndexPublisher.Delta delta)throws Exception{
        locals.recordSource(delta.file(),delta.sourceHash(),delta.symbols(),delta.tier(),delta.edges());
        generationSink.publishSourceState(delta);
    }
    public void recordSource(Path file,String contentHash,List<Map<String,Object>> symbols,int tier,List<SourceEdge> edges)throws Exception{
        locals.recordSource(file.toAbsolutePath().normalize(),contentHash,symbols,tier,edges);
    }
    void storeSource(long artifact,Path file,List<Map<String,Object>> symbols,int tier,List<SourceEdge> edges)throws Exception{
        var detached=edges.stream().map(edge->new IndexStore.SourceRelationship(edge.src(),edge.dst(),edge.kind())).toList();
        store.publishSourceFile(artifact,file,symbols,tier,detached);
    }
    private static void verifyChecksum(Path path)throws Exception {
        Path checksum=path.resolveSibling(path.getFileName()+".sha1");if(!Files.isRegularFile(checksum))return;
        String expected=Files.readString(checksum).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        var digest=java.security.MessageDigest.getInstance("SHA-1");try(var stream=Files.newInputStream(path)){byte[] bytes=new byte[65536];int count;while((count=stream.read(bytes))>=0)digest.update(bytes,0,count);}
        if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(expected))throw new java.io.IOException("Checksum mismatch: "+path);
    }
    public static String directoryHash(Path root)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(var files=Files.walk(root)){for(var p:files.filter(Files::isRegularFile).sorted().toList()){digest.update(root.relativize(p).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(Hashing.sha256(p).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}}return java.util.HexFormat.of().formatHex(digest.digest());}
    public long indexSources(Path sources)throws Exception {
        sources=sources.toAbsolutePath().normalize();Path tracked=sources;
        try(var permit=generationSink.acquireArtifact(sources)){
            Path binary=sources.resolveSibling(sources.getFileName().toString().replaceFirst("-sources\\.jar$",".jar"));
            if(!Files.isRegularFile(binary)){warn("sources_without_binary: "+sources);return -1;}
            var artifact=artifact(binary);if(artifact==null){indexJar(binary,gav(binary),"jar");artifact=artifact(binary);}
            var old=artifact(sources);var stamp=Files.readAttributes(sources,java.nio.file.attribute.BasicFileAttributes.class);
            long size=stamp.size(),mtime=stamp.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            String binaryCacheKey=ArtifactIndexFormat.key(artifact.sha256(),"signatures").cacheKey();
            if(old!=null&&artifact.hasDocs()&&!old.gav().contains("SNAPSHOT")&&old.size()==size&&old.mtime()==mtime
                    &&!generationSink.needsDocumentation(binaryCacheKey)){
                reused.incrementAndGet();return old.id();
            }

            active(sources,"hash");long hashStarted=System.nanoTime();verifyChecksum(sources);String hash=Hashing.sha256(sources);
            hashNanos.addAndGet(System.nanoTime()-hashStarted);hashed.incrementAndGet();
            active(sources,"source-parse");long parseStarted=System.nanoTime();
            var text=new LinkedHashMap<String,String>();
            try(var jar=new JarFile(sources.toFile(),false,JarFile.OPEN_READ,Runtime.version())){
                for(var entry:jar.versionedStream().filter(e->e.getName().endsWith(".java")&&!e.getName().startsWith("META-INF/")).toList())
                    try(var stream=jar.getInputStream(entry)){text.put(entry.getName(),new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            }
            var content=new BinaryReader().read(binary,false);
            var join=new SourceJoin().join(content.models(),text);
            parseNanos.addAndGet(System.nanoTime()-parseStarted);

            var members=new LinkedHashMap<String,Map<String,Object>>();
            for(var member:join.members()){
                String key=member.descriptor()==null?member.owner():member.descriptor().equals("field")
                        ?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();
                var data=new LinkedHashMap<String,Object>();
                data.put("doc",member.doc());data.put("source_file","jar:"+sources.toUri()+"!/"+member.file());
                data.put("line",member.line());data.put("source_start",member.start());data.put("source_end",member.end());
                data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());data.put("parameters",member.parameters());
                members.put(key,Collections.unmodifiableMap(data));
            }

            var key=ArtifactIndexFormat.key(hash,"sources");
            var sourceInput=new IndexStore.ArtifactInput(new ArtifactContext(gav(sources),"sources",location(sources)),key,size,mtime);
            active(sources,"source-storage");long storageStarted=System.nanoTime();
            long id=store.publishDocumentation(artifact.id(),sourceInput,Map.copyOf(members),join.unmatched().size());
            generationSink.publishDocumentation(binaryCacheKey,sourceInput,Map.copyOf(members),join.unmatched().size());
            storageNanos.addAndGet(System.nanoTime()-storageStarted);return id;
        }finally{activeArtifacts.remove(location(tracked));}
    }
    void ensureSignatureEdges(String workspace)throws Exception {
        boolean changed=false;
        for(var item:store.pendingSignatureArtifacts(workspace)){
            Path path=item.path().startsWith("jrt:")?Path.of(java.net.URI.create(item.path())):Path.of(item.path());
            if(!Files.exists(path))continue;
            if(item.gav().startsWith("jdk:"))indexJdk(path,item.gav().split(":")[1],Path.of(System.getProperty("java.home"),"lib/src.zip"));
            else if(item.kind().equals("local"))locals.refresh(path);
            else indexJar(path,item.gav(),item.kind());
            changed=true;
        }
        if(changed)linkEdges();
    }
    public void linkEdges()throws Exception{store.resolveGlobalRelationships();}

    public void configureModuleState(ArtifactGenerationSink.ModuleStateInput input)throws Exception{
        generationSink.configureModuleState(input);
    }
    public List<String> loadWorkspace(String workspace,List<WorkspaceArtifact> paths,List<Map.Entry<String,String>> dependencies)throws Exception{
        long started=System.nanoTime();workspaceResolutionCalls.incrementAndGet();
        try{
            var selected=paths.stream().map(item->new IndexStore.WorkspaceEntry(item.path(),item.scope())).toList();
            var warnings=store.loadWorkspace(workspace,selected,dependencies);
            generationSink.configureWorkspace(workspace,selected,dependencies);
            return warnings;
        }finally{workspaceResolutionNanos.addAndGet(System.nanoTime()-started);}
    }
    public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after)throws Exception{
        return find(query,workspace,substring,limit,after,Set.of());
    }
    public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception{
        long started=System.nanoTime();queryCalls.incrementAndGet();
        try{
            String mode=System.getProperty("jvmd.index.read.backend","shadow");
            if(!Set.of("sqlite","shadow","rocksdb-sst").contains(mode))
                throw new IllegalStateException("Unknown jvmd.index.read.backend: "+mode);

            if(mode.equals("rocksdb-sst")&&workspace!=null){
                var rocks=generationSink.shadowFind(workspace,query,substring,limit,after,kinds);
                if(rocks.isPresent())return rocks.get();
                shadowSkipped.incrementAndGet();
            }

            var authoritative=store.find(query,workspace,substring,limit,after,kinds);
            if(mode.equals("sqlite")||workspace==null)return authoritative;

            long shadowAfter=0L;
            if(after!=0){
                var boundary=store.byId(after,workspace);
                if(boundary==null){shadowSkipped.incrementAndGet();return authoritative;}
                var translated=generationSink.shadowCursor(workspace,Objects.toString(boundary.get("scip"),""));
                if(translated.isEmpty()){shadowSkipped.incrementAndGet();return authoritative;}
                shadowAfter=translated.getAsLong();
            }
            var shadow=generationSink.shadowFind(workspace,query,substring,limit,shadowAfter,kinds);
            if(shadow.isEmpty()){shadowSkipped.incrementAndGet();return authoritative;}
            shadowComparisons.incrementAndGet();
            var expected=authoritative.stream().map(value->Objects.toString(value.get("scip"),"")).filter(value->!value.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var actual=shadow.get().stream().map(value->Objects.toString(value.get("scip"),"")).filter(value->!value.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if(!expected.equals(actual)){
                shadowMismatches.incrementAndGet();
                warn("rocks_shadow_mismatch: workspace="+workspace+" query="+query+" after="+after+
                        " expected="+expected.size()+" actual="+actual.size());
            }
            return authoritative;
        }finally{queryNanos.addAndGet(System.nanoTime()-started);}
    }

    void validateRelationshipShadow(String workspace,Collection<String> scips,boolean outgoing,Set<String> kinds,
                                    List<IndexStore.ResolvedRelationship> authoritative)throws Exception{
        if(workspace==null){shadowSkipped.incrementAndGet();return;}
        var shadow=generationSink.shadowRelationships(workspace,scips,outgoing,kinds,Math.max(256,authoritative.size()+16));
        if(shadow.isEmpty()){shadowSkipped.incrementAndGet();return;}
        shadowComparisons.incrementAndGet();
        var expected=new LinkedHashSet<String>();
        for(var row:authoritative){
            String source=Objects.toString(row.source().get("scip"),""),target=Objects.toString(row.target().get("scip"),"");
            if(!source.isBlank()&&!target.isBlank())expected.add(source+"|"+row.kind()+"|"+target);
        }
        if(!expected.equals(shadow.get())){
            shadowMismatches.incrementAndGet();
            warn("rocks_shadow_relationship_mismatch: workspace="+workspace+" outgoing="+outgoing+
                    " expected="+expected.size()+" actual="+shadow.get().size());
        }
    }

    public List<Map<String,Object>> descendants(String path,String workspace,int depth,int limit,long after,Set<String> kinds)throws Exception{
        long started=System.nanoTime();queryCalls.incrementAndGet();
        try{return store.descendants(path,workspace,depth,limit,after,kinds);}
        finally{queryNanos.addAndGet(System.nanoTime()-started);}
    }
    public Map<String,Object> byId(long id)throws Exception{return byId(id,null);}
    public Map<String,Object> byId(long id,String workspace)throws Exception{
        long started=System.nanoTime();queryCalls.incrementAndGet();
        try{
            if(workspace!=null&&id>=(1L<<32)&&System.getProperty("jvmd.index.read.backend","shadow").equals("rocksdb-sst"))
                return generationSink.shadowById(workspace,id).orElse(null);
            return store.byId(id,workspace);
        }
        finally{queryNanos.addAndGet(System.nanoTime()-started);}
    }
    synchronized long indexJdk(Path file,String module,Path sourceZip)throws Exception{
        var old=artifact(file);if(old!=null&&old.hasDocs()&&old.hasSignatureEdges())return old.id();
        String gav="jdk:"+module+":"+Runtime.version().feature();
        var content=new BinaryReader().read(file,false);
        var sourceData=new HashMap<String,Map<String,Object>>();
        if(Files.isRegularFile(sourceZip)){
            String relative=file.toString().substring(("/modules/"+module+"/").length());
            String entry=module+"/"+relative.substring(0,relative.length()-6).split("\\$",2)[0]+".java";
            try(var zip=new java.util.zip.ZipFile(sourceZip.toFile())){
                var source=zip.getEntry(entry);
                if(source!=null){
                    String text;try(var input=zip.getInputStream(source)){text=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
                    for(var member:new SourceJoin().join(content.models(),Map.of(entry,text)).members()){
                        String memberKey=member.descriptor()==null?member.owner():member.descriptor().equals("field")
                                ?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();
                        var data=new LinkedHashMap<String,Object>();
                        data.put("doc",member.doc());data.put("source_file","jar:"+sourceZip.toUri()+"!/"+entry);
                        data.put("line",member.line());data.put("source_start",member.start());data.put("source_end",member.end());
                        data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());data.put("parameters",member.parameters());
                        sourceData.put(memberKey,Collections.unmodifiableMap(data));
                    }
                }
            }
        }
        String hash=Hashing.sha256(file);
        var key=ArtifactIndexFormat.key(hash,"jdk-signatures");
        var facts=ArtifactIndexFormat.from(content,key);
        var classReferences=CodeReader.classReferences(content.models().values());
        generationSink.publish(facts,classReferences);
        long id=store.publishArtifact(new IndexStore.ArtifactInput(new ArtifactContext(gav,"jar",location(file)),key,Files.size(file),0),
                facts,classReferences,Map.copyOf(sourceData));
        indexed.incrementAndGet();return id;
    }
    static Map<String,Object> symbol(ResultSet r)throws Exception{var s=new LinkedHashMap<String,Object>();for(String field:List.of("id","artifact_id","owner_id","flags","line","source_start","source_end","body_start","body_end"))s.put(field,r.getObject(field));for(String field:List.of("scip","kind","name","name_path","signature","erased_descriptor","source_file","doc","fqn","binary_key","class_entry","gav","artifact_path","artifact_kind"))s.put(field,r.getString(field));s.put("parameters",Json.MAPPER.readTree(r.getString("parameters")));s.put("metadata",Json.MAPPER.readTree(r.getString("metadata")));String variant=r.getString("variant_data");if(variant!=null)s.putAll(Json.MAPPER.readValue(variant,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));s.put("id",r.getLong("id"));s.put("artifact_id",r.getLong("selected_artifact"));s.put("gav",r.getString("gav"));s.put("artifact_path",r.getString("artifact_path"));s.put("artifact_kind",r.getString("artifact_kind"));return s;}
    public static String namePath(BinaryReader.Symbol s){String owner=s.fqn().replace('$','/');if(s.key().equals(s.fqn()))return owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return owner+"/"+s.name()+"("+String.join(",",Arrays.stream(type.parameterArray()).map(p->p.displayName().replace('$','.')).toList())+")";}return owner+"/"+s.name();}
    public static String scip(String gav,BinaryReader.Symbol s){String[] parts=gav.split(":",3);String prefix="maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" ";String owner=s.fqn().replace('.','/').replace('$','#')+"#";if(s.key().equals(s.fqn()))return prefix+owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return prefix+owner+(s.kind().equals("ctor")?"<init>":s.name())+"("+String.join(",",Arrays.stream(type.parameterArray()).map(Signatures::qualified).toList())+").";}return prefix+owner+s.name()+".";}
    @Override public void close()throws Exception {
        closed=true;scanner.shutdownNow();sourcePublisher.close();readers.shutdown();
        if(!readers.awaitTermination(60,TimeUnit.SECONDS)){readers.shutdownNow();
            if(!readers.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Index workers did not stop; native handles remain open");}
        if(!scanner.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Index scanner did not stop; native handles remain open");
        try{generationSink.close();}finally{store.close();}
    }
}
