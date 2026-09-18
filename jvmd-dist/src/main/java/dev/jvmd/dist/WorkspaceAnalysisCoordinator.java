package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.lang.management.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Workspace diagnostics maintain incrementally valid state and parallelize only isolated module actors. */
public final class WorkspaceAnalysisCoordinator {
    @FunctionalInterface public interface Provider { DiagnosticEngine forFile(Path file)throws Exception; }
    @FunctionalInterface public interface ExternalDiagnostics { ExternalResult forFile(Path file)throws Exception; }
    public record ExternalResult(List<CompilerPool.Problem> diagnostics,String provenance) {
        public ExternalResult { diagnostics=List.copyOf(diagnostics); }
    }
    private record Plan(DiagnosticEngine engine,List<Path> stale,int groupSize,boolean batch){}
    private record AnalysisResult(Map<Path,Envelope> values,int analysed,int batches,int incrementalModules){}
    private final Documents documents;
    private final Provider analyzers;
    private final Runnable yield;
    private final ExternalDiagnostics external;
    private final int parallelism;
    private final Map<Path,String> observed=new HashMap<>();
    private Set<Path> inventory=Set.of();
    private Map<String,Object> last=Map.of();
    private long generation,requests,totalAnalyses,totalBatches;

    public WorkspaceAnalysisCoordinator(Documents documents,Provider analyzers){this(documents,analyzers,()->{},_->null,1);}
    public WorkspaceAnalysisCoordinator(Documents documents,Provider analyzers,Runnable yield){this(documents,analyzers,yield,_->null,1);}
    public WorkspaceAnalysisCoordinator(Documents documents,Provider analyzers,Runnable yield,ExternalDiagnostics external){this(documents,analyzers,yield,external,1);}
    public WorkspaceAnalysisCoordinator(Documents documents,Provider analyzers,Runnable yield,ExternalDiagnostics external,int parallelism){
        this.documents=documents;this.analyzers=analyzers;this.yield=yield;this.external=external;this.parallelism=Math.max(1,Math.min(4,parallelism));
    }

    public Envelope get(List<Path> requested,boolean wholeWorkspace,int offset,int limit,List<String> initialWarnings)throws Exception{
        long started=System.nanoTime();requests++;long documentGeneration=documents.generation();
        var files=requested.stream().map(p->p.toAbsolutePath().normalize()).distinct().sorted().toList();
        var warnings=new LinkedHashSet<>(initialWarnings);
        var values=new LinkedHashMap<Path,Envelope>();var identities=new LinkedHashMap<Path,String>();
        long validationStart=System.nanoTime();
        if(wholeWorkspace){
            var current=Set.copyOf(files);
            if(!inventory.isEmpty()&&!inventory.equals(current)&&!files.isEmpty()){
                analyzers.forFile(files.getFirst()).namespaceChanged();generation++;
            }
            inventory=current;observed.keySet().retainAll(current);
        }
        for(Path file:files)identities.put(file,documents.sourceHash(file));
        double validationMs=millis(validationStart);

        long prepareStart=System.nanoTime();
        var enginesByFile=new LinkedHashMap<Path,DiagnosticEngine>();
        var enginesByGroup=new LinkedHashMap<String,DiagnosticEngine>();
        var grouped=new LinkedHashMap<String,List<Path>>();
        for(Path file:files){
            var engine=analyzers.forFile(file);enginesByFile.put(file,engine);String key=engine.contextKey();
            enginesByGroup.putIfAbsent(key,engine);grouped.computeIfAbsent(key,_->new ArrayList<>()).add(file);
        }
        double prepareMs=millis(prepareStart);

        resetHeapPeaks();long gcBefore=gcCollections();
        var cpuBefore=new IdentityHashMap<DiagnosticEngine,Long>();for(var engine:enginesByGroup.values())cpuBefore.put(engine,engine.cpuNanos());
        long analysisStart=System.nanoTime();int analysed=0,reused=0,batches=0,incrementalModules=0;

        var prerequisites=new LinkedHashSet<Path>();
        for(Path file:files)prerequisites.addAll(enginesByFile.get(file).pendingPrerequisites(file));
        for(Path prerequisite:prerequisites)if(!files.contains(prerequisite)&&!documents.sourceHash(prerequisite).equals("missing")){
            analyzers.forFile(prerequisite).diagnostics(prerequisite,documents);analysed++;
        }
        // Resolve changed APIs before dependent cache validation, independent of filename/module order.
        for(Path file:files){String hash=identities.get(file),previous=observed.get(file);
            if(previous!=null&&!previous.equals(hash)){enginesByFile.get(file).changed(file,hash);generation++;}
        }
        for(Path file:files){String previous=observed.get(file);
            if(previous!=null&&!previous.equals(identities.get(file))){values.put(file,enginesByFile.get(file).diagnostics(file,documents));analysed++;}
        }

        var plans=new ArrayList<Plan>();
        for(var entry:grouped.entrySet()){
            var engine=enginesByGroup.get(entry.getKey());var stale=new ArrayList<Path>();
            for(Path file:entry.getValue())if(!values.containsKey(file)){
                var cached=engine.cachedDiagnostics(file,documents);
                if(cached==null)stale.add(file);else{values.put(file,cached);reused++;}
            }
            if(!stale.isEmpty())plans.add(new Plan(engine,List.copyOf(stale),entry.getValue().size(),stale.size()>=16&&stale.size()*4>=entry.getValue().size()));
        }

        boolean isolated=plans.size()>1&&plans.stream().map(Plan::engine).allMatch(DiagnosticEngine::isolated)
                &&Collections.newSetFromMap(new IdentityHashMap<DiagnosticEngine,Boolean>()).addAll(plans.stream().map(Plan::engine).toList());
        int actorsUsed=isolated?Math.min(parallelism,plans.size()):1;
        var superseded=new AtomicBoolean(false);
        if(actorsUsed>1){
            var request=RequestScope.current();
            try(var executor=Executors.newFixedThreadPool(actorsUsed,Thread.ofVirtual().name("jvmd-module-dispatch-",0).factory())){
                var futures=new ArrayList<Future<AnalysisResult>>();
                for(var plan:plans)futures.add(executor.submit(()->RequestScope.with(request,()->analyse(plan,superseded))));
                while(futures.stream().anyMatch(f->!f.isDone())){
                    yield.run();
                    if(documentGeneration!=documents.generation())superseded.set(true);
                    LockSupport.parkNanos(200_000);
                }
                for(var future:futures){
                    var result=get(future);values.putAll(result.values());analysed+=result.analysed();batches+=result.batches();incrementalModules+=result.incrementalModules();
                }
            }
        }else for(var plan:plans){
            var result=analyse(plan,superseded);values.putAll(result.values());analysed+=result.analysed();batches+=result.batches();incrementalModules+=result.incrementalModules();
            yield.run();if(documentGeneration!=documents.generation())superseded.set(true);
        }
        if(superseded.get()||documentGeneration!=documents.generation()){
            RequestScope.clearMemo();warnings.add("diagnostics_superseded: documents changed during workspace refresh; request diagnostics again");
            return new Envelope(1,"live",false,null,List.copyOf(warnings),Map.of("diagnostics",List.of()));
        }

        double analysisMs=millis(analysisStart);long mergeStart=System.nanoTime();
        var diagnostics=new ArrayList<CompilerPool.Problem>();int tier=2,supersededFiles=0,highFidelityFiles=0;
        for(Path file:files){
            if(!identities.get(file).equals(documents.sourceHash(file))){
                enginesByFile.get(file).changed(file,documents.sourceHash(file));warnings.add("diagnostics_superseded: "+file);supersededFiles++;continue;
            }
            var value=values.get(file);if(value==null)continue;
            observed.put(file,identities.get(file));tier=Math.min(tier,value.tier());warnings.addAll(value.warnings());
            var highFidelity=external.forFile(file);
            if(highFidelity!=null){
                highFidelityFiles++;warnings.add("diagnostic_fidelity="+highFidelity.provenance());diagnostics.addAll(highFidelity.diagnostics());
            }else for(Object item:(List<?>)((Map<?,?>)value.result()).get("diagnostics"))diagnostics.add((CompilerPool.Problem)item);
        }
        diagnostics.sort(Comparator.comparing((CompilerPool.Problem p)->Objects.toString(p.file(),""))
                .thenComparingLong(CompilerPool.Problem::line).thenComparingLong(CompilerPool.Problem::character)
                .thenComparing(CompilerPool.Problem::code).thenComparing(CompilerPool.Problem::message));
        var unique=List.copyOf(new LinkedHashSet<>(diagnostics));double mergeMs=millis(mergeStart);
        long pageStart=System.nanoTime();int from=Math.min(offset,unique.size()),to=Math.min(unique.size(),from+limit);
        var page=List.copyOf(unique.subList(from,to));boolean more=to<unique.size();
        totalAnalyses+=analysed;totalBatches+=batches;

        long cpuNanos=0;for(var engine:enginesByGroup.values())cpuNanos+=Math.max(0,engine.cpuNanos()-cpuBefore.getOrDefault(engine,0L));
        var metrics=new LinkedHashMap<String,Object>();metrics.put("total_ms",millis(started));metrics.put("context_prepare_ms",prepareMs);metrics.put("source_validation_ms",validationMs);
        metrics.put("analysis_ms",analysisMs);metrics.put("analysis_cpu_ms",Math.round(cpuNanos/1000.0)/1000.0);metrics.put("snapshot_merge_ms",mergeMs);metrics.put("pagination_ms",millis(pageStart));
        metrics.put("peak_heap_bytes",peakHeap());metrics.put("gc_collections",Math.max(0,gcCollections()-gcBefore));
        metrics.put("actor_parallelism_configured",parallelism);metrics.put("actor_parallelism_used",actorsUsed);
        metrics.put("files_total",files.size());metrics.put("files_valid",reused);metrics.put("files_reanalysed",analysed);metrics.put("modules_total",grouped.size());metrics.put("modules_batch_analysed",batches);
        metrics.put("modules_incrementally_analysed",incrementalModules);metrics.put("superseded",supersededFiles);metrics.put("high_fidelity_files",highFidelityFiles);last=Map.copyOf(metrics);
        return new Envelope(tier,"live",more,more?Integer.toString(to):null,List.copyOf(warnings),Map.of("diagnostics",page));
    }

    private AnalysisResult analyse(Plan plan,AtomicBoolean superseded)throws Exception{
        var values=new LinkedHashMap<Path,Envelope>();int analysed=0,batches=0,incremental=0;
        if(plan.batch()){
            int position=0;
            while(position<plan.stale().size()&&!superseded.get()){
                var inputs=new LinkedHashMap<Path,String>();long bytes=0;
                while(position<plan.stale().size()&&inputs.size()<128&&bytes<4L*1024*1024){
                    Path file=plan.stale().get(position++);String text=documents.text(file);inputs.put(file,text);bytes+=2L*text.length();
                }
                values.putAll(plan.engine().diagnosticsBatch(inputs));batches++;analysed+=inputs.size();
            }
        }else{
            incremental=1;
            for(Path file:plan.stale())if(!superseded.get()){values.put(file,plan.engine().diagnostics(file,documents));analysed++;}
        }
        return new AnalysisResult(Map.copyOf(values),analysed,batches,incremental);
    }
    private static <T>T get(Future<T> future)throws Exception{
        try{return future.get();}
        catch(ExecutionException error){
            if(error.getCause() instanceof Exception exception)throw exception;
            if(error.getCause() instanceof Error fatal)throw fatal;
            throw new IllegalStateException(error.getCause());
        }
    }
    private static double millis(long start){return (System.nanoTime()-start)/1e6;}
    private static long gcCollections(){long value=0;for(var bean:ManagementFactory.getGarbageCollectorMXBeans())if(bean.getCollectionCount()>0)value+=bean.getCollectionCount();return value;}
    private static void resetHeapPeaks(){for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)try{pool.resetPeakUsage();}catch(RuntimeException ignored){}}
    private static long peakHeap(){long value=0;for(var pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP&&pool.getPeakUsage()!=null)value+=Math.max(0,pool.getPeakUsage().getUsed());return value;}
    public Map<String,Object> status(){return Map.of("requests",requests,"generation",generation,"files",observed.size(),"analyses",totalAnalyses,"batch_analyses",totalBatches,"last",last);}
}
