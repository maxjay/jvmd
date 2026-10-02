package dev.jvmd.index.layer.local;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.index.ClasspathSequence;
import java.io.*;
import java.util.*;

/**
 * The ordered classpath of one module scope, as references: each entry is a MACHINE leaf key, with
 * that leaf's resolution identity, or a sibling module of the same project ({@code module:<gav>}),
 * whose declarations are LOCAL. A route never copies what it refers to.
 */
public record Route(String module,String scope,ClasspathSequence sequence) {
    public static final String MODULE="module:";

    public Route {
        Objects.requireNonNull(module);Objects.requireNonNull(sequence);
        if(!scope.equals("main")&&!scope.equals("test"))throw new IllegalArgumentException("scope "+scope);
    }

    /** The key of this route: {@code <gav>:<scope>}. */
    public String key(){return module+":"+scope;}

    /** A sibling module entry; its declarations are read through LOCAL, so only its name is identity. */
    public static ClasspathSequence.Entry sibling(String gav,String location){
        return new ClasspathSequence.Entry(MODULE+gav,CanonicalDigestWriter.digest("local-route-module-v1",gav),location);
    }
    public static boolean sibling(ClasspathSequence.Entry entry){return entry.key().startsWith(MODULE);}

    /** The MACHINE leaf keys of this route, in classpath order. */
    public List<String> machineKeys(){return sequence.entries().stream().filter(entry->!sibling(entry)).map(ClasspathSequence.Entry::key).toList();}
    /** The sibling modules of this route, in classpath order. */
    public List<String> modules(){return sequence.entries().stream().filter(Route::sibling).map(entry->entry.key().substring(MODULE.length())).toList();}

    public Hash256 identity(){return CanonicalDigestWriter.digest("local-route-v1",module,scope,sequence.identity());}

    public byte[] encode(){
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeUTF(module);out.writeUTF(scope);var entries=sequence.entries();out.writeInt(entries.size());
            for(var entry:entries){
                out.writeUTF(entry.key());out.write(entry.resolutionIdentity().bytes());
                out.writeBoolean(entry.location()!=null);if(entry.location()!=null)out.writeUTF(entry.location());
            }
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        return bytes.toByteArray();
    }

    public static Route decode(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            String module=in.readUTF(),scope=in.readUTF();int size=in.readInt();var entries=new ArrayList<ClasspathSequence.Entry>(size);
            for(int i=0;i<size;i++){
                String key=in.readUTF();var identity=new Hash256(in.readNBytes(Hash256.BYTES));
                entries.add(new ClasspathSequence.Entry(key,identity,in.readBoolean()?in.readUTF():null));
            }
            return new Route(module,scope,ClasspathSequence.of(entries));
        }
    }
}
