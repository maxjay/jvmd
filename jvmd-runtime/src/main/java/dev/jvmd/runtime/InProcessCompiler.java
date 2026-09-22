package dev.jvmd.runtime;

import dev.jvmd.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.*;

/** Implements R1 and 4.7: public javac compilation without processor execution or a child-JVM startup. */
final class InProcessCompiler {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("jvmd-runtime-compiler").factory());
    private static volatile boolean quarantined;
    private InProcessCompiler() { }
    static boolean eligible(Path javaHome,List<String> options)throws IOException{
        if(quarantined)return false;
        for(String option:options)if(option.startsWith("-J")||option.startsWith("@")||option.startsWith("-Xplugin")||option.startsWith("-processor")||option.startsWith("--processor")||option.startsWith("-proc:")&&!option.equals("-proc:none"))return false;
        for(String option:options)for(String location:List.of("-cp","-classpath","--class-path","-sourcepath","--source-path","-d","-s","-h","--module-path","-p","--module-source-path","--patch-module","-extdirs","-endorseddirs","-bootclasspath","-Xbootclasspath"))
            if(option.equals(location)||option.startsWith(location+"=")||option.startsWith(location+":"))return false;
        Path release=javaHome.resolve("release");if(!Files.isRegularFile(release))return false;
        var metadata=new Properties();try(var input=Files.newInputStream(release)){metadata.load(input);}
        String version=metadata.getProperty("JAVA_RUNTIME_VERSION","").replace("\"",""),vendor=metadata.getProperty("IMPLEMENTOR","").replace("\"","");
        return version.equals(System.getProperty("java.runtime.version"))&&vendor.equals(System.getProperty("java.vendor"));
    }
    static RuntimeCompiler.Compilation compile(Path javaHome,List<Path> sources,List<Path> classpath,List<Path> roots,List<String> options,Duration timeout)throws Exception{
        Future<RuntimeCompiler.Compilation> future=WORKER.submit(()->compile(javaHome,sources,classpath,roots,options));
        try{return future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(TimeoutException error){
            // Javac has no force-stop API. Retire this single worker permanently, and use
            // external compilation for future requests instead of accumulating stuck compilers.
            quarantined=true;future.cancel(true);WORKER.shutdownNow();
            throw new RpcException(-32004,"verify_failed",Map.of("reason","Runtime compilation timed out; in-process compiler retired"));
        }catch(InterruptedException error){future.cancel(true);Thread.currentThread().interrupt();throw error;}
        catch(ExecutionException error){if(error.getCause() instanceof Exception cause)throw cause;if(error.getCause() instanceof Error cause)throw cause;throw error;}
    }
    private static RuntimeCompiler.Compilation compile(Path javaHome,List<Path> sources,List<Path> classpath,List<Path> roots,List<String> configured)throws Exception{
        long started=System.nanoTime();var before=new LinkedHashMap<Path,String>();for(Path source:sources)before.put(source,Hashing.sha256(source));
        Path temporary=Files.createTempDirectory("jvmd-runtime-compile-"),output=Files.createDirectories(temporary.resolve("classes"));
        var tail=new Tail();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,Locale.ROOT,StandardCharsets.UTF_8)){
            // Never fall back to the daemon's classpath. These are compiler inputs only.
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH,classpath);
            manager.setLocationFromPaths(StandardLocation.SOURCE_PATH,roots);
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(output));
            manager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT,List.of(Files.createDirectories(temporary.resolve("generated"))));
            var options=new ArrayList<String>();boolean system=false;
            for(int i=0;i<configured.size();i++){
                String option=configured.get(i);
                if(option.equals("--release")&&i+1<configured.size()&&configured.get(i+1).equals(Integer.toString(Runtime.version().feature()))){
                    String release=configured.get(++i);options.addAll(List.of("-source",release,"-target",release));system=true;
                }else if(option.equals("--release="+Runtime.version().feature())){String release=Integer.toString(Runtime.version().feature());options.addAll(List.of("-source",release,"-target",release));system=true;}
                else options.add(option);
            }
            boolean release=options.stream().anyMatch(o->o.equals("--release")||o.startsWith("--release="));
            if(system||!release){if(options.stream().noneMatch(o->o.equals("--system")||o.startsWith("--system=")))options.addAll(List.of("--system",javaHome.toString()));}
            options.addAll(List.of("-proc:none","-g","-parameters"));
            DiagnosticListener<JavaFileObject> diagnostics=diagnostic->{
                String message=(diagnostic.getSource()==null?"":diagnostic.getSource().getName()+":"+diagnostic.getLineNumber()+": ")+diagnostic.getCode()+": "+diagnostic.getMessage(Locale.ROOT)+"\n";
                tail.write(message,0,message.length());
            };
            boolean success=compiler.getTask(tail,manager,diagnostics,options,null,manager.getJavaFileObjectsFromPaths(sources)).call();
            if(!success)throw new RpcException(-32004,"verify_failed",Map.of("exit_code",1,"output",tail.toString()));
            for(var entry:before.entrySet())if(!Hashing.sha256(entry.getKey()).equals(entry.getValue()))throw RpcException.invalid("Source changed during compilation: "+entry.getKey());
            var classes=new LinkedHashMap<String,byte[]>();long bytes=0;
            try(var files=Files.walk(output)){for(Path file:files.filter(p->p.toString().endsWith(".class")).sorted().toList()){
                bytes+=Files.size(file);if(bytes>128L*1024*1024)throw new RpcException(-32005,"budget_exceeded",Map.of("reason","Compiled output exceeds 128 MiB"));
                classes.put(output.relativize(file).toString().replace(File.separatorChar,'/'),Files.readAllBytes(file));
            }}
            return new RuntimeCompiler.Compilation(Collections.unmodifiableMap(classes),(System.nanoTime()-started)/1e6,tail.toString(),"in_process");
        }finally{try(var files=Files.walk(temporary)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
    private static final class Tail extends Writer {
        private final StringBuilder value=new StringBuilder();
        @Override public synchronized void write(char[] chars,int offset,int length){value.append(chars,offset,length);trim();}
        @Override public synchronized void write(String text,int offset,int length){value.append(text,offset,offset+length);trim();}
        private void trim(){if(value.length()>32768)value.delete(0,value.length()-32768);}
        @Override public void flush() { }
        @Override public void close() { }
        @Override public synchronized String toString(){return value.toString();}
    }
}
