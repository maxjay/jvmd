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
        Map<Integer,QueryContext> queries,
        List<LocalDeclaration> locals) {

    public DocumentSemanticSnapshot {
        Objects.requireNonNull(file);
        contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        queries=Map.copyOf(queries);
        locals=List.copyOf(locals);
    }

    public QueryContext query(int selectorOffset){return queries.get(selectorOffset);}

    public DocumentSemanticSnapshot withQuery(QueryContext query,long epoch){
        var next=new LinkedHashMap<Integer,QueryContext>(queries);
        next.put(query.selectorOffset(),query);
        return new DocumentSemanticSnapshot(file,documentVersion,contentIdentity,epoch,next,locals);
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
            Set<String> accessibleMemberIds) {
        public QueryContext {
            if(selectorOffset<0)throw new IllegalArgumentException("selectorOffset");
            Objects.requireNonNull(receiverType);
            packageName=Objects.requireNonNullElse(packageName,"");
            scopedCandidates=List.copyOf(scopedCandidates);
            accessibleMemberIds=Set.copyOf(accessibleMemberIds);
        }
    }

    public record LocalDeclaration(
            String id,
            String name,
            String kind,
            SemanticType type,
            int scopeStart,
            int scopeEnd,
            int declarationStart) {
        public LocalDeclaration {
            Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(kind);Objects.requireNonNull(type);
            if(scopeStart<0||scopeEnd<scopeStart||declarationStart<0)throw new IllegalArgumentException("Invalid local range");
        }
        public boolean visibleAt(int offset){return scopeStart<=offset&&offset<=scopeEnd&&declarationStart<=offset;}
    }
}
