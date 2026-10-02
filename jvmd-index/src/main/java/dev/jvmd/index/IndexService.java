package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The index a daemon serves from: queries over a booted generation's storage, plus the
 * publication of reactor-module and source state that sessions add while they run.
 */
public final class IndexService implements AutoCloseable {
    /** Persisted artifact identity and the stamp of its location. */
    public record Artifact(long id,String gav,String kind,String sha256,String path,long size,long mtime,boolean hasDocs,boolean hasCodeEdges,boolean hasSignatureEdges) { }
    /** One workspace's filtered artifact membership. */
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
    private final AtomicLong indexed=new AtomicLong(),faults=new AtomicLong();
    private final AtomicLong queryCalls=new AtomicLong(),queryNanos=new AtomicLong(),workspaceResolutionCalls=new AtomicLong(),workspaceResolutionNanos=new AtomicLong();
    private final ConcurrentLinkedDeque<String> warnings=new ConcurrentLinkedDeque<>();
    private final Object shutdownLock=new Object();
    private boolean sourcePublisherClosed;
    private boolean storageClosed;
    private boolean cleanupComplete;
    /** Takes ownership of storage, which closes the store before its shared native resources. */
    public IndexService(IndexStorage storage,Path repository){
        this.repository=Objects.requireNonNull(repository).toAbsolutePath().normalize();
        this.storage=Objects.requireNonNull(storage);this.store=Objects.requireNonNull(storage.store());
    }
    public IndexStore store(){return store;}
    public IndexStorage storage(){return storage;}
    /** Changes whenever this process publishes local index state. */
    public long generation(){return indexed.get();}
    private void warn(String warning){faults.incrementAndGet();warnings.add(warning);while(warnings.size()>50)warnings.poll();System.getLogger("dev.jvmd.index").log(System.Logger.Level.WARNING,warning);}
    public Map<String,Object> status() throws Exception {
        var result=new LinkedHashMap<String,Object>();result.putAll(store.counts());result.put("source_publisher",sourcePublisher.status());
        result.put("indexed",indexed.get());result.put("faults",faults.get());result.put("warnings",List.copyOf(warnings));
        result.put("store",store.status());result.put("storage",storage.status());result.put("read_backend",store.backend());
        var timings=new LinkedHashMap<String,Object>();
        timings.put("query_calls",queryCalls.get());timings.put("query_ms",millis(queryNanos.get()));
        timings.put("workspace_resolution_calls",workspaceResolutionCalls.get());timings.put("workspace_resolution_ms",millis(workspaceResolutionNanos.get()));
        result.put("timings",Map.copyOf(timings));
        return result;
    }
    private static double millis(long nanos){return Math.round(nanos/1000.0)/1000.0;}
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
    synchronized void storeCode(long artifact,String gav,String hash,Path path,BinaryReader.Content content,List<BinaryReader.Edge> edges)throws Exception{
        var key=ArtifactIndexFormat.key(hash,"code");
        var facts=ArtifactIndexFormat.from(content,key,edges);
        var classReferences=CodeReader.classReferences(content.models().values());
        store.publishCode(artifact,new ArtifactContext(gav,"jar",location(path)),facts,classReferences);
        indexed.incrementAndGet();
    }
    /**
     * A reactor module's source and binary inputs. TEMPORARY(phase-3 LOCAL cold boot): LOCAL replaces
     * local artifacts as the source of module declarations.
     */
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
    public static String directoryHash(Path root)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(var files=Files.walk(root)){for(var p:files.filter(Files::isRegularFile).sorted().toList()){digest.update(root.relativize(p).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(Hashing.sha256(p).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}}return java.util.HexFormat.of().formatHex(digest.digest());}
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
    public static String namePath(BinaryReader.Symbol s){String owner=s.fqn().replace('$','/');if(s.key().equals(s.fqn()))return owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return owner+"/"+s.name()+"("+String.join(",",Arrays.stream(type.parameterArray()).map(p->p.displayName().replace('$','.')).toList())+")";}return owner+"/"+s.name();}
    public static String scip(String gav,BinaryReader.Symbol s){String[] parts=gav.split(":",3);String prefix="maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" ";String owner=s.fqn().replace('.','/').replace('$','#')+"#";if(s.key().equals(s.fqn()))return prefix+owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return prefix+owner+(s.kind().equals("ctor")?"<init>":s.name())+"("+String.join(",",Arrays.stream(type.parameterArray()).map(Signatures::qualified).toList())+").";}return prefix+owner+s.name()+".";}
    @Override public void close()throws Exception {
        synchronized(shutdownLock){
            if(cleanupComplete)return;
            if(!sourcePublisherClosed){sourcePublisher.close();sourcePublisherClosed=true;}
            readers.shutdown();
            if(!readers.awaitTermination(60,TimeUnit.SECONDS)){readers.shutdownNow();
                if(!readers.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Index workers did not stop; native handles remain open");}
            if(!storageClosed){storage.close();storageClosed=true;}
            cleanupComplete=true;
        }
    }
}
