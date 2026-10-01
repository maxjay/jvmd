package dev.jvmd.core;

import java.util.Objects;

/**
 * Commutative semantic-domain accumulator. Contributions bind domain, semantic key and value
 * identity; each is one 128-bit {@link IdentityEncoder} value split into two lanes summed with
 * wrapping arithmetic (Z/2^64 x Z/2^64). Cardinality is independent evidence alongside the sum.
 *
 * <p>Threat model: XXH3 is a non-cryptographic hash. Runtime identities assume workspace content
 * does not attack its own language server. Merkle identities are the structural authority, and
 * accumulators are fast filters, never trust anchors.
 */
public final class AlgebraicAccumulator {
    private final String domain;
    private Value value=Value.ZERO;

    public AlgebraicAccumulator(String domain){this.domain=Objects.requireNonNull(domain);}
    public Value value(){return value;}
    public long cardinality(){return value.cardinality();}
    public Id128 identity(){return value.identity(domain);}

    public void replace(Object oldKey,Object oldValue,Object newKey,Object newValue){
        if(oldValue!=null)value=value.minus(contribution(domain,oldKey,oldValue));
        if(newValue!=null)value=value.plus(contribution(domain,newKey,newValue));
    }
    public void add(Object key,Object value){this.value=this.value.plus(contribution(domain,key,value));}
    public void remove(Object key,Object value){this.value=this.value.minus(contribution(domain,key,value));}

    public record Value(long a,long b,long count) {
        public static final Value ZERO=new Value(0,0,0);
        public Value {
            if(count<0)throw new IllegalArgumentException("Negative aggregate cardinality");
        }
        public long cardinality(){return count;}
        public Value plus(Value other){return new Value(a+other.a,b+other.b,Math.addExact(count,other.count));}
        public Value minus(Value other){return new Value(a-other.a,b-other.b,Math.subtractExact(count,other.count));}
        public Id128 identity(String domain){return IdentityEncoder.begin("aggregate-v3").str(domain).num(count).num(a).num(b).finish();}
    }

    public static Value contribution(String domain,Object semanticKey,Object valueIdentity){
        Objects.requireNonNull(domain);Objects.requireNonNull(semanticKey);Objects.requireNonNull(valueIdentity);
        Id128 point=IdentityEncoder.begin("aggregate-contribution-v3").str(domain).value(semanticKey).value(valueIdentity).finish();
        return new Value(point.hi(),point.lo(),1);
    }
}
