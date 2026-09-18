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
    private final Config config;
    private final MavenEnvironment environment;
    private URLClassLoader loader;
    private Object bundle;
    private Method call;
    private boolean closed;
    private double bootstrapMillis;
    private long resolveCalls,requestCacheHits,lastRequestId;
    private RequestCacheKey lastRequestKey;
    private Resolution lastRequestResolution;
    private record RequestCacheKey(Path root,List<Path> roots,boolean ignoreVersions){
        RequestCacheKey{root=root.toAbsolutePath().normalize();roots=roots.stream().map(path->path.toAbsolutePath().normalize()).toList();}
    }
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
            var response=Json.MAPPER.readTree((String)call.invoke(bundle,operation,Json.MAPPER.writeValueAsString(parameters),callback));
            if(response.has("error")) { var error=response.path("error");throw new RpcException(error.path("code").asInt(),error.path("message").asText(),error.get("data")); }
            return response.path("result");
        } catch(InvocationTargetException e) { throw unwrap(e); }
        finally { thread.setContextClassLoader(previous); }
    }
    private static Exception unwrap(Exception error) {
        if(error instanceof InvocationTargetException invocation && invocation.getCause() instanceof Exception cause) return cause;
        return error;
    }
    public synchronized Map<String,Object> status() {
        try { var result=Json.MAPPER.convertValue(invoke("status",Map.of(),null),new TypeReference<Map<String,Object>>(){});result.put("bootstrap_ms",bootstrapMillis);result.put("resolve_calls",resolveCalls);result.put("request_cache_hits",requestCacheHits);return result; }
        catch(Exception e) { throw new IllegalStateException("Resolver status failed",e); }
    }
    public synchronized Resolution resolve(Path root) throws Exception { return resolveWorkspace(root,List.of(root),true); }
    public synchronized Resolution resolve(Path root,WorkspaceSource workspace) throws Exception {
        resolveCalls++;return Json.MAPPER.treeToValue(invoke("resolve",Map.of("root",root.toString(),"roots",List.of(root.toString()),"ignore_versions",true),workspace),Resolution.class);
    }
    public synchronized Resolution resolveWorkspace(Path root,List<Path> roots,boolean ignoreVersions) throws Exception {
        var key=new RequestCacheKey(root,roots,ignoreVersions);long request=RequestScope.id();
        if(request!=0&&request==lastRequestId&&key.equals(lastRequestKey)&&lastRequestResolution!=null){requestCacheHits++;return lastRequestResolution;}
        resolveCalls++;var result=Json.MAPPER.treeToValue(invoke("resolve",Map.of("root",root.toString(),"roots",roots.stream().map(Path::toString).toList(),"ignore_versions",ignoreVersions),null),Resolution.class);
        if(request!=0){lastRequestId=request;lastRequestKey=key;lastRequestResolution=result;}
        return result;
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;lastRequestResolution=null;lastRequestKey=null;
        try { if(bundle instanceof AutoCloseable closeable)closeable.close(); }
        catch(Exception e) { throw new IllegalStateException("Resolver shutdown failed",e); }
        finally { try { if(loader!=null)loader.close(); } catch(java.io.IOException e) { throw new IllegalStateException(e); } }
    }
}
