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
    private final Map<Key,Envelope> files=new LinkedHashMap<>();
    private long hits,misses,puts,invalidations;

    public Envelope get(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint){
        var value=files.get(new Key(file,sourceHash,contextFingerprint,classpathFingerprint));
        if(value==null)misses++;else hits++;
        return value;
    }

    public void put(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint,Envelope diagnostics){
        Path normalized=file.toAbsolutePath().normalize();
        files.keySet().removeIf(key->key.file().equals(normalized)&&key.contextFingerprint().equals(contextFingerprint));
        files.put(new Key(normalized,sourceHash,contextFingerprint,classpathFingerprint),diagnostics);puts++;
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
