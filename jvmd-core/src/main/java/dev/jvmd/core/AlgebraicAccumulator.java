package dev.jvmd.core;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Commutative semantic-domain accumulator. Contributions bind domain, semantic key and value
 * identity; cardinality is independent evidence alongside the modular sum.
 */
public final class AlgebraicAccumulator {
    private static final BigInteger FIELD=new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",16);
    private final String domain;
    private Value value=Value.ZERO;

    public AlgebraicAccumulator(String domain){this.domain=Objects.requireNonNull(domain);}
    public Value value(){return value;}
    public long cardinality(){return value.cardinality();}
    public Hash256 identity(){return value.identity(domain);}

    public void replace(Object oldKey,Object oldValue,Object newKey,Object newValue){
        if(oldValue!=null)value=value.minus(contribution(domain,oldKey,oldValue));
        if(newValue!=null)value=value.plus(contribution(domain,newKey,newValue));
    }
    public void add(Object key,Object value){this.value=this.value.plus(contribution(domain,key,value));}
    public void remove(Object key,Object value){this.value=this.value.minus(contribution(domain,key,value));}

    public record Value(BigInteger sum,long cardinality) {
        public static final Value ZERO=new Value(BigInteger.ZERO,0);
        public Value {
            Objects.requireNonNull(sum);sum=sum.mod(FIELD);
            if(cardinality<0)throw new IllegalArgumentException("Negative aggregate cardinality");
        }
        public Value plus(Value other){return new Value(sum.add(other.sum),Math.addExact(cardinality,other.cardinality));}
        public Value minus(Value other){return new Value(sum.subtract(other.sum),Math.subtractExact(cardinality,other.cardinality));}
        public Hash256 identity(String domain){return CanonicalDigestWriter.digest("aggregate-v2",domain,cardinality,fixedBytes(sum));}
    }

    public static Value contribution(String domain,Object semanticKey,Object valueIdentity){
        Objects.requireNonNull(domain);Objects.requireNonNull(semanticKey);Objects.requireNonNull(valueIdentity);
        BigInteger point=CanonicalDigestWriter.digest("aggregate-contribution-v2",domain,semanticKey,valueIdentity).unsignedInteger().mod(FIELD);
        return new Value(point,1);
    }

    private static byte[] fixedBytes(BigInteger value){
        byte[] source=value.mod(FIELD).toByteArray(),result=new byte[32];
        int sourceOffset=Math.max(0,source.length-result.length),length=Math.min(source.length,result.length);
        System.arraycopy(source,sourceOffset,result,result.length-length,length);return result;
    }
}
