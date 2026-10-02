package dev.jvmd.index.machine;

import dev.jvmd.core.CanonicalDigestWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * The inputs of a MACHINE cold boot: every artifact under the Maven repository and every module of
 * the configured JDK, found by one walk with one stat per file, then identified by content.
 */
public final class MachineInputs {
    public enum Kind { JAR, JDK_MODULE }

    /** File metadata observed once during enumeration. */
    public record Stamp(long size,long modifiedNanos,long changedNanos,String fileKey) {
        public Stamp { fileKey=Objects.requireNonNullElse(fileKey,""); }
        static Stamp of(Path path,BasicFileAttributes basic){
            long modified=basic.lastModifiedTime().to(TimeUnit.NANOSECONDS),changed=modified;
            try{if(Files.getAttribute(path,"unix:ctime") instanceof FileTime value)changed=value.to(TimeUnit.NANOSECONDS);}
            catch(UnsupportedOperationException|IllegalArgumentException|IOException ignored){}
            return new Stamp(basic.size(),modified,changed,Objects.toString(basic.fileKey(),""));
        }
    }

    /** A source archive paired with its binary by Maven naming. */
    public record Sources(Path path,Stamp stamp,String gav) {
        public Sources { Objects.requireNonNull(path);Objects.requireNonNull(stamp);Objects.requireNonNull(gav); }
    }

    /** One enumerated binary. {@code location} is its stable address: a file path or {@code jrt:/module}. */
    public record Input(Path path,String location,Stamp stamp,String gav,Kind kind,Sources sources) {
        public Input {
            Objects.requireNonNull(path);Objects.requireNonNull(location);Objects.requireNonNull(stamp);
            Objects.requireNonNull(gav);Objects.requireNonNull(kind);
        }
    }

    /** One distinct binary content and every input that has it, ordered by location. */
    public record Content(String sha256,Kind kind,List<Input> inputs) {
        public Content {
            Objects.requireNonNull(sha256);Objects.requireNonNull(kind);
            inputs=inputs.stream().sorted(Comparator.comparing(Input::location)).toList();
            if(inputs.isEmpty())throw new IllegalArgumentException("Content without inputs");
        }
        /** The input whose bytes are parsed. */
        public Input primary(){return inputs.getFirst();}
        /** The first input that has paired sources; its sources give the content's documentation. */
        public Optional<Input> documented(){return inputs.stream().filter(input->input.sources()!=null).findFirst();}
    }

    /** An enumerated file that is not a valid artifact, with the reason. It never becomes a leaf. */
    public record Rejected(String location,String reason) { }

    public record Identified(List<Content> contents,List<Rejected> rejected) {
        public Identified { contents=List.copyOf(contents);rejected=List.copyOf(rejected); }
    }

    private MachineInputs(){}

    /**
     * Walks the repository once for {@code *.jar} (except {@code -javadoc.jar}), pairs each
     * {@code -sources.jar} with its binary, and lists the configured JDK's modules. A JDK without a
     * runtime image ({@code lib/modules}) contributes no modules.
     */
    public static List<Input> enumerate(Path repository,Path jdkHome)throws IOException{
        var inputs=new ArrayList<Input>();
        Path root=repository.toAbsolutePath().normalize();
        if(Files.isDirectory(root)){
            var binaries=new TreeMap<Path,BasicFileAttributes>();var sources=new HashMap<Path,BasicFileAttributes>();
            Files.walkFileTree(root,new SimpleFileVisitor<>(){
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes){
                    String name=file.getFileName().toString();
                    if(!attributes.isRegularFile()||!name.endsWith(".jar")||name.endsWith("-javadoc.jar"))return FileVisitResult.CONTINUE;
                    if(name.endsWith("-sources.jar"))sources.put(file,attributes);else binaries.put(file,attributes);
                    return FileVisitResult.CONTINUE;
                }
            });
            for(var binary:binaries.entrySet()){
                Path path=binary.getKey();
                Path sourcePath=path.resolveSibling(path.getFileName().toString().replaceFirst("\\.jar$","-sources.jar"));
                var sourceAttributes=sources.get(sourcePath);
                var paired=sourceAttributes==null?null:new Sources(sourcePath,Stamp.of(sourcePath,sourceAttributes),gav(root,sourcePath));
                inputs.add(new Input(path,path.toString(),Stamp.of(path,binary.getValue()),gav(root,path),Kind.JAR,paired));
            }
        }
        Path image=jdkHome.toAbsolutePath().normalize().resolve("lib/modules");
        if(Files.isRegularFile(image)){
            var stamp=Stamp.of(image,Files.readAttributes(image,BasicFileAttributes.class));
            var modules=jrt(jdkHome).getPath("/modules");
            try(var listing=Files.list(modules)){
                for(Path module:listing.sorted().toList()){
                    String name=module.getFileName().toString();
                    inputs.add(new Input(module,"jrt:/"+name,stamp,"jdk:"+name+":"+Runtime.version().feature(),Kind.JDK_MODULE,null));
                }
            }
        }
        return List.copyOf(inputs);
    }

    /**
     * Reads every binary once, computing its SHA-256 and, in the same pass, the SHA-1 compared with
     * a {@code .sha1} sibling. A JDK module's content is its module name within the runtime image,
     * which is hashed once for all modules. Inputs are grouped by content.
     */
    public static Identified identify(List<Input> inputs,ExecutorService readers)throws Exception{
        var images=new ConcurrentHashMap<Path,String>();
        var jobs=new ArrayList<Future<Object>>(inputs.size());
        for(var input:inputs)jobs.add(readers.submit(()->identify(input,images)));
        var byContent=new TreeMap<String,List<Input>>();var kinds=new HashMap<String,Kind>();var rejected=new ArrayList<Rejected>();
        for(int i=0;i<inputs.size();i++){
            Object result;
            try{result=jobs.get(i).get();}
            catch(ExecutionException failure){throw failure.getCause() instanceof Exception cause?cause:failure;}
            var input=inputs.get(i);
            if(result instanceof Rejected value)rejected.add(value);
            else{byContent.computeIfAbsent((String)result,_->new ArrayList<>()).add(input);kinds.put((String)result,input.kind());}
        }
        var contents=new ArrayList<Content>(byContent.size());
        byContent.forEach((sha,group)->contents.add(new Content(sha,kinds.get(sha),group)));
        rejected.sort(Comparator.comparing(Rejected::location));
        return new Identified(contents,rejected);
    }

    private static Object identify(Input input,Map<Path,String> images)throws Exception{
        if(input.kind()==Kind.JDK_MODULE){
            String imageHash=images.computeIfAbsent(jdkImage(input.path()),path->{
                try{return digests(path)[0];}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
            });
            return CanonicalDigestWriter.digest("jdk-module-content-v1",imageHash,input.path().getFileName().toString()).hex();
        }
        String[] digests=digests(input.path());
        Path checksum=input.path().resolveSibling(input.path().getFileName()+".sha1");
        if(Files.isRegularFile(checksum)){
            String expected=Files.readString(checksum,StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if(!expected.equals(digests[1]))return new Rejected(input.location(),"checksum mismatch with "+checksum.getFileName());
        }
        return digests[0];
    }

    /** SHA-256 and SHA-1 of a file in one read. */
    private static String[] digests(Path path)throws IOException{
        try{
            var sha256=MessageDigest.getInstance("SHA-256");var sha1=MessageDigest.getInstance("SHA-1");
            try(InputStream in=Files.newInputStream(path)){
                byte[] buffer=new byte[65536];
                for(int read;(read=in.read(buffer))>=0;){sha256.update(buffer,0,read);sha1.update(buffer,0,read);}
            }
            var hex=HexFormat.of();return new String[]{hex.formatHex(sha256.digest()),hex.formatHex(sha1.digest())};
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }

    private static final Map<Path,FileSystem> JRT=new ConcurrentHashMap<>();
    private static final Map<FileSystem,Path> IMAGES=new ConcurrentHashMap<>();
    /** The runtime-image file system of a JDK; the running JDK's is shared, any other is opened once. */
    static FileSystem jrt(Path jdkHome)throws IOException{
        Path home=jdkHome.toAbsolutePath().normalize();
        try{
            return JRT.computeIfAbsent(home,key->{
                try{
                    var system=key.equals(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize())
                            ?FileSystems.getFileSystem(URI.create("jrt:/"))
                            :FileSystems.newFileSystem(URI.create("jrt:/"),Map.of("java.home",key.toString()));
                    IMAGES.put(system,key.resolve("lib/modules"));return system;
                }catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
            });
        }catch(java.io.UncheckedIOException failure){throw failure.getCause();}
    }
    private static Path jdkImage(Path module){return Objects.requireNonNull(IMAGES.get(module.getFileSystem()),"Unknown JDK image");}

    /** Maven coordinates from the repository layout: group/artifact/version/file. */
    static String gav(Path repository,Path file){
        Path relative=repository.relativize(file);int n=relative.getNameCount();
        if(n<4)return "local:"+file.getFileName()+":0";
        return relative.subpath(0,n-3).toString().replace(java.io.File.separatorChar,'.')+":"+relative.getName(n-3)+":"+relative.getName(n-2);
    }
}
