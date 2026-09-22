package dev.jvmd.runtime;

import dev.jvmd.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Implements 4.7: isolated javac compilation, bounded diagnostics, and class-file publication. */
public final class RuntimeCompiler {
    /** Implements 4.7: compiled bytecode stays private to the runtime implementation. */
    public record Compilation(Map<String,byte[]> classes,double elapsedMillis,String output,String mode) {
        public Compilation(Map<String,byte[]> classes,double elapsedMillis,String output){this(classes,elapsedMillis,output,"external");}
    }
    private RuntimeCompiler() { }
    public static Compilation compile(Path javaHome,Path directory,List<Path> sources,List<Path> classpath,List<Path> sourceRoots,List<String> options,Duration timeout)throws Exception{
        if(sources.isEmpty()||sources.size()>1000)throw RpcException.invalid("Compile requires 1..1000 source files");
        if(InProcessCompiler.eligible(javaHome,options))return InProcessCompiler.compile(javaHome,sources,classpath,sourceRoots,options,timeout);
        Path temporary=Files.createTempDirectory("jvmd-runtime-compile-"),output=Files.createDirectories(temporary.resolve("classes")),arguments=temporary.resolve("javac.args");
        var before=new LinkedHashMap<Path,String>();for(Path source:sources)before.put(source,Hashing.sha256(source));
        var args=new ArrayList<String>(options);if(options.stream().noneMatch(option->option.startsWith("-proc:")))args.add("-proc:none");args.addAll(List.of("-g","-parameters","-XDrawDiagnostics","-s",Files.createDirectories(temporary.resolve("generated")).toString(),"-d",output.toString()));
        if(!classpath.isEmpty())args.addAll(List.of("-classpath",String.join(java.io.File.pathSeparator,classpath.stream().map(Path::toString).toList())));
        if(!sourceRoots.isEmpty())args.addAll(List.of("-sourcepath",String.join(java.io.File.pathSeparator,sourceRoots.stream().map(Path::toString).toList())));
        sources.forEach(source->args.add(source.toString()));Files.write(arguments,args.stream().map(RuntimeCompiler::quote).toList(),StandardCharsets.UTF_8);
        var tail=new StringBuilder();long start=System.nanoTime();Process child=null;
        try{
            child=new ProcessBuilder(javaHome.resolve("bin/javac").toString(),"-J-Xmx256m","@"+arguments).directory(directory.toFile()).redirectErrorStream(true).start();child.getOutputStream().close();Process process=child;
            Thread reader=Thread.ofVirtual().name("jvmd-runtime-compiler-output").start(()->{try(var input=new java.io.InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){char[] buffer=new char[4096];int count;while((count=input.read(buffer))>=0)synchronized(tail){tail.append(buffer,0,count);if(tail.length()>32768)tail.delete(0,tail.length()-32768);}}catch(java.io.IOException ignored){}});
            if(!child.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){kill(child);throw new RpcException(-32004,"verify_failed",Map.of("reason","Runtime compilation timed out","output",tail.toString()));}
            reader.join(2000);if(child.exitValue()!=0)throw new RpcException(-32004,"verify_failed",Map.of("exit_code",child.exitValue(),"output",tail.toString()));
            for(var source:before.entrySet())if(!Hashing.sha256(source.getKey()).equals(source.getValue()))throw RpcException.invalid("Source changed during compilation: "+source.getKey());
            var classes=new LinkedHashMap<String,byte[]>();long size=0;try(var files=Files.walk(output)){for(Path file:files.filter(p->p.toString().endsWith(".class")).sorted().toList()){size+=Files.size(file);if(size>128L*1024*1024)throw new RpcException(-32005,"budget_exceeded",Map.of("reason","Compiled output exceeds 128 MiB"));classes.put(output.relativize(file).toString().replace(java.io.File.separatorChar,'/'),Files.readAllBytes(file));}}
            return new Compilation(Collections.unmodifiableMap(classes),(System.nanoTime()-start)/1e6,tail.toString());
        }finally{if(child!=null&&child.isAlive())kill(child);delete(temporary);}
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    public static void publish(Compilation compiled,Path output)throws Exception{
        output=output.toAbsolutePath().normalize();Files.createDirectories(output);
        for(var entry:compiled.classes().entrySet()){
            Path destination=output.resolve(entry.getKey()).normalize();if(!destination.startsWith(output))throw RpcException.invalid("Invalid class output path");Files.createDirectories(destination.getParent());
            Path temporary=Files.createTempFile(destination.getParent(),".jvmd-class-",".tmp");try{Files.write(temporary,entry.getValue());try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}
        }
    }
    private static void kill(Process child){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}
    private static void delete(Path directory)throws Exception{if(Files.exists(directory))try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
}
