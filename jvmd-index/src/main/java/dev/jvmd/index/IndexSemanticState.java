package dev.jvmd.index;

import dev.jvmd.core.LiveStateTree;
import java.nio.file.Path;
import java.util.*;

/** Persisted semantic revisions plus a projection of the canonical live module state. */
public interface IndexSemanticState {
    record ModuleStateInput(String moduleId,List<Path> sourceRoots,
                            List<String> compilerOptions,List<String> processors,
                            Map<String,String> generatedOutputs,List<String> orderedClasspath,String jdkFingerprint,
                            LiveStateTree.State sourceState,Set<Path> sourceFiles) {
        public ModuleStateInput {
            Objects.requireNonNull(moduleId);sourceRoots=sourceRoots.stream().map(path->path.toAbsolutePath().normalize()).toList();
            compilerOptions=List.copyOf(compilerOptions);processors=List.copyOf(processors);
            generatedOutputs=Map.copyOf(generatedOutputs);orderedClasspath=List.copyOf(orderedClasspath);
            jdkFingerprint=jdkFingerprint==null?"":jdkFingerprint;Objects.requireNonNull(sourceState);
            sourceFiles=sourceFiles.stream().map(path->path.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
    long semanticRevision(Path file)throws Exception;
    String moduleStateFingerprint(String moduleId)throws Exception;
    void publishSourceState(FileSemanticContribution contribution,String moduleId,String contextFingerprint)throws Exception;
    void configureModuleState(ModuleStateInput input)throws Exception;
}
