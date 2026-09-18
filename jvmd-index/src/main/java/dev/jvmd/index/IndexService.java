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
    private final Path repository;
    private final SourceIndexPublisher sourcePublisher=new SourceIndexPublisher(delta->recordSource(delta.file(),delta.sourceHash(),delta.symbols(),delta.tier(),delta.edges()),32L*1024*1024);
    public void publishSource(SourceIndexPublisher.Delta delta){sourcePublisher.enqueue(delta);}
    public Map<String,Object> sourcePublisherStatus(){return sourcePublisher.status();}
    private final ExecutorService readers=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())),Thread.ofVirtual().name("jvmd-index-reader-",0).factory());
    private final ScheduledExecutorService scanner=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jvmd-index-scan").factory());
    private final AtomicLong scanned=new AtomicLong(),indexed=new AtomicLong(),reused=new AtomicLong(),hashed=new AtomicLong(),faults=new AtomicLong();
    private final AtomicLong scans=new AtomicLong(),scanNanos=new AtomicLong(),discoveryNanos=new AtomicLong(),hashNanos=new AtomicLong(),
            parseNanos=new AtomicLong(),prepareNanos=new AtomicLong(),storageNanos=new AtomicLong(),symbolWriteNanos=new AtomicLong(),
            relationshipWriteNanos=new AtomicLong(),classReferenceWriteNanos=new AtomicLong(),docsNanos=new AtomicLong(),linkNanos=new AtomicLong(),
            queryCalls=new AtomicLong(),queryNanos=new AtomicLong(),workspaceResolutionCalls=new AtomicLong(),workspaceResolutionNanos=new AtomicLong();
    private final ConcurrentHashMap<String,String> activeArtifacts=new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> warnings=new ConcurrentLinkedDeque<>();
    private volatile String phase="idle";
    private volatile long total;
    private volatile boolean closed;
    public IndexService(Path database,Path repository) throws Exception {
        this.sqliteStore=new SqliteIndexStore(database);this.store=sqliteStore;this.database=sqliteStore.database();
        this.repository=repository.toAbsolutePath().normalize();
    }
    public IndexDatabase database(){return database;}
    public IndexStore store(){return store;}
    public long generation(){return indexed.get();}
    public void start(){scanner.scheduleWithFixedDelay(()->{try{scan();}catch(Exception e){warn("index_scan_fault: "+e);}},0,60,TimeUnit.SECONDS);}
    public synchronized void scan() throws Exception {
        if(closed||!Files.isDirectory(repository))return;
        long start=System.nanoTime();scans.incrementAndGet();phase="discovering";
        long discoveryStarted=System.nanoTime();
        List<Path> jars;try(var files=Files.walk(repository)){jars=files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")&&!p.getFileName().toString().endsWith("-javadoc.jar")).sorted().toList();}
        discoveryNanos.addAndGet(System.nanoTime()-discoveryStarted);
        total=jars.size();scanned.set(0);phase="skeletons";
        var jobs=new ArrayList<Future<?>>();
        for(var jar:jars)if(!jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{try{indexJar(jar,gav(jar),"jar");}catch(Exception|LinkageError e){warn("artifact_fault: "+jar+": "+e);}finally{scanned.incrementAndGet();}}));
        for(var job:jobs)job.get();jobs.clear();phase="docs";long docsStarted=System.nanoTime();
        for(var jar:jars)if(jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{try{indexSources(jar);}catch(Exception|LinkageError e){warn("source_fault: "+jar+": "+e);}finally{scanned.incrementAndGet();}}));
        for(var job:jobs)job.get();docsNanos.addAndGet(System.nanoTime()-docsStarted);
        phase="linking";long linkStarted=System.nanoTime();linkEdges();linkNanos.addAndGet(System.nanoTime()-linkStarted);phase="ready";
        long elapsed=System.nanoTime()-start;scanNanos.addAndGet(elapsed);
        System.getLogger("dev.jvmd.index").log(System.Logger.Level.INFO,"index scan: {0} artifacts in {1} ms",jars.size(),elapsed/1_000_000);
    }
    private void warn(String warning){faults.incrementAndGet();warnings.add(warning);while(warnings.size()>50)warnings.poll();System.getLogger("dev.jvmd.index").log(System.Logger.Level.WARNING,warning);}
    public Map<String,Object> status() throws Exception {
        var result=new LinkedHashMap<String,Object>();result.putAll(store.counts());result.put("source_publisher",sourcePublisher.status());
        result.put("phase",phase);result.put("total",total);result.put("scanned",scanned.get());result.put("indexed",indexed.get());
        result.put("reused",reused.get());result.put("hashes",hashed.get());result.put("faults",faults.get());result.put("warnings",List.copyOf(warnings));
        result.put("active_artifacts",Map.copyOf(activeArtifacts));result.put("store",store.status());
        var timings=new LinkedHashMap<String,Object>();
        timings.put("scans",scans.get());timings.put("scan_ms",millis(scanNanos.get()));timings.put("discovery_ms",millis(discoveryNanos.get()));
        timings.put("hash_ms",millis(hashNanos.get()));timings.put("parse_ms",millis(parseNanos.get()));timings.put("prepare_ms",millis(prepareNanos.get()));
        timings.put("storage_ms",millis(storageNanos.get()));timings.put("symbol_write_ms",millis(symbolWriteNanos.get()));
        timings.put("relationship_write_ms",millis(relationshipWriteNanos.get()));timings.put("class_reference_write_ms",millis(classReferenceWriteNanos.get()));
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
    public long indexJar(Path path,String gav,String kind) throws Exception {
        path=path.toAbsolutePath().normalize();Path tracked=path;active(path,"stat");
        try{
            var previous=artifact(path);
            var stamp=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);long size=stamp.size(),mtime=stamp.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            if(previous!=null&&previous.hasSignatureEdges()&&!gav.contains("SNAPSHOT")&&!kind.equals("local")&&previous.size()==size&&previous.mtime()==mtime){reused.incrementAndGet();return previous.id();}
            active(path,"hash");long hashStarted=System.nanoTime();verifyChecksum(path);String hash=Files.isDirectory(path)?directoryHash(path):Hashing.sha256(path);hashNanos.addAndGet(System.nanoTime()-hashStarted);hashed.incrementAndGet();
            if(previous!=null&&previous.hasSignatureEdges()&&previous.sha256().equals(hash)){recordPath(path,previous.id(),size,mtime);reused.incrementAndGet();return previous.id();}
            active(path,"parse");long parseStarted=System.nanoTime();var content=new BinaryReader().read(path,kind.equals("local"));parseNanos.addAndGet(System.nanoTime()-parseStarted);content.warnings().forEach(this::warn);
            Path file=path;active(path,"storage");long storageStarted=System.nanoTime();
            long id=database.write(c->{long artifact=putArtifact(c,file,gav,kind,hash,size,mtime);
                if(ids(c,artifact).isEmpty())storeContent(c,artifact,gav,kind,content,Map.of());
                else storeSignatureTargets(c,artifact,content.edges(),ids(c,artifact));
                return artifact;
            });storageNanos.addAndGet(System.nanoTime()-storageStarted);indexed.incrementAndGet();return id;
        }finally{activeArtifacts.remove(location(tracked));}
    }
    private void storeContent(Connection c,long artifact,String gav,String kind,BinaryReader.Content content,Map<String,Map<String,Object>> sourceData)throws Exception{
        var keys=ids(c,artifact);
        long prepareStarted=System.nanoTime();
        var identities=content.symbols().stream().collect(java.util.stream.Collectors.groupingBy(symbol->scip(gav,symbol),java.util.stream.Collectors.counting()));
        prepareNanos.addAndGet(System.nanoTime()-prepareStarted);
        long symbolsStarted=System.nanoTime();
        String insert="INSERT INTO symbols(scip,artifact_id,kind,name,signature,erased_descriptor,flags,binary_key,fqn,name_path,class_entry,parameters,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scip) DO NOTHING RETURNING id";
        try(var s=c.prepareStatement(insert);var lookup=c.prepareStatement("SELECT id,artifact_id FROM symbols WHERE scip=?");var associate=c.prepareStatement("INSERT OR REPLACE INTO artifact_symbols(artifact_id,symbol_id,data,source_file) VALUES(?,?,?,?)")){
            for(var symbol:content.symbols()){
                String identity=scip(gav,symbol);
                if(identities.get(identity)>1&&(symbol.kind().equals("method")||symbol.kind().equals("ctor")))identity=identity.substring(0,identity.length()-2)+";return="+Signatures.qualified(java.lang.constant.MethodTypeDesc.ofDescriptor(symbol.descriptor()).returnType())+").";
                s.setString(1,identity);s.setLong(2,artifact);s.setString(3,symbol.kind());s.setString(4,symbol.name());s.setString(5,symbol.signature());s.setString(6,symbol.descriptor());s.setInt(7,symbol.flags());s.setString(8,symbol.key());s.setString(9,symbol.fqn());s.setString(10,namePath(symbol));s.setString(11,symbol.entry());s.setString(12,Json.MAPPER.writeValueAsString(symbol.parameters()));s.setString(13,Json.MAPPER.writeValueAsString(symbol.metadata()));
                long id,primary;try(var r=s.executeQuery()){if(r.next()){id=r.getLong(1);primary=artifact;}else{lookup.setString(1,identity);try(var found=lookup.executeQuery()){found.next();id=found.getLong(1);primary=found.getLong(2);}}}keys.put(symbol.key(),id);
                Map<String,Object> data=null;
                if(kind.equals("local")||primary!=artifact||!sourceData.isEmpty()){
                    data=new LinkedHashMap<>();data.put("scip",identity);data.put("name",symbol.name());data.put("kind",symbol.kind());data.put("signature",symbol.signature());data.put("erased_descriptor",symbol.descriptor());data.put("flags",symbol.flags());data.put("binary_key",symbol.key());data.put("fqn",symbol.fqn());data.put("name_path",namePath(symbol));data.put("class_entry",symbol.entry());data.put("parameters",symbol.parameters());data.put("metadata",symbol.metadata());data.put("source_file",null);data.put("doc",null);data.put("line",null);data.put("source_start",-1);data.put("source_end",-1);data.put("body_start",-1);data.put("body_end",-1);data.put("tier",2);
                    data.putAll(sourceData.getOrDefault(symbol.key(),Map.of()));
                }
                associate.setLong(1,artifact);associate.setLong(2,id);associate.setString(3,data==null?null:Json.MAPPER.writeValueAsString(data));associate.setString(4,data==null?null:(String)data.get("source_file"));associate.addBatch();
            }associate.executeBatch();
        }
        try(var owners=c.prepareStatement("UPDATE symbols SET owner_id=? WHERE id=? AND artifact_id=?");var names=c.prepareStatement("INSERT INTO simple_names VALUES(?,?,?)")){
            for(var symbol:content.symbols()){
                if(symbol.owner()!=null&&keys.containsKey(symbol.owner())){owners.setLong(1,keys.get(symbol.owner()));owners.setLong(2,keys.get(symbol.key()));owners.setLong(3,artifact);owners.addBatch();}
                if(symbol.key().equals(symbol.fqn())){names.setString(1,symbol.name());names.setString(2,symbol.fqn());names.setLong(3,artifact);names.addBatch();}
            }owners.executeBatch();names.executeBatch();
        }
        symbolWriteNanos.addAndGet(System.nanoTime()-symbolsStarted);
        long relationshipsStarted=System.nanoTime();
        try(var edges=c.prepareStatement("INSERT OR IGNORE INTO edge_targets VALUES(?,?,?)")){for(var edge:content.edges())if(keys.containsKey(edge.src())){edges.setLong(1,keys.get(edge.src()));edges.setString(2,edge.target());edges.setString(3,edge.kind());edges.addBatch();}edges.executeBatch();}
        storeSignatureTargets(c,artifact,content.edges(),keys);
        relationshipWriteNanos.addAndGet(System.nanoTime()-relationshipsStarted);
        if(!content.models().isEmpty()){
            long classRefsStarted=System.nanoTime();storeClassReferences(c,artifact,content.models().values());
            classReferenceWriteNanos.addAndGet(System.nanoTime()-classRefsStarted);
        }
    }
    private static void storeSignatureTargets(Connection c,long artifact,List<BinaryReader.Edge> edges,Map<String,Long> keys)throws Exception {
        try(var insert=c.prepareStatement("INSERT OR IGNORE INTO signature_targets VALUES(?,?,?,?)")) {
            for(var edge:edges)if(keys.containsKey(edge.src())){insert.setLong(1,artifact);insert.setLong(2,keys.get(edge.src()));insert.setString(3,edge.target());insert.setString(4,edge.kind());insert.addBatch();}
            insert.executeBatch();
        }
        try(var update=c.prepareStatement("UPDATE artifacts SET has_signature_edges=1 WHERE id=?")){update.setLong(1,artifact);update.executeUpdate();}
    }
    static void storeClassReferences(Connection c,long artifact,Collection<java.lang.classfile.ClassModel> classes)throws Exception{
        try(var clear=c.prepareStatement("DELETE FROM artifact_class_refs WHERE artifact_id=?")){clear.setLong(1,artifact);clear.executeUpdate();}
        try(var insert=c.prepareStatement("INSERT OR IGNORE INTO artifact_class_refs VALUES(?,?)")){for(String target:CodeReader.classReferences(classes)){insert.setLong(1,artifact);insert.setString(2,target);insert.addBatch();}insert.executeBatch();}
        try(var update=c.prepareStatement("UPDATE artifacts SET has_class_refs=1 WHERE id=?")){update.setLong(1,artifact);update.executeUpdate();}
    }
    synchronized void storeCode(long artifact,String gav,BinaryReader.Content content,List<BinaryReader.Edge> edges)throws Exception{
        database.write(c->{
            var known=ids(c,artifact);
            var missing=content.symbols().stream().filter(symbol->!known.containsKey(symbol.key())).toList();
            if(!missing.isEmpty())storeContent(c,artifact,gav,"jar",new BinaryReader.Content(missing,content.edges(),content.models(),content.warnings()),Map.of());
            var keys=ids(c,artifact);long relationshipsStarted=System.nanoTime();
            try(var remove=c.prepareStatement("DELETE FROM code_targets WHERE artifact_id=?")){remove.setLong(1,artifact);remove.executeUpdate();}
            try(var insert=c.prepareStatement("INSERT OR IGNORE INTO code_targets VALUES(?,?,?,?)")){
                for(var edge:edges)if(keys.containsKey(edge.src())){insert.setLong(1,artifact);insert.setLong(2,keys.get(edge.src()));insert.setString(3,edge.target());insert.setString(4,edge.kind());insert.addBatch();}insert.executeBatch();
            }
            relationshipWriteNanos.addAndGet(System.nanoTime()-relationshipsStarted);
            long classRefsStarted=System.nanoTime();storeClassReferences(c,artifact,content.models().values());classReferenceWriteNanos.addAndGet(System.nanoTime()-classRefsStarted);
            try(var update=c.prepareStatement("UPDATE artifacts SET has_code_edges=1 WHERE id=?")){update.setLong(1,artifact);update.executeUpdate();}
            return null;
        });indexed.incrementAndGet();
    }
    /** Implements 4.4 and phase 6: a module's source and binary inputs, independent of Maven objects. */
    public record LocalModule(Path directory,String gav,List<Path> sources,List<Path> outputs) {
        public LocalModule { directory=directory.toAbsolutePath().normalize();sources=sources.stream().map(p->p.toAbsolutePath().normalize()).distinct().toList();outputs=outputs.stream().map(p->p.toAbsolutePath().normalize()).distinct().toList(); }
    }
    private final LocalArtifacts locals=new LocalArtifacts(this);
    public void registerLocal(LocalModule module){if(locals.register(module))readers.submit(()->{try{locals.refresh(module.directory());}catch(Exception e){warn("local_artifact_fault: "+module.directory()+": "+e);}});}
    public long refreshLocal(Path directory)throws Exception{return locals.refresh(directory);}
    public void refreshLocalWorkspace(String workspace)throws Exception{locals.refreshWorkspace(workspace);}
    long replaceLocal(LocalModule module,String hash,long size,long mtime,BinaryReader.Content content,Map<String,Map<String,Object>> sourceData)throws Exception{
        long id=database.write(c->{
            long artifact=putArtifact(c,module.directory(),module.gav(),"local",hash,size,mtime);
            if(ids(c,artifact).isEmpty())storeContent(c,artifact,module.gav(),"local",content,sourceData);
            else storeSignatureTargets(c,artifact,content.edges(),ids(c,artifact));
            return artifact;
        });indexed.incrementAndGet();return id;
    }
    /** Implements 4.4: detached source relationships, never compiler-owned trees. */
    public record SourceEdge(String src,String dst,String kind) { }
    public void recordSource(Path file,String contentHash,List<Map<String,Object>> symbols,int tier,List<SourceEdge> edges)throws Exception{
        locals.recordSource(file.toAbsolutePath().normalize(),contentHash,symbols,tier,edges);
    }
    void storeSource(long artifact,Path file,List<Map<String,Object>> symbols,int tier,List<SourceEdge> edges)throws Exception{
        database.write(c->{
            try(var remove=c.prepareStatement("DELETE FROM artifact_edges WHERE src_artifact=? AND src IN (SELECT symbol_id FROM artifact_symbols WHERE artifact_id=? AND source_file=?)")) {remove.setLong(1,artifact);remove.setLong(2,artifact);remove.setString(3,file.toString());remove.executeUpdate();}
            try(var remove=c.prepareStatement("DELETE FROM signature_targets WHERE artifact_id=? AND src IN (SELECT symbol_id FROM artifact_symbols WHERE artifact_id=? AND source_file=?)")) {remove.setLong(1,artifact);remove.setLong(2,artifact);remove.setString(3,file.toString());remove.executeUpdate();}
            try(var remove=c.prepareStatement("DELETE FROM artifact_symbols WHERE artifact_id=? AND source_file=?")){remove.setLong(1,artifact);remove.setString(2,file.toString());remove.executeUpdate();}
            var kinds=Set.of("package","class","interface","enum","record","annotation","method","ctor","field","enumconst");
            try(var insert=c.prepareStatement("INSERT INTO symbols(scip,artifact_id,kind,name,signature,erased_descriptor,binary_key,fqn,name_path,parameters,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scip) DO NOTHING");
                var lookup=c.prepareStatement("SELECT id FROM symbols WHERE scip=?");
                var associate=c.prepareStatement("INSERT OR REPLACE INTO artifact_symbols VALUES(?,?,?,?)")){
                for(var symbol:symbols){
                    if(symbol.get("scip")==null||!kinds.contains(symbol.get("kind"))||!file.toString().equals(symbol.get("source_file")))continue;
                    String scip=symbol.get("scip").toString();String fqn=Objects.toString(symbol.get("fqn"),Objects.toString(symbol.get("name_path"),""));
                    insert.setString(1,scip);insert.setLong(2,artifact);insert.setString(3,symbol.get("kind").toString());insert.setString(4,Objects.toString(symbol.get("name"),""));insert.setString(5,(String)symbol.get("signature"));insert.setString(6,(String)symbol.get("erased_descriptor"));insert.setString(7,Objects.toString(symbol.get("binary_key"),Set.of("class","interface","enum","record","annotation").contains(symbol.get("kind"))?fqn:fqn+"#"+("ctor".equals(symbol.get("kind"))?"<init>":symbol.get("name"))+("method".equals(symbol.get("kind"))||"ctor".equals(symbol.get("kind"))?Objects.toString(symbol.get("erased_descriptor"),""):"")));insert.setString(8,fqn);insert.setString(9,Objects.toString(symbol.get("name_path"),scip));insert.setString(10,Json.MAPPER.writeValueAsString(symbol.getOrDefault("parameters",List.of())));insert.setString(11,"{}");insert.executeUpdate();
                    lookup.setString(1,scip);long id;try(var r=lookup.executeQuery()){r.next();id=r.getLong(1);}
                    var data=new LinkedHashMap<>(symbol);data.put("tier",tier);
                    associate.setLong(1,artifact);associate.setLong(2,id);associate.setString(3,Json.MAPPER.writeValueAsString(data));associate.setString(4,file.toString());associate.addBatch();
                }associate.executeBatch();
            }
            // Keep the unique SCIP row while any installed or local artifact still owns it.
            try(var cleanup=c.prepareStatement("DELETE FROM symbols WHERE artifact_id=? AND NOT EXISTS(SELECT 1 FROM artifact_symbols a WHERE a.symbol_id=symbols.id)")){cleanup.setLong(1,artifact);cleanup.executeUpdate();}
            try(var link=c.prepareStatement("INSERT OR IGNORE INTO edges SELECT a.id,b.id,? FROM symbols a,symbols b WHERE a.scip=? AND b.scip=?")){
                for(var edge:edges){link.setString(1,edge.kind());link.setString(2,edge.src());link.setString(3,edge.dst());link.addBatch();}link.executeBatch();
            }
            try(var link=c.prepareStatement("INSERT OR IGNORE INTO artifact_edges SELECT ?,a.id,v.artifact_id,b.id,? FROM symbols a JOIN artifact_symbols own ON own.symbol_id=a.id AND own.artifact_id=? JOIN symbols b ON b.scip=? JOIN artifact_symbols v ON v.symbol_id=b.id WHERE a.scip=?")) {
                for(var edge:edges){link.setLong(1,artifact);link.setString(2,edge.kind());link.setLong(3,artifact);link.setString(4,edge.dst());link.setString(5,edge.src());link.addBatch();}link.executeBatch();
            }
            try(var names=c.prepareStatement("DELETE FROM simple_names WHERE artifact_id=?")){names.setLong(1,artifact);names.executeUpdate();}
            try(var names=c.prepareStatement("INSERT INTO simple_names SELECT s.name,s.fqn,? FROM symbols s JOIN artifact_symbols a ON a.symbol_id=s.id WHERE a.artifact_id=? AND s.kind IN ('class','interface','record','enum','annotation')")){names.setLong(1,artifact);names.setLong(2,artifact);names.executeUpdate();}
            return null;
        });
    }
    private static void preserveSharedSymbols(Connection c,long artifact)throws Exception{
        try(var s=c.prepareStatement("UPDATE symbols SET artifact_id=(SELECT min(a.artifact_id) FROM artifact_symbols a WHERE a.symbol_id=symbols.id AND a.artifact_id<>?) WHERE artifact_id=? AND EXISTS(SELECT 1 FROM artifact_symbols a WHERE a.symbol_id=symbols.id AND a.artifact_id<>?)")){
            s.setLong(1,artifact);s.setLong(2,artifact);s.setLong(3,artifact);s.executeUpdate();
        }
    }
    private long putArtifact(Connection c,Path path,String gav,String kind,String hash,long size,long mtime) throws Exception {
        // Preserve a content identity shared by multiple paths; remove a replaced path's old identity only when unused.
        Long old=null;try(var s=c.prepareStatement("SELECT artifact_id FROM artifact_paths WHERE path=?")){s.setString(1,location(path));try(var r=s.executeQuery()){if(r.next())old=r.getLong(1);}}
        long id;try(var s=c.prepareStatement("INSERT INTO artifacts(gav,kind,sha256,path,size,mtime,indexed_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(sha256) DO NOTHING",Statement.RETURN_GENERATED_KEYS)){s.setString(1,gav);s.setString(2,kind);s.setString(3,hash);s.setString(4,location(path));s.setLong(5,size);s.setLong(6,mtime);s.setLong(7,System.currentTimeMillis());s.executeUpdate();}
        try(var s=c.prepareStatement("SELECT id FROM artifacts WHERE sha256=?")){s.setString(1,hash);try(var r=s.executeQuery()){r.next();id=r.getLong(1);}}
        putPath(c,path,id,size,mtime);
        if(old!=null&&old!=id){
            try(var copy=c.prepareStatement("INSERT OR IGNORE INTO workspace_artifacts SELECT workspace_id,?,scope FROM workspace_artifacts WHERE artifact_id=?")){copy.setLong(1,id);copy.setLong(2,old);copy.executeUpdate();}
            boolean unused;try(var q=c.prepareStatement("SELECT count(*) FROM artifact_paths WHERE artifact_id=?")){q.setLong(1,old);try(var r=q.executeQuery()){r.next();unused=r.getLong(1)==0;}}
            if(unused){preserveSharedSymbols(c,old);try(var q=c.prepareStatement("DELETE FROM artifacts WHERE id=?")){q.setLong(1,old);q.executeUpdate();}}
        }
        return id;
    }
    private void recordPath(Path p,long id,long size,long mtime)throws Exception{database.write(c->{putPath(c,p,id,size,mtime);return null;});}
    private static void putPath(Connection c,Path p,long id,long size,long mtime)throws Exception{try(var s=c.prepareStatement("INSERT OR REPLACE INTO artifact_paths VALUES(?,?,?,?)")){s.setString(1,location(p));s.setLong(2,id);s.setLong(3,size);s.setLong(4,mtime);s.executeUpdate();}}
    private static Map<String,Long> ids(Connection c,long artifact)throws Exception{var ids=new HashMap<String,Long>();try(var s=c.prepareStatement("SELECT s.id,COALESCE(json_extract(a.data,'$.binary_key'),s.binary_key) FROM artifact_symbols a JOIN symbols s ON s.id=a.symbol_id WHERE a.artifact_id=?")){s.setLong(1,artifact);try(var r=s.executeQuery()){while(r.next())ids.put(r.getString(2),r.getLong(1));}}return ids;}
    private static void verifyChecksum(Path path)throws Exception {
        Path checksum=path.resolveSibling(path.getFileName()+".sha1");if(!Files.isRegularFile(checksum))return;
        String expected=Files.readString(checksum).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        var digest=java.security.MessageDigest.getInstance("SHA-1");try(var stream=Files.newInputStream(path)){byte[] bytes=new byte[65536];int count;while((count=stream.read(bytes))>=0)digest.update(bytes,0,count);}
        if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(expected))throw new java.io.IOException("Checksum mismatch: "+path);
    }
    public static String directoryHash(Path root)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(var files=Files.walk(root)){for(var p:files.filter(Files::isRegularFile).sorted().toList()){digest.update(root.relativize(p).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(Hashing.sha256(p).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}}return java.util.HexFormat.of().formatHex(digest.digest());}
    public long indexSources(Path sources)throws Exception {
        sources=sources.toAbsolutePath().normalize();Path binary=sources.resolveSibling(sources.getFileName().toString().replaceFirst("-sources\\.jar$",".jar"));
        if(!Files.isRegularFile(binary)){warn("sources_without_binary: "+sources);return -1;}
        var artifact=artifact(binary);if(artifact==null){indexJar(binary,gav(binary),"jar");artifact=artifact(binary);}
        var old=artifact(sources);var stamp=Files.readAttributes(sources,java.nio.file.attribute.BasicFileAttributes.class);long size=stamp.size(),mtime=stamp.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        if(old!=null&&artifact.hasDocs()&&!old.gav().contains("SNAPSHOT")&&old.size()==size&&old.mtime()==mtime){reused.incrementAndGet();return old.id();}
        active(sources,"hash");long hashStarted=System.nanoTime();verifyChecksum(sources);String hash=Hashing.sha256(sources);hashNanos.addAndGet(System.nanoTime()-hashStarted);hashed.incrementAndGet();
        active(sources,"source-parse");long parseStarted=System.nanoTime();
        var text=new LinkedHashMap<String,String>();try(var jar=new JarFile(sources.toFile(),false,JarFile.OPEN_READ,Runtime.version())){for(var entry:jar.versionedStream().filter(e->e.getName().endsWith(".java")&&!e.getName().startsWith("META-INF/")).toList()){try(var stream=jar.getInputStream(entry)){text.put(entry.getName(),new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}}
        var content=new BinaryReader().read(binary,false);var join=new SourceJoin().join(content.models(),text);parseNanos.addAndGet(System.nanoTime()-parseStarted);long binaryId=artifact.id();Path sourceFile=sources;
        active(sources,"source-storage");long storageStarted=System.nanoTime();
        long sourceResult=database.write(c->{long sourceId=putArtifact(c,sourceFile,gav(sourceFile),"sources",hash,size,mtime);var keys=ids(c,binaryId);
            try(var update=c.prepareStatement("UPDATE artifact_symbols SET data=?,source_file=? WHERE artifact_id=? AND symbol_id=?");
                var lookup=c.prepareStatement("SELECT v.data,s.signature,s.parameters,s.metadata FROM artifact_symbols v JOIN symbols s ON s.id=v.symbol_id WHERE v.artifact_id=? AND v.symbol_id=?")){
                for(var member:join.members()){
                    String key=member.descriptor()==null?member.owner():member.descriptor().equals("field")?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();Long id=keys.get(key);if(id==null)continue;
                    lookup.setLong(1,binaryId);lookup.setLong(2,id);
                    try(var row=lookup.executeQuery()){
                        if(!row.next())continue;
                        var data=row.getString(1)==null?Json.MAPPER.createObjectNode():(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(row.getString(1));
                        String location="jar:"+sourceFile.toUri()+"!/"+member.file();
                        data.put("doc",member.doc());data.put("source_file",location);data.put("line",member.line());data.put("source_start",member.start());data.put("source_end",member.end());data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());
                        var metadata=data.has("metadata")?data.get("metadata"):Json.MAPPER.readTree(row.getString(4));
                        if(!member.parameters().isEmpty()&&!metadata.path("parameter_names_from_class").asBoolean()){
                            var original=data.has("parameters")?data.get("parameters"):Json.MAPPER.readTree(row.getString(3));String signature=data.path("signature").asText(row.getString(2));
                            for(int i=0;i<Math.min(member.parameters().size(),original.size());i++)signature=signature.replaceAll("\\b"+java.util.regex.Pattern.quote(original.get(i).asText())+"\\b",java.util.regex.Matcher.quoteReplacement(member.parameters().get(i)));
                            data.put("signature",signature);data.set("parameters",Json.MAPPER.valueToTree(member.parameters()));
                        }
                        update.setString(1,Json.MAPPER.writeValueAsString(data));update.setString(2,location);update.setLong(3,binaryId);update.setLong(4,id);update.addBatch();
                    }
                }update.executeBatch();
            }
            try(var s=c.prepareStatement("UPDATE artifacts SET has_docs=1 WHERE id=? OR id=?")){s.setLong(1,binaryId);s.setLong(2,sourceId);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT OR REPLACE INTO source_artifacts VALUES(?,?)")){s.setLong(1,binaryId);s.setLong(2,sourceId);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO counters VALUES('unmatched_source_members',?) ON CONFLICT(name) DO UPDATE SET value=value+excluded.value")){s.setInt(1,join.unmatched().size());s.executeUpdate();}
            return sourceId;
        });storageNanos.addAndGet(System.nanoTime()-storageStarted);activeArtifacts.remove(location(sources));return sourceResult;
    }
    void ensureSignatureEdges(String workspace)throws Exception {
        var pending=database.read(c->{var paths=new ArrayList<String[]>();try(var q=c.prepareStatement("SELECT a.path,a.gav,a.kind FROM artifacts a WHERE a.has_signature_edges=0 AND a.kind<>'sources'"+(workspace==null?"":" AND (a.gav LIKE 'jdk:%' OR EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id=a.id))"))){if(workspace!=null)q.setString(1,workspace);try(var r=q.executeQuery()){while(r.next())paths.add(new String[]{r.getString(1),r.getString(2),r.getString(3)});}}return paths;});
        boolean changed=false;
        for(var item:pending){
            Path path=item[0].startsWith("jrt:")?Path.of(java.net.URI.create(item[0])):Path.of(item[0]);
            if(!Files.exists(path))continue;
            if(item[1].startsWith("jdk:"))indexJdk(path,item[1].split(":")[1],Path.of(System.getProperty("java.home"),"lib/src.zip"));
            else if(item[2].equals("local"))locals.refresh(path);
            else indexJar(path,item[1],item[2]);
            changed=true;
        }
        if(changed)linkEdges();
    }
    public void linkEdges()throws Exception{database.write(c->{try(var s=c.createStatement()){
        s.executeUpdate("INSERT OR IGNORE INTO edges SELECT t.src,s.id,t.kind FROM edge_targets t JOIN symbols s ON s.binary_key=t.target");
        s.executeUpdate("INSERT OR IGNORE INTO artifact_edges SELECT t.artifact_id,t.src,v.artifact_id,s.id,t.kind FROM signature_targets t JOIN symbols s ON s.binary_key=t.target JOIN artifact_symbols v ON v.symbol_id=s.id");
        s.executeUpdate("INSERT OR IGNORE INTO artifact_edges SELECT h.src_artifact,child.id,h.dst_artifact,parent.id,'overrides' FROM artifact_edges h JOIN symbols child ON child.owner_id=h.src JOIN artifact_symbols cv ON cv.symbol_id=child.id AND cv.artifact_id=h.src_artifact JOIN symbols parent ON parent.owner_id=h.dst AND parent.name=child.name JOIN artifact_symbols pv ON pv.symbol_id=parent.id AND pv.artifact_id=h.dst_artifact WHERE h.kind IN ('extends','implements') AND child.kind='method' AND parent.kind='method' AND substr(COALESCE(json_extract(cv.data,'$.erased_descriptor'),child.erased_descriptor),1,instr(COALESCE(json_extract(cv.data,'$.erased_descriptor'),child.erased_descriptor),')'))=substr(COALESCE(json_extract(pv.data,'$.erased_descriptor'),parent.erased_descriptor),1,instr(COALESCE(json_extract(pv.data,'$.erased_descriptor'),parent.erased_descriptor),')')) AND (COALESCE(json_extract(cv.data,'$.flags'),child.flags) & 8)=0 AND (COALESCE(json_extract(pv.data,'$.flags'),parent.flags) & 10)=0");
        s.executeUpdate("INSERT OR IGNORE INTO edges SELECT child.id,parent.id,'overrides' FROM edges hierarchy CROSS JOIN symbols child ON child.owner_id=hierarchy.src CROSS JOIN symbols parent ON parent.owner_id=hierarchy.dst AND parent.name=child.name AND substr(parent.erased_descriptor,1,instr(parent.erased_descriptor,')'))=substr(child.erased_descriptor,1,instr(child.erased_descriptor,')')) WHERE hierarchy.kind IN ('extends','implements') AND child.kind='method' AND parent.kind='method' AND (child.flags & 8)=0 AND (parent.flags & 10)=0");
    }return null;});}
    public List<String> loadWorkspace(String workspace,List<WorkspaceArtifact> paths,List<Map.Entry<String,String>> dependencies)throws Exception{
        long started=System.nanoTime();workspaceResolutionCalls.incrementAndGet();
        try{
            var selected=paths.stream().map(item->new IndexStore.WorkspaceEntry(item.path(),item.scope())).toList();
            return store.loadWorkspace(workspace,selected,dependencies);
        }finally{workspaceResolutionNanos.addAndGet(System.nanoTime()-started);}
    }
    public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after)throws Exception{
        return find(query,workspace,substring,limit,after,Set.of());
    }
    public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception{
        long started=System.nanoTime();queryCalls.incrementAndGet();
        try{return store.find(query,workspace,substring,limit,after,kinds);}
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
        try{return store.byId(id,workspace);}
        finally{queryNanos.addAndGet(System.nanoTime()-started);}
    }
    synchronized long indexJdk(Path file,String module,Path sourceZip)throws Exception{
        var old=artifact(file);if(old!=null&&old.hasDocs()&&old.hasSignatureEdges())return old.id();
        String gav="jdk:"+module+":"+Runtime.version().feature();var content=new BinaryReader().read(file,false);var sourceData=new HashMap<String,Map<String,Object>>();
        if(Files.isRegularFile(sourceZip)){
            String relative=file.toString().substring(("/modules/"+module+"/").length());String entry=module+"/"+relative.substring(0,relative.length()-6).split("\\$",2)[0]+".java";
            try(var zip=new java.util.zip.ZipFile(sourceZip.toFile())){var source=zip.getEntry(entry);if(source!=null){
                String text;try(var input=zip.getInputStream(source)){text=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
                for(var member:new SourceJoin().join(content.models(),Map.of(entry,text)).members()){
                    String key=member.descriptor()==null?member.owner():member.descriptor().equals("field")?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();
                    var data=new LinkedHashMap<String,Object>();data.put("doc",member.doc());data.put("source_file","jar:"+sourceZip.toUri()+"!/"+entry);data.put("line",member.line());data.put("source_start",member.start());data.put("source_end",member.end());data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());data.put("parameters",member.parameters());sourceData.put(key,data);
                }
            }}
        }
        String hash=Hashing.sha256(file);long id=database.write(c->{long artifact=putArtifact(c,file,gav,"jar",hash,Files.size(file),0);
            storeSignatureTargets(c,artifact,content.edges(),ids(c,artifact));
            if(ids(c,artifact).isEmpty())storeContent(c,artifact,gav,"jar",content,sourceData);
            else if(!sourceData.isEmpty()){
                // A previous image may have had signatures but no src.zip.
                try(var clear=c.prepareStatement("DELETE FROM artifact_symbols WHERE artifact_id=?")){clear.setLong(1,artifact);clear.executeUpdate();}
                storeContent(c,artifact,gav,"jar",content,sourceData);
            }
            if(!sourceData.isEmpty())try(var update=c.prepareStatement("UPDATE artifacts SET has_docs=1 WHERE id=?")){update.setLong(1,artifact);update.executeUpdate();}
            return artifact;
        });indexed.incrementAndGet();return id;
    }
    static Map<String,Object> symbol(ResultSet r)throws Exception{var s=new LinkedHashMap<String,Object>();for(String field:List.of("id","artifact_id","owner_id","flags","line","source_start","source_end","body_start","body_end"))s.put(field,r.getObject(field));for(String field:List.of("scip","kind","name","name_path","signature","erased_descriptor","source_file","doc","fqn","binary_key","class_entry","gav","artifact_path","artifact_kind"))s.put(field,r.getString(field));s.put("parameters",Json.MAPPER.readTree(r.getString("parameters")));s.put("metadata",Json.MAPPER.readTree(r.getString("metadata")));String variant=r.getString("variant_data");if(variant!=null)s.putAll(Json.MAPPER.readValue(variant,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));s.put("id",r.getLong("id"));s.put("artifact_id",r.getLong("selected_artifact"));s.put("gav",r.getString("gav"));s.put("artifact_path",r.getString("artifact_path"));s.put("artifact_kind",r.getString("artifact_kind"));return s;}
    public static String namePath(BinaryReader.Symbol s){String owner=s.fqn().replace('$','/');if(s.key().equals(s.fqn()))return owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return owner+"/"+s.name()+"("+String.join(",",Arrays.stream(type.parameterArray()).map(p->p.displayName().replace('$','.')).toList())+")";}return owner+"/"+s.name();}
    public static String scip(String gav,BinaryReader.Symbol s){String[] parts=gav.split(":",3);String prefix="maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" ";String owner=s.fqn().replace('.','/').replace('$','#')+"#";if(s.key().equals(s.fqn()))return prefix+owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return prefix+owner+(s.kind().equals("ctor")?"<init>":s.name())+"("+String.join(",",Arrays.stream(type.parameterArray()).map(Signatures::qualified).toList())+").";}return prefix+owner+s.name()+".";}
    @Override public void close()throws Exception {closed=true;sourcePublisher.close();scanner.shutdownNow();readers.shutdown();if(!readers.awaitTermination(60,TimeUnit.SECONDS))readers.shutdownNow();store.close();}
}
