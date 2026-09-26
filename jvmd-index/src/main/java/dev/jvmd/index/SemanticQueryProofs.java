package dev.jvmd.index;

import java.util.*;

/** Canonical query-proof construction over the unified semantic read boundary. */
public final class SemanticQueryProofs {
    private SemanticQueryProofs(){}

    public static QueryProof.Key exactSymbol(String symbolId){
        return new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,Objects.requireNonNull(symbolId));
    }
    public static QueryProof.Key memberRange(String ownerId,String prefix){
        return new QueryProof.Key(QueryProof.Domain.MEMBER_RANGE,SemanticReadView.memberIdentityKey(ownerId,prefix));
    }
    public static QueryProof.Key overloadGroup(String ownerId,String name){
        return new QueryProof.Key(QueryProof.Domain.OVERLOAD_GROUP,SemanticReadView.memberIdentityKey(ownerId,name));
    }

    /**
     * Build a proof only when every requested semantic domain has an exact maintained identity.
     * Missing identity is uncertainty and must be handled by the caller's conservative fallback.
     */
    public static Optional<QueryProof> proof(SemanticReadView view,Collection<QueryProof.Key> keys)throws Exception{
        Objects.requireNonNull(view);Objects.requireNonNull(keys);
        var dependencies=new ArrayList<QueryProof.Dependency>(keys.size());
        for(var key:keys){
            var identity=view.identity(key.domain(),key.value());
            if(identity.isEmpty())return Optional.empty();
            dependencies.add(new QueryProof.Dependency(key,identity.get()));
        }
        return Optional.of(new QueryProof(dependencies));
    }

    public static Optional<QueryProof> exact(SemanticReadView view,String symbolId)throws Exception{
        return proof(view,List.of(exactSymbol(symbolId)));
    }
    public static Optional<QueryProof> range(SemanticReadView view,String ownerId,String prefix)throws Exception{
        return proof(view,List.of(memberRange(ownerId,prefix)));
    }
    public static Optional<QueryProof> overload(SemanticReadView view,String ownerId,String name)throws Exception{
        return proof(view,List.of(overloadGroup(ownerId,name)));
    }
}
