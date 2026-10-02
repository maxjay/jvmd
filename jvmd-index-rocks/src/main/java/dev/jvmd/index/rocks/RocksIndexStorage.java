package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import dev.jvmd.index.layer.machine.MachineLayer;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Owns one generation's native resources: the artifact repository, the MACHINE store, the index
 * store that serves queries over {@link MachineLayer}, and the semantic state. A generation is a
 * directory named for its format; a different name is a different, empty location.
 */
public final class RocksIndexStorage implements IndexStorage {
    private final Path generation;
    private final RocksMemory memory;
    private final RocksArtifactRepository repository;
    private final RocksMachineStore machineStore;
    private final RocksIndexSemanticState semanticState;
    private final RocksArtifactAdmission admission;
    private final RocksIndexStore store;
    private final MachineLayer machine=new MachineLayer();
    private boolean closed;

    /** The generation directory for this format under an index root. */
    public static Path generation(Path indexRoot){
        return indexRoot.resolve("generations").resolve("format-"+ArtifactIndexFormat.FORMAT_VERSION+"-jdk"
                +Runtime.version().feature()+"-"+ArtifactIndexFormat.INDEXER_VERSION);
    }
    public static Path machineDirectory(Path generation){return generation.resolve("machine");}

    /**
     * Create a generation's storage. Only the MACHINE cold boot's create stage calls this. It fails
     * when the generation holds a committed MACHINE root. Anything else in the directory was left by
     * an interrupted cold boot, covers nothing committed, and is deleted.
     */
    public static RocksIndexStorage create(Path generation,long maxEstimatedBytes)throws Exception{
        if(RocksMachineStore.committedRoot(machineDirectory(generation)).isPresent())
            throw new IllegalStateException("Generation already has a committed MACHINE root: "+generation);
        delete(generation);
        for(String directory:List.of("machine","db","store","semantic-state","workspace-state","local"))
            Files.createDirectories(generation.resolve(directory));
        return new RocksIndexStorage(generation,maxEstimatedBytes);
    }

    private RocksIndexStorage(Path generation,long maxEstimatedBytes)throws Exception{
        this.generation=generation.toAbsolutePath().normalize();
        admission=new RocksArtifactAdmission(maxEstimatedBytes);
        memory=new RocksMemory(Math.multiplyExact(Long.getLong("jvmd.index.native_budget_mb",64L),1024L*1024L));
        var opened=new ArrayList<AutoCloseable>();opened.add(memory);
        try{
            repository=new RocksArtifactRepository(this.generation,memory.creating(128));opened.add(repository);
            machineStore=new RocksMachineStore(machineDirectory(this.generation),memory.creating(64));opened.add(machineStore);
            semanticState=new RocksIndexSemanticState(this.generation,()->memory.creating(64));opened.add(semanticState);
            store=new RocksIndexStore(this.generation.resolve("store"),memory.creating(64),repository,admission,machine);opened.add(store);
        }catch(Exception|LinkageError error){
            Collections.reverse(opened);for(var item:opened)try{item.close();}catch(Exception close){error.addSuppressed(close);}throw error;
        }
    }

    public Path generation(){return generation;}
    public RocksArtifactRepository repository(){return repository;}
    public RocksMachineStore machineStore(){return machineStore;}
    public MachineLayer machine(){return machine;}
    /** The native cache and write-buffer budget shared by every database in this generation. */
    public RocksMemory memory(){return memory;}
    @Override public IndexStore store(){return store;}
    @Override public IndexSemanticState semanticState(){return semanticState;}
    @Override public ArtifactAdmission admission(){return admission;}

    @Override public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("backend","rocksdb-sst");result.put("generation",generation.getFileName().toString());
        result.put("machine_root",machine.root().map(root->root.identity().hex()).orElse(""));
        result.put("machine_leaves",machine.tree().size());result.put("machine_store",machineStore.status());
        result.put("native_memory",memory.status());result.putAll(admission.status());result.putAll(semanticState.status());
        try{result.put("repository",repository.status());}catch(Exception e){result.put("repository_error",e.toString());}
        return Map.copyOf(result);
    }

    @Override public synchronized void close()throws Exception{
        if(closed)return;
        // A store that cannot drain must retain the resources used by its active builders.
        store.close();closed=true;
        Exception failure=null;
        for(var resource:List.<AutoCloseable>of(semanticState,machineStore,repository,memory))try{resource.close();}
        catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }

    private static void delete(Path root)throws IOException{
        if(!Files.exists(root))return;
        try(var paths=Files.walk(root)){
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
    }
}
