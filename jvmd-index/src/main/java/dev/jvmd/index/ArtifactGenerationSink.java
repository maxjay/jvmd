package dev.jvmd.index;

import java.util.*;

/** Optional destination for immutable artifact generations prepared during repository indexing. */
public interface ArtifactGenerationSink extends AutoCloseable {
    void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception;
    default Map<String,Object> status(){return Map.of("backend","none");}
    @Override default void close()throws Exception { }

    static ArtifactGenerationSink none(){
        return new ArtifactGenerationSink(){
            @Override public void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences){ }
        };
    }
}
