package dev.jvmd.resolver.engine;

import dev.jvmd.core.*;
import dev.jvmd.resolver.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;

/** Implements 4.3: JSON-only calls between the daemon and a platform-parented shaded resolver bundle. */
public final class Bundle implements AutoCloseable {
    private final MavenEngine engine;
    public Bundle(String configuration) throws Exception {
        // All initialization stays inside the cold request. Native services do not
        // depend on config parsing or DTO codecs, so the two class-loading paths overlap.
        try(var startup=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().name("jvmd-resolver-bootstrap").factory())){
            var ready=startup.submit(VersionModels::bootstrap);Models models=null;
            try{
                var c=Json.MAPPER.readTree(configuration);
                var config=new Config(Path.of(c.path("jdk_home").asText()),null,Path.of(c.path("m2_repo").asText()),c.path("maven_major").asInt(),
                        Duration.ofHours(4),512,false,Path.of(c.path("state").asText()),Path.of(c.path("socket").asText()));
                var environment=new MavenEnvironment(config,Path.of(c.path("settings").asText()));
                for(Class<?> type:List.of(MavenEngine.Cached.class,ProjectModelState.class,ProjectModelState.Input.class,ProjectModelState.Resolved.class,Resolution.class,
                        Resolution.Module.class,Resolution.Node.class,Resolution.Edge.class,Resolution.Processing.class))
                    Json.MAPPER.writerFor(type);
                models=ready.get().create(config,environment);engine=new MavenEngine(config,environment,models);
            }catch(Exception|Error failure){
                if(models!=null){try{models.close();}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}}
                else discard(ready,failure);
                throw failure;
            }
        }
    }
    private static void discard(java.util.concurrent.Future<Models.Bootstrap> ready,Throwable failure){
        boolean interrupted=false;
        try{
            for(;;)try{ready.get().close();return;}
            catch(InterruptedException retry){interrupted=true;}
            catch(java.util.concurrent.ExecutionException|RuntimeException|Error cleanup){failure.addSuppressed(cleanup);return;}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    public String call(String method,String request,BiFunction<String,String,String> callback) throws Exception {
        try {
            Object result;
            if(method.equals("status")) result=engine.status();
            else {
                var params=Json.MAPPER.readTree(request); Path root=Path.of(params.path("root").asText());
                if(callback==null) {
                    var roots=new ArrayList<Path>(); for(var p:params.path("roots")) roots.add(Path.of(p.asText()));
                    result=engine.resolveWorkspaceState(root,roots,params.path("ignore_versions").asBoolean(true));
                } else result=engine.resolve(root,new OverlayReader(new WorkspaceSource() {
                    private String ask(String operation,Artifact a) {
                        try { return callback.apply(operation,Json.MAPPER.writeValueAsString(a)); }
                        catch(Exception e) { throw new IllegalStateException(e); }
                    }
                    @Override public java.io.File findArtifact(Artifact a) { String file=ask("artifact",a); return file==null?null:new java.io.File(file); }
                    @Override public List<String> findVersions(Artifact a) {
                        try { return Json.MAPPER.readValue(ask("versions",a),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}); }
                        catch(Exception e) { throw new IllegalStateException(e); }
                    }
                }));
            }
            return Json.MAPPER.writeValueAsString(Map.of("result",result));
        } catch(RpcException e) {
            var error=new LinkedHashMap<String,Object>();error.put("code",e.code());error.put("message",e.getMessage());error.put("data",e.data());
            return Json.MAPPER.writeValueAsString(Map.of("error",error));
        }
    }
    @Override public void close() { engine.close(); }
}
