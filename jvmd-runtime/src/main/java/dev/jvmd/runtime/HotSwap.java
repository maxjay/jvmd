package dev.jvmd.runtime;

import com.sun.jdi.*;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Implements 4.7: compiled method-body redefinition and an explicit restart-required result. */
public final class HotSwap {
    private final DebugSession debug;
    public HotSwap(DebugSession debug){this.debug=debug;}
    public Envelope apply(Path compilerHome,List<Path> changed,List<Path> sourceRoots,List<String> options,Path output)throws Exception{return apply(compilerHome,changed,sourceRoots,options,output,debug.launch().classpath());}
    public Envelope apply(Path compilerHome,List<Path> changed,List<Path> sourceRoots,List<String> options,Path output,List<Path> classpath)throws Exception{
        debug.vm();var compiled=RuntimeCompiler.compile(compilerHome,debug.launch().directory(),changed,classpath,sourceRoots,options,Duration.ofSeconds(60));
        var classes=new LinkedHashMap<ReferenceType,byte[]>();var names=new ArrayList<String>();
        for(var entry:compiled.classes().entrySet()){
            String name=entry.getKey().substring(0,entry.getKey().length()-6).replace('/','.');names.add(name);
            for(var type:debug.vm().classesByName(name))classes.put(type,entry.getValue());
        }
        long before=System.nanoTime();String restart=null;
        try{if(!classes.isEmpty())debug.vm().redefineClasses(classes);}
        catch(UnsupportedOperationException e){restart=e.getClass().getSimpleName()+": "+e.getMessage();}
        catch(ClassFormatError|VerifyError e){throw new RpcException(-32003,"unsupported_capability",Map.of("capability","hotswap","reason",e.toString()));}
        double redefine=(System.nanoTime()-before)/1e6;
        // The same classpath is also ready for newly loaded classes or an explicitly requested restart.
        RuntimeCompiler.publish(compiled,output);
        if(restart==null)debug.redefined();
        var result=new LinkedHashMap<String,Object>();result.put("restart_required",restart!=null);result.put("reason",restart);result.put("classes",names);result.put("redefined",restart==null?classes.size():0);result.put("compile_ms",compiled.elapsedMillis());result.put("redefine_ms",redefine);result.put("compiler_output",compiled.output());
        result.put("compiler_mode",compiled.mode());return Envelope.of(2,"live",result);
    }
}
