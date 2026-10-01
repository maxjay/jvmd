package dev.jvmd.core;

import java.util.HexFormat;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 128-bit runtime semantic identity (XXH3-128 via {@link IdentityEncoder}). Canonical byte form is
 * big-endian {@code hi} then {@code lo}, matching {@code XXH128_canonicalFromHash}. Hex is a
 * presentation/persistence boundary, not storage.
 */
public record Id128(long hi,long lo) implements Comparable<Id128> {
    public static final int BYTES=16;
    private static final HexFormat HEX=HexFormat.of();

    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static Id128 fromHex(String value){
        if(Objects.requireNonNull(value).length()!=2*BYTES)throw new IllegalArgumentException("Expected 32 hex chars, got "+value.length());
        return new Id128(HexFormat.fromHexDigitsToLong(value,0,16),HexFormat.fromHexDigitsToLong(value,16,32));
    }
    public byte[] bytes(){
        byte[] out=new byte[BYTES];
        for(int i=0;i<8;i++){out[i]=(byte)(hi>>>(56-8*i));out[8+i]=(byte)(lo>>>(56-8*i));}
        return out;
    }
    @JsonValue
    public String hex(){return HEX.toHexDigits(hi)+HEX.toHexDigits(lo);}
    @Override public String toString(){return hex();}
    @Override public int hashCode(){return (int)(lo^lo>>>32);}
    @Override public int compareTo(Id128 other){
        int compared=Long.compareUnsigned(hi,other.hi);
        return compared!=0?compared:Long.compareUnsigned(lo,other.lo);
    }
}
