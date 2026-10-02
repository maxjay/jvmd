package dev.jvmd.core.tree;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A layer's committed root: one digest over the tree root hash, every aggregate, the leaf count and
 * the format version. It is written last in a commit, so its presence means the whole layer exists.
 */
public record Root(int format,Hash256 tree,SortedMap<String,Hash256> aggregates,long leaves) {
    public Root {
        Objects.requireNonNull(tree);aggregates=Collections.unmodifiableSortedMap(new TreeMap<>(aggregates));
        if(leaves<0)throw new IllegalArgumentException("Negative leaf count");
    }

    public static Root of(int format,Hash256 tree,Collection<Aggregate> aggregates,long leaves){
        var identities=new TreeMap<String,Hash256>();
        for(var aggregate:aggregates)if(identities.put(aggregate.projection(),aggregate.identity())!=null)
            throw new IllegalArgumentException("Duplicate aggregate "+aggregate.projection());
        return new Root(format,tree,identities,leaves);
    }

    public Hash256 digest(){
        var parts=new ArrayList<Object>();aggregates.forEach((name,identity)->parts.add(new Object[]{name,identity}));
        return CanonicalDigestWriter.digest("layer-root-v1",format,tree,parts,leaves);
    }

    public byte[] encode(){
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(format);out.write(tree.bytes());out.writeLong(leaves);out.writeInt(aggregates.size());
            for(var entry:aggregates.entrySet()){
                byte[] name=entry.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeInt(name.length);out.write(name);out.write(entry.getValue().bytes());
            }
            out.write(digest().bytes());
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        return bytes.toByteArray();
    }

    /** Decodes a root; a truncated or inconsistent record is unreadable, which means not committed. */
    public static Optional<Root> decode(byte[] bytes){
        if(bytes==null)return Optional.empty();
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            int format=in.readInt();var tree=new Hash256(in.readNBytes(Hash256.BYTES));long leaves=in.readLong();
            int count=in.readInt();if(count<0||count>1024)return Optional.empty();
            var aggregates=new TreeMap<String,Hash256>();
            for(int i=0;i<count;i++){
                int length=in.readInt();if(length<0||length>4096)return Optional.empty();
                aggregates.put(new String(in.readNBytes(length),StandardCharsets.UTF_8),new Hash256(in.readNBytes(Hash256.BYTES)));
            }
            var root=new Root(format,tree,aggregates,leaves);
            var digest=new Hash256(in.readNBytes(Hash256.BYTES));
            return in.available()==0&&digest.equals(root.digest())?Optional.of(root):Optional.empty();
        }catch(IOException|IllegalArgumentException unreadable){return Optional.empty();}
    }
}
