package dev.jvmd.boot.cold.machine;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.ArtifactIndexFormat;
import dev.jvmd.index.BinaryReader;
import dev.jvmd.index.layer.machine.ArtifactBuilder;
import dev.jvmd.index.layer.machine.MachinePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One MACHINE cold boot input: an artifact found by enumeration, read into memory once. */
public sealed interface MachineInput permits MachineInput.Jar {
    String MODE="signatures";

    /** The location this input was found at, with its stamp. */
    MachinePath path();
    /** Read the binary and its paired sources into memory and hash those bytes; nothing is decompressed. */
    Raw read()throws IOException;

    /**
     * The bytes of one input, as read, with their SHA-256 and the admission estimate of decompressing
     * and parsing them, taken from the archives' central directories. Every later stage works from these.
     */
    record Raw(String binarySha256,byte[] binary,String sourcesSha256,byte[] sources,long estimatedBytes) {
        public String cacheKey(){return ArtifactIndexFormat.key(binarySha256,MODE).cacheKey();}

        /** Decompress the class files and the paired Java sources. */
        public Read decompress()throws IOException{
            ArtifactBuilder.Sources paired=null;
            if(sources!=null){
                var files=new LinkedHashMap<String,String>();
                for(var entry:BinaryReader.entries(sources,".java").entrySet())
                    if(!entry.getKey().startsWith("META-INF/"))files.put(entry.getKey(),new String(entry.getValue(),StandardCharsets.UTF_8));
                paired=new ArtifactBuilder.Sources(sourcesSha256,files);
            }
            return new Read(new ArtifactBuilder.Binary(binarySha256,MODE,BinaryReader.entries(binary,".class")),paired);
        }
    }

    /** The decompressed content of one input. */
    record Read(ArtifactBuilder.Binary binary,ArtifactBuilder.Sources sources) { }

    /** A jar in the repository, paired with its {@code -sources.jar} when there is one. */
    record Jar(MachinePath path,Path binary,Path sources) implements MachineInput {
        @Override public Raw read()throws IOException{
            byte[] bytes=Files.readAllBytes(binary);
            checkSha1(binary,bytes);
            long estimate=estimate(BinaryReader.entrySizes(bytes,".class"));
            byte[] sourceBytes=null;String sourcesSha256=null;
            if(sources!=null){
                sourceBytes=Files.readAllBytes(sources);checkSha1(sources,sourceBytes);
                sourcesSha256=Hashing.sha256(sourceBytes);estimate+=estimate(BinaryReader.entrySizes(sourceBytes,".java"))-BASE;
            }
            return new Raw(Hashing.sha256(bytes),bytes,sourcesSha256,sourceBytes,estimate);
        }
        private static void checkSha1(Path file,byte[] bytes)throws IOException{
            Path checksum=file.resolveSibling(file.getFileName()+".sha1");if(!Files.isRegularFile(checksum))return;
            String expected=Files.readString(checksum).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            try{
                String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
                if(!actual.equals(expected))throw new IOException("Checksum mismatch: "+file);
            }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }

    long BASE=8L*1024*1024;
    /** The admission estimate of decompressing and parsing entries: a base plus twelve times their size. */
    private static long estimate(BinaryReader.EntrySizes entries){return BASE+entries.bytes()*12L+entries.count()*1024L;}
}
