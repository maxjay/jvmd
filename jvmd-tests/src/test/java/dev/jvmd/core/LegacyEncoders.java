package dev.jvmd.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Oracle copies of the three SHA-256 canonical encoders as they were at c8fcb9f
 * ({@code CanonicalDigestWriter}, {@code LiveStateTree.digest/write}, {@code CompilerInputs.compose/write}).
 * Only their equivalence classes matter. Deleted with the oracle test in P5.
 */
final class LegacyEncoders {
    private LegacyEncoders(){}

    /** CanonicalDigestWriter.digest. */
    static String canonical(String domain,Object... parts){
        return sha(out->{canonicalWrite(out,domain);for(Object part:parts)canonicalWrite(out,part);});
    }
    private static void canonicalWrite(DataOutputStream out,Object value)throws IOException{
        if(value instanceof Object[] values){out.writeByte(1);out.writeInt(values.length);for(Object item:values)canonicalWrite(out,item);return;}
        if(value instanceof Collection<?> values){out.writeByte(2);out.writeInt(values.size());for(Object item:values)canonicalWrite(out,item);return;}
        // Was Hash256 (int32 32 + raw bytes); the identity type is now Id128, framed the same way.
        if(value instanceof Id128 hash){out.writeByte(4);out.writeInt(Id128.BYTES);out.write(hash.bytes());return;}
        if(value instanceof byte[] bytes){out.writeByte(5);out.writeInt(bytes.length);out.write(bytes);return;}
        scalar(out,value);
    }

    /** LiveStateTree.digest. */
    static String liveState(String domain,Object... parts){
        return sha(out->{liveStateWrite(out,domain);for(Object part:parts)liveStateWrite(out,part);});
    }
    private static void liveStateWrite(DataOutputStream out,Object value)throws IOException{
        if(value instanceof Object[] values){out.writeByte(1);out.writeInt(values.length);for(Object item:values)liveStateWrite(out,item);return;}
        if(value instanceof Collection<?> values){out.writeByte(2);out.writeInt(values.size());for(Object item:values)liveStateWrite(out,item);return;}
        scalar(out,value);
    }

    /** CompilerInputs.compose. */
    static String compilerInputs(String version,Object... components){
        return sha(out->{compilerWrite(out,version);for(Object value:components)compilerWrite(out,value);});
    }
    private static void compilerWrite(DataOutputStream out,Object value)throws IOException{
        if(value instanceof Map<?,?> map){out.writeByte(1);out.writeInt(map.size());for(var entry:map.entrySet()){compilerWrite(out,entry.getKey());compilerWrite(out,entry.getValue());}}
        else if(value instanceof Collection<?> values){out.writeByte(2);out.writeInt(values.size());for(Object item:values)compilerWrite(out,item);}
        else scalar(out,value);
    }

    private static void scalar(DataOutputStream out,Object value)throws IOException{
        byte[] bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);out.writeByte(3);out.writeInt(bytes.length);out.write(bytes);
    }
    private interface Body { void write(DataOutputStream out)throws IOException; }
    private static String sha(Body body){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){body.write(out);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
