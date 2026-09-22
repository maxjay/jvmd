package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
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
    private final IndexStore store;
    private final IndexStorage storage;
    private final Path repository;
    private final SourceIndexPublisher sourcePublisher=new SourceIndexPublisher(this::recordSource,32L*1024*1024);
    public void publishSource(SourceIndexPublisher.Delta delta){sourcePublisher.enqueue(delta);}
    public long semanticRevision(Path file)throws Exception{return storage.semanticState().semanticRevision(file);}
    public String moduleStateFingerprint(String moduleId)throws Exception{return storage.semanticState().moduleStateFingerprint(moduleId);}
    public Map<String,Object> sourcePublisherStatus(){return sourcePublisher.status();}
    private final ExecutorService readers=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())),Thread.ofVirtual().name("jvmd-index-reader-",0).factory());
    private final ScheduledExecutorService scanner=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jvmd-index-scan").factory());
    private final AtomicLong scanned=new AtomicLong(),indexed=new AtomicLong(),reused=new AtomicLong(),hashed=new AtomicLong(),faults=new AtomicLong();
    private final AtomicLong scans=new AtomicLong(),scanNanos=new AtomicLong(),discoveryNanos=new AtomicLong(),hashNanos=new AtomicLong(),
            parseNanos=new AtomicLong(),storageNanos=new AtomicLong(),docsNanos=new AtomicLong(),linkNanos=new AtomicLong(),
            queryCalls=new AtomicLong(),queryNanos=new AtomicLong(),workspaceResolutionCalls=new AtomicLong(),workspaceResolutionNanos=new AtomicLong();
    private final ConcurrentHashMap<String,String> activeArtifacts=new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> warnings=new ConcurrentLinkedDeque<>();
    private final CompletableFuture<Void> readiness=new CompletableFuture<>();
    private final AtomicBoolean started=new AtomicBoolean();
    private volatile String phase="idle";
    private volatile long total;
    private volatile boolean closed;
    private final AtomicBoolean closing=new AtomicBoolean();
    /** The legacy database path selects the sibling Rocks directory; SQLite files are never opened. */
    public IndexService(Path database,Path repository) throws Exception {
        this(IndexStorage.open(database.resolveSibling(database.getFileName().toString().equals("index.db")?"index-v2":database.getFileName()+".rocks"),128L*1024*1024),repository);
    }
    /** Takes ownership of storage, which closes the store before its shared native resources. */
    public IndexService(IndexStorage storage,Path repository)throws Exception{
        this.repository=Objects.requireNonNull(repository).toAbsolutePath().normalize();
        this.storage=Objects.requireNonNull(storage);this.store=Objects.requireNonNull(storage.store());
    }
    public IndexStore store(){return store;}
    public long generation(){return indexed.get();}
    public CompletableFuture<Void> start(){
        long initialDelaySeconds=Long.getLong("jvmd.index.scan.initial_delay_seconds",2L);
        if(initialDelaySeconds<0)throw new IllegalArgumentException("jvmd.index.scan.initial_delay_seconds must be non-negative");
        if(!started.compareAndSet(false,true))return readiness;
        var initialCause=RequestScope.detached();
        scanner.schedule(()->{
            if(closed){
                readiness.completeExceptionally(new CancellationException("Index closed before initial scan"));
                return;
            }
            try{
                if(initialCause==null)scan();else RequestScope.with(initialCause,()->{scan();return null;});
                readiness.complete(null);
                if(!closed)scanner.scheduleWithFixedDelay(()->{
                    try{scan();}
                    catch(Exception|LinkageError e){warn("index_scan_fault: "+e);}
                },60,60,TimeUnit.SECONDS);
            }catch(Exception|LinkageError e){
                warn("index_scan_fault: "+e);
                readiness.completeExceptionally(e);
            }
        },initialDelaySeconds,TimeUnit.SECONDS);
        return readiness;
    }
    public synchronized void scan() throws Exception {
        if(closed)return;
        try(var trace=RequestScope.stage("index.scan")){
        long start=System.nanoTime();scans.incrementAndGet();
        if(!Files.isDirectory(repository)){
            total=0;scanned.set(0);phase="reconciling";
            long inventoryGeneration=storage.inventory().beginScan();
            storage.inventory().completeScan(inventoryGeneration);
            if(store.reconcilePaths(repository,Set.of()))indexed.incrementAndGet();
            phase="linking";long linkStarted=System.nanoTime();linkEdges();linkNanos.addAndGet(System.nanoTime()-linkStarted);
            phase="ready";scanNanos.addAndGet(System.nanoTime()-start);return;
        }
        phase="discovering";
        long discoveryStarted=System.nanoTime();
        List<Path> jars;try(var files=Files.walk(repository)){jars=files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")&&!p.getFileName().toString().endsWith("-javadoc.jar")).sorted().toList();}
        discoveryNanos.addAndGet(System.nanoTime()-discoveryStarted);
        trace.count("jar_inventory_entries",jars.size());
        total=jars.size();scanned.set(0);phase="skeletons";
        long inventoryGeneration=storage.inventory().beginScan();
        var skeletonComplete=new AtomicBoolean(true);
        var jobs=new ArrayList<Future<?>>();
        var cause=RequestScope.detached();
        for(var jar:jars)if(!jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{
            try{
                if(cause==null)indexJar(jar,gav(jar),"jar",inventoryGeneration);
                else RequestScope.with(cause,()->{try(var span=RequestScope.stage("index.binary")){span.count("jar_requests",1);indexJar(jar,gav(jar),"jar",inventoryGeneration);}return null;});
            }
            catch(Exception|LinkageError e){skeletonComplete.set(false);warn("artifact_fault: "+jar+": "+e);}
            finally{scanned.incrementAndGet();}
        }));
        for(var job:jobs)job.get();
        jobs.clear();phase="docs";long docsStarted=System.nanoTime();var docsComplete=new AtomicBoolean(true);
        for(var jar:jars)if(jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{
            try{
                if(cause==null)indexSources(jar);
                else RequestScope.with(cause,()->{try(var span=RequestScope.stage("index.sources")){span.count("source_jar_requests",1);indexSources(jar);}return null;});
            }
            catch(Exception|LinkageError e){docsComplete.set(false);warn("source_fault: "+jar+": "+e);}
            finally{scanned.incrementAndGet();}
        }));
        for(var job:jobs)job.get();docsNanos.addAndGet(System.nanoTime()-docsStarted);
        if(skeletonComplete.get()&&docsComplete.get()){
            storage.inventory().completeScan(inventoryGeneration);
            if(store.reconcilePaths(repository,Set.copyOf(jars)))indexed.incrementAndGet();
        }
        phase="linking";long linkStarted=System.nanoTime();linkEdges();linkNanos.addAndGet(System.nanoTime()-linkStarted);phase="ready";
        long elapsed=System.nanoTime()-start;scanNanos.addAndGet(elapsed);
        System.getLogger("dev.jvmd.index").log(System.Logger.Level.INFO,"index scan: {0} artifacts in {1} ms",jars.size(),elapsed/1_000_000);
        }
    }
    private void warn(String warning){faults.incrementAndGet();warnings.add(warning);while(warnings.size()>50)warnings.poll();System.getLogger("dev.jvmd.index").log(System.Logger.Level.WARNING,warning);}
    public Map<String,Object> status() throws Exception {
        var result=new LinkedHashMap<String,Object>();result.putAll(store.counts());result.put("source_publisher",sourcePublisher.status());
        result.put("phase",phase);result.put("total",total);result.put("scanned",scanned.get());result.put("indexed",indexed.get());
        result.put("reused",reused.get());result.put("hashes",hashed.get());result.put("faults",faults.get());result.put("warnings",List.copyOf(warnings));
        result.put("active_artifacts",Map.copyOf(activeArtifacts));result.put("store",store.status());result.put("storage",storage.status());
        result.put("read_backend",store.backend());
        var timings=new LinkedHashMap<String,Object>();
        timings.put("scans",scans.get());timings.put("scan_ms",millis(scanNanos.get()));timings.put("discovery_ms",millis(discoveryNanos.get()));
        timings.put("hash_ms",millis(hashNanos.get()));timings.put("parse_ms",millis(parseNanos.get()));timings.put("storage_ms",millis(storageNanos.get()));
        timings.put("docs_ms",millis(docsNanos.get()));timings.put("link_ms",millis(linkNanos.get()));
        timings.put("query_calls",queryCalls.get());timings.put("query_ms",millis(queryNanos.get()));
        timings.put("workspace_resolution_calls",workspaceResolutionCalls.get());timings.put("workspace_resolution_ms",millis(workspaceResolutionNanos.get()));
        result.put("timings",Map.copyOf(timings));
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
        try(var permit=storage.admission().acquireArtifact(path)){
            var previous=artifact(path);
            var stamp=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);long size=stamp.size(),mtime=stamp.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            if(previous!=null&&previous.hasSignatureEdges()&&!gav.contains("SNAPSHOT")&&!kind.equals("local")&&previous.size()==size&&previous.mtime()==mtime){
                if(inventoryGeneration>0){
                    var key=ArtifactIndexFormat.key(previous.sha256(),"signatures");
                    storage.inventory().observe(inventoryGeneration,new IndexStore.ArtifactInput(new ArtifactContext(gav,kind,location(path)),key,size,mtime));
                }
                RequestScope.count("metadata_reuses",1);reused.incrementAndGet();return previous.id();
            }
            active(path,"hash");long hashStarted=System.nanoTime();verifyChecksum(path);String hash=Files.isDirectory(path)?directoryHash(path):Hashing.sha256(path);hashNanos.addAndGet(System.nanoTime()-hashStarted);hashed.incrementAndGet();RequestScope.count("artifact_hash_operations",1);
            if(previous!=null&&previous.hasSignatureEdges()&&previous.sha256().equals(hash)){
                store.publishPath(path,previous.id(),size,mtime);
                if(inventoryGeneration>0){
                    var key=ArtifactIndexFormat.key(hash,kind.equals("local")?"local-signatures":"signatures");
                    storage.inventory().observe(inventoryGeneration,new IndexStore.ArtifactInput(new ArtifactContext(gav,kind,location(path)),key,size,mtime));
                }
                reused.incrementAndGet();return previous.id();
            }
            active(path,"parse");long parseStarted=System.nanoTime();var content=new BinaryReader().read(path,kind.equals("local"));parseNanos.addAndGet(System.nanoTime()-parseStarted);content.warnings().forEach(this::warn);
            RequestScope.count("class_models_parsed",content.models().size());
            active(path,"storage");long storageStarted=System.nanoTime();
            var key=ArtifactIndexFormat.key(hash,kind.equals("local")?"local-signatures":"signatures");
            var input=new IndexStore.ArtifactInput(new ArtifactContext(gav,kind,location(path)),key,size,mtime);
            var facts=ArtifactIndexFormat.from(content,key);var classReferences=CodeReader.classReferences(content.models().values());
            long id=store.publishBinary(input,facts,classReferences);
            if(inventoryGeneration>0)storage.inventory().observe(inventoryGeneration,input);
            storageNanos.addAndGet(System.nanoTime()-storageStarted);indexed.incrementAndGet();return id;
        }finally{activeArtifacts.remove(location(tracked));}
    }
    synchronized void storeCode(long artifact,String gav,String hash,Path path,BinaryReader.Content content,List<BinaryReader.Edge> edges)throws Exception{
        var key=ArtifactIndexFormat.key(hash,"code");
        var facts=ArtifactIndexFormat.from(content,key,edges);
        var classReferences=CodeReader.classReferences(content.models().values());
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
        long id=store.publishArtifact(new IndexStore.ArtifactInput(
                new ArtifactContext(module.gav(),"local",location(module.directory())),key,size,mtime),
                facts,classReferences,sourceData);
        indexed.incrementAndGet();return id;
    }
    /** Implements 4.4: detached source relationships, never compiler-owned trees. */
    public record SourceEdge(String src,String dst,String kind) { }
    private void recordSource(SourceIndexPublisher.Delta delta)throws Exception{
        locals.recordSource(delta.file(),delta.sourceHash(),delta.symbols(),delta.tier(),delta.edges());
        storage.semanticState().publishSourceState(delta.contribution(),delta.moduleId(),delta.contextFingerprint());
    }
    public void recordSource(Path file,String contentHash,List<Map<String,Object>> symbols,int tier,List<SourceEdge> edges)throws Exception{
        locals.recordSource(file.toAbsolutePath().normalize(),contentHash,symbols,tier,edges);
    }
    void storeSource(long artifact,Path file,String contentHash,List<Map<String,Object>> symbols,int tier,List<SourceEdge> edges)throws Exception{
        var detached=edges.stream().map(edge->new IndexStore.SourceRelationship(edge.src(),edge.dst(),edge.kind())).toList();
        store.publishSourceFile(artifact,file,contentHash,symbols,tier,detached);
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
        try(var permit=storage.admission().acquireArtifact(sources)){
            Path binary=sources.resolveSibling(sources.getFileName().toString().replaceFirst("-sources\\.jar$",".jar"));
            if(!Files.isRegularFile(binary)){warn("sources_without_binary: "+sources);return -1;}
            var artifact=artifact(binary);if(artifact==null){indexJar(binary,gav(binary),"jar");artifact=artifact(binary);}
            var old=artifact(sources);var stamp=Files.readAttributes(sources,java.nio.file.attribute.BasicFileAttributes.class);
            long size=stamp.size(),mtime=stamp.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            if(old!=null&&artifact.hasDocs()&&!old.gav().contains("SNAPSHOT")&&old.size()==size&&old.mtime()==mtime){
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
                if(member.nameStart()>=0)data.put("name_range",Map.of("start",Documents.position(text.get(member.file()),member.nameStart()),"end",Documents.position(text.get(member.file()),member.nameEnd())));
                data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());data.put("parameters",member.parameters());
                members.put(key,Collections.unmodifiableMap(data));
            }

            var key=ArtifactIndexFormat.key(hash,"sources");
            var sourceInput=new IndexStore.ArtifactInput(new ArtifactContext(gav(sources),"sources",location(sources)),key,size,mtime);
            active(sources,"source-storage");long storageStarted=System.nanoTime();
            long id=store.publishDocumentation(artifact.id(),sourceInput,Map.copyOf(members),join.unmatched().size());
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

    public void configureModuleState(IndexSemanticState.ModuleStateInput input)throws Exception{
        storage.semanticState().configureModuleState(input);
    }
    public List<String> loadWorkspace(String workspace,List<WorkspaceArtifact> paths,List<Map.Entry<String,String>> dependencies)throws Exception{
        long started=System.nanoTime();workspaceResolutionCalls.incrementAndGet();
        try{
            var selected=paths.stream().map(item->new IndexStore.WorkspaceEntry(item.path(),item.scope())).toList();
            var warnings=store.loadWorkspace(workspace,selected,dependencies);
            return warnings;
        }finally{workspaceResolutionNanos.addAndGet(System.nanoTime()-started);}
    }
    public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after)throws Exception{
        return find(query,workspace,substring,limit,after,Set.of());
    }
    public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception{
        long started=System.nanoTime();queryCalls.incrementAndGet();
        try{
            return store.find(query,workspace,substring,limit,after,kinds);
        }finally{queryNanos.addAndGet(System.nanoTime()-started);}
    }

    public List<Map<String,Object>> findNamePrefix(String prefix,String workspace,int limit,Set<String> kinds)throws Exception{
        long started=System.nanoTime();queryCalls.incrementAndGet();
        try{return store.findNamePrefix(prefix,workspace,limit,kinds);}
        finally{queryNanos.addAndGet(System.nanoTime()-started);}
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
                        if(member.nameStart()>=0)data.put("name_range",Map.of("start",Documents.position(text,member.nameStart()),"end",Documents.position(text,member.nameEnd())));
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
        long id=store.publishArtifact(new IndexStore.ArtifactInput(new ArtifactContext(gav,"jar",location(file)),key,Files.size(file),0),
                facts,classReferences,Map.copyOf(sourceData));
        indexed.incrementAndGet();return id;
    }
    public static String namePath(BinaryReader.Symbol s){String owner=s.fqn().replace('$','/');if(s.key().equals(s.fqn()))return owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return owner+"/"+s.name()+"("+String.join(",",Arrays.stream(type.parameterArray()).map(p->p.displayName().replace('$','.')).toList())+")";}return owner+"/"+s.name();}
    public static String scip(String gav,BinaryReader.Symbol s){String[] parts=gav.split(":",3);String prefix="maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" ";String owner=s.fqn().replace('.','/').replace('$','#')+"#";if(s.key().equals(s.fqn()))return prefix+owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return prefix+owner+(s.kind().equals("ctor")?"<init>":s.name())+"("+String.join(",",Arrays.stream(type.parameterArray()).map(Signatures::qualified).toList())+").";}return prefix+owner+s.name()+".";}
    @Override public void close()throws Exception {
        if(!closing.compareAndSet(false,true))return;
        closed=true;
        readiness.completeExceptionally(new CancellationException("Index closed"));
        scanner.shutdownNow();sourcePublisher.close();readers.shutdown();
        if(!readers.awaitTermination(60,TimeUnit.SECONDS)){readers.shutdownNow();
            if(!readers.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Index workers did not stop; native handles remain open");}
        if(!scanner.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Index scanner did not stop; native handles remain open");
        storage.close();
    }
}
