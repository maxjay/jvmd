package dev.jvmd.analyzer;

import dev.jvmd.core.Envelope;
import java.nio.file.Path;
import java.util.*;

/** Detached diagnostic snapshots keyed by source and compiler-context identity. */
public final class DiagnosticStore {
    public record Key(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint) {
        public Key {
            file=file.toAbsolutePath().normalize();
            Objects.requireNonNull(sourceHash);
            Objects.requireNonNull(contextFingerprint);
            Objects.requireNonNull(classpathFingerprint);
        }
    }
    public record State(Envelope diagnostics,String apiFingerprint,Set<Path> dependencies) {
        public State { dependencies=Set.copyOf(dependencies); }
    }
    private final Map<Key,State> files=new LinkedHashMap<>();
    private long hits,misses,puts,invalidations;

    public State get(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint){
        var value=files.get(new Key(file,sourceHash,contextFingerprint,classpathFingerprint));
        if(value==null)misses++;else hits++;
        return value;
    }

    public void put(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint,Envelope diagnostics,String apiFingerprint,Set<Path> dependencies){
        Path normalized=file.toAbsolutePath().normalize();
        files.keySet().removeIf(key->key.file().equals(normalized)&&key.contextFingerprint().equals(contextFingerprint));
        files.put(new Key(normalized,sourceHash,contextFingerprint,classpathFingerprint),new State(diagnostics,apiFingerprint,dependencies));puts++;
    }

    public String apiFingerprint(Path file){
        Path normalized=file.toAbsolutePath().normalize();String result=null;
        for(var entry:files.entrySet())if(entry.getKey().file().equals(normalized))result=entry.getValue().apiFingerprint();
        return result;
    }

    public Set<Path> unresolvedFiles(){
        var result=new LinkedHashSet<Path>();
        for(var entry:files.entrySet())if(hasUnresolved(entry.getValue().diagnostics()))result.add(entry.getKey().file());
        return Set.copyOf(result);
    }

    private static boolean hasUnresolved(Envelope envelope){
        if(!(envelope.result() instanceof Map<?,?> result)||!(result.get("diagnostics") instanceof List<?> diagnostics))return false;
        for(Object value:diagnostics)if(value instanceof CompilerPool.Problem problem&&problem.code().contains("cant.resolve"))return true;
        return false;
    }

    public void invalidate(Collection<Path> paths){
        if(paths.isEmpty())return;
        var normalized=new HashSet<Path>();for(Path path:paths)normalized.add(path.toAbsolutePath().normalize());
        int before=files.size();files.keySet().removeIf(key->normalized.contains(key.file()));invalidations+=before-files.size();
    }

    public void clear(){invalidations+=files.size();files.clear();}

    public Map<String,Object> status(){
        return Map.of(
                "entries",files.size(),
                "hits",hits,
                "misses",misses,
                "puts",puts,
                "invalidations",invalidations);
    }
}
