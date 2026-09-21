package dev.jvmd.analyzer;

import dev.jvmd.core.Envelope;
import dev.jvmd.index.FileSemanticContribution;
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
    public record State(Envelope diagnostics,String apiFingerprint,Set<Path> dependencies,FileSemanticContribution contribution) {
        public State(Envelope diagnostics,String apiFingerprint,Set<Path> dependencies){this(diagnostics,apiFingerprint,dependencies,null);}
        public State { dependencies=Set.copyOf(dependencies); }
    }
    public enum Validity { VALID, STALE, CONDITIONALLY_STALE, UNKNOWN }
    public enum Reason { SOURCE_CONTENT_CHANGED, DEPENDENCY_API_CHANGED, SOURCE_ADDED, SOURCE_REMOVED,
        NAMESPACE_CHANGED, CONTEXT_CHANGED, CLASSPATH_CHANGED, PROCESSOR_OUTPUT_CHANGED, JDK_CHANGED,
        ANALYZER_VERSION_CHANGED, UNKNOWN }
    private final Map<Key,State> files=new LinkedHashMap<>(256,.75f,true);
    private final Map<Key,Long> weights=new HashMap<>();
    private final Map<Reason,Long> reasons=new EnumMap<>(Reason.class);
    private long budget=32L*1024*1024,bytes,evictions;
    private long hits,misses,puts,invalidations;
    private DiagnosticSnapshots snapshots;
    private Map<Path,String> inputHashes=Map.of();
    public void inputHashes(Map<Path,String> hashes){inputHashes=hashes;}
    public void persistence(DiagnosticSnapshots snapshots){this.snapshots=snapshots;}
    public void budget(long bytes){budget=Math.max(1024,bytes);trim();}
    private void remove(Key key){files.remove(key);bytes-=weights.getOrDefault(key,0L);weights.remove(key);}
    private void trim(){while(bytes>budget&&!files.isEmpty()){remove(files.keySet().iterator().next());evictions++;}}

    public Envelope get(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint){
        var value=lookup(file,sourceHash,contextFingerprint,classpathFingerprint);return value==null?null:value.diagnostics();
    }
    public State state(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint){
        return lookup(file,sourceHash,contextFingerprint,classpathFingerprint);
    }
    private State lookup(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint){
        var key=new Key(file,sourceHash,contextFingerprint,classpathFingerprint);var value=files.get(key);
        if(value==null&&snapshots!=null){value=snapshots.restore(key);if(value!=null)put(file,sourceHash,contextFingerprint,classpathFingerprint,value.diagnostics(),value.apiFingerprint(),value.dependencies(),value.contribution(),false);}
        if(value==null)misses++;else hits++;
        return value;
    }

    public void put(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint,Envelope diagnostics){
        put(file,sourceHash,contextFingerprint,classpathFingerprint,diagnostics,null,Set.of());
    }
    public void put(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint,Envelope diagnostics,String apiFingerprint,Set<Path> dependencies){
        put(file,sourceHash,contextFingerprint,classpathFingerprint,diagnostics,apiFingerprint,dependencies,null,true);
    }
    public void put(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint,Envelope diagnostics,String apiFingerprint,Set<Path> dependencies,FileSemanticContribution contribution){
        put(file,sourceHash,contextFingerprint,classpathFingerprint,diagnostics,apiFingerprint,dependencies,contribution,true);
    }
    private void put(Path file,String sourceHash,String contextFingerprint,String classpathFingerprint,Envelope diagnostics,String apiFingerprint,Set<Path> dependencies,FileSemanticContribution contribution,boolean persist){
        Path normalized=file.toAbsolutePath().normalize();
        for(var key:List.copyOf(files.keySet()))if(key.file().equals(normalized)&&key.contextFingerprint().equals(contextFingerprint))remove(key);
        var key=new Key(normalized,sourceHash,contextFingerprint,classpathFingerprint);
        var state=new State(diagnostics,apiFingerprint,dependencies,contribution);
        long size;
        try{size=512L+2L*dev.jvmd.core.Json.MAPPER.writeValueAsBytes(diagnostics).length+2L*key.toString().length()+dependencies.stream().mapToLong(p->128L+2L*p.toString().length()).sum();}
        catch(Exception error){throw new IllegalArgumentException("Diagnostic state is not detached",error);}
        files.put(key,state);weights.put(key,size);bytes+=size;puts++;trim();if(persist&&snapshots!=null)snapshots.save(key,state,inputHashes);
    }

    public void invalidate(Collection<Path> paths){
        invalidate(paths,Reason.SOURCE_CONTENT_CHANGED);
    }
    public void invalidate(Collection<Path> paths,Reason reason){
        if(paths.isEmpty())return;
        var normalized=new HashSet<Path>();for(Path path:paths)normalized.add(path.toAbsolutePath().normalize());
        int before=files.size();for(var key:List.copyOf(files.keySet()))if(normalized.contains(key.file()))remove(key);invalidations+=before-files.size();
        reasons.merge(reason,(long)normalized.size(),Long::sum);
    }

    public void clear(){invalidations+=files.size();files.clear();weights.clear();bytes=0;}

    public Map<String,Object> status(){
        return Map.of(
                "bytes",bytes,"budget_bytes",budget,"evictions",evictions,"invalidation_reasons",Map.copyOf(reasons),
                "entries",files.size(),
                "hits",hits,
                "misses",misses,
                "puts",puts,
                "invalidations",invalidations);
    }
}
