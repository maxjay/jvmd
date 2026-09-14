package dev.jvmd.index;

import dev.jvmd.core.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Implements 4.4 and 4.5: lazy content invalidation of registered module inputs. */
final class LocalArtifacts {
    private static final class State {
        final IndexService.LocalModule module;
        Map<Path,String> observed=Map.of();long artifact=-1;
        State(IndexService.LocalModule module){this.module=module;}
    }
    private record Stamp(Object size,Object modified,Object changed,Object inode,String hash) { }
    private final IndexService index;
    private final Map<Path,State> modules=new java.util.concurrent.ConcurrentHashMap<>();
    private final LinkedHashMap<Path,Stamp> hashes=new LinkedHashMap<>(256,.75f,true);
    LocalArtifacts(IndexService index){this.index=index;}
    boolean register(IndexService.LocalModule module){
        var added=new java.util.concurrent.atomic.AtomicBoolean();
        modules.compute(module.directory(),(_,old)->{if(old==null||!old.module.equals(module)){added.set(true);return new State(module);}return old;});
        return added.get();
    }
    private synchronized String hash(Path file)throws Exception{
        Map<String,Object> stat=null;try{stat=Files.readAttributes(file,"unix:size,lastModifiedTime,ctime,ino");}catch(UnsupportedOperationException|IllegalArgumentException ignored){}
        if(stat!=null){var old=hashes.get(file);if(old!=null&&Objects.equals(old.size(),stat.get("size"))&&Objects.equals(old.modified(),stat.get("lastModifiedTime"))&&Objects.equals(old.changed(),stat.get("ctime"))&&Objects.equals(old.inode(),stat.get("ino")))return old.hash();}
        String hash=Hashing.sha256(file);
        if(stat!=null){hashes.put(file,new Stamp(stat.get("size"),stat.get("lastModifiedTime"),stat.get("ctime"),stat.get("ino"),hash));while(hashes.size()>32768)hashes.remove(hashes.keySet().iterator().next());}
        return hash;
    }
    private static long changed(Path file)throws Exception{
        long modified=Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS);
        try{return Math.max(modified,((java.nio.file.attribute.FileTime)Files.getAttribute(file,"unix:ctime")).to(java.util.concurrent.TimeUnit.NANOSECONDS));}
        catch(UnsupportedOperationException|IllegalArgumentException ignored){return modified;}
    }
    long refresh(Path directory)throws Exception{
        var state=modules.get(directory.toAbsolutePath().normalize());if(state==null)return -1;
        synchronized(state){return refresh(state);}
    }
    private long refresh(State state)throws Exception{
        var module=state.module;var digest=MessageDigest.getInstance("SHA-256");long size=0,mtime=0,newestSource=0,oldestClass=Long.MAX_VALUE;
        digest.update(("local\0"+module.directory()+"\0"+module.gav()+"\0").getBytes(StandardCharsets.UTF_8));
        var sourcePaths=new LinkedHashMap<String,Path>();var observed=new HashMap<Path,String>();
        var roots=new ArrayList<>(module.sources());roots.addAll(module.outputs());
        for(int i=0;i<roots.size();i++){
            Path root=roots.get(i);digest.update((i+":"+root+"\0").getBytes(StandardCharsets.UTF_8));if(!Files.isDirectory(root))continue;
            try(var files=Files.walk(root)){
                for(Path file:files.filter(Files::isRegularFile).sorted().toList()){
                    String contentHash=hash(file);observed.put(file,contentHash);digest.update((root.relativize(file)+"\0"+contentHash+"\0").getBytes(StandardCharsets.UTF_8));size+=Files.size(file);long modified=Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS);mtime=Math.max(mtime,modified);
                    if(i<module.sources().size()&&file.toString().endsWith(".java")){newestSource=Math.max(newestSource,changed(file));sourcePaths.put(i+"/"+root.relativize(file).toString().replace(java.io.File.separatorChar,'/'),file);}
                    if(i>=module.sources().size()&&file.toString().endsWith(".class"))oldestClass=Math.min(oldestClass,modified);
                }
            }
        }
        String fingerprint=HexFormat.of().formatHex(digest.digest());var previous=index.artifact(module.directory());if(previous!=null&&fingerprint.equals(previous.sha256())){state.observed=Map.copyOf(observed);state.artifact=previous.id();return previous.id();}
        var symbols=new LinkedHashMap<String,BinaryReader.Symbol>();var edges=new LinkedHashSet<BinaryReader.Edge>();var models=new LinkedHashMap<String,java.lang.classfile.ClassModel>();var warnings=new ArrayList<String>();
        // Stale outputs must not reintroduce declarations removed from the source API.
        if(oldestClass!=Long.MAX_VALUE&&oldestClass>=newestSource)for(Path output:module.outputs())if(Files.isDirectory(output)){
            var content=new BinaryReader().read(output,true);content.symbols().forEach(s->symbols.putIfAbsent(s.key(),s));edges.addAll(content.edges());content.models().forEach(models::putIfAbsent);warnings.addAll(content.warnings());
        }
        var sourceData=new HashMap<String,Map<String,Object>>();
        if(!models.isEmpty()&&!sourcePaths.isEmpty()){
            var text=new LinkedHashMap<String,String>();for(var source:sourcePaths.entrySet())text.put(source.getKey(),Files.readString(source.getValue()));
            var joined=new SourceJoin().join(models,text);
            for(var member:joined.members()){
                String key=member.descriptor()==null?member.owner():member.descriptor().equals("field")?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();
                Path file=sourcePaths.get(member.file());if(file==null)continue;
                var data=new LinkedHashMap<String,Object>();data.put("source_file",file.toString());data.put("file",file.toString());data.put("line",member.line());data.put("doc",member.doc());data.put("source_start",member.start());data.put("source_end",member.end());data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());data.put("parameters",member.parameters());sourceData.put(key,data);
            }
        }
        state.artifact=index.replaceLocal(module,fingerprint,size,mtime,new BinaryReader.Content(List.copyOf(symbols.values()),List.copyOf(edges),Map.of(),List.copyOf(warnings)),sourceData);state.observed=Map.copyOf(observed);return state.artifact;
    }
    void refreshWorkspace(String workspace)throws Exception{
        var paths=index.database().read(c->{var result=new ArrayList<Path>();try(var q=c.prepareStatement("SELECT a.path FROM workspace_artifacts w JOIN artifacts a ON a.id=w.artifact_id WHERE w.workspace_id=? AND a.kind='local'")){q.setString(1,workspace);try(var r=q.executeQuery()){while(r.next())result.add(Path.of(r.getString(1)));}}return result;});
        for(Path path:paths)refresh(path);
    }
    void recordSource(Path file,String contentHash,List<Map<String,Object>> symbols,int tier,List<IndexService.SourceEdge> edges)throws Exception{
        var state=modules.values().stream().filter(s->s.module.sources().stream().anyMatch(file::startsWith)).max(Comparator.comparingInt(s->s.module.directory().getNameCount())).orElse(null);
        if(state==null)return;
        synchronized(state){if(!Files.isRegularFile(file)||!hash(file).equals(contentHash))return;long artifact=contentHash.equals(state.observed.get(file))&&state.artifact>=0?state.artifact:refresh(state);index.storeSource(artifact,file,symbols,tier,edges);}
    }
}
