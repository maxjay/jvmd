package dev.jvmd.analyzer;

import com.sun.source.util.JavacTask;
import dev.jvmd.core.Hash256;
import java.nio.file.Path;
import java.util.*;
import javax.tools.ToolProvider;

/**
 * Current binary P_diag of units javac reads from class files (Lombok-processed units, W6), for
 * restore. One classpath-only javac task per observation epoch answers every lookup; nothing is
 * parsed or attributed, classes are completed on demand from the compile classpath.
 */
final class BinaryProjections implements AutoCloseable {
    private final JavacTask task;
    private final Map<String,Optional<Hash256>> cache=new HashMap<>();

    BinaryProjections(List<Path> classpath,List<String> options)throws Exception{
        var compiler=ToolProvider.getSystemJavaCompiler();
        // The same class-reading options as the in-process compiler, so both sides see the same element model.
        var arguments=new ArrayList<String>(List.of("-proc:none"));
        for(int i=0;i<options.size();i++){
            String option=options.get(i);
            if(option.equals("--release")||option.equals("-source")||option.equals("-target")){arguments.add(option);if(i+1<options.size())arguments.add(options.get(++i));}
            else if(option.startsWith("--release=")||option.equals("--enable-preview"))arguments.add(option);
        }
        // IndexedFileManager resolves class directories before archives (first match wins in each); same order here.
        var ordered=new ArrayList<Path>();classpath.stream().filter(path->!path.toString().endsWith(".jar")).forEach(ordered::add);
        classpath.stream().filter(path->path.toString().endsWith(".jar")).forEach(ordered::add);
        if(!ordered.isEmpty()){arguments.add("-classpath");arguments.add(String.join(java.io.File.pathSeparator,ordered.stream().map(Path::toString).toList()));}
        task=(JavacTask)compiler.getTask(new java.io.StringWriter(),null,diagnostic->{},arguments,null,null);
    }

    /** P_diag of the top-level type {@code binaryName} as loaded from the classpath, or empty when it is not there. */
    Optional<Hash256> projection(String binaryName){
        return cache.computeIfAbsent(binaryName,name->{
            try{
                var type=task.getElements().getTypeElement(name);
                return type==null?Optional.empty():Optional.of(DiagnosticProjection.ofBinary(task.getElements(),type));
            }catch(RuntimeException unreadable){return Optional.empty();}
        });
    }
    @Override public void close(){cache.clear();}
}
