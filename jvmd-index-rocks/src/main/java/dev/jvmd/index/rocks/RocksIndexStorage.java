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
            long started=dev.jvmd.core.BootEvents.nanos();
            repository=new RocksArtifactRepository(root,memory);opened.add(repository);
            dev.jvmd.core.BootEvents.timed("open.repository_db",started);started=dev.jvmd.core.BootEvents.nanos();
            inventory=new RocksArtifactInventory(root.resolve("inventory"),memory);opened.add(inventory);
            dev.jvmd.core.BootEvents.timed("open.inventory_db",started);started=dev.jvmd.core.BootEvents.nanos();
            semanticState=new RocksIndexSemanticState(root,memory);opened.add(semanticState);
            dev.jvmd.core.BootEvents.timed("open.semantic_state_dbs",started);started=dev.jvmd.core.BootEvents.nanos();
            store=new RocksIndexStore(root.resolve("store"),repository,memory,admission);opened.add(store);
            dev.jvmd.core.BootEvents.timed("open.metadata_store",started);
        }catch(Exception|LinkageError error){
            Collections.reverse(opened);for(var item:opened)try{item.close();}catch(Exception close){error.addSuppressed(close);}throw error;
        }
        this.migration=migration;this.candidateGeneration=candidateGeneration;
        dev.jvmd.core.BootEvents.provider("inventory.entries",()->inventory.entries().stream().map(entry->java.util.Arrays.asList(entry.path(),entry.gav(),entry.kind(),
                entry.cacheKey(),entry.binarySha256(),entry.stamp().size(),entry.stamp().modifiedNanos())).toList());
        dev.jvmd.core.BootEvents.provider("migration",()->{
            try{var manifest=migration==null?null:migration.manifest();
                return Map.of("candidate",String.valueOf(candidateGeneration),"active",manifest==null?"":manifest.active(),
                        "validated",migration!=null&&candidateGeneration!=null&&migration.validated(candidateGeneration));}
            catch(Exception e){return Map.of("error",e.toString());}
        });
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
        long started=dev.jvmd.core.BootEvents.nanos();
        inventory.observe(scanGeneration,path,input.context().gav(),input.context().kind(),
                input.key().cacheKey(),input.key().binarySha256(),RocksArtifactInventory.Stamp.read(path));
        dev.jvmd.core.BootEvents.timed("inventory.observe",started);
    }

    @Override public Set<String> completeScan(long scanGeneration)throws Exception{
        if(scanGeneration<=0)return Set.of();
        long started=dev.jvmd.core.BootEvents.nanos();
        Set<String> unreferenced=inventory.completeScan(scanGeneration);
        dev.jvmd.core.BootEvents.timed("inventory.complete_scan",started);
        if(migration!=null&&candidateGeneration!=null){
            if(!migration.validated(candidateGeneration)){
                started=dev.jvmd.core.BootEvents.nanos();
                validateCandidate();
                dev.jvmd.core.BootEvents.timed("activation.validate_candidate",started);
                migration.markValidated(candidateGeneration);
            }
            started=dev.jvmd.core.BootEvents.nanos();
            migration.activate(candidateGeneration);
            migration.pruneObsolete();
            dev.jvmd.core.BootEvents.timed("activation.activate_and_prune",started);
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



    /** The active generation is this format's generation only after a completed, validated scan activated it. */
    @Override public boolean scanCompleted(){
        if(migration==null||candidateGeneration==null)return false;
        try{return migration.manifest().active().equals(candidateGeneration)&&migration.validated(candidateGeneration);}
        catch(Exception unreadable){return false;}
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
