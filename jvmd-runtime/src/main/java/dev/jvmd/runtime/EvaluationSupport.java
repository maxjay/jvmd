package dev.jvmd.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Implements 4.7: install only the java.base evaluation bridge on the target classpath. */
final class EvaluationSupport {
    private static Path bridge;
    private EvaluationSupport() { }
    static synchronized Path bridge()throws IOException{
        if(bridge!=null&&Files.isRegularFile(bridge))return bridge;
        Path file=Files.createTempFile("jvmd-evaluation-",".jar");boolean complete=false;
        try(var jar=new JarOutputStream(Files.newOutputStream(file))){
            var pending=new ArrayDeque<Class<?>>();pending.add(EvaluationBridge.class);
            while(!pending.isEmpty()){
                Class<?> type=pending.removeFirst();String name=type.getName().replace('.','/')+".class";
                try(var input=type.getResourceAsStream("/"+name)){
                    if(input==null)throw new IOException("Missing evaluation bridge class: "+name);
                    var entry=new JarEntry(name);entry.setTime(0);jar.putNextEntry(entry);input.transferTo(jar);jar.closeEntry();
                }
                pending.addAll(Arrays.asList(type.getDeclaredClasses()));
            }
            complete=true;
        }finally{if(!complete)Files.deleteIfExists(file);}
        file.toFile().deleteOnExit();return bridge=file;
    }
}
