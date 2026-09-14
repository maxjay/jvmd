package dev.jvmd.resolver;

import dev.jvmd.resolver.WorkspaceSource.Artifact;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Implements 4.6: built artifacts, source-only modules, version substitution and cycle reporting. */
public final class WorkspaceOverlay implements WorkspaceSource {
    private final List<Resolution.Module> modules;
    private final Map<String,List<Resolution.Module>> byGa=new LinkedHashMap<>();
    private final Set<String> warnings=new java.util.concurrent.ConcurrentSkipListSet<>();
    private final boolean ignoreVersions;
    public WorkspaceOverlay(List<Resolution.Module> modules,boolean ignoreVersions){
        this.modules=List.copyOf(modules);this.ignoreVersions=ignoreVersions;
        for(var module:modules)byGa.computeIfAbsent(ga(module.gav()),_->new ArrayList<>()).add(module);
        for(var entry:byGa.entrySet())if(entry.getValue().size()>1)warnings.add("overlay_duplicate: "+entry.getKey()+": "+entry.getValue().stream().map(Resolution.Module::directory).toList());
        var visited=new HashSet<String>();for(var module:modules)cycles(module,visited,new LinkedHashSet<>());
    }
    public static String ga(String gav){int split=gav.lastIndexOf(':');return split<0?gav:gav.substring(0,split);}
    private Resolution.Module match(String gav){
        var values=byGa.getOrDefault(ga(gav),List.of());if(values.isEmpty())return null;
        var exact=values.stream().filter(m->m.gav().equals(gav)).findFirst();
        if(exact.isPresent())return exact.get();if(!ignoreVersions)return null;
        var module=values.getFirst();warnings.add("overlay_version: requested "+gav+"; using "+module.gav()+" ["+module.directory()+"]");return module;
    }
    public Resolution.Module match(Artifact artifact){return artifact==null?null:match(artifact.groupId()+":"+artifact.artifactId()+":"+artifact.version());}
    @Override public File findArtifact(Artifact artifact){
        var module=match(artifact);if(module==null)return null;
        if(artifact.extension().equals("pom"))return Path.of(module.directory(),"pom.xml").toFile();
        if(!artifact.extension().equals("jar")||!Set.of("","tests").contains(artifact.classifier()))return null;
        boolean test=artifact.classifier().equals("tests");Path classes=Path.of(test?module.testClasses():module.classes());
        try{return fresh(classes,test?java.util.stream.Stream.concat(module.sources().stream(),module.testSources().stream()).toList():module.sources())?classes.toFile():null;}
        catch(IOException e){warnings.add("overlay_io: "+module.gav()+": "+e.getMessage());return null;}
    }
    // Resolver 1.9 has only File; this also provides the 2.0 Path seam without an internal API.
    public Path findArtifactPath(Artifact artifact){File file=findArtifact(artifact);return file==null?null:file.toPath();}
    @Override public List<String> findVersions(Artifact artifact){
        return byGa.getOrDefault(artifact.groupId()+":"+artifact.artifactId(),List.of()).stream().map(m->m.gav().substring(m.gav().lastIndexOf(':')+1)).distinct().toList();
    }
    public boolean sourceOnly(Artifact artifact){
        return artifact!=null&&artifact.extension().equals("jar")&&Set.of("","tests").contains(artifact.classifier())&&match(artifact)!=null&&findArtifact(artifact)==null;
    }
    public boolean requiresSource(Resolution.Module module){
        try{return !fresh(Path.of(module.classes()),module.sources());}catch(IOException e){warnings.add("overlay_io: "+module.gav()+": "+e.getMessage());return true;}
    }
    private static long changedAt(Path file)throws IOException{
        try{var attrs=Files.readAttributes(file,"unix:lastModifiedTime,ctime");return Math.max(((java.nio.file.attribute.FileTime)attrs.get("lastModifiedTime")).to(java.util.concurrent.TimeUnit.NANOSECONDS),((java.nio.file.attribute.FileTime)attrs.get("ctime")).to(java.util.concurrent.TimeUnit.NANOSECONDS));}
        catch(UnsupportedOperationException e){return Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    private static boolean fresh(Path classes,List<String> sources)throws IOException{
        if(!Files.isDirectory(classes))return false;long oldest=Long.MAX_VALUE,newest=Long.MIN_VALUE;
        try(var files=Files.walk(classes)){for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".class")).toList())oldest=Math.min(oldest,Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS));}
        if(oldest==Long.MAX_VALUE)return false;
        for(String root:sources)if(Files.isDirectory(Path.of(root)))try(var files=Files.walk(Path.of(root))){for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")).toList())newest=Math.max(newest,changedAt(file));}
        return oldest>=newest;
    }
    public List<Resolution.Module> dependencies(Resolution graph,String owner,boolean test){
        var result=new LinkedHashMap<String,Resolution.Module>();
        for(var node:graph.nodes())if(node.id().startsWith(owner+"|")&&node.winner()==null&&(test||!Set.of("runtime","test").contains(node.scope()))){
            var module=match(node.gav());if(module!=null&&!module.gav().equals(owner))result.putIfAbsent(module.gav(),module);
        }
        return List.copyOf(result.values());
    }
    private void cycles(Resolution.Module module,Set<String> visited,LinkedHashSet<String> path){
        if(path.contains(module.gav())){warnings.add("overlay_cycle: "+String.join(" -> ",path)+" -> "+module.gav());return;}
        if(!visited.add(module.gav()))return;path.add(module.gav());
        for(String dependency:module.dependencies()){var next=match(dependency);if(next!=null)cycles(next,visited,path);}path.remove(module.gav());
    }
    public List<String> warnings(){return List.copyOf(warnings);}
    public Map<String,Object> status(){
        return Map.of("modules",modules.stream().map(m->Map.of("gav",m.gav(),"directory",m.directory(),"source_roots",m.sources(),"test_source_roots",m.testSources())).toList(),
                "warnings",warnings(),"errors",warnings.stream().filter(w->w.startsWith("overlay_cycle:")||w.startsWith("overlay_duplicate:")).toList());
    }
}
