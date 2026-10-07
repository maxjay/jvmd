package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarFile;

/** Immutable processor byte snapshot owned by one compiler scope. Instances never retain processors or javac objects. */
public final class ProcessorPath implements java.io.Closeable {
    public record Loader(URLClassLoader value,boolean owned) { }
    public record Statistics(long hashedBytes,int openedJars,int loaders) { }
    private record Stamp(long size,java.nio.file.attribute.FileTime modified,Object key) {
        static Stamp of(Path path) throws IOException {
            var a=Files.readAttributes(path,BasicFileAttributes.class);
            if(!a.isRegularFile())throw new IOException("Processor path is not a file: "+path);
            return new Stamp(a.size(),a.lastModifiedTime(),a.fileKey());
        }
    }
    private final Path directory;
    private final List<Path> originals,copies=new ArrayList<>();
    private final List<Stamp> stamps=new ArrayList<>();
    private final List<URL> urls=new ArrayList<>();
    private final Map<String,Integer> declarations=new LinkedHashMap<>();
    private final Identity identity;
    private long bytes;
    private int opened,loaders;
    private URLClassLoader shared;
    private FrozenConfiguration configuration;
    private boolean closed;

    public ProcessorPath(List<Path> path,Digest digest,Identity expected) throws IOException {
        originals=path.stream().map(p->p.toAbsolutePath().normalize()).toList();
        directory=Files.createTempDirectory("jvmd-processors-");
        var all=digest.hasher();
        try {
            for(int i=0;i<originals.size();i++) {
                var file=originals.get(i);var stamp=Stamp.of(file);
                var copy=directory.resolve(i+".jar");copies.add(copy);Files.copy(file,copy);urls.add(copy.toUri().toURL());
                if(!Stamp.of(file).equals(stamp))throw new IOException("Processor path changed during snapshot");
                stamps.add(stamp);
                var hasher=digest.hasher();
                try(var input=Files.newInputStream(copy)) {
                    var buffer=new byte[65536];
                    for(int n;(n=input.read(buffer))>0;) {hasher.update(buffer,0,n);bytes+=n;}
                }
                all.update(hasher.finish().view(),0,digest.width());
                try(var jar=new JarFile(copy.toFile())) {
                    opened++;
                    var declaration=jar.getJarEntry("META-INF/gradle/incremental.annotation.processors");
                    if(declaration!=null)try(var input=jar.getInputStream(declaration)) {
                        new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().forEach(line->{
                            var fields=line.split("#",2)[0].strip().split(",",-1);
                            if(fields.length==2)declarations.putIfAbsent(fields[0].strip(),switch(fields[1].strip().toLowerCase(Locale.ROOT)) {
                                case "isolating"->1;case "aggregating"->2;case "dynamic"->3;default->0;
                            });
                        });
                    }
                }
            }
            identity=all.finish();
            if(!identity.equals(expected))throw new IllegalStateException("Cannot prepare annotation processors",
                    new IllegalArgumentException("Processor bytes differ from Stage 2"));
        } catch(IOException|RuntimeException|Error failure) {try{close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
    }
    public Identity identity() {return identity;}
    public synchronized void configuration(FrozenConfiguration configuration) {
        if(shared!=null)throw new IllegalStateException("Processor loader already initialized");
        this.configuration=configuration;
    }
    int declaration(String name) {return declarations.getOrDefault(name,0);}
    /** Execution always reads the immutable copies. Metadata checks also reject ordinary source-path replacement promptly. */
    public synchronized void check() throws IOException {
        if(closed)throw new IllegalStateException("Processor snapshot is closed");
        for(int i=0;i<originals.size();i++)if(!Stamp.of(originals.get(i)).equals(stamps.get(i)))
            throw new IllegalArgumentException("Processor path changed after snapshot");
    }
    synchronized Loader loader(boolean reusableOverlay) throws IOException {
        check();
        if(reusableOverlay) {
            if(shared==null) {
                shared=newLoader();loaders++;
                if(configuration!=null)try{configuration.install(shared);}
                catch(ReflectiveOperationException failure){throw new IOException("Cannot bind frozen Lombok configuration",failure);}
            }
            return new Loader(shared,false);
        }
        loaders++;return new Loader(newLoader(),true);
    }
    private URLClassLoader newLoader() {return new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader());}
    public synchronized Statistics statistics() {return new Statistics(bytes,opened,loaders);}
    @Override public synchronized void close() throws IOException {
        if(closed)return;closed=true;
        IOException failure=null;
        if(shared!=null)try{shared.close();}catch(IOException e){failure=e;}
        // Lombok's shadow loader uses JarURLConnection resources outside URLClassLoader's closeable set.
        // These private snapshot URLs belong only to this scope; release their JDK cache handles before deletion.
        for(var url:urls)try {
            var connection=(java.net.JarURLConnection)new URL("jar:"+url+"!/").openConnection();
            connection.getJarFile().close();
        }catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        for(var file:copies)try{Files.deleteIfExists(file);}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        try{Files.deleteIfExists(directory);}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
}
