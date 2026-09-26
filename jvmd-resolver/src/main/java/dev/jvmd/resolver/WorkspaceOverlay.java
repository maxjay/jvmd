package dev.jvmd.resolver;

import dev.jvmd.core.RequestScope;
import dev.jvmd.resolver.WorkspaceSource.Artifact;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Implements 4.6: built artifacts, source-only modules, version substitution and cycle reporting. */
public final class WorkspaceOverlay implements WorkspaceSource,AutoCloseable {
    private static final long SETTLE_NANOS=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2);
    private static final class Freshness {
        final Path classes;final List<Path> sources;
        boolean value,initialized,dirty=true;
        Freshness(Path classes,List<String> sources){
            this.classes=normalize(classes);this.sources=sources.stream().map(Path::of).map(WorkspaceOverlay::normalize).toList();
        }
    }
    private final List<Resolution.Module> modules;
    private final Map<String,List<Resolution.Module>> byGa=new LinkedHashMap<>();
    private final Set<String> warnings=new java.util.concurrent.ConcurrentSkipListSet<>();
    private final boolean ignoreVersions;
    private final Map<String,Freshness> freshness=new LinkedHashMap<>();
    private final Map<WatchKey,Path> watchKeys=new HashMap<>();
    private final Set<Path> watchedDirectories=new HashSet<>();
    private WatchService watcher;
    private Thread publisher;
    private Runnable freshnessListener;
    private boolean verificationOnly,closed;
    private long freshnessScans,watchEvents,watchOverflows,freshnessGeneration;

    public WorkspaceOverlay(List<Resolution.Module> modules,boolean ignoreVersions){this(modules,ignoreVersions,true);}
    /**
     * watchFreshness is false for the resolver's short-lived overlay: it preserves the original
     * one-shot verification semantics without allocating a watcher that would immediately close.
     */
    public WorkspaceOverlay(List<Resolution.Module> modules,boolean ignoreVersions,boolean watchFreshness){
        this.modules=List.copyOf(modules);this.ignoreVersions=ignoreVersions;
        for(var module:modules){
            byGa.computeIfAbsent(ga(module.gav()),_->new ArrayList<>()).add(module);
            freshness.put(freshnessKey(module,false),new Freshness(Path.of(module.classes()),module.sources()));
            freshness.put(freshnessKey(module,true),new Freshness(Path.of(module.testClasses()),
                    java.util.stream.Stream.concat(module.sources().stream(),module.testSources().stream()).toList()));
        }
        for(var entry:byGa.entrySet())if(entry.getValue().size()>1)warnings.add("overlay_duplicate: "+entry.getKey()+": "+entry.getValue().stream().map(Resolution.Module::directory).toList());
        var visited=new HashSet<String>();for(var module:modules)cycles(module,visited,new LinkedHashSet<>());
        if(watchFreshness)try{
            watcher=FileSystems.getDefault().newWatchService();
            for(var state:freshness.values()){registerPath(state.classes);for(Path source:state.sources)registerPath(source);}
            publisher=Thread.ofPlatform().daemon(true).name("jvmd-workspace-overlay-watch").start(this::publishLoop);
        }catch(IOException|UnsupportedOperationException unavailable){
            verificationOnly=true;closeWatcher();warnings.add("overlay_watch_unavailable: "+unavailable.getClass().getSimpleName());
        }else verificationOnly=true;
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
        boolean test=artifact.classifier().equals("tests");
        try{return fresh(module,test)?Path.of(test?module.testClasses():module.classes()).toFile():null;}
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
        try{return !fresh(module,false);}catch(IOException e){warnings.add("overlay_io: "+module.gav()+": "+e.getMessage());return true;}
    }

    /** Unchanged requests consume the resident decision; scans happen only after relevant mutations. */
    private boolean fresh(Resolution.Module module,boolean test)throws IOException{
        Freshness state;boolean verify;
        synchronized(this){
            state=freshness.get(freshnessKey(module,test));
            if(state==null){
                state=new Freshness(Path.of(test?module.testClasses():module.classes()),
                        test?java.util.stream.Stream.concat(module.sources().stream(),module.testSources().stream()).toList():module.sources());
                freshness.put(freshnessKey(module,test),state);
            }
            verify=verificationOnly;
        }
        if(!verify){
            try{RequestScope.settleFilesystemStart(SETTLE_NANOS);}
            catch(Exception failed){synchronized(this){verificationOnly=true;markAllDirty();}}
        }
        synchronized(this){
            if(!verificationOnly)drainAvailable();
            if(verificationOnly||!state.initialized||state.dirty){
                state.value=scanFresh(state.classes,state.sources);state.initialized=true;state.dirty=false;freshnessScans++;
            }
            return state.value;
        }
    }
    private void publishLoop(){
        while(true){
            WatchKey key;
            try{var current=watcher;if(current==null)return;key=current.take();}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(ClosedWatchServiceException stopped){return;}
            Runnable listener=null;
            try{
                synchronized(this){
                    if(closed)return;
                    if(process(key)){freshnessGeneration++;listener=freshnessListener;}
                }
            }catch(IOException failed){
                synchronized(this){verificationOnly=true;markAllDirty();freshnessGeneration++;listener=freshnessListener;}
            }
            if(listener!=null)try{listener.run();}catch(RuntimeException ignored){}
        }
    }
    private void drainAvailable()throws IOException{
        WatchService current=watcher;if(current==null){verificationOnly=true;markAllDirty();return;}
        for(WatchKey key;(key=current.poll())!=null;)if(process(key)){
            freshnessGeneration++;var listener=freshnessListener;if(listener!=null)listener.run();
        }
    }
    private boolean process(WatchKey key)throws IOException{
        Path directory=watchKeys.get(key);boolean relevant=false;
        if(directory==null){key.reset();return false;}
        for(var event:key.pollEvents()){
            watchEvents++;
            if(event.kind()==StandardWatchEventKinds.OVERFLOW){watchOverflows++;markAllDirty();relevant=true;continue;}
            if(!(event.context() instanceof Path relative)){markAllDirty();relevant=true;continue;}
            Path changed=normalize(directory.resolve(relative));relevant|=markAffected(changed);
            if(event.kind()==StandardWatchEventKinds.ENTRY_CREATE&&Files.isDirectory(changed,LinkOption.NOFOLLOW_LINKS))registerTree(changed);
        }
        if(!key.reset()){
            watchKeys.remove(key);watchedDirectories.remove(directory);verificationOnly=true;markAllDirty();relevant=true;
        }
        return relevant;
    }
    private boolean markAffected(Path changed){
        boolean relevant=false;
        for(var state:freshness.values()){
            if(intersects(changed,state.classes)){state.dirty=true;relevant=true;continue;}
            for(Path source:state.sources)if(intersects(changed,source)){state.dirty=true;relevant=true;break;}
        }
        return relevant;
    }
    public synchronized void onFreshnessChange(Runnable listener){freshnessListener=listener;}
    public synchronized boolean watchReliable(){return !verificationOnly&&!closed&&watcher!=null;}
    public synchronized long freshnessGeneration(){return freshnessGeneration;}
    private static boolean intersects(Path changed,Path root){return changed.startsWith(root)||root.startsWith(changed);}
    private void markAllDirty(){for(var state:freshness.values())state.dirty=true;}

    private void registerPath(Path logical)throws IOException{
        logical=normalize(logical);
        if(Files.isDirectory(logical,LinkOption.NOFOLLOW_LINKS)){registerTree(logical);return;}
        Path nearest=logical.getParent();
        while(nearest!=null&&!Files.isDirectory(nearest,LinkOption.NOFOLLOW_LINKS))nearest=nearest.getParent();
        if(nearest!=null)registerDirectory(nearest);
    }
    private void registerTree(Path root)throws IOException{
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(root)){for(Path directory:paths.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList())registerDirectory(directory);}
    }
    private void registerDirectory(Path directory)throws IOException{
        if(watcher==null)return;directory=normalize(directory);if(!watchedDirectories.add(directory))return;
        var key=directory.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
        watchKeys.put(key,directory);
    }

    private static long changedAt(Path file)throws IOException{
        try{var attrs=Files.readAttributes(file,"unix:lastModifiedTime,ctime");return Math.max(((java.nio.file.attribute.FileTime)attrs.get("lastModifiedTime")).to(java.util.concurrent.TimeUnit.NANOSECONDS),((java.nio.file.attribute.FileTime)attrs.get("ctime")).to(java.util.concurrent.TimeUnit.NANOSECONDS));}
        catch(UnsupportedOperationException|IllegalArgumentException e){return Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    private static boolean scanFresh(Path classes,List<Path> sources)throws IOException{
        if(!Files.isDirectory(classes))return false;long oldest=Long.MAX_VALUE,newest=Long.MIN_VALUE;
        try(var files=Files.walk(classes)){for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".class")).toList())oldest=Math.min(oldest,Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS));}
        if(oldest==Long.MAX_VALUE)return false;
        for(Path root:sources)if(Files.isDirectory(root))try(var files=Files.walk(root)){for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")).toList())newest=Math.max(newest,changedAt(file));}
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
    public synchronized Map<String,Object> status(){
        return Map.of("modules",modules.stream().map(m->Map.of("gav",m.gav(),"directory",m.directory(),"source_roots",m.sources(),"test_source_roots",m.testSources())).toList(),
                "warnings",warnings(),"errors",warnings.stream().filter(w->w.startsWith("overlay_cycle:")||w.startsWith("overlay_duplicate:")).toList(),
                "freshness_scans",freshnessScans,"freshness_watch_events",watchEvents,"freshness_watch_overflows",watchOverflows,
                "freshness_generation",freshnessGeneration,"freshness_verification_only",verificationOnly);
    }
    private static String freshnessKey(Resolution.Module module,boolean test){return module.gav()+"|"+test;}
    private static Path normalize(Path path){return path.toAbsolutePath().normalize();}
    private synchronized void closeWatcher(){var current=watcher;watcher=null;if(current!=null)try{current.close();}catch(IOException ignored){}watchKeys.clear();watchedDirectories.clear();}
    @Override public synchronized void close(){if(closed)return;closed=true;freshnessListener=null;closeWatcher();}
}
