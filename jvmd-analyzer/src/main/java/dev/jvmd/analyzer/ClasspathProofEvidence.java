package dev.jvmd.analyzer;

import dev.jvmd.index.*;
import java.nio.file.Path;
import java.util.*;

final class ClasspathProofEvidence {
    long transitions,intervals,reconsidered,equal,changed,unavailable,consumersVisited,consumersChanged,consumersEqual,
            consumersFallback,consumersDeferred,broadContextInvalidations,coarseFallbacks,validatedInputReconciliations;
    List<String> lastIntervals=List.of();
    long lastReconsidered,lastEqual,lastChanged,lastUnavailable,lastConsumersVisited,lastConsumersChanged,lastConsumersEqual,
            lastConsumersFallback,lastConsumersDeferred,lastBroadContextInvalidations;
    void record(ClasspathSearchProofs.Update update){
        transitions++;lastIntervals=update.structuralDiff().intervals().stream().map(Object::toString).toList();
        intervals+=lastIntervals.size();lastReconsidered=update.reconsidered().size();lastEqual=update.equal().size();
        lastChanged=update.changed().size();lastUnavailable=update.unavailable().size();
        reconsidered+=lastReconsidered;equal+=lastEqual;changed+=lastChanged;unavailable+=lastUnavailable;
        lastConsumersVisited=lastConsumersChanged=lastConsumersEqual=lastConsumersFallback=lastConsumersDeferred=lastBroadContextInvalidations=0;
    }
    void propagation(SemanticUpdatePolicy.ProofPropagation value){
        lastConsumersVisited=value.recomputed().size();lastConsumersChanged=value.changed().size();
        lastConsumersEqual=value.equal().size();lastConsumersFallback=value.fallback().size();lastConsumersDeferred=value.deferred().size();
        consumersVisited+=lastConsumersVisited;consumersChanged+=lastConsumersChanged;
        consumersEqual+=lastConsumersEqual;consumersFallback+=lastConsumersFallback;consumersDeferred+=lastConsumersDeferred;
    }
    void broadContexts(long count){lastBroadContextInvalidations=count;broadContextInvalidations+=count;}
    Map<String,Object> status(){
        return Map.ofEntries(
                Map.entry("transitions",transitions),Map.entry("structural_intervals",intervals),
                Map.entry("search_proofs_reconsidered",reconsidered),Map.entry("search_proofs_equal",equal),
                Map.entry("search_proofs_changed",changed),Map.entry("search_proofs_unavailable",unavailable),
                Map.entry("proof_consumers_visited",consumersVisited),Map.entry("proof_consumers_changed",consumersChanged),
                Map.entry("proof_consumers_equal",consumersEqual),Map.entry("proof_consumers_fallback",consumersFallback),
                Map.entry("proof_consumers_deferred",consumersDeferred),Map.entry("last_consumers_deferred",lastConsumersDeferred),
                Map.entry("broad_context_invalidations",broadContextInvalidations),Map.entry("coarse_fallbacks",coarseFallbacks),
                Map.entry("validated_input_reconciliations",validatedInputReconciliations),
                Map.entry("last_intervals",lastIntervals),Map.entry("last_reconsidered",lastReconsidered),
                Map.entry("last_equal",lastEqual),Map.entry("last_changed",lastChanged),
                Map.entry("last_unavailable",lastUnavailable),Map.entry("last_consumers_visited",lastConsumersVisited),
                Map.entry("last_consumers_changed",lastConsumersChanged),Map.entry("last_consumers_equal",lastConsumersEqual),
                Map.entry("last_consumers_fallback",lastConsumersFallback),
                Map.entry("last_broad_context_invalidations",lastBroadContextInvalidations));
    }
}
