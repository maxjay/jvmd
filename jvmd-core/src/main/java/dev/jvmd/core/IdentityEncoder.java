package dev.jvmd.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.*;

/**
 * The single canonical encoder for runtime semantic identities: Merkle nodes, treap priorities,
 * accumulator contributions, fact, type and proof identities. Parts are written into one growable
 * buffer (wire format v3, little-endian) and hashed once with XXH3-128.
 *
 * <p>Scalars keep the legacy equivalence classes: {@code null} encodes as {@code ""}, and
 * {@code Integer}/{@code Long}/{@code Short}/{@code Byte} encode as their decimal text, so {@code 5},
 * {@code 5L} and {@code "5"} are one identity input. Identities are a pure function of their
 * inputs: no seeds, {@code hashCode()}, locale or platform byte order.
 *
 * <p>Threat model: XXH3 is a non-cryptographic hash. Runtime identities assume workspace content
 * does not attack its own language server. Merkle identities are the structural authority, and
 * accumulators are fast filters, never trust anchors. Anything that crosses a trust boundary
 * (integrity, content checks, external formats, on-disk names) keeps SHA-256 via {@link Hashing}.
 */
public final class IdentityEncoder {
    private static final VarHandle LONG=MethodHandles.byteArrayViewVarHandle(long[].class,ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT=MethodHandles.byteArrayViewVarHandle(int[].class,ByteOrder.LITTLE_ENDIAN);
    private static final byte OBJECTS=1,COLLECTION=2,SCALAR=3,IDENTITY=4,BYTES=5,MAP=6;
    private byte[] buffer=new byte[256];
    private int length;

    private IdentityEncoder(){}

    /** Varargs front door: the domain, then each part by its runtime type. */
    public static Id128 of(String domain,Object... parts){
        var encoder=begin(domain);
        for(Object part:parts)encoder.value(part);
        return encoder.finish();
    }

    /** Typed front door for hot paths; produces the same bytes as {@link #of} for the same values. */
    public static IdentityEncoder begin(String domain){return new IdentityEncoder().str(Objects.requireNonNull(domain));}

    public IdentityEncoder str(String value){
        if(value==null)value="";
        int count=value.length();ensure(5+2*count);
        buffer[length]=SCALAR;INT.set(buffer,length+1,count);length+=5;
        for(int i=0;i<count;i++){char c=value.charAt(i);buffer[length++]=(byte)c;buffer[length++]=(byte)(c>>>8);}
        return this;
    }
    /** Decimal text, identical to {@code str(Long.toString(value))} without the String. */
    public IdentityEncoder num(long value){
        int count=digits(value);ensure(5+2*count);
        buffer[length]=SCALAR;INT.set(buffer,length+1,count);length+=5;
        int end=length+2*count,position=end;
        long rest=value<0?value:-value;
        do{position-=2;buffer[position]=(byte)('0'-rest%10);buffer[position+1]=0;rest/=10;}while(rest!=0);
        if(value<0){buffer[position-2]='-';buffer[position-1]=0;}
        length=end;return this;
    }
    public IdentityEncoder bool(boolean value){return str(value?"true":"false");}
    public IdentityEncoder id(Id128 value){
        if(value==null)return str(null);
        ensure(17);buffer[length]=IDENTITY;LONG.set(buffer,length+1,value.hi());LONG.set(buffer,length+9,value.lo());length+=17;
        return this;
    }
    public IdentityEncoder bytes(byte[] value){
        if(value==null)return str(null);
        ensure(5+value.length);buffer[length]=BYTES;INT.set(buffer,length+1,value.length);length+=5;
        System.arraycopy(value,0,buffer,length,value.length);length+=value.length;return this;
    }
    /** Header of an ordered sequence of {@code count} parts; identical to passing a {@link List}. */
    public IdentityEncoder seq(int count){return header(COLLECTION,count);}

    public IdentityEncoder value(Object value){
        switch(value){
            case null -> str(null);
            case String text -> str(text);
            case Id128 identity -> id(identity);
            case Long number -> num(number);
            case Integer number -> num(number);
            case Short number -> num(number);
            case Byte number -> num(number);
            case byte[] raw -> bytes(raw);
            case Object[] values -> {header(OBJECTS,values.length);for(Object item:values)value(item);}
            case Collection<?> values -> {
                assert !(values instanceof Set<?>)||values instanceof SortedSet<?>:"unordered Set in identity: "+values.getClass().getName();
                header(COLLECTION,values.size());for(Object item:values)value(item);
            }
            case Map<?,?> map -> {
                assert map instanceof SortedMap<?,?>||map instanceof LinkedHashMap<?,?>:"unordered Map in identity: "+map.getClass().getName();
                header(MAP,map.size());for(var entry:map.entrySet()){value(entry.getKey());value(entry.getValue());}
            }
            default -> {
                assert !value.getClass().isArray():"unsupported array in identity: "+value.getClass().getName();
                assert !(value instanceof Record):"record reached the toString identity fallback: "+value.getClass().getName();
                str(value.toString());
            }
        }
        return this;
    }

    public Id128 finish(){return Xxh3.hash128(buffer,0,length);}

    private IdentityEncoder header(byte tag,int count){
        ensure(5);buffer[length]=tag;INT.set(buffer,length+1,count);length+=5;return this;
    }
    private void ensure(int extra){
        if(extra>buffer.length-length)buffer=Arrays.copyOf(buffer,Math.max(buffer.length*2,length+extra));
    }
    private static int digits(long value){
        int sign=1;
        if(value>=0){sign=0;value=-value;}
        long bound=-10;
        for(int i=1;i<19;i++){if(value>bound)return i+sign;bound*=10;}
        return 19+sign;
    }
}
