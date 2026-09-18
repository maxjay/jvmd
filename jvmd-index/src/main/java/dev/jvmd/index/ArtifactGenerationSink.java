package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;

/** Optional destination for immutable artifact generations prepared during repository indexing. */
public interface ArtifactGenerationSink extends AutoCloseable {
    void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception;
    default long beginScan()throws Exception{return 0L;}
    default void observe(long scanGeneration,IndexStore.ArtifactInput input)throws Exception{ }
    default Set<String> completeScan(long scanGeneration)throws Exception{return Set.of();}
    default boolean needsDocumentation(String binaryCacheKey)throws Exception{return false;}
    default void publishDocumentation(String binaryCacheKey,IndexStore.ArtifactInput sourceInput,Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception{ }
    default void configureWorkspace(String workspace,List<IndexStore.WorkspaceEntry> paths,List<Map.Entry<String,String>> dependencies)throws Exception{ }
    default Optional<List<Map<String,Object>>> shadowFind(String workspace,String query,boolean substring,int limit,Set<String> kinds)throws Exception{return Optional.empty();}
    default Optional<Set<String>> shadowRelationships(String workspace,Collection<String> scips,boolean outgoing,Set<String> kinds,int limit)throws Exception{return Optional.empty();}
    default Map<String,Object> status(){return Map.of("backend","none");}
    @Override default void close()throws Exception { }

    static ArtifactGenerationSink open(Path root,long maxEstimatedBytes)throws Exception{
        return open(System.getProperty("jvmd.index.generation.backend","auto"),root,maxEstimatedBytes);
    }

    static ArtifactGenerationSink open(String backend,Path root,long maxEstimatedBytes)throws Exception{
        Objects.requireNonNull(backend);Objects.requireNonNull(root);
        if(backend.equals("none"))return none();
        var providers=ServiceLoader.load(ArtifactGenerationSinkProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(ArtifactGenerationSinkProvider::priority).reversed()
                        .thenComparing(ArtifactGenerationSinkProvider::backend))
                .toList();
        if(backend.equals("auto")){
            if(providers.isEmpty())return none();
            return providers.getFirst().open(root,maxEstimatedBytes);
        }
        for(var provider:providers)if(provider.backend().equals(backend))return provider.open(root,maxEstimatedBytes);
        throw new IllegalStateException("Artifact generation backend is unavailable: "+backend);
    }

    static ArtifactGenerationSink none(){
        return new ArtifactGenerationSink(){
            @Override public void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences){ }
        };
    }
}
