package dev.jvmd.index.machine;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.ArtifactIndexFormat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * One MACHINE leaf: one distinct artifact content, keyed by its content cacheKey. Paths with equal
 * content are one leaf with several locations. Its facts live in an immutable SST under the same key.
 */
public record MachineLeaf(String cacheKey,String binarySha256,String mode,List<Location> locations,
                          Hash256 resolution,Documentation documentation,Counts counts) {
    /** Where the content was found and the stamp it had. */
    public record Location(String location,String gav,MachineInputs.Kind kind,MachineInputs.Stamp stamp) {
        public Location { Objects.requireNonNull(location);Objects.requireNonNull(gav);Objects.requireNonNull(kind);Objects.requireNonNull(stamp); }
    }
    /** Documentation joined from one paired sources archive. Its identity is the archive's content. */
    public record Documentation(String docsKey,String sourcesSha256,MachineInputs.Sources sources,int unmatched) {
        public Documentation { Objects.requireNonNull(docsKey);Objects.requireNonNull(sourcesSha256);Objects.requireNonNull(sources); }
        public Hash256 identity(){return Hash256.fromHex(sourcesSha256);}
    }
    /** Sizes the store reports for the leaf's facts. */
    public record Counts(long symbols,long relationships,long simpleNames,boolean classReferences) { }

    public MachineLeaf {
        Objects.requireNonNull(cacheKey);Objects.requireNonNull(binarySha256);Objects.requireNonNull(mode);
        locations=locations.stream().sorted(Comparator.comparing(Location::location)).toList();
        Objects.requireNonNull(resolution);Objects.requireNonNull(counts);
    }

    public ArtifactIndexFormat.Key key(){return ArtifactIndexFormat.key(binarySha256,mode);}
    /** Documentation identity, or null when the leaf has no paired sources. */
    public Hash256 documentationIdentity(){return documentation==null?null:documentation.identity();}
    public MachineLeaf withDocumentation(Documentation value){return new MachineLeaf(cacheKey,binarySha256,mode,locations,resolution,value,counts);}

    public byte[] encode(){
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            write(out,cacheKey);write(out,binarySha256);write(out,mode);
            out.writeInt(locations.size());
            for(var location:locations){
                write(out,location.location());write(out,location.gav());write(out,location.kind().name());writeStamp(out,location.stamp());
            }
            out.write(resolution.bytes());
            out.writeBoolean(documentation!=null);
            if(documentation!=null){
                write(out,documentation.docsKey());write(out,documentation.sourcesSha256());
                write(out,documentation.sources().path().toString());writeStamp(out,documentation.sources().stamp());write(out,documentation.sources().gav());
                out.writeInt(documentation.unmatched());
            }
            out.writeLong(counts.symbols());out.writeLong(counts.relationships());out.writeLong(counts.simpleNames());out.writeBoolean(counts.classReferences());
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        return bytes.toByteArray();
    }

    public static MachineLeaf decode(byte[] bytes){
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            String cacheKey=read(in),sha=read(in),mode=read(in);
            int count=in.readInt();var locations=new ArrayList<Location>(count);
            for(int i=0;i<count;i++)locations.add(new Location(read(in),read(in),MachineInputs.Kind.valueOf(read(in)),readStamp(in)));
            var resolution=new Hash256(in.readNBytes(Hash256.BYTES));
            Documentation documentation=null;
            if(in.readBoolean()){
                String docsKey=read(in),sourcesSha=read(in);var path=java.nio.file.Path.of(read(in));var stamp=readStamp(in);String gav=read(in);
                documentation=new Documentation(docsKey,sourcesSha,new MachineInputs.Sources(path,stamp,gav),in.readInt());
            }
            var counts=new Counts(in.readLong(),in.readLong(),in.readLong(),in.readBoolean());
            if(in.available()!=0)throw new IOException("Trailing machine leaf bytes");
            return new MachineLeaf(cacheKey,sha,mode,locations,resolution,documentation,counts);
        }catch(IOException invalid){throw new UncheckedIOException(invalid);}
    }

    private static void writeStamp(DataOutputStream out,MachineInputs.Stamp stamp)throws IOException{
        out.writeLong(stamp.size());out.writeLong(stamp.modifiedNanos());out.writeLong(stamp.changedNanos());write(out,stamp.fileKey());
    }
    private static MachineInputs.Stamp readStamp(DataInputStream in)throws IOException{
        return new MachineInputs.Stamp(in.readLong(),in.readLong(),in.readLong(),read(in));
    }
    private static void write(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static String read(DataInputStream in)throws IOException{
        int length=in.readInt();if(length<0||length>1<<20)throw new IOException("Invalid machine leaf string");
        byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();
        return new String(bytes,StandardCharsets.UTF_8);
    }
}
