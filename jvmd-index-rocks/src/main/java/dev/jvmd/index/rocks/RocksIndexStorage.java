package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import dev.jvmd.index.layer.machine.MachineLayer;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.rocks.layer.RocksMachineStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntFunction;
import org.rocksdb.Options;

/**
 * Owns one generation's native resources: the artifact repository, the MACHINE store, the index
 * store that serves queries, the inventory and the semantic state. A generation is a directory
 * named for its format; a different name is a different, empty location.
 */
public final class RocksIndexStorage implements IndexStorage,ArtifactInventory {
    private static final List<String> DATABASES=List.of("machine","db","store","inventory","semantic-state","workspace-state");
    private final Path generation;
    private final RocksMemory memory;
    private final RocksArtifactRepository repository;
    private final RocksMachineStore machineStore;
    private final RocksArtifactInventory inventory;
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
        rejectRetiredSettings();
        if(RocksMachineStore.committedRoot(machineDirectory(generation)).isPresent())
            throw new IllegalStateException("Generation already has a committed MACHINE root: "+generation);
        delete(generation);
        for(String database:DATABASES)Files.createDirectories(generation.resolve(database));
        return new RocksIndexStorage(generation,maxEstimatedBytes,RocksMemory::creating);
    }

    /**
     * Open a generation whose MACHINE root is committed. TEMPORARY(warm-boot): the existing warm path
     * reopens the generation's A| records and inventory; the warm boot task replaces this with
     * restoring the committed MACHINE tree.
     */
    public static RocksIndexStorage open(Path generation,long maxEstimatedBytes)throws Exception{
        rejectRetiredSettings();
        var storage=new RocksIndexStorage(generation,maxEstimatedBytes,RocksMemory::options);
        try{
            // TEMPORARY(warm-boot): serve the committed MACHINE tree as it was committed, so a LOCAL
            // cold boot can route to its leaves. The warm boot task restores and updates it.
            storage.machine.committed(storage.machineStore.committedTree());
            return storage;
        }catch(Exception|Error failure){
            try{storage.close();}catch(Exception close){failure.addSuppressed(close);}
            throw failure;
        }
    }

    /** Old settings must fail visibly, never silently select a different data store. */
    private static void rejectRetiredSettings(){
        for(String property:List.of("jvmd.index.store.backend","jvmd.index.read.backend","jvmd.index.generation.backend")){
            String value=System.getProperty(property);
            if(value!=null&&!value.equals("rocksdb-sst")&&!(property.equals("jvmd.index.generation.backend")&&value.equals("auto")))
                throw new IllegalArgumentException(property+"="+value+" is no longer supported; Rocks is the sole production backend. Remove this setting and rebuild the index from source artifacts. Existing SQLite files are left untouched.");
        }
    }

    private RocksIndexStorage(Path generation,long maxEstimatedBytes,OptionsFactory factory)throws Exception{
        this.generation=generation.toAbsolutePath().normalize();
        admission=new RocksArtifactAdmission(maxEstimatedBytes);
        memory=new RocksMemory(Math.multiplyExact(Long.getLong("jvmd.index.native_budget_mb",64L),1024L*1024L));
        IntFunction<Options> options=openFiles->factory.options(memory,openFiles);
        var opened=new ArrayList<AutoCloseable>();opened.add(memory);
        try{
            repository=new RocksArtifactRepository(this.generation,options.apply(128));opened.add(repository);
            machineStore=new RocksMachineStore(machineDirectory(this.generation),options.apply(64));opened.add(machineStore);
            inventory=new RocksArtifactInventory(this.generation.resolve("inventory"),options.apply(64));opened.add(inventory);
            semanticState=new RocksIndexSemanticState(this.generation,()->options.apply(64));opened.add(semanticState);
            store=new RocksIndexStore(this.generation.resolve("store"),options.apply(64),repository,admission);opened.add(store);
        }catch(Exception|LinkageError error){
            Collections.reverse(opened);for(var item:opened)try{item.close();}catch(Exception close){error.addSuppressed(close);}throw error;
        }
    }
    @FunctionalInterface private interface OptionsFactory { Options options(RocksMemory memory,int openFiles); }

    public Path generation(){return generation;}
    public RocksArtifactRepository repository(){return repository;}
    public RocksMachineStore machineStore(){return machineStore;}
    public MachineLayer machine(){return machine;}
    /** The native cache and write-buffer budget shared by every database in this generation. */
    public RocksMemory memory(){return memory;}
    @Override public IndexStore store(){return store;}
    @Override public ArtifactInventory inventory(){return this;}
    @Override public IndexSemanticState semanticState(){return semanticState;}
    @Override public ArtifactAdmission admission(){return admission;}

    /**
     * Commit a MACHINE tree built by a cold boot: write what the existing warm path reads, then the
     * MACHINE leaves, nodes and path table, then the root, then serve it.
     */
    public synchronized void commitMachine(MachineTree tree)throws Exception{
        var previous=machine.tree();
        // TEMPORARY(warm-boot): the MACHINE leaves and path table replace the A| records and the
        // inventory P| entries, which only the existing warm path reads.
        store.installMachine(previous,tree);
        long scan=inventory.beginScan();
        for(var leaf:tree.leaves())for(var path:leaf.paths())if(!path.location().startsWith("jrt:"))
            inventory.observe(scan,Path.of(path.location()),path.gav(),"jar",leaf.cacheKey(),leaf.binarySha256(),
                    new RocksArtifactInventory.Stamp(path.stamp().size(),path.stamp().modifiedNanos(),path.stamp().modifiedNanos(),path.stamp().fileKey()));
        machineStore.commit(tree);
        machine.committed(tree);
    }

    @Override public long beginScan()throws Exception{return inventory.beginScan();}

    @Override public void observe(long scanGeneration,IndexStore.ArtifactInput input)throws Exception{
        if(scanGeneration<=0||!input.context().kind().equals("jar"))return;
        Path path=Path.of(input.context().path());
        if(!Files.isRegularFile(path))return;
        inventory.observe(scanGeneration,path,input.context().gav(),input.context().kind(),
                input.key().cacheKey(),input.key().binarySha256(),RocksArtifactInventory.Stamp.read(path));
    }

    @Override public Set<String> completeScan(long scanGeneration)throws Exception{
        if(scanGeneration<=0)return Set.of();
        return inventory.completeScan(scanGeneration);
    }

    @Override public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("backend","rocksdb-sst");result.put("generation",generation.getFileName().toString());
        result.put("machine_root",machine.root().map(root->root.identity().hex()).orElse(""));
        result.put("machine_leaves",machine.tree().size());result.put("machine_store",machineStore.status());
        result.put("native_memory",memory.status());result.putAll(admission.status());result.putAll(semanticState.status());
        try{
            result.put("repository",repository.status());
            result.put("inventory_entries",inventory.entries().size());
        }catch(Exception e){result.put("repository_error",e.toString());}
        return Map.copyOf(result);
    }

    @Override public synchronized void close()throws Exception{
        if(closed)return;
        // A store that cannot drain must retain the resources used by its active builders.
        store.close();closed=true;
        Exception failure=null;
        for(var resource:List.<AutoCloseable>of(semanticState,inventory,machineStore,repository,memory))try{resource.close();}
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
