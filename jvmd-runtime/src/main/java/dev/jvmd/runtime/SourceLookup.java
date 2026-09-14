package dev.jvmd.runtime;

import com.sun.jdi.*;
import java.nio.file.*;
import java.util.*;

/** Implements 4.6 and 4.7: debug source lookup across source roots in manifest order. */
public final class SourceLookup {
    private final List<Path> roots;
    public SourceLookup(List<Path> roots){this.roots=roots.stream().map(p->p.toAbsolutePath().normalize()).distinct().toList();}
    public Path find(String relative){
        for(Path root:roots){Path candidate=root.resolve(relative.replace('\\','/')).normalize();if(candidate.startsWith(root)&&Files.isRegularFile(candidate))return candidate;}return null;
    }
    public Path find(ReferenceType type){
        try{for(String source:type.sourcePaths(null)){Path file=find(source);if(file!=null)return file;}}catch(AbsentInformationException ignored){}
        return find(type.name().split("\\$",2)[0].replace('.','/')+".java");
    }
    public String symbol(ReferenceType type,Method method){
        String gav=type.name().startsWith("java.")||type.name().startsWith("jdk.")?"jdk/"+type.module().name()+" 25":"local/workspace 0";
        String owner="maven "+gav+" "+type.name().replace('.','/').replace('{return roots.stream().map(Path::toString).toList();}
}
,'#')+"#";
        return method==null?owner:owner+method.name()+"("+String.join(",",method.argumentTypeNames())+").";
    }
    public List<String> roots(){return roots.stream().map(Path::toString).toList();}
}
