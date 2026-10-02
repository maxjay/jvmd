package dev.jvmd.core.tree;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Hash256;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The additive set hash of one projection over a layer's leaves: the sum of a contribution per
 * (leaf key, projected identity). Insert, remove and replace are O(1), and the value is independent
 * of order, so inserting A, then B, then removing B returns exactly the value after A.
 */
public record Aggregate(String domain,AlgebraicAccumulator.Value value) {
    public Aggregate {
        Objects.requireNonNull(domain);Objects.requireNonNull(value);
    }
    public static Aggregate empty(String domain){return new Aggregate(domain,AlgebraicAccumulator.Value.ZERO);}

    public Aggregate add(Object key,Object identity){return new Aggregate(domain,value.plus(contribution(key,identity)));}
    public Aggregate remove(Object key,Object identity){return new Aggregate(domain,value.minus(contribution(key,identity)));}
    /** Remove the old contribution and add the new one; a null identity is an absent side. */
    public Aggregate replace(Object oldKey,Object oldIdentity,Object newKey,Object newIdentity){
        var next=value;
        if(oldIdentity!=null)next=next.minus(contribution(oldKey,oldIdentity));
        if(newIdentity!=null)next=next.plus(contribution(newKey,newIdentity));
        return new Aggregate(domain,next);
    }
    public Aggregate plus(Aggregate other){requireDomain(other);return new Aggregate(domain,value.plus(other.value));}
    public Aggregate minus(Aggregate other){requireDomain(other);return new Aggregate(domain,value.minus(other.value));}

    public long cardinality(){return value.cardinality();}
    public Hash256 identity(){return value.identity(domain);}

    public byte[] encode(){
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            byte[] name=domain.getBytes(StandardCharsets.UTF_8),sum=value.sum().toByteArray();
            out.writeInt(name.length);out.write(name);out.writeInt(sum.length);out.write(sum);out.writeLong(value.cardinality());
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        return bytes.toByteArray();
    }
    public static Aggregate decode(DataInputStream in)throws IOException{
        String domain=new String(read(in),StandardCharsets.UTF_8);var sum=new BigInteger(1,read(in));
        return new Aggregate(domain,new AlgebraicAccumulator.Value(sum,in.readLong()));
    }

    private AlgebraicAccumulator.Value contribution(Object key,Object identity){
        return AlgebraicAccumulator.contribution(domain,key,identity);
    }
    private void requireDomain(Aggregate other){
        if(!domain.equals(other.domain))throw new IllegalArgumentException("Aggregate domains differ: "+domain+" and "+other.domain);
    }
    private static byte[] read(DataInputStream in)throws IOException{
        int length=in.readInt();if(length<0)throw new IOException("Negative aggregate field");
        byte[] value=in.readNBytes(length);if(value.length!=length)throw new EOFException();return value;
    }
}
