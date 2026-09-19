package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;

/** Optional destination for immutable artifact generations prepared during repository indexing. */
public interface ArtifactGenerationSink extends AutoCloseable {
    record ModuleStateInput(String moduleId,List<Path> sourceRoots,Map<Path,String> overlays,
                            List<String> compilerOptions,List<String> processors,
                            Map<String,String> generatedOutputs,List<String> orderedClasspath,String jdkFingerprint) {
        public ModuleStateInput {
            Objects.requireNonNull(moduleId);sourceRoots=List.copyOf(sourceRoots);overlays=Map.copyOf(overlays);
            compilerOptions=List.copyOf(compilerOptions);processors=List.copyOf(processors);
            generatedOutputs=Map.copyOf(generatedOutputs);orderedClasspath=List.copyOf(orderedClasspath);
            jdkFingerprint=jdkFingerprint==null?"":jdkFingerprint;
        }
    }
    default AutoCloseable acquireArtifact(Path path)throws Exception{return ()->{};}
    /** Open the authoritative store sharing this generation's native resources. */
    default IndexStore openStore()throws Exception{throw new UnsupportedOperationException("No authoritative index store for this provider");}
    void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception;
    default boolean contains(ArtifactIndexFormat.Key key)throws Exception{return true;}
    default long beginScan()throws Exception{return 0L;}
    default void observe(long scanGeneration,IndexStore.ArtifactInput input)throws Exception{ }
    default Set<String> completeScan(long scanGeneration)throws Exception{return Set.of();}
    default boolean needsDocumentation(String binaryCacheKey)throws Exception{return false;}
    default void publishDocumentation(String binaryCacheKey,IndexStore.ArtifactInput sourceInput,Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception{ }
    default long semanticRevision(Path file)throws Exception{return 0L;}
    default void publishSourceState(SourceIndexPublisher.Delta delta)throws Exception{ }
    default void configureModuleState(ModuleStateInput input)throws Exception{ }
    default void configureWorkspace(String workspace,List<IndexStore.WorkspaceEntry> paths,List<Map.Entry<String,String>> dependencies)throws Exception{ }
    default Optional<Map<String,Object>> shadowById(String workspace,long id)throws Exception{return Optional.empty();}
    default OptionalLong shadowCursor(String workspace,String scip)throws Exception{return OptionalLong.empty();}
    default Optional<List<Map<String,Object>>> shadowFind(String workspace,String query,boolean substring,int limit,long after,Set<String> kinds)throws Exception{return Optional.empty();}
    default Optional<List<Map<String,Object>>> shadowFind(String workspace,String query,boolean substring,int limit,Set<String> kinds)throws Exception{
        return shadowFind(workspace,query,substring,limit,0L,kinds);
    }
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
