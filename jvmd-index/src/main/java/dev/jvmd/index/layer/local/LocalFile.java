package dev.jvmd.index.layer.local;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.io.*;
import java.util.Objects;

/**
 * One LOCAL leaf: a source file of the project and the declarations attributed from it. Its key is
 * the logical source path, relative to the project root with {@code /} separators.
 *
 * @param module       the reactor module that compiles the file
 * @param scope        {@code main} or {@code test}
 * @param content      the SHA-256 of the bytes that were attributed
 * @param api          the file's API projection
 * @param namespace    the file's exported-name projection
 * @param semanticRoot the root of the file's own semantic tree
 * @param resolution   the identity of the resolution range sum over the file's declarations
 * @param facts        the number of declarations
 */
public record LocalFile(String path,String module,String scope,String content,String api,String namespace,
                        Hash256 semanticRoot,Hash256 resolution,long facts) {
    public LocalFile {
        Objects.requireNonNull(path);Objects.requireNonNull(module);Objects.requireNonNull(scope);Objects.requireNonNull(content);
        Objects.requireNonNull(api);Objects.requireNonNull(namespace);Objects.requireNonNull(semanticRoot);Objects.requireNonNull(resolution);
        if(path.isBlank()||path.startsWith("/")||path.contains("\\"))throw new IllegalArgumentException("Not a logical source path: "+path);
    }

    /** Identity hashed into the file tree: covers every field, including the file's semantic tree root. */
    public Hash256 identity(){
        return CanonicalDigestWriter.digest("local-file-v1",path,module,scope,content,api,namespace,semanticRoot,resolution,facts);
    }

    public byte[] encode(){
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeUTF(path);out.writeUTF(module);out.writeUTF(scope);out.writeUTF(content);out.writeUTF(api);out.writeUTF(namespace);
            out.write(semanticRoot.bytes());out.write(resolution.bytes());out.writeLong(facts);
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        return bytes.toByteArray();
    }

    public static LocalFile decode(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            String path=in.readUTF(),module=in.readUTF(),scope=in.readUTF(),content=in.readUTF(),api=in.readUTF(),namespace=in.readUTF();
            byte[] semantic=in.readNBytes(Hash256.BYTES),resolution=in.readNBytes(Hash256.BYTES);
            return new LocalFile(path,module,scope,content,api,namespace,new Hash256(semantic),new Hash256(resolution),in.readLong());
        }
    }
}
