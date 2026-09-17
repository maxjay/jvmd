package dev.jvmd.dist;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import java.nio.file.Path;
import java.util.*;

/** Workspace diagnostics maintain file states; requests merge only currently valid results. */
public final class WorkspaceAnalysisCoordinator {
    @FunctionalInterface public interface Provider { Analyzer forFile(Path file)throws Exception; }
    private final Documents documents;
    private final Provider analyzers;
    private final Runnable yield;
    private final Map<Path,String> observed=new HashMap<>();
    private Set<Path> inventory=Set.of();
    private Map<String,Object> last=Map.of();
    private long generation,requests,totalAnalyses,totalBatches;
    public WorkspaceAnalysisCoordinator(Documents documents,Provider analyzers){this(documents,analyzers,()->{});}
    public WorkspaceAnalysisCoordinator(Documents documents,Provider analyzers,Runnable yield){this.documents=documents;this.analyzers=analyzers;this.yield=yield;}

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
        var grouped=new LinkedHashMap<String,List<Path>>();
        for(Path file:files){var analyzer=analyzers.forFile(file);grouped.computeIfAbsent(analyzer.contextKey(),_->new ArrayList<>()).add(file);}
        double prepareMs=millis(prepareStart);
        long analysisStart=System.nanoTime();int analysed=0,reused=0,batches=0,incrementalModules=0;
        var prerequisites=new LinkedHashSet<Path>();
        for(Path file:files)prerequisites.addAll(analyzers.forFile(file).pendingPrerequisites(file));
        for(Path prerequisite:prerequisites)if(!files.contains(prerequisite)&&!documents.sourceHash(prerequisite).equals("missing")){
            analyzers.forFile(prerequisite).diagnostics(prerequisite,documents);analysed++;
        }
        // Establish changed APIs before testing dependants, independent of filename order.
        for(Path file:files){String hash=identities.get(file),previous=observed.get(file);
            if(previous!=null&&!previous.equals(hash)){
                var analyzer=analyzers.forFile(file);analyzer.changed(file,hash);generation++;
            }
        }
        for(Path file:files){String previous=observed.get(file);
            if(previous!=null&&!previous.equals(identities.get(file))){
                values.put(file,analyzers.forFile(file).diagnostics(file,documents));analysed++;
            }
        }
        for(var group:grouped.values()){
            var stale=new ArrayList<Path>();
            for(Path file:group)if(!values.containsKey(file)){
                var cached=analyzers.forFile(file).cachedDiagnostics(file,documents);
                if(cached==null)stale.add(file);else{values.put(file,cached);reused++;}
            }
            if(stale.size()>=16&&stale.size()*4>=group.size()){
                int position=0;
                while(position<stale.size()){
                    var inputs=new LinkedHashMap<Path,String>();long bytes=0;
                    while(position<stale.size()&&inputs.size()<128&&bytes<4L*1024*1024){
                        Path file=stale.get(position++);String text=documents.text(file);inputs.put(file,text);bytes+=2L*text.length();
                    }
                    values.putAll(analyzers.forFile(inputs.keySet().iterator().next()).diagnosticsBatch(inputs));
                    batches++;analysed+=inputs.size();
                    yield.run();
                    if(documentGeneration!=documents.generation()){
                        RequestScope.clearMemo();
                        warnings.add("diagnostics_superseded: documents changed during workspace refresh; request diagnostics again");
                        return new Envelope(1,"live",false,null,List.copyOf(warnings),Map.of("diagnostics",List.of()));
                    }
                }
            }else if(!stale.isEmpty()){
                incrementalModules++;
                for(Path file:stale){values.put(file,analyzers.forFile(file).diagnostics(file,documents));analysed++;}
            }
        }
        double analysisMs=millis(analysisStart);long mergeStart=System.nanoTime();
        var diagnostics=new ArrayList<CompilerPool.Problem>();int tier=2,superseded=0;
        for(Path file:files){
            // Filesystem changes are independent of the session executor; never relabel old results.
            if(!identities.get(file).equals(documents.sourceHash(file))){
                analyzers.forFile(file).changed(file,documents.sourceHash(file));warnings.add("diagnostics_superseded: "+file);superseded++;continue;
            }
            var value=values.get(file);if(value==null)continue;
            observed.put(file,identities.get(file));tier=Math.min(tier,value.tier());warnings.addAll(value.warnings());
            for(Object item:(List<?>)((Map<?,?>)value.result()).get("diagnostics"))diagnostics.add((CompilerPool.Problem)item);
        }
        diagnostics.sort(Comparator.comparing((CompilerPool.Problem p)->Objects.toString(p.file(),""))
                .thenComparingLong(CompilerPool.Problem::line).thenComparingLong(CompilerPool.Problem::character)
                .thenComparing(CompilerPool.Problem::code).thenComparing(CompilerPool.Problem::message));
        var unique=List.copyOf(new LinkedHashSet<>(diagnostics));double mergeMs=millis(mergeStart);
        long pageStart=System.nanoTime();int from=Math.min(offset,unique.size()),to=Math.min(unique.size(),from+limit);
        var page=List.copyOf(unique.subList(from,to));boolean more=to<unique.size();
        totalAnalyses+=analysed;totalBatches+=batches;
        var metrics=new LinkedHashMap<String,Object>();metrics.put("total_ms",millis(started));metrics.put("context_prepare_ms",prepareMs);metrics.put("source_validation_ms",validationMs);
        metrics.put("analysis_ms",analysisMs);metrics.put("snapshot_merge_ms",mergeMs);metrics.put("pagination_ms",millis(pageStart));
        metrics.put("files_total",files.size());metrics.put("files_valid",reused);metrics.put("files_reanalysed",analysed);metrics.put("modules_total",grouped.size());metrics.put("modules_batch_analysed",batches);
        metrics.put("modules_incrementally_analysed",incrementalModules);metrics.put("superseded",superseded);last=Map.copyOf(metrics);
        return new Envelope(tier,"live",more,more?Integer.toString(to):null,List.copyOf(warnings),Map.of("diagnostics",page));
    }
    private static double millis(long start){return (System.nanoTime()-start)/1e6;}
    public Map<String,Object> status(){return Map.of("requests",requests,"generation",generation,"files",observed.size(),"analyses",totalAnalyses,"batch_analyses",totalBatches,"last",last);}
}
