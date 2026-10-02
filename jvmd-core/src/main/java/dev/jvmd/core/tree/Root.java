package dev.jvmd.core.tree;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.io.*;
import java.util.*;

/**
 * A layer's root: the top tree's root hash (which covers each leaf's own trees through the leaf
 * identities hashed into it), every aggregate, the leaf count and the format version. A root is
 * written last, after everything it covers is durable, so its presence is the layer's only
 * completeness signal. Equal inputs give an equal root.
 */
public record Root(int format,Hash256 tree,List<Aggregate> aggregates,long leaves) {
    public Root {
        Objects.requireNonNull(tree);
        aggregates=aggregates.stream().sorted(Comparator.comparing(Aggregate::domain)).toList();
        if(leaves<0)throw new IllegalArgumentException("Negative leaf count");
        for(int i=1;i<aggregates.size();i++)if(aggregates.get(i-1).domain().equals(aggregates.get(i).domain()))
            throw new IllegalArgumentException("Duplicate aggregate "+aggregates.get(i).domain());
    }

    public Aggregate aggregate(String domain){
        for(var aggregate:aggregates)if(aggregate.domain().equals(domain))return aggregate;
        throw new IllegalArgumentException("No aggregate "+domain);
    }

    /** One digest over everything the root covers. */
    public Hash256 identity(){
        var parts=new ArrayList<Object>();
        for(var aggregate:aggregates){parts.add(aggregate.domain());parts.add(aggregate.identity());}
        return CanonicalDigestWriter.digest("layer-root-v1",format,tree,parts,leaves);
    }

    public byte[] encode(){
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(format);out.write(tree.bytes());out.writeLong(leaves);out.writeInt(aggregates.size());
            for(var aggregate:aggregates)out.write(aggregate.encode());
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        return bytes.toByteArray();
    }

    public static Root decode(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            int format=in.readInt();byte[] tree=in.readNBytes(Hash256.BYTES);if(tree.length!=Hash256.BYTES)throw new EOFException();
            long leaves=in.readLong();int count=in.readInt();if(count<0)throw new IOException("Negative aggregate count");
            var aggregates=new ArrayList<Aggregate>(count);for(int i=0;i<count;i++)aggregates.add(Aggregate.decode(in));
            if(in.available()!=0)throw new IOException("Trailing root bytes");
            return new Root(format,new Hash256(tree),aggregates,leaves);
        }
    }
}
