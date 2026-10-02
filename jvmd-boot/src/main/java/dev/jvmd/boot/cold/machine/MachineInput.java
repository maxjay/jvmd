package dev.jvmd.boot.cold.machine;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.BinaryReader;
import dev.jvmd.index.layer.machine.ArtifactBuilder;
import dev.jvmd.index.layer.machine.MachinePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One MACHINE cold boot input: an artifact found by enumeration, read into memory once. */
public sealed interface MachineInput permits MachineInput.Jar {
    /** The location this input was found at, with its stamp. */
    MachinePath path();
    /** Read the binary and its paired sources into memory and hash those bytes. */
    Read read()throws IOException;

    /** The bytes of one input; every later stage works from these. */
    record Read(ArtifactBuilder.Binary binary,ArtifactBuilder.Sources sources,long estimatedBytes) { }

    /** A jar in the repository, paired with its {@code -sources.jar} when there is one. */
    record Jar(MachinePath path,Path binary,Path sources) implements MachineInput {
        @Override public Read read()throws IOException{
            byte[] bytes=Files.readAllBytes(binary);
            checkSha1(binary,bytes);
            var classes=BinaryReader.entries(bytes,".class");
            ArtifactBuilder.Sources paired=null;long estimate=estimate(classes.values());
            if(sources!=null){
                byte[] sourceBytes=Files.readAllBytes(sources);checkSha1(sources,sourceBytes);
                var files=new LinkedHashMap<String,String>();var raw=BinaryReader.entries(sourceBytes,".java");
                for(var entry:raw.entrySet())if(!entry.getKey().startsWith("META-INF/"))files.put(entry.getKey(),new String(entry.getValue(),StandardCharsets.UTF_8));
                paired=new ArtifactBuilder.Sources(Hashing.sha256(sourceBytes),files);estimate+=estimate(raw.values())-BASE;
            }
            return new Read(new ArtifactBuilder.Binary(Hashing.sha256(bytes),"signatures",classes),paired,estimate);
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
    /** The admission estimate of parsing these entries: a base plus twelve times their size. */
    private static long estimate(Collection<byte[]> entries){
        long estimate=BASE;for(byte[] entry:entries)estimate+=entry.length*12L+1024L;return estimate;
    }
}
