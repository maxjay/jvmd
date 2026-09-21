package dev.jvmd.index;

import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Implements 4.4 and 4.5: lazy content invalidation of registered module inputs. */
final class LocalArtifacts {
    private final class State {
        final IndexService.LocalModule module;
        Map<Path,String> observed=Map.of();long artifact=-1;
        final CompilerInputs inputs=new CompilerInputs(files);
        State(IndexService.LocalModule module){this.module=module;}
    }
    private final IndexService index;
    private final Map<Path,State> modules=new java.util.concurrent.ConcurrentHashMap<>();
    private final FileStateRegistry files=FileStateRegistry.shared();
    private final Documents disk=new Documents();
    LocalArtifacts(IndexService index){this.index=index;}
    boolean register(IndexService.LocalModule module){
        var added=new java.util.concurrent.atomic.AtomicBoolean();
        modules.compute(module.directory(),(_,old)->{if(old==null||!old.module.equals(module)){added.set(true);return new State(module);}return old;});
        return added.get();
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
        var module=state.module;long size=0,mtime=0,newestSource=0,oldestClass=Long.MAX_VALUE;
        var sourcePaths=new LinkedHashMap<String,Path>();
        var roots=new ArrayList<>(module.sources());roots.addAll(module.outputs());
        var configuration=new CompilerInputs.Configuration("local:"+module.gav(),roots,List.of(),List.of());
        var inventory=new ArrayList<Path>();for(Path root:roots)inventory.addAll(files.inventory(root,""));
        var snapshot=state.inputs.capture(configuration,disk,inventory);var observed=snapshot.sources();
        String fingerprint=CompilerInputs.compose("local-artifact-v2",module.directory(),module.gav(),roots,new TreeMap<>(observed));
        var previous=index.artifact(module.directory());
        if(previous!=null&&previous.hasSignatureEdges()&&fingerprint.equals(previous.sha256())){state.observed=observed;state.artifact=previous.id();return previous.id();}
        for(int i=0;i<roots.size();i++){
            Path root=roots.get(i);
            for(Path file:files.inventory(root,"")){
                size+=Files.size(file);long modified=Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS);mtime=Math.max(mtime,modified);
                if(i<module.sources().size()&&file.toString().endsWith(".java")){newestSource=Math.max(newestSource,changed(file));sourcePaths.put(i+"/"+root.relativize(file).toString().replace(java.io.File.separatorChar,'/'),file);}
                if(i>=module.sources().size()&&file.toString().endsWith(".class"))oldestClass=Math.min(oldestClass,modified);
            }
        }
        var symbols=new LinkedHashMap<String,BinaryReader.Symbol>();var edges=new LinkedHashSet<BinaryReader.Edge>();var models=new LinkedHashMap<String,java.lang.classfile.ClassModel>();var warnings=new ArrayList<String>();
        // Stale outputs must not reintroduce declarations removed from the source API.
        if(oldestClass!=Long.MAX_VALUE&&oldestClass>=newestSource)for(Path output:module.outputs())if(Files.isDirectory(output)){
            var content=new BinaryReader().read(output,true);content.symbols().forEach(s->symbols.putIfAbsent(s.key(),s));edges.addAll(content.edges());content.models().forEach(models::putIfAbsent);warnings.addAll(content.warnings());
        }
        var sourceData=new HashMap<String,Map<String,Object>>();
        if(!models.isEmpty()&&!sourcePaths.isEmpty()){
            var text=new LinkedHashMap<String,String>();for(var source:sourcePaths.entrySet())text.put(source.getKey(),snapshot.text(source.getValue(),disk));
            var joined=new SourceJoin().join(models,text);
            for(var member:joined.members()){
                String key=member.descriptor()==null?member.owner():member.descriptor().equals("field")?member.owner()+"#"+member.name():member.owner()+"#"+member.name()+member.descriptor();
                Path file=sourcePaths.get(member.file());if(file==null)continue;
                var data=new LinkedHashMap<String,Object>();data.put("source_file",file.toString());data.put("file",file.toString());data.put("line",member.line());data.put("doc",member.doc());data.put("source_start",member.start());data.put("source_end",member.end());data.put("body_start",member.bodyStart());data.put("body_end",member.bodyEnd());data.put("parameters",member.parameters());sourceData.put(key,data);
            }
        }
        inventory.clear();for(Path root:roots)inventory.addAll(files.inventory(root,""));
        if(!snapshot.equals(state.inputs.capture(configuration,disk,inventory)))throw new CompilerInputs.Superseded("Local artifact inputs changed during refresh");
        state.artifact=index.replaceLocal(module,fingerprint,size,mtime,new BinaryReader.Content(List.copyOf(symbols.values()),List.copyOf(edges),Map.of(),List.copyOf(warnings)),sourceData);state.observed=observed;return state.artifact;
    }
    void refreshWorkspace(String workspace)throws Exception{
        for(Path path:index.store().localWorkspaceArtifacts(workspace))refresh(path);
    }
    void recordSource(Path file,String contentHash,List<Map<String,Object>> symbols,int tier,List<IndexService.SourceEdge> edges)throws Exception{
        var state=modules.values().stream().filter(s->s.module.sources().stream().anyMatch(file::startsWith)).max(Comparator.comparingInt(s->s.module.directory().getNameCount())).orElse(null);
        if(state==null)return;
        synchronized(state){if(!Files.isRegularFile(file)||!files.hash(file).equals(contentHash))return;long artifact=contentHash.equals(state.observed.get(file))&&state.artifact>=0?state.artifact:refresh(state);index.storeSource(artifact,file,contentHash,symbols,tier,edges);}
    }
}
