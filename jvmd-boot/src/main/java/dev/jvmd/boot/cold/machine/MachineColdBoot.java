package dev.jvmd.boot.cold.machine;

import dev.jvmd.index.layer.machine.ArtifactBuilder;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachinePath;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.rocks.RocksIndexStorage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * MACHINE cold boot: builds the MACHINE layer from {@code ~/.m2} and the configured JDK because the
 * generation holds no committed MACHINE root. It never reads prior state, because there is none.
 * Each stage is one method; {@link #run} calls them in order. A failure in any stage aborts the
 * boot without writing a root.
 */
public final class MachineColdBoot {
    /** An input that could not be read or parsed, and why. It contributes no leaf. */
    public record Fault(String location,String reason) { }

    private final Path generation,repository,jdkHome;
    private final long admissionBytes;
    private final List<Fault> faults=new CopyOnWriteArrayList<>();

    /**
     * @param generation     the generation directory this boot creates
     * @param repository     the local Maven repository
     * @param jdkHome        the configured JDK, whose modules become leaves
     * @param admissionBytes the memory budget for artifacts being parsed at once
     */
    public MachineColdBoot(Path generation,Path repository,Path jdkHome,long admissionBytes){
        this.generation=generation.toAbsolutePath().normalize();this.repository=repository.toAbsolutePath().normalize();
        this.jdkHome=jdkHome.toAbsolutePath().normalize();this.admissionBytes=admissionBytes;
    }

    /** Create, enumerate, build, commit. Returns the generation's storage with MACHINE committed. */
    public RocksIndexStorage run()throws Exception{
        var storage=create();
        try{
            var inputs=enumerate();
            var leaves=buildArtifacts(storage,inputs);
            var tree=buildTree(leaves);
            commit(storage,tree);
            return storage;
        }catch(Exception|Error failure){
            try{storage.close();}catch(Exception close){failure.addSuppressed(close);}
            throw failure;
        }
    }

    /** Create the generation's storage. This is the only call site that creates it. */
    public RocksIndexStorage create()throws Exception{return RocksIndexStorage.create(generation,admissionBytes);}

    /**
     * One walk of the repository (every {@code *.jar} except {@code *-javadoc.jar}) plus the configured
     * JDK's modules, one stat per file. A {@code -sources.jar} is paired with its binary into one input.
     */
    public List<MachineInput> enumerate()throws IOException{
        var inputs=new ArrayList<MachineInput>();
        if(Files.isDirectory(repository)){
            var jars=new TreeMap<String,MachinePath.Stamp>();
            Files.walkFileTree(repository,new SimpleFileVisitor<>(){
                @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attributes){
                    String name=file.getFileName().toString();
                    if(attributes.isRegularFile()&&name.endsWith(".jar")&&!name.endsWith("-javadoc.jar"))jars.put(file.toString(),MachinePath.Stamp.of(attributes));
                    return FileVisitResult.CONTINUE;
                }
            });
            for(var jar:jars.entrySet()){
                Path binary=Path.of(jar.getKey());String name=binary.getFileName().toString();if(name.endsWith("-sources.jar"))continue;
                Path sources=binary.resolveSibling(name.substring(0,name.length()-".jar".length())+"-sources.jar");
                boolean paired=jars.containsKey(sources.toString());
                var path=new MachinePath(binary.toString(),gav(binary),paired?sources.toString():null,jar.getValue());
                inputs.add(new MachineInput.Jar(path,binary,paired?sources:null));
            }
        }
        Path modules=jdkHome.resolve("lib/modules");
        if(Files.isRegularFile(modules)){
            var stamp=MachinePath.Stamp.read(modules);String feature=feature(jdkHome);
            Path zip=jdkHome.resolve("lib/src.zip");String sources=Files.isRegularFile(zip)?zip.toString():null;
            try(var image=MachineInput.image(jdkHome);var names=Files.list(image.getPath("/modules"))){
                for(String module:names.map(path->path.getFileName().toString()).sorted().toList())
                    inputs.add(new MachineInput.JdkModule(new MachinePath("jrt:/"+module,"jdk:"+module+":"+feature,sources,stamp),jdkHome,module));
            }
        }
        return List.copyOf(inputs);
    }

    /**
     * One job per input on a bounded pool. Equal contents build once; every path is attached to its
     * content's leaf afterwards, so the leaves do not depend on which job built them.
     */
    public List<MachineLeaf> buildArtifacts(RocksIndexStorage storage,List<MachineInput> inputs)throws Exception{
        var claims=new ClaimMap();var builder=new ArtifactBuilder(storage.repository());
        int threads=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()));
        var built=new ArrayList<MachineLeaf>();
        try(var pool=Executors.newFixedThreadPool(threads,Thread.ofVirtual().name("jvmd-machine-cold-boot-",0).factory())){
            var jobs=new ArrayList<Future<ArtifactJob.Outcome>>(inputs.size());
            for(var input:inputs)jobs.add(pool.submit(new ArtifactJob(input,claims,storage.admission(),builder)));
            for(var job:jobs){
                switch(get(job)){
                    case ArtifactJob.Built value->built.add(value.leaf());
                    case ArtifactJob.Faulted value->faults.add(new Fault(value.location(),value.reason()));
                    case ArtifactJob.Duplicate _->{}
                }
            }
        }
        var leaves=new ArrayList<MachineLeaf>(built.size());
        for(var leaf:built)leaves.add(leaf.withPaths(claims.paths(leaf.cacheKey())));
        return leaves;
    }

    /** Bulk-build the MACHINE tree, its aggregates and its path table from the leaves. */
    public MachineTree buildTree(List<MachineLeaf> leaves){return MachineTree.build(leaves);}

    /** Write leaves, nodes and the path table, then the root, and publish the committed tree. */
    public void commit(RocksIndexStorage storage,MachineTree tree)throws Exception{
        storage.machineStore().commit(tree);
        storage.machine().committed(tree);
    }

    /** Inputs this boot could not read or parse. */
    public List<Fault> faults(){return List.copyOf(faults);}

    private String gav(Path path){
        Path relative=repository.relativize(path);int n=relative.getNameCount();
        if(n<4)return "local:"+path.getFileName()+":0";
        return relative.subpath(0,n-3).toString().replace(java.io.File.separatorChar,'.')+":"+relative.getName(n-3)+":"+relative.getName(n-2);
    }

    private static String feature(Path jdkHome)throws IOException{
        Path release=jdkHome.resolve("release");
        if(Files.isRegularFile(release))for(String line:Files.readAllLines(release)){
            if(!line.startsWith("JAVA_VERSION="))continue;
            String version=line.substring("JAVA_VERSION=".length()).replace("\"","");
            return Integer.toString(Runtime.Version.parse(version).feature());
        }
        return Integer.toString(Runtime.version().feature());
    }

    private static ArtifactJob.Outcome get(Future<ArtifactJob.Outcome> job)throws Exception{
        try{return job.get();}
        catch(ExecutionException failed){
            if(failed.getCause() instanceof Exception cause)throw cause;
            if(failed.getCause() instanceof Error error)throw error;
            throw failed;
        }
    }
}
