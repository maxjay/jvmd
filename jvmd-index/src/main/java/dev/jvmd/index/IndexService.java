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
    public record Artifact(long id,String gav,String kind,String sha256,String path,long size,long mtime,boolean hasDocs,boolean hasCodeEdges) { }
    /** Implements 4.4: one workspace's filtered artifact membership. */
    public record WorkspaceArtifact(String path,String scope) { }
    private final IndexDatabase database;
    private final Path repository;
    private final ExecutorService readers=Executors.newFixedThreadPool(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())),Thread.ofVirtual().name("jvmd-index-reader-",0).factory());
    private final ScheduledExecutorService scanner=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("jvmd-index-scan").factory());
    private final AtomicLong scanned=new AtomicLong(),indexed=new AtomicLong(),reused=new AtomicLong(),hashed=new AtomicLong(),faults=new AtomicLong();
    private final ConcurrentLinkedDeque<String> warnings=new ConcurrentLinkedDeque<>();
    private volatile String phase="idle";
    private volatile long total;
    private volatile boolean closed;
    public IndexService(Path database,Path repository) throws Exception {this.database=new IndexDatabase(database);this.repository=repository.toAbsolutePath().normalize();}
    public IndexDatabase database(){return database;}
    public long generation(){return indexed.get();}
    public void start(){scanner.scheduleWithFixedDelay(()->{try{scan();}catch(Exception e){warn("index_scan_fault: "+e);}},0,60,TimeUnit.SECONDS);}
    public synchronized void scan() throws Exception {
        if(closed||!Files.isDirectory(repository))return;
        long start=System.nanoTime(); phase="discovering";
        List<Path> jars;try(var files=Files.walk(repository)){jars=files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".jar")&&!p.getFileName().toString().endsWith("-javadoc.jar")).sorted().toList();}
        total=jars.size();scanned.set(0);phase="skeletons";
        var jobs=new ArrayList<Future<?>>();
        for(var jar:jars)if(!jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{try{indexJar(jar,gav(jar),"jar");}catch(Exception|LinkageError e){warn("artifact_fault: "+jar+": "+e);}finally{scanned.incrementAndGet();}}));
        for(var job:jobs)job.get();jobs.clear();phase="docs";
        for(var jar:jars)if(jar.getFileName().toString().endsWith("-sources.jar"))jobs.add(readers.submit(()->{try{indexSources(jar);}catch(Exception|LinkageError e){warn("source_fault: "+jar+": "+e);}finally{scanned.incrementAndGet();}}));
        for(var job:jobs)job.get();
        phase="linking";linkEdges();phase="ready";
        System.getLogger("dev.jvmd.index").log(System.Logger.Level.INFO,"index scan: {0} artifacts in {1} ms",jars.size(),(System.nanoTime()-start)/1_000_000);
    }
    private void warn(String warning){faults.incrementAndGet();warnings.add(warning);while(warnings.size()>50)warnings.poll();System.getLogger("dev.jvmd.index").log(System.Logger.Level.WARNING,warning);}
    public Map<String,Object> status() throws Exception {var result=new LinkedHashMap<String,Object>();result.putAll(database.counts());result.put("phase",phase);result.put("total",total);result.put("scanned",scanned.get());result.put("indexed",indexed.get());result.put("reused",reused.get());result.put("hashes",hashed.get());result.put("faults",faults.get());result.put("warnings",List.copyOf(warnings));return result;}
    public String gav(Path path){
        Path relative=repository.relativize(path.toAbsolutePath().normalize());int n=relative.getNameCount();
        if(n<4)return "local:"+path.getFileName()+":0";
        return relative.subpath(0,n-3).toString().replace(java.io.File.separatorChar,'.')+":"+relative.getName(n-3)+":"+relative.getName(n-2);
    }
    public Artifact artifact(Path path) throws Exception {return database.read(c->{try(var s=c.prepareStatement("SELECT a.*,p.size AS actual_size,p.mtime AS actual_mtime FROM artifact_paths p JOIN artifacts a ON a.id=p.artifact_id WHERE p.path=?")){s.setString(1,path.toAbsolutePath().normalize().toString());try(var r=s.executeQuery()){return r.next()?new Artifact(r.getLong("id"),r.getString("gav"),r.getString("kind"),r.getString("sha256"),path.toAbsolutePath().normalize().toString(),r.getLong("actual_size"),r.getLong("actual_mtime"),r.getInt("has_docs")!=0,r.getInt("has_code_edges")!=0):null;}}});}
    public long indexJar(Path path,String gav,String kind) throws Exception {
        path=path.toAbsolutePath().normalize();var previous=artifact(path);
        var stamp=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);long size=stamp.size(),mtime=stamp.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        if(previous!=null&&!gav.contains("SNAPSHOT")&&!kind.equals("local")&&previous.size()==size&&previous.mtime()==mtime){reused.incrementAndGet();return previous.id();}
        verifyChecksum(path);String hash=Files.isDirectory(path)?directoryHash(path):Hashing.sha256(path);hashed.incrementAndGet();
        if(previous!=null&&previous.sha256().equals(hash)){recordPath(path,previous.id(),size,mtime);reused.incrementAndGet();return previous.id();}
        var content=new BinaryReader().read(path,kind.equals("local"));content.warnings().forEach(this::warn);
        Path file=path;long id=database.write(c->{long artifact=putArtifact(c,file,gav,kind,hash,size,mtime);
            var existing=ids(c,artifact);if(!existing.isEmpty())return artifact;
            var keys=new HashMap<String,Long>();
            var identities=content.symbols().stream().collect(java.util.stream.Collectors.groupingBy(symbol->scip(gav,symbol),java.util.stream.Collectors.counting()));
            String insert="INSERT INTO symbols(scip,artifact_id,kind,name,signature,erased_descriptor,flags,binary_key,fqn,name_path,class_entry,parameters,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try(var s=c.prepareStatement(insert,Statement.RETURN_GENERATED_KEYS)){
                for(var symbol:content.symbols()){
                    String identity=scip(gav,symbol);
                    if(identities.get(identity)>1 && (symbol.kind().equals("method")||symbol.kind().equals("ctor"))) identity=identity.substring(0,identity.length()-2)+";return="+Signatures.qualified(java.lang.constant.MethodTypeDesc.ofDescriptor(symbol.descriptor()).returnType())+").";
                    s.setString(1,identity);s.setLong(2,artifact);s.setString(3,symbol.kind());s.setString(4,symbol.name());s.setString(5,symbol.signature());s.setString(6,symbol.descriptor());s.setInt(7,symbol.flags());s.setString(8,symbol.key());s.setString(9,symbol.fqn());s.setString(10,namePath(symbol));s.setString(11,symbol.entry());s.setString(12,Json.MAPPER.writeValueAsString(symbol.parameters()));s.setString(13,Json.MAPPER.writeValueAsString(symbol.metadata()));s.executeUpdate();
                    try(var generated=s.getGeneratedKeys()){generated.next();keys.put(symbol.key(),generated.getLong(1));}
                }
            }
            try(var s=c.prepareStatement("UPDATE symbols SET owner_id=? WHERE id=?");var names=c.prepareStatement("INSERT INTO simple_names VALUES(?,?,?)")){
                for(var symbol:content.symbols()){
                    if(symbol.owner()!=null&&keys.containsKey(symbol.owner())){s.setLong(1,keys.get(symbol.owner()));s.setLong(2,keys.get(symbol.key()));s.addBatch();}
                    if(symbol.key().equals(symbol.fqn())){names.setString(1,symbol.name());names.setString(2,symbol.fqn());names.setLong(3,artifact);names.addBatch();}
                }s.executeBatch();names.executeBatch();
            }
            try(var s=c.prepareStatement("INSERT OR IGNORE INTO edge_targets VALUES(?,?,?)")){for(var edge:content.edges())if(keys.containsKey(edge.src())){s.setLong(1,keys.get(edge.src()));s.setString(2,edge.target());s.setString(3,edge.kind());s.addBatch();}s.executeBatch();}
            return artifact;
        });indexed.incrementAndGet();return id;
    }
    private long putArtifact(Connection c,Path path,String gav,String kind,String hash,long size,long mtime) throws Exception {
        // Preserve a content identity shared by multiple paths; remove a replaced path's old identity only when unused.
        Long old=null;try(var s=c.prepareStatement("SELECT artifact_id FROM artifact_paths WHERE path=?")){s.setString(1,path.toString());try(var r=s.executeQuery()){if(r.next())old=r.getLong(1);}}
        long id;try(var s=c.prepareStatement("INSERT INTO artifacts(gav,kind,sha256,path,size,mtime,indexed_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(sha256) DO NOTHING",Statement.RETURN_GENERATED_KEYS)){s.setString(1,gav);s.setString(2,kind);s.setString(3,hash);s.setString(4,path.toString());s.setLong(5,size);s.setLong(6,mtime);s.setLong(7,System.currentTimeMillis());s.executeUpdate();}
        try(var s=c.prepareStatement("SELECT id FROM artifacts WHERE sha256=?")){s.setString(1,hash);try(var r=s.executeQuery()){r.next();id=r.getLong(1);}}
        putPath(c,path,id,size,mtime);
        if(old!=null&&old!=id)try(var s=c.prepareStatement("DELETE FROM artifacts WHERE id=? AND NOT EXISTS(SELECT 1 FROM artifact_paths WHERE artifact_id=?)")){s.setLong(1,old);s.setLong(2,old);s.executeUpdate();}
        return id;
    }
    private void recordPath(Path p,long id,long size,long mtime)throws Exception{database.write(c->{putPath(c,p,id,size,mtime);return null;});}
    private static void putPath(Connection c,Path p,long id,long size,long mtime)throws Exception{try(var s=c.prepareStatement("INSERT OR REPLACE INTO artifact_paths VALUES(?,?,?,?)")){s.setString(1,p.toString());s.setLong(2,id);s.setLong(3,size);s.setLong(4,mtime);s.executeUpdate();}}
    private static Map<String,Long> ids(Connection c,long artifact)throws Exception{var ids=new HashMap<String,Long>();try(var s=c.prepareStatement("SELECT id,binary_key FROM symbols WHERE artifact_id=?")){s.setLong(1,artifact);try(var r=s.executeQuery()){while(r.next())ids.put(r.getString(2),r.getLong(1));}}return ids;}
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
        verifyChecksum(sources);String hash=Hashing.sha256(sources);hashed.incrementAndGet();
        var text=new LinkedHashMap<String,String>();try(var jar=new JarFile(sources.toFile(),false,JarFile.OPEN_READ,Runtime.version())){for(var entry:jar.versionedStream().filter(e->e.getName().endsWith(".java")&&!e.getName().startsWith("META-INF/")).toList()){try(var stream=jar.getInputStream(entry)){text.put(entry.getName(),new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}}
        var content=new BinaryReader().read(binary,false);var join=new SourceJoin().join(content.models(),text);long binaryId=artifact.id();Path sourceFile=sources;
        return database.write(c->{long sourceId=putArtifact(c,sourceFile,gav(sourceFile),"sources",hash,size,mtime);var keys=ids(c,binaryId);
            try(var s=c.prepareStatement("UPDATE symbols SET doc=?,source_file=?,line=?,source_start=?,source_end=?,body_start=?,body_end=? WHERE id=?")){
                for(var member:join.members()){
                    String key=member.descriptor()==null?member.owner():member.descriptor().equals("field")?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();Long id=keys.get(key);if(id==null)continue;
                    s.setString(1,member.doc());s.setString(2,"jar:"+sourceFile.toUri()+"!/"+member.file());s.setInt(3,member.line());s.setInt(4,member.start());s.setInt(5,member.end());s.setInt(6,member.bodyStart());s.setInt(7,member.bodyEnd());s.setLong(8,id);s.addBatch();
                    if(!member.parameters().isEmpty()) updateParameters(c,id,member.parameters());
                }s.executeBatch();
            }
            try(var s=c.prepareStatement("UPDATE artifacts SET has_docs=1 WHERE id=? OR id=?")){s.setLong(1,binaryId);s.setLong(2,sourceId);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT OR REPLACE INTO source_artifacts VALUES(?,?)")){s.setLong(1,binaryId);s.setLong(2,sourceId);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO counters VALUES('unmatched_source_members',?) ON CONFLICT(name) DO UPDATE SET value=value+excluded.value")){s.setInt(1,join.unmatched().size());s.executeUpdate();}
            return sourceId;
        });
    }
    private static void updateParameters(Connection c,long id,List<String> names)throws Exception {
        try(var q=c.prepareStatement("SELECT signature,parameters,metadata FROM symbols WHERE id=?")){q.setLong(1,id);try(var r=q.executeQuery()){
            if(!r.next()||Json.MAPPER.readTree(r.getString(3)).path("parameter_names_from_class").asBoolean())return;
            var original=Json.MAPPER.readTree(r.getString(2));String signature=r.getString(1);
            for(int i=0;i<Math.min(names.size(),original.size());i++)signature=signature.replaceAll("\\b"+java.util.regex.Pattern.quote(original.get(i).asText())+"\\b",java.util.regex.Matcher.quoteReplacement(names.get(i)));
            try(var s=c.prepareStatement("UPDATE symbols SET signature=?,parameters=? WHERE id=?")){s.setString(1,signature);s.setString(2,Json.MAPPER.writeValueAsString(names));s.setLong(3,id);s.executeUpdate();}
        }}
    }
    public void linkEdges()throws Exception{database.write(c->{try(var s=c.createStatement()){
        s.executeUpdate("INSERT OR IGNORE INTO edges SELECT t.src,s.id,t.kind FROM edge_targets t JOIN symbols s ON s.binary_key=t.target");
        s.executeUpdate("INSERT OR IGNORE INTO edges SELECT child.id,parent.id,'overrides' FROM edges hierarchy CROSS JOIN symbols child ON child.owner_id=hierarchy.src CROSS JOIN symbols parent ON parent.owner_id=hierarchy.dst AND parent.name=child.name AND substr(parent.erased_descriptor,1,instr(parent.erased_descriptor,')'))=substr(child.erased_descriptor,1,instr(child.erased_descriptor,')')) WHERE hierarchy.kind IN ('extends','implements') AND child.kind='method' AND parent.kind='method' AND (child.flags & 8)=0 AND (parent.flags & 10)=0");
    }return null;});}
    public List<String> loadWorkspace(String workspace,List<WorkspaceArtifact> paths,List<Map.Entry<String,String>> dependencies)throws Exception{
        return database.write(c->{try(var s=c.prepareStatement("DELETE FROM workspace_artifacts WHERE workspace_id=?")){s.setString(1,workspace);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT OR IGNORE INTO workspace_artifacts SELECT ?,artifact_id,? FROM artifact_paths WHERE path=?")){for(var item:paths){s.setString(1,workspace);s.setString(2,item.scope());s.setString(3,Path.of(item.path()).toAbsolutePath().normalize().toString());s.addBatch();}s.executeBatch();}
            try(var s=c.prepareStatement("INSERT OR IGNORE INTO edges SELECT -a.id,-b.id,'depends_on' FROM artifacts a,artifacts b WHERE a.gav=? AND b.gav=?")){for(var edge:dependencies){s.setString(1,edge.getKey());s.setString(2,edge.getValue());s.addBatch();}s.executeBatch();}
            var warnings=new ArrayList<String>();
            try(var s=c.prepareStatement("SELECT s.fqn,group_concat(a.gav||' ['||a.path||']','; ') FROM simple_names s JOIN artifacts a ON a.id=s.artifact_id JOIN workspace_artifacts w ON w.artifact_id=a.id WHERE w.workspace_id=? GROUP BY s.fqn HAVING count(DISTINCT a.id)>1")){s.setString(1,workspace);try(var r=s.executeQuery()){while(r.next())warnings.add("duplicate_class: "+r.getString(1)+": "+r.getString(2));}}
            try(var s=c.prepareStatement("SELECT substr(s.fqn,1,length(s.fqn)-length(s.simple)-1) AS package,group_concat(DISTINCT a.gav) FROM simple_names s JOIN artifacts a ON a.id=s.artifact_id JOIN workspace_artifacts w ON w.artifact_id=a.id WHERE w.workspace_id=? GROUP BY package HAVING count(DISTINCT a.id)>1")){s.setString(1,workspace);try(var r=s.executeQuery()){while(r.next())warnings.add("split_package: "+r.getString(1)+": "+r.getString(2));}}
            return warnings;
        });
    }
    public List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after)throws Exception{
        return database.read(c->{String match=substring?(query.length()>=3?"s.id IN (SELECT rowid FROM symbols_fts WHERE name LIKE ? ESCAPE '\\')":"s.name LIKE ? ESCAPE '\\'"):"(s.name=? OR s.name_path=? OR s.scip=? OR s.binary_key=? OR s.name_path LIKE ? ESCAPE '\\')";
            String sql="SELECT s.*,a.gav,a.path AS artifact_path,a.kind AS artifact_kind FROM symbols s JOIN artifacts a ON a.id=s.artifact_id WHERE s.id>? AND "+match+(workspace==null?"":" AND EXISTS(SELECT 1 FROM workspace_artifacts w WHERE w.workspace_id=? AND w.artifact_id=a.id)")+" ORDER BY s.id LIMIT ?";
            var result=new ArrayList<Map<String,Object>>();try(var s=c.prepareStatement(sql)){int i=1;s.setLong(i++,after);if(substring)s.setString(i++,"%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");else { for(int j=0;j<4;j++)s.setString(i++,query);s.setString(i++,"%."+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")); }if(workspace!=null)s.setString(i++,workspace);s.setInt(i,limit);try(var r=s.executeQuery()){while(r.next())result.add(symbol(r));}}return result;
        });
    }
    public Map<String,Object> byId(long id)throws Exception{return database.read(c->{try(var s=c.prepareStatement("SELECT s.*,a.gav,a.path AS artifact_path,a.kind AS artifact_kind FROM symbols s JOIN artifacts a ON a.id=s.artifact_id WHERE s.id=?")){s.setLong(1,id);try(var r=s.executeQuery()){return r.next()?symbol(r):null;}}});}
    static Map<String,Object> symbol(ResultSet r)throws Exception{var s=new LinkedHashMap<String,Object>();for(String field:List.of("id","artifact_id","owner_id","flags","line","source_start","source_end","body_start","body_end"))s.put(field,r.getObject(field));for(String field:List.of("scip","kind","name","name_path","signature","erased_descriptor","source_file","doc","fqn","binary_key","class_entry","gav","artifact_path","artifact_kind"))s.put(field,r.getString(field));s.put("parameters",Json.MAPPER.readTree(r.getString("parameters")));s.put("metadata",Json.MAPPER.readTree(r.getString("metadata")));return s;}
    public static String namePath(BinaryReader.Symbol s){String owner=s.fqn().replace('$','/');if(s.key().equals(s.fqn()))return owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return owner+"/"+s.name()+"("+String.join(",",Arrays.stream(type.parameterArray()).map(p->p.displayName().replace('$','.')).toList())+")";}return owner+"/"+s.name();}
    public static String scip(String gav,BinaryReader.Symbol s){String[] parts=gav.split(":",3);String prefix="maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" ";String owner=s.fqn().replace('.','/').replace('$','#')+"#";if(s.key().equals(s.fqn()))return prefix+owner;if(s.kind().equals("method")||s.kind().equals("ctor")){var type=java.lang.constant.MethodTypeDesc.ofDescriptor(s.descriptor());return prefix+owner+(s.kind().equals("ctor")?"<init>":s.name())+"("+String.join(",",Arrays.stream(type.parameterArray()).map(Signatures::qualified).toList())+").";}return prefix+owner+s.name()+".";}
    @Override public void close()throws Exception {closed=true;scanner.shutdownNow();readers.shutdown();if(!readers.awaitTermination(60,TimeUnit.SECONDS))readers.shutdownNow();database.close();}
}
