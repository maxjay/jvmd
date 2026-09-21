package dev.jvmd.tests;

import dev.jvmd.index.*;
import java.nio.file.Path;
import java.util.*;

/** Test-only historical SQL oracle. Never registered as a production provider. */
final class ReferenceIndexStorage implements IndexStorage,ArtifactInventory,IndexSemanticState {
    private final SqliteIndexStore store;
    private final java.util.function.ToLongFunction<Path> revision;
    ReferenceIndexStorage(Path database)throws Exception{this(database,file->0L);}
    ReferenceIndexStorage(Path database,java.util.function.ToLongFunction<Path> revision)throws Exception{
        store=new SqliteIndexStore(database);this.revision=revision;
    }
    public IndexStore store(){return store;}
    public ArtifactInventory inventory(){return this;}
    public IndexSemanticState semanticState(){return this;}
    public ArtifactAdmission admission(){return path->()->{};}
    public Map<String,Object> status(){return Map.of("backend","test-sqlite");}
    public long beginScan(){return 0;}
    public void observe(long generation,IndexStore.ArtifactInput input){}
    public Set<String> completeScan(long generation){return Set.of();}
    public long semanticRevision(Path file){return revision.applyAsLong(file);}
    public String moduleStateFingerprint(String moduleId){return null;}
    public void publishSourceState(FileSemanticContribution contribution,String moduleId,String contextFingerprint){}
    public void configureModuleState(ModuleStateInput input){}
    public void close()throws Exception{store.close();}
}
