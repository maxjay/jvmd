package dev.jvmd.resolver;

import dev.jvmd.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;

/** Implements 4.3: lazily select a shaded Maven bundle; only platform types and JSON cross the boundary. */
public final class MavenResolver implements AutoCloseable {
    private static final int MAX_RESIDENTS=16;
    private final Config config;
    private final MavenEnvironment environment;
    private URLClassLoader loader;
    private Object bundle;
    private Method call;
    private boolean closed;
    private double bootstrapMillis;
    private long resolveCalls,resolveWorkspaceCalls,residentHits,residentInvalidations,retiredWatchEvents,retiredWatchOverflows;

    private record RequestCacheKey(Path root,List<Path> roots,boolean ignoreVersions,List<Path> lifecycleInputs){
        RequestCacheKey{
            root=root.toAbsolutePath().normalize();
            roots=roots.stream().map(path->path.toAbsolutePath().normalize()).toList();
            lifecycleInputs=lifecycleInputs.stream().map(path->path.toAbsolutePath().normalize()).distinct().sorted().toList();
        }
    }
    private static final class Resident implements AutoCloseable {
        final Resolution resolution;
        final ProjectModelState model;
        final ProjectModelWatch watch;
        Resident(ProjectModelState.Resolved resolved,Collection<Path> lifecycleInputs,Runnable invalidated)throws Exception{
            resolution=resolved.resolution();model=resolved.model();watch=new ProjectModelWatch(model,lifecycleInputs,invalidated);
        }
        boolean current(){return watch.current();}
        boolean reliable(){return watch.reliable();}
        void invalidated(Runnable listener){watch.invalidated(listener);}
        @Override public void close(){watch.close();}
    }
    /**
     * Mutation-owned project-model validity. The fast path drains queued filesystem events and
     * reads one dirty bit; it never stats/hashes the accepted Maven input set.
     */
    private static final class ProjectModelWatch implements AutoCloseable {
        private final Set<Path> relevant=new HashSet<>();
        private final Map<WatchKey,Path> directories=new HashMap<>();
        private WatchService watcher;
        private Thread publisher;
        private Runnable invalidated;
        private boolean dirty,closed,reliable;
        private long events,overflows;

        ProjectModelWatch(ProjectModelState state,Collection<Path> lifecycleInputs,Runnable invalidated)throws Exception{
            this.invalidated=invalidated;
            try{
                watcher=FileSystems.getDefault().newWatchService();
                for(var input:state.inputs())register(Path.of(input.path()).toAbsolutePath().normalize());
                for(Path input:lifecycleInputs)register(input.toAbsolutePath().normalize());
                reliable=true;
                publisher=Thread.ofPlatform().daemon(true).name("jvmd-project-model-watch").start(this::publishLoop);
            }catch(Exception unavailable){
                dirty=true;reliable=false;closeWatcher();
            }
        }
        synchronized void invalidated(Runnable listener){invalidated=listener;}
        synchronized boolean reliable(){return reliable&&!closed;}
        private void register(Path input)throws java.io.IOException{
            Path directory=input.getParent();
            while(directory!=null&&!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))directory=directory.getParent();
            if(directory==null){dirty=true;reliable=false;return;}
            Path watched=directory;
            if(!directories.containsValue(watched)){
                var key=watched.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
                directories.put(key,watched);
            }
            for(Path path=input;path!=null&&!path.equals(directory)&&path.startsWith(directory);path=path.getParent())relevant.add(path);
        }
        synchronized boolean current(){
            if(closed||dirty)return false;
            try{RequestScope.settleFilesystemStart(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));}
            catch(Exception failed){dirty=true;reliable=false;return false;}
            drain(false);
            return !dirty;
        }
        private void publishLoop(){
            while(true){
                WatchKey key;
                try{var current=watcher;if(current==null)return;key=current.take();}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
                catch(ClosedWatchServiceException stopped){return;}
                Runnable listener=null;
                synchronized(this){
                    if(closed)return;
                    boolean becameDirty=process(key);
                    if(becameDirty)listener=invalidated;
                }
                if(listener!=null)try{listener.run();}catch(RuntimeException failed){
                    System.getLogger("jvmd.resolver").log(System.Logger.Level.WARNING,"Project-model invalidation publication failed",failed);
                }
            }
        }
        private void drain(boolean publish){
            if(watcher==null){dirty=true;reliable=false;return;}
            for(WatchKey key;(key=watcher.poll())!=null;){
                boolean becameDirty=process(key);
                if(publish&&becameDirty&&invalidated!=null)invalidated.run();
            }
        }
        /** Caller holds this monitor. */
        private boolean process(WatchKey key){
            Path directory=directories.get(key);boolean becameDirty=false;
            for(var event:key.pollEvents()){
                events++;
                if(event.kind()==StandardWatchEventKinds.OVERFLOW){
                    overflows++;reliable=false;
                    if(!dirty){dirty=true;becameDirty=true;}
                    continue;
                }
                if(directory==null||!(event.context() instanceof Path relative)){
                    reliable=false;
                    if(!dirty){dirty=true;becameDirty=true;}
                    continue;
                }
                Path changed=directory.resolve(relative).toAbsolutePath().normalize();
                if(relevant.contains(changed)&&!dirty){dirty=true;becameDirty=true;}
            }
            if(!key.reset()){
                directories.remove(key);reliable=false;
                if(!dirty){dirty=true;becameDirty=true;}
            }
            return becameDirty;
        }
        synchronized long events(){drain(false);return events;}
        synchronized long overflows(){drain(false);return overflows;}
        private void closeWatcher(){
            var current=watcher;watcher=null;
            if(current!=null)try{current.close();}catch(java.io.IOException ignored){}
        }
        @Override public synchronized void close(){
            if(closed)return;closed=true;reliable=false;closeWatcher();
            directories.clear();relevant.clear();invalidated=null;
        }
    }

    private final LinkedHashMap<RequestCacheKey,Resident> residents=new LinkedHashMap<>(16,.75f,true);

    public MavenResolver(Config config) { this(config,new MavenEnvironment(config)); }
    public MavenResolver(Config config,MavenEnvironment environment) { this.config=config;this.environment=environment; }

    private Path bundlePath() {
        String override=System.getProperty("jvmd.resolvers");
        if(override!=null) return Path.of(override,"maven"+config.mavenMajor()+".jar");
        Path installed=Path.of(System.getProperty("java.home"),"lib/jvmd/resolvers","maven"+config.mavenMajor()+".jar");
        if(Files.isRegularFile(installed)) return installed;
        for(Path directory=Path.of("").toAbsolutePath();directory!=null;directory=directory.getParent()) {
            Path built=directory.resolve("jvmd-resolver/maven"+config.mavenMajor()+"/target/maven"+config.mavenMajor()+".jar");
            if(Files.isRegularFile(built)) return built;
        }
        throw new IllegalStateException("Missing Maven "+config.mavenMajor()+" bundle; build the full reactor or set jvmd.resolvers");
    }
    private void initialize() throws Exception {
        if(closed) throw new IllegalStateException("Resolver is closed");
        if(bundle!=null) return;
        long started=System.nanoTime();Path jar=bundlePath().toRealPath();
        var candidate=new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        var thread=Thread.currentThread();var previous=thread.getContextClassLoader();thread.setContextClassLoader(candidate);
        try {
            var type=candidate.loadClass("dev.jvmd.resolver.engine.Bundle");
            String args=Json.MAPPER.writeValueAsString(Map.of("jdk_home",config.jdkHome().toString(),"m2_repo",config.m2Repo().toString(),
                    "maven_major",config.mavenMajor(),"state",config.stateDir().toString(),"socket",config.socket().toString(),"settings",environment.settingsFile().toString()));
            bundle=type.getConstructor(String.class).newInstance(args);
            call=type.getMethod("call",String.class,String.class,BiFunction.class);loader=candidate;bootstrapMillis=(System.nanoTime()-started)/1e6;
        } catch(Exception e) { candidate.close();throw unwrap(e); }
        finally { thread.setContextClassLoader(previous); }
    }
    private com.fasterxml.jackson.databind.JsonNode invoke(String operation,Object parameters,WorkspaceSource workspace) throws Exception {
        initialize();var thread=Thread.currentThread();var previous=thread.getContextClassLoader();thread.setContextClassLoader(loader);
        BiFunction<String,String,String> callback=workspace==null?null:(method,json)->{
            try {
                var artifact=Json.MAPPER.readValue(json,WorkspaceSource.Artifact.class);
                if(method.equals("artifact")) { var file=workspace.findArtifact(artifact);return file==null?null:file.toString(); }
                return Json.MAPPER.writeValueAsString(workspace.findVersions(artifact));
            } catch(Exception e) { throw new IllegalStateException(e); }
        };
        try {
            String requestJson=Json.MAPPER.writeValueAsString(parameters);
            String responseJson=(String)call.invoke(bundle,operation,requestJson,callback);
            var response=Json.MAPPER.readTree(responseJson);
            if(response.has("error")) { var error=response.path("error");throw new RpcException(error.path("code").asInt(),error.path("message").asText(),error.get("data")); }
            return response.path("result");
        } catch(InvocationTargetException e) { throw unwrap(e); }
        finally { thread.setContextClassLoader(previous); }
    }
    private static Exception unwrap(Exception error) {
        if(error instanceof InvocationTargetException invocation && invocation.getCause() instanceof Exception cause) return cause;
        return error;
    }
    private void retire(RequestCacheKey key,Resident resident){
        if(resident==null)return;
        retiredWatchEvents+=resident.watch.events();retiredWatchOverflows+=resident.watch.overflows();
        resident.close();residents.remove(key);
    }
    private void install(RequestCacheKey key,ProjectModelState.Resolved resolved,Runnable invalidated)throws Exception{
        retire(key,residents.get(key));residents.put(key,new Resident(resolved,key.lifecycleInputs(),invalidated));
        while(residents.size()>MAX_RESIDENTS){
            var oldest=residents.entrySet().iterator().next();retire(oldest.getKey(),oldest.getValue());
        }
    }

    public synchronized Map<String,Object> status() {
        try {
            var result=Json.MAPPER.convertValue(invoke("status",Map.of(),null),new TypeReference<Map<String,Object>>(){});
            long activeEvents=0,activeOverflows=0;
            for(var resident:residents.values()){activeEvents+=resident.watch.events();activeOverflows+=resident.watch.overflows();}
            result.put("bootstrap_ms",bootstrapMillis);result.put("resolve_calls",resolveCalls);result.put("resolve_workspace_calls",resolveWorkspaceCalls);
            result.put("request_cache_hits",residentHits);result.put("project_model_fast_hits",residentHits);
            result.put("project_model_invalidations",residentInvalidations);result.put("project_model_residents",residents.size());
            result.put("project_model_watch_events",retiredWatchEvents+activeEvents);result.put("project_model_watch_overflows",retiredWatchOverflows+activeOverflows);
            return result;
        } catch(Exception e) { throw new IllegalStateException("Resolver status failed",e); }
    }

    public synchronized Resolution resolve(Path root) throws Exception { return resolveWorkspace(root,List.of(root),true); }

    public synchronized Resolution resolve(Path root,WorkspaceSource workspace) throws Exception {
        if(closed)throw new IllegalStateException("Resolver is closed");
        resolveCalls++;
        return Json.MAPPER.treeToValue(invoke("resolve_overlay",Map.of("root",root.toString(),"roots",List.of(root.toString()),"ignore_versions",true),workspace),Resolution.class);
    }

    public synchronized Resolution resolveWorkspace(Path root,List<Path> roots,boolean ignoreVersions) throws Exception {
        return resolveWorkspace(root,roots,ignoreVersions,List.of(),null);
    }
    /**
     * Resolve/publish one workspace and attach a mutation listener to the accepted model-input watch.
     * Ordinary editor reads do not call this method; the listener owns refresh after a relevant event.
     */
    public synchronized Resolution resolveWorkspace(Path root,List<Path> roots,boolean ignoreVersions,
                                                    Collection<Path> lifecycleInputs,Runnable invalidated) throws Exception {
        if(closed)throw new IllegalStateException("Resolver is closed");
        resolveWorkspaceCalls++;
        var key=new RequestCacheKey(root,roots,ignoreVersions,List.copyOf(lifecycleInputs));var resident=residents.get(key);
        if(resident!=null)resident.invalidated(invalidated);
        if(resident!=null&&resident.current()){residentHits++;return resident.resolution.cachedCopy();}
        if(resident!=null){residentInvalidations++;retire(key,resident);}
        resolveCalls++;
        var resolved=Json.MAPPER.treeToValue(invoke("resolve",Map.of("root",root.toString(),"roots",roots.stream().map(Path::toString).toList(),"ignore_versions",ignoreVersions),null),ProjectModelState.Resolved.class);
        install(key,resolved,invalidated);return resolved.resolution();
    }
    /** Whether mutation publication is reliable for the currently installed workspace resident. */
    public synchronized boolean workspaceWatchReliable(Path root,List<Path> roots,boolean ignoreVersions,Collection<Path> lifecycleInputs){
        var resident=residents.get(new RequestCacheKey(root,roots,ignoreVersions,List.copyOf(lifecycleInputs)));
        return resident!=null&&resident.reliable();
    }

    @Override public synchronized void close() {
        if(closed)return;closed=true;
        for(var resident:new ArrayList<>(residents.values()))resident.close();residents.clear();
        try { if(bundle instanceof AutoCloseable closeable)closeable.close(); }
        catch(Exception e) { throw new IllegalStateException("Resolver shutdown failed",e); }
        finally { try { if(loader!=null)loader.close(); } catch(java.io.IOException e) { throw new IllegalStateException(e); } }
    }
}
