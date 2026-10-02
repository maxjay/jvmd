package dev.jvmd.index.layer.machine;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * One location of a MACHINE leaf's content: the artifact's path, its Maven coordinates, the
 * location of its paired sources (or null) and the file stamp observed when the leaf was built.
 * Equal content at two locations is one leaf with two paths.
 */
public record MachinePath(String location,String gav,String sources,Stamp stamp) {
    public MachinePath {
        Objects.requireNonNull(location);Objects.requireNonNull(gav);Objects.requireNonNull(stamp);
        if(location.isBlank()||gav.isBlank())throw new IllegalArgumentException("MACHINE path needs a location and coordinates");
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
        out.writeUTF(location);out.writeUTF(gav);out.writeBoolean(sources!=null);if(sources!=null)out.writeUTF(sources);
        out.writeLong(stamp.size());out.writeLong(stamp.modifiedNanos());out.writeUTF(stamp.fileKey());
    }
    static MachinePath read(DataInputStream in)throws IOException{
        String location=in.readUTF(),gav=in.readUTF(),sources=in.readBoolean()?in.readUTF():null;
        return new MachinePath(location,gav,sources,new Stamp(in.readLong(),in.readLong(),in.readUTF()));
    }
}
