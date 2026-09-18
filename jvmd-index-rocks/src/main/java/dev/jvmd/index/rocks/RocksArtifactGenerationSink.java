package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.*;

/** Bounded caller-parallel publisher for immutable RocksDB artifact generations. */
public final class RocksArtifactGenerationSink implements ArtifactGenerationSink {
    private static final long UNIT=1024L*1024L;
    private final RocksArtifactRepository repository;
    private final RocksArtifactInventory inventory;
    private final RocksMigrationManager migration;
    private final String candidateGeneration;
    private final RocksWorkspaceResolver workspaceResolver;
    private final RocksSemanticInvalidation semanticInvalidation;
    private final java.util.concurrent.ConcurrentHashMap<String,RocksWorkspaceResolver.Workspace> workspaces=new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong semanticUpdates=new AtomicLong(),semanticReanalyze=new AtomicLong(),semanticApiChanges=new AtomicLong(),semanticBodyOnly=new AtomicLong();
    private volatile Map<String,Object> lastSemanticResult=Map.of();
    private final Semaphore budget;
    private final int totalUnits;
    private final AtomicLong published=new AtomicLong(),reused=new AtomicLong(),waitNanos=new AtomicLong(),validationFailures=new AtomicLong();
    private final AtomicInteger unitsInFlight=new AtomicInteger(),peakUnits=new AtomicInteger();

    public RocksArtifactGenerationSink(Path root,long maxEstimatedBytes)throws Exception{
        this(root,maxEstimatedBytes,null,null);
    }

    RocksArtifactGenerationSink(Path root,long maxEstimatedBytes,RocksMigrationManager migration,String candidateGeneration)throws Exception{
        if(maxEstimatedBytes<UNIT)throw new IllegalArgumentException("maxEstimatedBytes must be at least 1 MiB");
        this.repository=new RocksArtifactRepository(root);
        this.inventory=new RocksArtifactInventory(root.resolve("inventory"));
        this.workspaceResolver=new RocksWorkspaceResolver(root.resolve("workspace-resolution"),repository,inventory);
        this.semanticInvalidation=new RocksSemanticInvalidation(root.resolve("semantic-state"));
        this.migration=migration;this.candidateGeneration=candidateGeneration;
        this.totalUnits=(int)Math.min(Integer.MAX_VALUE,Math.max(1,(maxEstimatedBytes+UNIT-1)/UNIT));
        this.budget=new Semaphore(totalUnits,true);
    }

    @Override public void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        int units=Math.min(totalUnits,Math.max(1,(int)((estimatedBytes(facts,classReferences)+UNIT-1)/UNIT)));
        long waiting=System.nanoTime();
        try{budget.acquire(units);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        waitNanos.addAndGet(System.nanoTime()-waiting);
        int active=unitsInFlight.addAndGet(units);peakUnits.accumulateAndGet(active,Math::max);
        try{
            var result=repository.publish(facts,classReferences);
            if(result.reused())reused.incrementAndGet();else published.incrementAndGet();
        }finally{
            unitsInFlight.addAndGet(-units);budget.release(units);
        }
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
        Set<String> unreferenced=inventory.completeScan(scanGeneration);
        if(migration!=null&&candidateGeneration!=null){
            validateCandidate();
            migration.markValidated(candidateGeneration);
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
                if(!repository.verify(entry.cacheKey()))failures.add(entry.path()+": verification failed");
                else{
                    var facts=repository.artifact(entry.cacheKey());
                    if(facts==null||!facts.key().binarySha256().equals(entry.binarySha256()))
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



    @Override public boolean needsDocumentation(String binaryCacheKey)throws Exception{
        var docs=inventory.documentation(binaryCacheKey);
        return docs.isEmpty();
    }

    @Override public void publishDocumentation(String binaryCacheKey,IndexStore.ArtifactInput sourceInput,
                                               Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception{
        String docsKey=repository.publishDocumentation(binaryCacheKey,sourceInput.key(),members,unmatchedMembers);
        inventory.setDocumentation(binaryCacheKey,docsKey);
    }


    @Override public void publishSourceState(SourceIndexPublisher.Delta delta)throws Exception{
        if(!delta.hasSemanticState())return;
        var input=new RocksSemanticInvalidation.FileInput(delta.sourceHash(),delta.apiFingerprint(),
                delta.dependencies(),delta.exportedNames(),delta.unresolvedTargets());
        var result=semanticInvalidation.observeFile(delta.moduleId(),delta.contextFingerprint(),delta.file(),input);
        semanticUpdates.incrementAndGet();semanticReanalyze.addAndGet(result.reanalyze().size());
        semanticApiChanges.addAndGet(result.apiChanged().size());semanticBodyOnly.addAndGet(result.bodyOnly().size());
        lastSemanticResult=Map.of(
                "module",delta.moduleId(),"file",delta.file().toString(),
                "reanalyze",result.reanalyze().stream().map(Path::toString).sorted().toList(),
                "api_changed",result.apiChanged().stream().map(Path::toString).sorted().toList(),
                "body_only",result.bodyOnly().stream().map(Path::toString).sorted().toList(),
                "context_changed",result.contextChanged());
    }

    @Override public void configureWorkspace(String workspace,List<IndexStore.WorkspaceEntry> paths,List<Map.Entry<String,String>> dependencies)throws Exception{
        var inventoryByPath=new HashMap<String,RocksArtifactInventory.Entry>();
        for(var entry:inventory.entries())inventoryByPath.put(Path.of(entry.path()).toAbsolutePath().normalize().toString(),entry);
        var classpath=new ArrayList<RocksWorkspaceResolver.Entry>();
        for(var item:paths){
            String normalized;
            try{normalized=Path.of(item.path()).toAbsolutePath().normalize().toString();}
            catch(Exception ignored){continue;}
            var inventoryEntry=inventoryByPath.get(normalized);if(inventoryEntry==null)continue;
            if(!repository.contains(inventoryEntry.cacheKey()))continue;
            var context=new ArtifactContext(inventoryEntry.gav(),inventoryEntry.kind(),inventoryEntry.path());
            classpath.add(new RocksWorkspaceResolver.Entry(inventoryEntry.cacheKey(),context,item.scope(),"",""));
        }
        String fingerprint=dev.jvmd.core.Hashing.sha256(dependencies.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        workspaces.put(workspace,new RocksWorkspaceResolver.Workspace(List.copyOf(classpath),fingerprint));
    }

    @Override public OptionalLong shadowCursor(String workspace,String scip)throws Exception{
        var configured=workspaces.get(workspace);return configured==null?OptionalLong.empty():workspaceResolver.cursorByScip(configured,scip);
    }

    @Override public Optional<List<Map<String,Object>>> shadowFind(String workspace,String query,boolean substring,int limit,long after,Set<String> kinds)throws Exception{
        var configured=workspaces.get(workspace);if(configured==null)return Optional.empty();
        List<RocksWorkspaceResolver.WorkspaceSymbol> values=substring
                ?workspaceResolver.findSubstring(configured,query,limit,after)
                :workspaceResolver.findExact(configured,query,limit,after);
        var result=new ArrayList<Map<String,Object>>();
        for(var value:values){
            if(!kinds.isEmpty()&&!kinds.contains(value.symbol().kind()))continue;
            var row=new LinkedHashMap<String,Object>();
            int classpathIndex=indexOf(configured,value.entry().artifactCacheKey());
            long cursor=RocksWorkspaceResolver.cursor(classpathIndex,value.symbol().id());
            row.put("id",cursor);row.put("artifact_id",(long)classpathIndex+1);
            row.put("owner_id",value.symbol().ownerId()<0?null:RocksWorkspaceResolver.cursor(classpathIndex,value.symbol().ownerId()));
            row.put("flags",value.symbol().flags());row.put("line",null);
            row.put("source_start",-1);row.put("source_end",-1);row.put("body_start",-1);row.put("body_end",-1);
            row.put("scip",value.scip());row.put("kind",value.symbol().kind());row.put("name",value.symbol().name());
            row.put("name_path",value.namePath());row.put("signature",value.symbol().signature());
            row.put("erased_descriptor",value.symbol().descriptor());row.put("source_file",null);row.put("doc",null);
            row.put("fqn",value.symbol().fqn());row.put("binary_key",value.symbol().key());row.put("class_entry",value.symbol().entry());
            row.put("gav",value.entry().context().gav());row.put("artifact_path",value.entry().context().path());
            row.put("artifact_kind",value.entry().context().kind());row.put("parameters",value.symbol().parameters());
            row.put("metadata",dev.jvmd.core.Json.MAPPER.readTree(value.symbol().metadataJson()));row.put("tier",2);
            row.putAll(value.sourceData());
            result.add(Collections.unmodifiableMap(row));if(result.size()>=limit)break;
        }
        return Optional.of(List.copyOf(result));
    }


    @Override public Optional<Set<String>> shadowRelationships(String workspace,Collection<String> scips,boolean outgoing,Set<String> kinds,int limit)throws Exception{
        var configured=workspaces.get(workspace);if(configured==null)return Optional.empty();
        var edges=new LinkedHashSet<String>();
        for(String scip:scips){
            if(edges.size()>=limit)break;
            var selected=workspaceResolver.byScip(configured,scip);if(selected.isEmpty())continue;
            var source=selected.get();
            if(outgoing){
                var rows=workspaceResolver.outgoing(configured,source.entry().artifactCacheKey(),source.symbol().id(),kinds,limit-edges.size());
                for(var row:rows){
                    var target=workspaceResolver.resolvedSymbol(configured,row.target());if(target.isEmpty())continue;
                    edges.add(source.scip()+"|"+row.kind()+"|"+target.get().scip());
                    if(edges.size()>=limit)break;
                }
            }else{
                var rows=workspaceResolver.incoming(configured,source.symbol().key(),kinds,limit-edges.size());
                for(var row:rows){
                    var from=workspaceResolver.resolvedSymbol(configured,
                            new RocksWorkspaceResolver.ResolvedSymbol(row.sourceArtifactCacheKey(),row.sourceLocalId(),
                                    indexOf(configured,row.sourceArtifactCacheKey())));
                    if(from.isEmpty())continue;
                    edges.add(from.get().scip()+"|"+row.kind()+"|"+source.scip());
                    if(edges.size()>=limit)break;
                }
            }
        }
        return Optional.of(Set.copyOf(edges));
    }

    private static int indexOf(RocksWorkspaceResolver.Workspace workspace,String artifactCacheKey){
        for(int i=0;i<workspace.classpath().size();i++)if(workspace.classpath().get(i).artifactCacheKey().equals(artifactCacheKey))return i;
        return -1;
    }

    @Override public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>();
        result.put("backend","rocksdb-sst");result.put("published",published.get());result.put("reused",reused.get());
        result.put("validation_failures",validationFailures.get());result.put("shadow_workspaces",workspaces.size());
        result.put("workspace_resolution",workspaceResolver.status());
        result.put("semantic_state",Map.of("updates",semanticUpdates.get(),"reanalyze_total",semanticReanalyze.get(),
                "api_changes",semanticApiChanges.get(),"body_only",semanticBodyOnly.get(),"last",lastSemanticResult));
        result.put("budget_bytes",(long)totalUnits*UNIT);result.put("estimated_bytes_in_flight",(long)unitsInFlight.get()*UNIT);
        result.put("peak_estimated_bytes_in_flight",(long)peakUnits.get()*UNIT);result.put("budget_wait_ms",Math.round(waitNanos.get()/1000.0)/1000.0);
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

    private static long estimatedBytes(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences){
        return UNIT+facts.symbols().size()*192L+facts.relationships().size()*96L+classReferences.size()*64L;
    }

    @Override public void close(){
        try{workspaceResolver.close();}
        finally{try{semanticInvalidation.close();}finally{try{inventory.close();}finally{repository.close();}}}
    }
}
