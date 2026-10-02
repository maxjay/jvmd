package dev.jvmd.boot.cold.local;

import dev.jvmd.analyzer.Analyzer;
import java.util.Objects;

/**
 * One module scope and the compiler context the analyzer compiles it with. The LOCAL cold boot
 * compiles with the same context, so javac's inputs are those of analysis.
 */
record Context(String module,String scope,Analyzer.Context compiler) {
    Context { Objects.requireNonNull(module);Objects.requireNonNull(compiler); }

    /** {@code <gav>:<scope>}, as the resolver keys classpaths and the LOCAL layer keys routes. */
    String key(){return module+":"+scope;}

    /** The coordinates of the module that owns {@code location}, as the analyzer names declarations. */
    String coordinates(String location){
        String best=null;int length=-1;
        for(var entry:compiler.coordinates().entrySet())
            if(location.startsWith(entry.getKey())&&entry.getKey().length()>length){best=entry.getValue();length=entry.getKey().length();}
        return best;
    }
}
