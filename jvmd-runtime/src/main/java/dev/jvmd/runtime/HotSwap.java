package dev.jvmd.runtime;

import com.sun.jdi.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Implements 4.7: compiled method-body redefinition and an explicit restart-required result. */
public final class HotSwap {
    /** One module's part of a hot swap: its changed files, compiled with its options against its classpath, published into its output. */
    public record Group(List<Path> changed,List<String> options,Path output,List<Path> classpath) { }
    private final DebugSession debug;
    public HotSwap(DebugSession debug){this.debug=debug;}
    public Envelope apply(Path compilerHome,List<Path> changed,List<Path> sourceRoots,List<String> options,Path output)throws Exception{return apply(compilerHome,changed,sourceRoots,options,output,debug.launch().classpath());}
    public Envelope apply(Path compilerHome,List<Path> changed,List<Path> sourceRoots,List<String> options,Path output,List<Path> classpath)throws Exception{
        return Envelope.of(2,"live",applyAll(compilerHome,List.of(new Group(changed,options,output,classpath))).getFirst());
    }
    /**
     * Every group is compiled first, in the order given (a module before the modules that depend on it), each against the classes the
     * groups before it just compiled. Then every class is redefined in one call, which the VM applies to all of them or to none, and only
     * then is anything published. A compile error anywhere leaves the program and every folder as they were; a redefinition the VM
     * refuses leaves the whole program on its old classes, with the new ones published for the restart it asks for.
     */
    public List<Map<String,Object>> applyAll(Path compilerHome,List<Group> groups)throws Exception{
        long started=System.nanoTime();debug.vm();
        Path staging=Files.createTempDirectory("jvmd-hotswap-");
        try{
            var compiled=new ArrayList<RuntimeCompiler.Compilation>();var staged=new ArrayList<Path>();
            for(var group:groups){
                var classpath=new ArrayList<Path>(staged);classpath.addAll(group.classpath());
                var compilation=RuntimeCompiler.compile(compilerHome,debug.launch().directory(),group.changed(),classpath,List.of(),exact(group.options()),Duration.ofSeconds(60));
                compiled.add(compilation);
                var directory=staging.resolve(Integer.toString(staged.size()));RuntimeCompiler.publish(compilation,directory);staged.add(directory);
            }
            long compiledAt=System.nanoTime();
            var classes=new LinkedHashMap<ReferenceType,byte[]>();var names=new ArrayList<List<String>>();var loaded=new int[groups.size()];
            for(int i=0;i<compiled.size();i++){
                var list=new ArrayList<String>();
                for(var entry:compiled.get(i).classes().entrySet()){
                    String name=entry.getKey().substring(0,entry.getKey().length()-6).replace('/','.');list.add(name);
                    for(var type:debug.vm().classesByName(name)){classes.put(type,entry.getValue());loaded[i]++;}
                }
                names.add(list);
            }
            long before=System.nanoTime();String restart=null;
            try{if(!classes.isEmpty())debug.vm().redefineClasses(classes);}
            catch(UnsupportedOperationException e){restart=e.getClass().getSimpleName()+": "+e.getMessage();}
            catch(ClassFormatError|VerifyError e){throw new RpcException(-32003,"unsupported_capability",Map.of("capability","hotswap","reason",e.toString()));}
            long redefinedAt=System.nanoTime();double redefine=(redefinedAt-before)/1e6;
            // The same classpath is also ready for newly loaded classes or an explicitly requested restart.
            for(int i=0;i<groups.size();i++){var output=groups.get(i).output();synchronized(RuntimeCompiler.outputLock(output)){RuntimeCompiler.publish(compiled.get(i),output);}}
            long publishedAt=System.nanoTime();
            if(restart==null)debug.redefined();
            var results=new ArrayList<Map<String,Object>>();
            for(int i=0;i<groups.size();i++){
                var compilation=compiled.get(i);var result=new LinkedHashMap<String,Object>();
                result.put("restart_required",restart!=null);result.put("reason",restart);result.put("classes",names.get(i));result.put("redefined",restart==null?loaded[i]:0);
                result.put("compile_ms",compilation.elapsedMillis());result.put("redefine_ms",redefine);result.put("compiler_output",compilation.output());
                result.put("elapsed_ms",(System.nanoTime()-started)/1e6);result.put("lookup_ms",(before-compiledAt)/1e6);result.put("publish_ms",(publishedAt-redefinedAt)/1e6);result.put("rebind_ms",(System.nanoTime()-publishedAt)/1e6);
                result.put("compiler_mode",compilation.mode());results.add(result);
            }
            return results;
        }finally{
            try(var files=Files.walk(staging)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    /**
     * Exactly the given files, against the classes the program has: an empty source path, so no other source is compiled or read. A
     * reference to an edit that was not given then fails to compile, rather than swap in a class that cannot link to what runs.
     */
    private static List<String> exact(List<String> options){
        var exact=new ArrayList<String>();
        for(int i=0;i<options.size();i++){String option=options.get(i);if(option.equals("-sourcepath")||option.equals("--source-path")){i++;continue;}exact.add(option);}
        exact.add("-implicit:none");return exact;
    }
}
