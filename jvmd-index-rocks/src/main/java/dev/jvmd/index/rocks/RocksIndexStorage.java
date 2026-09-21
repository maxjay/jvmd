package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Owns native resources and validated format activation; publication belongs to RocksIndexStore. */
public final class RocksIndexStorage implements IndexStorage,ArtifactInventory {
    private final RocksMemory memory;
    private final RocksArtifactRepository repository;
    private final RocksArtifactInventory inventory;
    private final RocksIndexSemanticState semanticState;
    private final RocksArtifactAdmission admission;
    private final RocksIndexStore store;
    private final RocksMigrationManager migration;
    private final String candidateGeneration;
    private final AtomicLong validationFailures=new AtomicLong();
    private boolean closed;

    public RocksIndexStorage(Path root,long maxEstimatedBytes)throws Exception{this(root,maxEstimatedBytes,null,null);}

    RocksIndexStorage(Path root,long maxEstimatedBytes,RocksMigrationManager migration,String candidateGeneration)throws Exception{
        admission=new RocksArtifactAdmission(maxEstimatedBytes);
        memory=new RocksMemory(Math.multiplyExact(Long.getLong("jvmd.index.native_budget_mb",64L),1024L*1024L));
        var opened=new ArrayList<AutoCloseable>();opened.add(memory);
        try{
            repository=new RocksArtifactRepository(root,memory);opened.add(repository);
            inventory=new RocksArtifactInventory(root.resolve("inventory"),memory);opened.add(inventory);
            semanticState=new RocksIndexSemanticState(root,memory);opened.add(semanticState);
            store=new RocksIndexStore(root.resolve("store"),repository,memory,admission);opened.add(store);
        }catch(Exception|LinkageError error){
            Collections.reverse(opened);for(var item:opened)try{item.close();}catch(Exception close){error.addSuppressed(close);}throw error;
        }
        this.migration=migration;this.candidateGeneration=candidateGeneration;
    }
    @Override public IndexStore store(){return store;}
    @Override public ArtifactInventory inventory(){return this;}
    @Override public IndexSemanticState semanticState(){return semanticState;}
    @Override public ArtifactAdmission admission(){return admission;}

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
        Set<String> unreferenced=inventory.completeScan(scanGeneration);
        if(migration!=null&&candidateGeneration!=null){
            if(!migration.validated(candidateGeneration)){
                validateCandidate();
                migration.markValidated(candidateGeneration);
            }
            migration.activate(candidateGeneration);
            migration.pruneObsolete();
        }
        return unreferenced;
    }

    private void validateCandidate()throws Exception{
        var failures=new ArrayList<String>();
        var seen=new HashSet<String>();
        for(var entry:inventory.entries()){
            if(!seen.add(entry.cacheKey()))continue;
            try{
                if(!repository.verifyForActivation(entry.cacheKey()))failures.add(entry.path()+": verification failed");
                else{
                    var identity=repository.artifactKey(entry.cacheKey());
                    if(identity==null||!identity.binarySha256().equals(entry.binarySha256()))
                        failures.add(entry.path()+": binary identity mismatch");
                }
            }catch(Exception e){
                failures.add(entry.path()+": "+e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),""));
            }
            if(failures.size()>=20)break;
        }
        if(!failures.isEmpty()){
            validationFailures.incrementAndGet();
            throw new IllegalStateException("Rocks candidate validation failed: "+String.join("; ",failures));
        }
    }



    @Override public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("backend","rocksdb-sst");result.put("validation_failures",validationFailures.get());
        result.put("native_memory",memory.status());result.putAll(admission.status());result.putAll(semanticState.status());
        try{
            result.put("repository",repository.status());
            result.put("inventory_entries",inventory.entries().size());
            if(migration!=null){
                var manifest=migration.manifest();
                result.put("candidate_generation",candidateGeneration);
                result.put("active_generation",manifest.active());
                result.put("previous_generation",manifest.previous());
                result.put("generation_pins",migration.pins());
            }
        }catch(Exception e){result.put("repository_error",e.toString());}
        return Map.copyOf(result);
    }

    @Override public synchronized void close()throws Exception{
        if(closed)return;
        // A store that cannot drain must retain the resources used by its active builders.
        store.close();closed=true;
        Exception failure=null;
        for(var resource:List.<AutoCloseable>of(semanticState,inventory,repository,memory))try{resource.close();}
        catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
}
