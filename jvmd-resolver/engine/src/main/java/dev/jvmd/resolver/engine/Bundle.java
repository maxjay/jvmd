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
        var c=Json.MAPPER.readTree(configuration);
        var config=new Config(Path.of(c.path("jdk_home").asText()),null,Path.of(c.path("m2_repo").asText()),c.path("maven_major").asInt(),
                Duration.ofHours(4),512,false,Path.of(c.path("state").asText()),Path.of(c.path("socket").asText()));
        var environment=new MavenEnvironment(config,Path.of(c.path("settings").asText()));
        // Both tasks are part of the cold request. DTO codecs have no Maven state and
        // can initialize while native services load, keeping reflection off the later cache-write path.
        try(var codecs=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().name("jvmd-resolver-codecs-"+config.mavenMajor()).factory())){
            var ready=codecs.submit(()->{
                for(Class<?> type:List.of(MavenEngine.Input.class,MavenEngine.Cached.class,Resolution.class,
                        Resolution.Module.class,Resolution.Node.class,Resolution.Edge.class,Resolution.Processing.class))
                    Json.MAPPER.writerFor(type);
            });
            Models models=new VersionModels(config,environment);
            try{ready.get();engine=new MavenEngine(config,environment,models);}
            catch(Exception|Error failure){models.close();throw failure;}
        }
    }
    public String call(String method,String request,BiFunction<String,String,String> callback) throws Exception {
        try {
            Object result;
            if(method.equals("status")) result=engine.status();
            else {
                var params=Json.MAPPER.readTree(request); Path root=Path.of(params.path("root").asText());
                if(callback==null) {
                    var roots=new ArrayList<Path>(); for(var p:params.path("roots")) roots.add(Path.of(p.asText()));
                    result=engine.resolveWorkspace(root,roots,params.path("ignore_versions").asBoolean(true));
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
