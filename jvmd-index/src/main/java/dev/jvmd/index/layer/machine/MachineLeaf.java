package dev.jvmd.index.layer.machine;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.index.ArtifactIndexFormat;
import java.io.*;
import java.util.*;

/**
 * One MACHINE leaf: one distinct artifact content. Its key is the content cacheKey. It carries every
 * path where that content was found, its projected resolution and documentation identities, the
 * root of its semantic tree, and the keys of its immutable stores (facts and documentation).
 *
 * @param docsKey the documentation store key, or null when no sources were paired with the content
 */
public record MachineLeaf(String binarySha256,String mode,List<MachinePath> paths,Hash256 resolution,Hash256 documentation,
                          String docsKey,Hash256 semanticRoot,long symbols,long relationships,long types) {
    public MachineLeaf {
        Objects.requireNonNull(binarySha256);Objects.requireNonNull(mode);Objects.requireNonNull(resolution);
        Objects.requireNonNull(documentation);Objects.requireNonNull(semanticRoot);
        paths=paths.stream().sorted(Comparator.comparing(MachinePath::location)).toList();
        for(int i=1;i<paths.size();i++)if(paths.get(i-1).location().equals(paths.get(i).location()))
            throw new IllegalArgumentException("Duplicate MACHINE path "+paths.get(i).location());
    }

    public ArtifactIndexFormat.Key key(){return ArtifactIndexFormat.key(binarySha256,mode);}
    public String cacheKey(){return key().cacheKey();}

    /** This leaf with one more path, or with that path's entry replaced. */
    public MachineLeaf withPath(MachinePath path){
        var next=new ArrayList<MachinePath>(paths.size()+1);
        for(var existing:paths)if(!existing.location().equals(path.location()))next.add(existing);
        next.add(path);
        return new MachineLeaf(binarySha256,mode,next,resolution,documentation,docsKey,semanticRoot,symbols,relationships,types);
    }
    public MachineLeaf withPaths(Collection<MachinePath> values){
        return new MachineLeaf(binarySha256,mode,List.copyOf(values),resolution,documentation,docsKey,semanticRoot,symbols,relationships,types);
    }

    /** Identity hashed into the MACHINE tree: covers the content, its projections, its paths and its semantic tree. */
    public Hash256 identity(){
        var locations=new ArrayList<Object>();
        for(var path:paths)locations.add(new Object[]{path.location(),path.gav(),Objects.toString(path.sources(),""),
                path.stamp().size(),path.stamp().modifiedNanos(),path.stamp().fileKey()});
        return CanonicalDigestWriter.digest("machine-leaf-v1",cacheKey(),resolution,documentation,Objects.toString(docsKey,""),
                semanticRoot,symbols,relationships,types,locations);
    }

    public byte[] encode(){
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeUTF(binarySha256);out.writeUTF(mode);out.write(resolution.bytes());out.write(documentation.bytes());
            out.writeBoolean(docsKey!=null);if(docsKey!=null)out.writeUTF(docsKey);out.write(semanticRoot.bytes());
            out.writeLong(symbols);out.writeLong(relationships);out.writeLong(types);
            out.writeInt(paths.size());for(var path:paths)path.write(out);
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        return bytes.toByteArray();
    }

    public static MachineLeaf decode(byte[] bytes)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            String sha=in.readUTF(),mode=in.readUTF();Hash256 resolution=hash(in),documentation=hash(in);
            String docsKey=in.readBoolean()?in.readUTF():null;Hash256 semanticRoot=hash(in);
            long symbols=in.readLong(),relationships=in.readLong(),types=in.readLong();
            int count=in.readInt();if(count<0)throw new IOException("Negative MACHINE path count");
            var paths=new ArrayList<MachinePath>(count);for(int i=0;i<count;i++)paths.add(MachinePath.read(in));
            if(in.available()!=0)throw new IOException("Trailing MACHINE leaf bytes");
            return new MachineLeaf(sha,mode,paths,resolution,documentation,docsKey,semanticRoot,symbols,relationships,types);
        }
    }
    private static Hash256 hash(DataInputStream in)throws IOException{
        byte[] value=in.readNBytes(Hash256.BYTES);if(value.length!=Hash256.BYTES)throw new EOFException();return new Hash256(value);
    }
}
