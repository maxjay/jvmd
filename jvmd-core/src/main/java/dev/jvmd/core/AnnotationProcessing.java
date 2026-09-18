package dev.jvmd.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Implements phase 5: time-boxed external processors and content-addressed generated output. */
public final class AnnotationProcessing implements AutoCloseable {
    /** Implements phase 5: detached processor request; no user class is loaded in this JVM. */
    public record Request(String key,Path directory,List<Path> sourceRoots,List<Path> classpath,
                          List<Path> processorPath,List<String> processors,List<String> compilerOptions,
                          boolean lombok) { }
    /** Detached diagnostic emitted by the process-isolated compiler. */
    public record Problem(String code,String kind,String file,long line,long character,String message) { }
    /** Implements phase 5/diagnostics phase 9: generated APIs plus optional external semantic diagnostics. */
    public record Output(String fingerprint,List<Path> sourceRoots,List<Path> classpath,Set<Path> binarySources,
                         List<Problem> diagnostics,String diagnosticFidelity,List<String> warnings,
                         int exitCode,boolean timedOut,long elapsedMillis,String log) { }
    private final Config config;
    private record CachedOutput(String inputFingerprint,Output output) { }
    private final Map<String,CachedOutput> cache=new LinkedHashMap<>();
    private record Hashed(Map<String,Object> stamp,String hash) { }
    private final Map<Path,Hashed> hashes=new HashMap<>();
    private long runs,hits,bytesHashed;
    private volatile Process active;
    public AnnotationProcessing(Config config){this.config=config;}
    public synchronized Output prepare(Request request,Duration timeout)throws Exception {
        var inputs=javaFiles(request.sourceRoots());
        var fingerprint=new StringBuilder(Json.MAPPER.writeValueAsString(request));
        for(Path input:inputs)fingerprint.append(input).append(contentHash(input));
        var binaries=new LinkedHashSet<Path>(request.classpath());binaries.addAll(request.processorPath());
        for(Path path:binaries) {
            if(Files.isRegularFile(path))fingerprint.append(path).append(contentHash(path));
            else if(Files.isDirectory(path))try(var entries=Files.walk(path)){for(Path file:entries.filter(Files::isRegularFile).sorted().toList())fingerprint.append(file).append(contentHash(file));}
        }
        // Lombok's own configuration can change generated signatures without a source edit.
        for(Path path=request.directory();path!=null;path=path.getParent()){Path file=path.resolve("lombok.config");if(Files.isRegularFile(file))fingerprint.append(file).append(contentHash(file));}
        String hash=Hashing.sha256(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
        var priorEntry=cache.get(request.key());var prior=priorEntry==null?null:priorEntry.output();
        if(priorEntry!=null&&priorEntry.inputFingerprint().equals(hash)&&available(prior)){hits++;return prior;}
        long started=System.nanoTime();var warnings=new ArrayList<String>();
        if(request.lombok())warnings.add("lombok_reduced_fidelity: generated member bodies and positions are unavailable; diagnostics use external javac when available");
        Path workspace=config.stateDir().resolve("apt").resolve(Hashing.sha256(request.key().getBytes(StandardCharsets.UTF_8)));
        Files.createDirectories(workspace);Path work=Files.createTempDirectory(workspace,hash.substring(0,12)+"-");
        Path generated=Files.createDirectories(work.resolve("sources")),classes=Files.createDirectories(work.resolve("classes")),log=work.resolve("processor.log");
        int exit=0;boolean timedOut=false,fullLombok=false;var binarySources=new LinkedHashSet<Path>();
        try{
            if(!inputs.isEmpty()){
                var result=invoke(request,inputs,generated,classes,work,log,"only",timeout);exit=result.exitCode();timedOut=result.timedOut();runs++;
                if(exit==0&&!timedOut&&request.lombok()){
                    long remaining=timeout.toMillis()-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                    if(remaining<=0){timedOut=true;exit=-1;}
                    else{result=invoke(request,inputs,generated,classes,work,log,"full",Duration.ofMillis(remaining));exit=result.exitCode();timedOut=result.timedOut();fullLombok=!timedOut;runs++;}
                    if(exit==0&&!timedOut)for(Path input:inputs)if(lombokSource(Files.readString(input)))binarySources.add(input.toAbsolutePath().normalize());
                }
            }
            String output=Files.exists(log)?Files.readString(log):"";
            if(exit!=0||timedOut)warnings.add((timedOut?"annotation_processing_timeout":"annotation_processing_failed")+": exit="+exit+"; "+output);
            var diagnostics=fullLombok?diagnostics(request.directory(),output):List.<Problem>of();
            String fidelity=fullLombok?"full_lombok_external":request.lombok()?"reduced_lombok":"processor";
            if(request.lombok())warnings.add("lombok_diagnostic_fidelity="+fidelity);
            String semantic=exit==0&&!timedOut?outputFingerprint(generated,classes,request.lombok()):hash;
            var result=new Output(semantic,List.of(generated),exit==0&&request.lombok()?List.of(classes):List.of(),Set.copyOf(binarySources),
                    diagnostics,fidelity,List.copyOf(warnings),exit,timedOut,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),output);
            // If a rerun produces the same detached output, retain the old paths as well as the
            // semantic fingerprint. Downstream compiler contexts therefore remain stable.
            if(prior!=null&&prior.fingerprint().equals(semantic)&&available(prior)){
                deleteTree(work);
                result=new Output(semantic,prior.sourceRoots(),prior.classpath(),Set.copyOf(binarySources),
                        diagnostics,fidelity,List.copyOf(warnings),exit,timedOut,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),output);
            }else if(prior!=null)for(Path source:prior.sourceRoots())deleteTree(source.getParent());
            cache.put(request.key(),new CachedOutput(hash,result));
            return result;
        }catch(Exception e){deleteTree(work);throw e;}
    }
    private String contentHash(Path path)throws IOException {
        Map<String,Object> stamp;
        try{stamp=Files.readAttributes(path,"unix:size,lastModifiedTime,ctime,ino");}
        catch(UnsupportedOperationException e){bytesHashed+=Files.size(path);return Hashing.sha256(path);}
        var previous=hashes.get(path);if(previous!=null&&previous.stamp().equals(stamp))return previous.hash();
        String hash=Hashing.sha256(path);bytesHashed+=Files.size(path);hashes.put(path,new Hashed(stamp,hash));return hash;
    }
    private static final java.util.regex.Pattern DRAW_DIAGNOSTIC=java.util.regex.Pattern.compile("^(.+\\.java):(\\d+):(\\d+): (compiler\\.(?:err|warn|note)\\.[^: ]+)(?:: (.*))?$");
    private static List<Problem> diagnostics(Path directory,String output){
        var diagnostics=new ArrayList<Problem>();
        for(String line:output.split("\\R")){
            var match=DRAW_DIAGNOSTIC.matcher(line.trim());if(!match.matches())continue;
            Path file=Path.of(match.group(1));if(!file.isAbsolute())file=directory.resolve(file);file=file.toAbsolutePath().normalize();
            String code=match.group(4),kind=code.startsWith("compiler.err.")?"ERROR":code.startsWith("compiler.warn.")?"WARNING":"NOTE";
            String message=match.group(5)==null?code:match.group(5);
            diagnostics.add(new Problem(code,kind,file.toUri().toString(),Long.parseLong(match.group(2)),Math.max(0,Long.parseLong(match.group(3))-1),message));
        }
        return List.copyOf(diagnostics);
    }
    private static boolean available(Output output){
        return output!=null&&output.sourceRoots().stream().allMatch(Files::isDirectory)&&output.classpath().stream().allMatch(Files::isDirectory);
    }
    private String outputFingerprint(Path generated,Path classes,boolean includeClasses)throws Exception{
        var fingerprint=new StringBuilder("processor-output-v1");
        appendOutput(fingerprint,generated);
        if(includeClasses)appendOutput(fingerprint,classes);
        return Hashing.sha256(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void appendOutput(StringBuilder fingerprint,Path root)throws Exception{
        if(!Files.isDirectory(root))return;
        try(var files=Files.walk(root)){
            for(Path file:files.filter(Files::isRegularFile).sorted().toList())
                fingerprint.append('\0').append(root.relativize(file)).append(':').append(contentHash(file));
        }
    }
    private record Exit(int exitCode,boolean timedOut) { }
    private Exit invoke(Request request,List<Path> inputs,Path generated,Path classes,Path work,Path log,String mode,Duration timeout)throws Exception {
        var args=new ArrayList<String>();
        for(int i=0;i<request.compilerOptions().size();i++){
            String option=request.compilerOptions().get(i);
            if(Set.of("-processor","-processorpath","--processor-path","--processor-module-path","-s","-d").contains(option)){i++;continue;}
            if(option.startsWith("-proc:")||option.startsWith("--processor-path=")||option.startsWith("--processor-module-path="))continue;
            args.add(option);
        }
        args.addAll(List.of("-proc:"+mode,"-implicit:none","-g","-parameters","-XDrawDiagnostics",
                "-processorpath",join(request.processorPath()),"-classpath",join(request.classpath()),"-s",generated.toString(),"-d",classes.toString()));
        if(!request.processors().isEmpty())args.addAll(List.of("-processor",String.join(",",request.processors())));
        inputs.forEach(p->args.add(p.toString()));
        Path arguments=work.resolve("javac-"+mode+".args");
        Files.writeString(arguments,args.stream().map(AnnotationProcessing::quote).collect(java.util.stream.Collectors.joining("\n")),StandardCharsets.UTF_8);
        var builder=new ProcessBuilder(config.jdkHome().resolve("bin/javac").toString(),"-J-Xmx256m","@"+arguments).directory(request.directory().toFile()).redirectErrorStream(true);
        builder.environment().put("JAVA_HOME",config.jdkHome().toString());
        var process=builder.start();active=process;process.getOutputStream().close();
        var ioFailure=new java.util.concurrent.atomic.AtomicReference<IOException>();
        Thread reader=Thread.ofVirtual().name("jvmd-processor-output").start(()->{
            try(var input=process.getInputStream();var output=Files.newOutputStream(log,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){
                byte[] buffer=new byte[8192];int count,written=0;boolean truncated=false;
                while((count=input.read(buffer))!=-1){int accepted=Math.min(count,64*1024-written);if(accepted>0){output.write(buffer,0,accepted);written+=accepted;}if(accepted<count)truncated=true;}
                if(truncated)output.write("\n[processor output truncated]\n".getBytes(StandardCharsets.UTF_8));
            }catch(IOException e){ioFailure.set(e);}
        });
        try{
            boolean finished=process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(!finished)kill(process);reader.join(5000);if(reader.isAlive()){process.getInputStream().close();reader.interrupt();}
            if(ioFailure.get()!=null&&finished)throw ioFailure.get();
            return new Exit(finished?process.exitValue():-1,!finished);
        }finally{if(process.isAlive())kill(process);active=null;}
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    private static String join(List<Path> paths){return paths.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator));}
    private static List<Path> javaFiles(List<Path> roots)throws IOException{
        var files=new TreeSet<Path>();for(Path root:roots)if(Files.isDirectory(root))try(var paths=Files.walk(root)){paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")).forEach(files::add);}return List.copyOf(files);
    }
    private static boolean lombokSource(String source){return source.contains("lombok.")&&source.contains("@");}
    private static void kill(Process process){var children=process.descendants().toList();children.reversed().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
    private static void deleteTree(Path root)throws IOException{if(Files.exists(root))try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    public synchronized Map<String,Object> status(){
        return Map.of("runs",runs,"cache_hits",hits,"bytes_hashed",bytesHashed,"modules",cache.entrySet().stream().map(e->Map.of("module",e.getKey(),"exit_code",e.getValue().output().exitCode(),"timed_out",e.getValue().output().timedOut(),"elapsed_ms",e.getValue().output().elapsedMillis(),"diagnostic_fidelity",e.getValue().output().diagnosticFidelity(),"diagnostics",e.getValue().output().diagnostics().size(),"warnings",e.getValue().output().warnings())).toList());
    }
    @Override public void close(){var process=active;if(process!=null)kill(process);}
}
