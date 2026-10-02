package dev.jvmd.index.layer.machine;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * One location of a MACHINE leaf's content: the artifact's path, its Maven coordinates, its stamp,
 * and the location and stamp of its paired sources (both null when it has none). Equal content at
 * two locations is one leaf with two paths.
 */
public record MachinePath(String location,String gav,Stamp stamp,String sources,Stamp sourcesStamp) {
    public MachinePath {
        Objects.requireNonNull(location);Objects.requireNonNull(gav);Objects.requireNonNull(stamp);
        if(location.isBlank()||gav.isBlank())throw new IllegalArgumentException("MACHINE path needs a location and coordinates");
        if((sources==null)!=(sourcesStamp==null))throw new IllegalArgumentException("Paired sources need a location and a stamp");
    }

    /** One stat of a file: its size, modification time and file key. */
    public record Stamp(long size,long modifiedNanos,String fileKey) {
        public Stamp { fileKey=Objects.requireNonNullElse(fileKey,""); }
        public static Stamp of(BasicFileAttributes attributes){
            return new Stamp(attributes.size(),attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),Objects.toString(attributes.fileKey(),""));
        }
        public static Stamp read(Path path)throws IOException{return of(Files.readAttributes(path,BasicFileAttributes.class));}
    }

    void write(DataOutputStream out)throws IOException{
        out.writeUTF(location);out.writeUTF(gav);write(out,stamp);
        out.writeBoolean(sources!=null);if(sources!=null){out.writeUTF(sources);write(out,sourcesStamp);}
    }
    static MachinePath read(DataInputStream in)throws IOException{
        String location=in.readUTF(),gav=in.readUTF();Stamp stamp=readStamp(in);
        if(!in.readBoolean())return new MachinePath(location,gav,stamp,null,null);
        String sources=in.readUTF();return new MachinePath(location,gav,stamp,sources,readStamp(in));
    }
    private static void write(DataOutputStream out,Stamp stamp)throws IOException{
        out.writeLong(stamp.size());out.writeLong(stamp.modifiedNanos());out.writeUTF(stamp.fileKey());
    }
    private static Stamp readStamp(DataInputStream in)throws IOException{return new Stamp(in.readLong(),in.readLong(),in.readUTF());}
}
