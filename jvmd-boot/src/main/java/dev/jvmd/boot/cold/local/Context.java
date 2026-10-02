package dev.jvmd.boot.cold.local;

import java.nio.file.Path;
import java.util.*;

/**
 * The compiler context of one module scope: what javac compiles that scope's units against. Sibling
 * modules are on the source path, so their declarations come from project source, not from MACHINE.
 *
 * @param coordinates longest-prefix location to module coordinates, as the analyzer names declarations
 */
record Context(String module,String scope,String release,List<Path> classpath,List<Path> sources,List<String> options,
               Map<String,String> coordinates,List<Path> navigationSources,String generation) {
    Context {
        classpath=List.copyOf(classpath);sources=List.copyOf(sources);options=List.copyOf(options);
        coordinates=Map.copyOf(coordinates);navigationSources=List.copyOf(navigationSources);
    }

    /** {@code <gav>:<scope>}, as the resolver keys classpaths and the LOCAL layer keys routes. */
    String key(){return module+":"+scope;}

    /** The coordinates of the module that owns {@code location}. */
    String coordinates(String location){
        String best=null;int length=-1;
        for(var entry:coordinates.entrySet())if(location.startsWith(entry.getKey())&&entry.getKey().length()>length){best=entry.getValue();length=entry.getKey().length();}
        return best;
    }
}
