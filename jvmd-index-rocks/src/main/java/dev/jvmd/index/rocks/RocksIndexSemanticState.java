package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Source invalidation and module Merkle state, sharing the storage lifetime's native memory. */
final class RocksIndexSemanticState implements IndexSemanticState,AutoCloseable {
    private final RocksSemanticInvalidation semanticInvalidation;
    private final RocksWorkspaceState workspaceState;
    private final AtomicLong semanticUpdates=new AtomicLong(),semanticReanalyze=new AtomicLong(),semanticApiChanges=new AtomicLong(),semanticBodyOnly=new AtomicLong();
    private final AtomicLong workspaceStateUpdates=new AtomicLong(),workspaceFileWrites=new AtomicLong(),workspaceDirectoryWrites=new AtomicLong(),workspaceMetadataWrites=new AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String,ModuleStateInput> moduleInputs=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Map<String,Object> lastSemanticResult=Map.of(),lastWorkspaceState=Map.of();
    RocksIndexSemanticState(Path root,RocksMemory memory)throws Exception{
        semanticInvalidation=new RocksSemanticInvalidation(root.resolve("semantic-state"),memory);
        try{workspaceState=new RocksWorkspaceState(root.resolve("workspace-state"),memory);}
        catch(Exception|LinkageError error){semanticInvalidation.close();throw error;}
    }

    @Override public long semanticRevision(Path file)throws Exception{return semanticInvalidation.revision(file);}
    @Override public String moduleStateFingerprint(String moduleId)throws Exception{return workspaceState.fingerprint(moduleId);}

    @Override public void publishSourceState(FileSemanticContribution contribution,String requestedModuleId,String contextFingerprint)throws Exception{
        String moduleId=moduleInputs.values().stream()
                .filter(module->module.sourceRoots().stream().anyMatch(contribution.file()::startsWith))
                .sorted(Comparator.comparingInt((ModuleStateInput module)->module.sourceRoots().stream().filter(contribution.file()::startsWith).mapToInt(Path::getNameCount).max().orElse(0)).reversed())
                .map(ModuleStateInput::moduleId).findFirst().orElse(requestedModuleId);
        var result=semanticInvalidation.observeFile(moduleId,contextFingerprint,contribution);
        semanticUpdates.incrementAndGet();semanticReanalyze.addAndGet(result.reanalyze().size());
        semanticApiChanges.addAndGet(result.apiChanged().size());semanticBodyOnly.addAndGet(result.bodyOnly().size());
        lastSemanticResult=Map.of(
                "module",moduleId,"file",contribution.file().toString(),
                "reanalyze",result.reanalyze().stream().map(Path::toString).sorted().toList(),
                "api_changed",result.apiChanged().stream().map(Path::toString).sorted().toList(),
                "body_only",result.bodyOnly().stream().map(Path::toString).sorted().toList(),
                "context_changed",result.contextChanged());
    }

    @Override public void configureModuleState(IndexSemanticState.ModuleStateInput input)throws Exception{
        moduleInputs.put(input.moduleId(),input);
        var state=workspaceState.update(new RocksWorkspaceState.ModuleInput(input.moduleId(),input.sourceRoots(),
                input.compilerOptions(),input.processors(),input.generatedOutputs(),input.orderedClasspath(),input.jdkFingerprint()),
                input.sourceState(),input.sourceFiles());
        if(!state.deletedFiles().isEmpty())semanticInvalidation.removeFiles(input.moduleId(),state.deletedFiles());
        workspaceStateUpdates.incrementAndGet();workspaceFileWrites.addAndGet(state.fileWrites());
        workspaceDirectoryWrites.addAndGet(state.directoryWrites());workspaceMetadataWrites.addAndGet(state.metadataWrites());
        lastWorkspaceState=Map.of("module",input.moduleId(),"fingerprint",state.fingerprint(),
                "changed_files",state.changedFiles().stream().map(Path::toString).sorted().toList(),
                "deleted_files",state.deletedFiles().stream().map(Path::toString).sorted().toList(),
                "unchanged_files",state.unchangedFiles(),"file_writes",state.fileWrites(),
                "directory_writes",state.directoryWrites(),"metadata_writes",state.metadataWrites());
    }

    Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("semantic_state",Map.of("updates",semanticUpdates.get(),"reanalyze_total",semanticReanalyze.get(),
                "api_changes",semanticApiChanges.get(),"body_only",semanticBodyOnly.get(),"last",lastSemanticResult));
        result.put("workspace_state",Map.of("updates",workspaceStateUpdates.get(),"file_writes",workspaceFileWrites.get(),
                "directory_writes",workspaceDirectoryWrites.get(),"metadata_writes",workspaceMetadataWrites.get(),"last",lastWorkspaceState));
        return Map.copyOf(result);
    }
    @Override public void close(){try{semanticInvalidation.close();}finally{workspaceState.close();}}
}
