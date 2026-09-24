package dev.jvmd.core;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Immutable raw SHA-256 value. Hex is a presentation/serialization boundary, not storage. */
public final class Hash256 implements Comparable<Hash256> {
    public static final int BYTES=32;
    private static final HexFormat HEX=HexFormat.of();
    private final byte[] bytes;
    private final int hashCode;

    public Hash256(byte[] bytes){
        Objects.requireNonNull(bytes);
        if(bytes.length!=BYTES)throw new IllegalArgumentException("Expected 32 bytes, got "+bytes.length);
        this.bytes=bytes.clone();this.hashCode=Arrays.hashCode(this.bytes);
    }
    public static Hash256 sha256(byte[] value){
        try{return new Hash256(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static Hash256 fromHex(String value){return new Hash256(HEX.parseHex(Objects.requireNonNull(value)));}
    public byte[] bytes(){return bytes.clone();}
    public BigInteger unsignedInteger(){return new BigInteger(1,bytes);}
    @JsonValue
    public String hex(){return HEX.formatHex(bytes);}
    @Override public String toString(){return hex();}
    @Override public boolean equals(Object other){return other instanceof Hash256 hash&&Arrays.equals(bytes,hash.bytes);}
    @Override public int hashCode(){return hashCode;}
    @Override public int compareTo(Hash256 other){
        for(int i=0;i<BYTES;i++){int compared=Integer.compare(Byte.toUnsignedInt(bytes[i]),Byte.toUnsignedInt(other.bytes[i]));if(compared!=0)return compared;}
        return 0;
    }
}
