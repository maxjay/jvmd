package dev.jvmd.index;

import java.util.*;

/**
 * Detached semantic state for one document version.
 *
 * Query contexts are keyed by the selector offset they describe so a document version can
 * accumulate cursor-local semantic facts without retaining javac-owned objects.
 */
public record DocumentSemanticSnapshot(
        String file,
        int documentVersion,
        String contentIdentity,
        long globalEpoch,
        Map<Integer,QueryContext> queries) {

    public DocumentSemanticSnapshot {
        Objects.requireNonNull(file);
        contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        queries=Map.copyOf(queries);
    }

    public QueryContext query(int selectorOffset){return queries.get(selectorOffset);}

    public DocumentSemanticSnapshot withQuery(QueryContext query,long epoch){
        var next=new LinkedHashMap<Integer,QueryContext>(queries);
        next.put(query.selectorOffset(),query);
        return new DocumentSemanticSnapshot(file,documentVersion,contentIdentity,epoch,next);
    }

    public record QueryContext(
            int selectorOffset,
            SemanticType receiverType,
            String receiverSymbolId,
            boolean staticReceiver,
            String packageName,
            String enclosingTypeId,
            boolean staticContext,
            List<CompletionCandidate> scopedCandidates,
            String accessibilityKey) {
        public QueryContext {
            if(selectorOffset<0)throw new IllegalArgumentException("selectorOffset");
            Objects.requireNonNull(receiverType);
            packageName=Objects.requireNonNullElse(packageName,"");
            scopedCandidates=List.copyOf(scopedCandidates);
            accessibilityKey=Objects.requireNonNullElse(accessibilityKey,"");
        }
        public QueryContext withAccessibilityKey(String key){
            return new QueryContext(selectorOffset,receiverType,receiverSymbolId,staticReceiver,packageName,enclosingTypeId,
                    staticContext,scopedCandidates,key);
        }
    }

}
