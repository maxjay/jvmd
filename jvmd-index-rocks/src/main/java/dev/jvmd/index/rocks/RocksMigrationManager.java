package dev.jvmd.index.rocks;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Versioned candidate/active layout for disposable Rocks index data.
 * Only a validated generation may be atomically selected as active; the prior active generation
 * remains referenced for rollback.
 */
public final class RocksMigrationManager {
    public record Manifest(int format,String active,String previous,long switchedAtMillis) {
        public Manifest {
            active=active==null?"":active;previous=previous==null?"":previous;
            if(format!=1)throw new IllegalArgumentException("manifest format");
        }
    }

    private static final String MANIFEST="active.manifest",VALIDATED="VALIDATED";
    private final Path root,generations,manifest;

    public RocksMigrationManager(Path root)throws Exception{
        this.root=root.toAbsolutePath().normalize();
        this.generations=this.root.resolve("generations");
        this.manifest=this.root.resolve(MANIFEST);
        Files.createDirectories(generations);
    }

    public synchronized Path candidate(String generation)throws Exception{
        validateGeneration(generation);
        Path path=generations.resolve(generation);
        Files.createDirectories(path);
        return path;
    }

    public synchronized void markValidated(String generation)throws Exception{
        Path path=candidate(generation),marker=path.resolve(VALIDATED);
        writeDurable(marker,"validated\n");
    }

    public synchronized boolean validated(String generation){
        validateGeneration(generation);
        return Files.isRegularFile(generations.resolve(generation).resolve(VALIDATED));
    }

    public synchronized Manifest manifest()throws Exception{
        if(!Files.isRegularFile(manifest))return new Manifest(1,"","",0);
        var values=new LinkedHashMap<String,String>();
        for(String line:Files.readAllLines(manifest,StandardCharsets.UTF_8)){
            if(line.isBlank())continue;int split=line.indexOf('=');
            if(split<=0)throw new IOException("Invalid index migration manifest");
            values.put(line.substring(0,split),line.substring(split+1));
        }
        if(!"1".equals(values.get("format")))throw new IOException("Unsupported index migration manifest");
        return new Manifest(1,values.getOrDefault("active",""),values.getOrDefault("previous",""),
                Long.parseLong(values.getOrDefault("switched_at","0")));
    }

    public synchronized Manifest activate(String generation)throws Exception{
        validateGeneration(generation);
        if(!validated(generation))throw new IllegalStateException("Generation is not validated: "+generation);
        Manifest current=manifest();
        if(current.active().equals(generation))return current;
        Manifest next=new Manifest(1,generation,current.active(),System.currentTimeMillis());
        writeManifest(next);return next;
    }

    public synchronized Manifest rollback()throws Exception{
        Manifest current=manifest();
        if(current.previous().isBlank())throw new IllegalStateException("No previous index generation");
        if(!validated(current.previous()))throw new IllegalStateException("Previous generation is not validated: "+current.previous());
        Manifest next=new Manifest(1,current.previous(),current.active(),System.currentTimeMillis());
        writeManifest(next);return next;
    }

    public synchronized Optional<Path> active()throws Exception{
        String value=manifest().active();return value.isBlank()?Optional.empty():Optional.of(generations.resolve(value));
    }

    public synchronized Optional<Path> previous()throws Exception{
        String value=manifest().previous();return value.isBlank()?Optional.empty():Optional.of(generations.resolve(value));
    }

    public Path root(){return root;}

    private void writeManifest(Manifest value)throws Exception{
        String text="format=1\nactive="+value.active()+"\nprevious="+value.previous()+"\nswitched_at="+value.switchedAtMillis()+"\n";
        Path temp=Files.createTempFile(root,"active-", ".tmp");
        try{
            writeDurable(temp,text);
            try{Files.move(temp,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,manifest,StandardCopyOption.REPLACE_EXISTING);}
            forceDirectory(root);
        }finally{Files.deleteIfExists(temp);}
    }

    private static void writeDurable(Path path,String text)throws Exception{
        Files.writeString(path,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}
        forceDirectory(path.getParent());
    }

    private static void forceDirectory(Path directory){
        try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
        catch(Exception ignored){}
    }

    private static void validateGeneration(String generation){
        if(generation==null||!generation.matches("[A-Za-z0-9._-]{1,128}"))throw new IllegalArgumentException("generation");
    }
}
