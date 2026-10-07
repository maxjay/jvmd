package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.machine.Keys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Scope-owned configuration bytes for the supported Lombok overlay. Native parsing and bubbling
 * consume these bytes, not mutable filesystem files. Other processors retain their native execution.
 */
public final class FrozenConfiguration {
    public record Statistics(long filesRead,long bytesHashed,long metadataChecks) { }
    private record Stamp(long size,java.nio.file.attribute.FileTime modified,Object key) {
        static Stamp of(Path path) throws IOException {
            try {
                var a=Files.readAttributes(path,BasicFileAttributes.class);
                return a.isRegularFile()?new Stamp(a.size(),a.lastModifiedTime(),a.fileKey()):null;
            } catch(java.nio.file.NoSuchFileException missing) {return null;}
        }
    }
    private record Input(Stamp stamp,Entry entry) { }
    private final Path project;
    private final Map<Path,Input> inputs=new HashMap<>();
    private final Map<URI,ProcessorConfiguration.Snapshot> sources=new HashMap<>();
    private long filesRead,bytesHashed,metadataChecks;

    public FrozenConfiguration(Path project) {this.project=project.toAbsolutePath().normalize();}

    public synchronized ProcessorConfiguration.Snapshot current(Digest digest,String source) throws IOException {
        var uri=project.resolve(source).normalize().toUri();
        var snapshot=sources.get(uri);
        if(snapshot!=null) {
            // Imports remain unsupported for reuse and run through the original native resolver.
            if(!snapshot.unmodelledImports().isEmpty())return ProcessorConfiguration.current(digest,project,source);
            for(var entry:snapshot.proof())check(project.resolve(entry.path()).normalize());
            return snapshot;
        }
        var imports=new ArrayList<String>();
        try {
            var proof=ProcessorConfiguration.proof(digest,project,source,file->{
                try {
                    var input=read(digest,file);
                    if(input.entry()!=null && ProcessorConfiguration.imports(input.entry().value()))
                        imports.add(ProcessorConfiguration.path(project,file));
                    return input.entry();
                }catch(IOException failure){throw new UncheckedIOException(failure);}
            });
            snapshot=new ProcessorConfiguration.Snapshot(proof,List.copyOf(imports));
            sources.put(uri,snapshot);return snapshot;
        }catch(UncheckedIOException failure){throw failure.getCause();}
    }
    private Input read(Digest digest,Path path) throws IOException {
        path=path.toAbsolutePath().normalize();
        if(inputs.containsKey(path)){check(path);return inputs.get(path);}
        var before=Stamp.of(path);Entry entry=null;
        if(before!=null) {
            var bytes=Files.readAllBytes(path);filesRead++;bytesHashed+=bytes.length;
            var key=Keys.resourceKey(ProcessorConfiguration.path(project,path));
            entry=new Entry(key,bytes,digest.hash(key,digest.hash(bytes).view()));
        }
        if(!Objects.equals(before,Stamp.of(path)))throw new IOException("Processor configuration changed during snapshot");
        var input=new Input(before,entry);inputs.put(path,input);return input;
    }
    private void check(Path path) throws IOException {
        metadataChecks++;
        if(!Objects.equals(inputs.get(path).stamp(),Stamp.of(path)))
            throw new IllegalArgumentException("Processor configuration changed after snapshot: "+path);
    }
    private synchronized boolean nativeImports(URI source) {
        if(source==null)return false;
        var snapshot=sources.get(source.normalize());
        if(snapshot==null)throw new IllegalStateException("Unproved Lombok configuration source: "+source);
        return !snapshot.unmodelledImports().isEmpty();
    }
    private synchronized byte[] bytes(Path path) {
        var input=inputs.get(path.toAbsolutePath().normalize());
        if(input==null)throw new IllegalStateException("Unproved Lombok configuration path: "+path);
        return input.entry()==null?null:input.entry().value();
    }
    public synchronized Statistics statistics(){return new Statistics(filesRead,bytesHashed,metadataChecks);}

    /** Install once in a scope's private shadow loader, preserving native parser diagnostics and original paths. */
    void install(ClassLoader launch) throws ReflectiveOperationException {
        var main=Class.forName("lombok.launch.Main",true,launch);
        var shadow=main.getDeclaredMethod("getShadowClassLoader");shadow.setAccessible(true);
        var loader=(ClassLoader)shadow.invoke(null);
        var config=Class.forName("lombok.core.LombokConfiguration",true,loader);
        if(System.getProperty("lombok.disableConfig")!=null)return;
        String pkg="lombok.core.configuration.";
        var factory=Class.forName(pkg+"ConfigurationResolverFactory",true,loader);
        var resolver=config.getDeclaredField("configurationResolverFactory");resolver.setAccessible(true);
        var original=resolver.get(null);var create=factory.getMethod("createResolver",URI.class);
        var file=Class.forName(pkg+"ConfigurationFile",true,loader);
        var directory=file.getMethod("forDirectory",java.io.File.class);
        var text=file.getMethod("fromCharSequence",String.class,CharSequence.class,long.class);
        var description=file.getDeclaredMethod("description");description.setAccessible(true);
        var mapper=Class.forName(pkg+"ConfigurationFileToSource",true,loader);
        var reporter=Class.forName(pkg+"ConfigurationProblemReporter",true,loader);
        var parser=Class.forName(pkg+"ConfigurationParser",true,loader);
        var nativeParser=parser.getConstructor(reporter).newInstance(reporter.getField("CONSOLE").get(null));
        var cache=Class.forName(pkg+"FileSystemSourceCache",true,loader);
        var nativeMapper=cache.getMethod("fileToSource",parser).invoke(cache.getConstructor().newInstance(),nativeParser);
        var parsed=mapper.getMethod("parsed",file);
        var parsedInputs=new HashMap<String,Object>();
        var frozenMapper=Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method.getName(),args);
            var name=(String)description.invoke(args[0]);
            synchronized(parsedInputs) {
                if(parsedInputs.containsKey(name))return parsedInputs.get(name);
                var bytes=bytes(Path.of(name));
                var value=bytes==null?null:parsed.invoke(nativeMapper,text.invoke(null,name,new String(bytes,StandardCharsets.UTF_8),1L));
                parsedInputs.put(name,value);return value;
            }
        });
        var bubbling=Class.forName(pkg+"BubblingConfigurationResolver",true,loader).getConstructor(file,mapper);
        var frozenFactory=Proxy.newProxyInstance(loader,new Class<?>[]{factory},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method.getName(),args);
            var uri=(URI)args[0];
            if(nativeImports(uri))return create.invoke(original,uri);
            var start=uri==null?null:directory.invoke(null,Path.of(uri).getParent().toFile());
            return bubbling.newInstance(start,frozenMapper);
        });
        config.getMethod("overrideConfigurationResolverFactory",factory).invoke(null,frozenFactory);
    }
    private static Object objectMethod(Object proxy,String method,Object[] args) {
        return switch(method) {case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);
            case "toString"->"JVMD frozen configuration";default->throw new IllegalArgumentException(method);};
    }
}
