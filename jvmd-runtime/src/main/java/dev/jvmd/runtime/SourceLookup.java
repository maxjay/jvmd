package dev.jvmd.runtime;

import com.sun.jdi.*;
import java.nio.file.*;
import java.util.*;

/** Implements 4.6 and 4.7: debug source lookup across source roots in manifest order. */
public final class SourceLookup {
    private final List<Path> roots;
    private final Map<Path,String> coordinates;
    private final Map<String,String> binaryCoordinates=new java.util.concurrent.ConcurrentHashMap<>();
    public SourceLookup(List<Path> roots){this(roots,Map.of());}
    public SourceLookup(List<Path> roots,Map<Path,String> coordinates){
        this.roots=roots.stream().map(p->p.toAbsolutePath().normalize()).distinct().toList();
        var canonical=new LinkedHashMap<Path,String>();coordinates.forEach((path,gav)->canonical.put(path.toAbsolutePath().normalize(),gav));this.coordinates=Collections.unmodifiableMap(canonical);
    }
    public Path find(String relative){
        for(Path root:roots){Path candidate=root.resolve(relative.replace('\\','/')).normalize();if(candidate.startsWith(root)&&Files.isRegularFile(candidate))return candidate;}return null;
    }
    public Path find(ReferenceType type){
        try{for(String source:type.sourcePaths(null)){Path file=find(source);if(file!=null)return file;}}catch(AbsentInformationException ignored){}
        return find(type.name().split("\\$",2)[0].replace('.','/')+".java");
    }
    public String symbol(ReferenceType type,Method method){
        String gav="local:workspace:0";Path file=find(type);
        if(file!=null)gav=coordinates.entrySet().stream().filter(e->file.startsWith(e.getKey())).max(Comparator.comparingInt(e->e.getKey().toString().length())).map(Map.Entry::getValue).orElse(gav);
        else if(type.module()!=null&&type.module().name()!=null&&(type.module().name().startsWith("java.")||type.module().name().startsWith("jdk.")))gav="jdk:"+type.module().name()+":25";
        else gav=binaryCoordinates.computeIfAbsent(type.name(),name->{
            for(var entry:coordinates.entrySet())if(entry.getKey().toString().endsWith(".jar"))try(var jar=new java.util.jar.JarFile(entry.getKey().toFile(),false,java.util.jar.JarFile.OPEN_READ,Runtime.version())){if(jar.getJarEntry(name.replace('.','/')+".class")!=null)return entry.getValue();}catch(java.io.IOException ignored){}
            return "local:workspace:0";
        });
        String[] parts=gav.split(":",3);String owner="maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" "+type.name().replace('.','/').replace('$','#')+"#";
        return method==null?owner:owner+method.name()+"("+String.join(",",method.argumentTypeNames())+").";
    }
    public List<String> roots(){return roots.stream().map(Path::toString).toList();}
}
