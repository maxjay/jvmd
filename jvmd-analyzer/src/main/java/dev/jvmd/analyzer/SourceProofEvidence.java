package dev.jvmd.analyzer;

import dev.jvmd.index.*;
import java.nio.file.Path;
import java.util.*;

final class SourceProofEvidence {
    long transitions,leavesPublished,consumersVisited,consumersChanged,consumersEqual,consumersFallback,consumersDeferred,
            coarseFiles,sourceConsumersInvalidated,preProofDependantInvalidations;
    long lastLeavesPublished,lastConsumersVisited,lastConsumersChanged,lastConsumersEqual,lastConsumersFallback,lastConsumersDeferred,
            lastCoarseFiles,lastSourceConsumersInvalidated,lastPreProofDependantInvalidations;
    String lastCoverageFile="";
    List<String> lastCoverageFailures=List.of();
    final Map<String,List<String>> coverageFailuresByFile=new TreeMap<>();
    void coverage(Path file,Collection<String> failures){
        lastCoverageFile=file.toAbsolutePath().normalize().toString();
        lastCoverageFailures=List.copyOf(failures);
        if(failures.isEmpty())coverageFailuresByFile.remove(lastCoverageFile);
        else coverageFailuresByFile.put(lastCoverageFile,lastCoverageFailures);
    }
    /**
     * Source files whose API a receiver-lookup proof binds: the receiver and every ancestor reached
     * through its hierarchy. A dependency javac completed only to walk that hierarchy is covered by
     * the proof and must not count as uncovered.
     */
    static void coverHierarchy(SemanticReadView view,String type,Set<Path> covered)throws Exception{
        var queue=new ArrayDeque<String>();queue.add(type);var seen=new HashSet<String>();
        while(!queue.isEmpty()){
            var symbol=view.symbol(queue.removeFirst());if(symbol==null||!seen.add(symbol.id()))continue;
            if(symbol.sourceFile()!=null)try{covered.add(Path.of(symbol.sourceFile()).toAbsolutePath().normalize());}catch(RuntimeException ignored){}
            for(var parent:symbol.directSupertypes())if(parent instanceof SemanticType.Declared declared){
                var resolved=view.type(declared.name().replace((char)36,'.'));if(resolved!=null)queue.add(resolved.id());
            }
        }
    }
    void beginMutation(int dependantInvalidations){
        lastPreProofDependantInvalidations=dependantInvalidations;
        preProofDependantInvalidations+=dependantInvalidations;
    }
    void record(int leaves,SemanticUpdatePolicy.ProofPropagation propagation,int leafEqualStops,int coarse,int sourceInvalidated){
        transitions++;lastLeavesPublished=leaves;lastConsumersVisited=propagation.recomputed().size();
        lastConsumersChanged=propagation.changed().size();lastConsumersEqual=leafEqualStops+propagation.equal().size();
        lastConsumersFallback=propagation.fallback().size();lastConsumersDeferred=propagation.deferred().size();
        lastCoarseFiles=coarse;lastSourceConsumersInvalidated=sourceInvalidated;consumersDeferred+=lastConsumersDeferred;
        leavesPublished+=lastLeavesPublished;consumersVisited+=lastConsumersVisited;consumersChanged+=lastConsumersChanged;
        consumersEqual+=lastConsumersEqual;consumersFallback+=lastConsumersFallback;coarseFiles+=coarse;
        sourceConsumersInvalidated+=sourceInvalidated;
    }
    Map<String,Object> status(){
        return Map.ofEntries(
                Map.entry("transitions",transitions),Map.entry("leaves_published",leavesPublished),
                Map.entry("proof_consumers_visited",consumersVisited),Map.entry("proof_consumers_recomputed",consumersVisited),
                Map.entry("proof_consumers_changed",consumersChanged),Map.entry("proof_consumers_stopped_equal",consumersEqual),
                Map.entry("proof_consumers_fallback",consumersFallback),Map.entry("coarse_fallback_files",coarseFiles),
                Map.entry("proof_consumers_deferred",consumersDeferred),Map.entry("last_proof_consumers_deferred",lastConsumersDeferred),
                Map.entry("source_consumers_invalidated",sourceConsumersInvalidated),
                Map.entry("pre_proof_dependant_invalidations",preProofDependantInvalidations),
                Map.entry("last_leaves_published",lastLeavesPublished),Map.entry("last_proof_consumers_visited",lastConsumersVisited),
                Map.entry("last_proof_consumers_recomputed",lastConsumersVisited),Map.entry("last_proof_consumers_changed",lastConsumersChanged),
                Map.entry("last_proof_consumers_stopped_equal",lastConsumersEqual),
                Map.entry("last_proof_consumers_fallback",lastConsumersFallback),Map.entry("last_coarse_fallback_files",lastCoarseFiles),
                Map.entry("last_source_consumers_invalidated",lastSourceConsumersInvalidated),
                Map.entry("last_pre_proof_dependant_invalidations",lastPreProofDependantInvalidations),
                Map.entry("last_coverage_file",lastCoverageFile),
                Map.entry("last_coverage_failures",lastCoverageFailures),
                Map.entry("coverage_failures_by_file",Collections.unmodifiableMap(new TreeMap<>(coverageFailuresByFile))));
    }
}
