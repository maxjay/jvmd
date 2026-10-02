package dev.jvmd.core.tree;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Hash256;
import java.util.Objects;

/**
 * Immutable additive set hash over (leaf key, projected identity) for one projection of a layer.
 * Insert, remove and replace are O(1) and independent of order; a null identity contributes nothing.
 */
public record Aggregate(String projection,AlgebraicAccumulator.Value value) {
    public Aggregate { Objects.requireNonNull(projection);Objects.requireNonNull(value); }

    public static Aggregate empty(String projection){return new Aggregate(projection,AlgebraicAccumulator.Value.ZERO);}

    public Aggregate add(Object key,Object identity){
        return identity==null?this:new Aggregate(projection,value.plus(AlgebraicAccumulator.contribution(projection,key,identity)));
    }
    public Aggregate remove(Object key,Object identity){
        return identity==null?this:new Aggregate(projection,value.minus(AlgebraicAccumulator.contribution(projection,key,identity)));
    }
    public Aggregate replace(Object key,Object before,Object after){return remove(key,before).add(key,after);}
    public Aggregate replace(Object beforeKey,Object before,Object afterKey,Object after){return remove(beforeKey,before).add(afterKey,after);}

    public long cardinality(){return value.cardinality();}
    public Hash256 identity(){return value.identity(projection);}
}
