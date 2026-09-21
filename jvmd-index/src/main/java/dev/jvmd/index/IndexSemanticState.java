package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;

/** Persisted source revisions and module fingerprints, independent of artifact publication. */
public interface IndexSemanticState {
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
    long semanticRevision(Path file)throws Exception;
    String moduleStateFingerprint(String moduleId)throws Exception;
    void publishSourceState(FileSemanticContribution contribution,String moduleId,String contextFingerprint)throws Exception;
    void configureModuleState(ModuleStateInput input)throws Exception;
}
