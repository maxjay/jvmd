package dev.jvmd.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Domain-separated canonical SHA-256 writer shared by structural and algebraic identities. */
public final class CanonicalDigestWriter {
    private CanonicalDigestWriter(){}

    public static Hash256 digest(String domain,Object... parts){
        Objects.requireNonNull(domain);
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){
                write(out,domain);for(Object part:parts)write(out,part);
            }
            return new Hash256(digest.digest());
        }catch(IOException|NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }

    private static void write(DataOutputStream out,Object value)throws IOException{
        if(value instanceof Object[] values){out.writeByte(1);out.writeInt(values.length);for(Object item:values)write(out,item);return;}
        if(value instanceof Collection<?> values){out.writeByte(2);out.writeInt(values.size());for(Object item:values)write(out,item);return;}
        if(value instanceof Hash256 hash){out.writeByte(4);out.writeInt(Hash256.BYTES);out.write(hash.bytes());return;}
        if(value instanceof byte[] bytes){out.writeByte(5);out.writeInt(bytes.length);out.write(bytes);return;}
        byte[] bytes=Objects.toString(value,"").getBytes(StandardCharsets.UTF_8);out.writeByte(3);out.writeInt(bytes.length);out.write(bytes);
    }
}
