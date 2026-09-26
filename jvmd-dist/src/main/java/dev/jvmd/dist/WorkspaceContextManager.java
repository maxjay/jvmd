package dev.jvmd.dist;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.RequestScope;
import dev.jvmd.resolver.Resolution;
import java.nio.file.Path;
import java.util.*;

/** Constructs each main/test module context once per request, including processor preparation. */
final class WorkspaceContextManager {
    @FunctionalInterface interface Factory { Analyzer.Context create(Path file) throws Exception; }
    private final Map<String,Analyzer.Context> maintained=new HashMap<>();
    private long constructions,hits,misses,invalidations;
    Analyzer.Context context(Path file, Resolution graph, Factory factory) throws Exception {
        return context(file,graph,false,factory);
    }
    Analyzer.Context context(Path file,Resolution graph,boolean cacheable,Factory factory)throws Exception{
        String key;
        try(var trace=RequestScope.stage("application.analyzer.ownerKey")){key=key(file,graph);}
        try(var trace=RequestScope.stage("application.analyzer.contextLookup")){
            if(cacheable){
                synchronized(this){
                    var cached=maintained.get(key);
                    if(cached!=null){hits++;trace.cache("maintained");return cached;}
                    misses++;
                }
            }
            return RequestScope.memo(List.of(this,key,cacheable),()->{
                if(cacheable)synchronized(this){
                    var cached=maintained.get(key);
                    if(cached!=null){hits++;return cached;}
                }
                constructions++;
                Analyzer.Context created;
                try(var create=RequestScope.stage("application.analyzer.contextCreate")){created=factory.create(file);}
                if(cacheable)synchronized(this){maintained.put(key,created);}
                return created;
            });
        }
    }
    synchronized void invalidateAll(){if(!maintained.isEmpty()){maintained.clear();invalidations++;}}
    synchronized boolean hasMaintained(){return !maintained.isEmpty();}
    static String key(Path file,Resolution graph){
        var module=owner(file,graph);
        return module==null?"plain":module.directory()+":"+
                module.testSources().stream().anyMatch(root->file.startsWith(Path.of(root)));
    }
    static Resolution.Module owner(Path file,Resolution graph){
        if(graph==null||graph.modules().isEmpty())return null;
        var fallback=graph.modules().stream().filter(m->file.startsWith(Path.of(m.directory())))
                .max(Comparator.comparingInt(m->m.directory().length())).orElse(graph.modules().getFirst());
        return graph.modules().stream().filter(m->java.util.stream.Stream.concat(m.sources().stream(),m.testSources().stream())
                .anyMatch(root->file.startsWith(Path.of(root))))
                .max(Comparator.comparingInt(m->java.util.stream.Stream.concat(m.sources().stream(),m.testSources().stream())
                        .filter(root->file.startsWith(Path.of(root))).mapToInt(String::length).max().orElse(0))).orElse(fallback);
    }
    synchronized Map<String,Object> status(){return Map.of(
            "context_constructions",constructions,"maintained_contexts",maintained.size(),
            "maintained_hits",hits,"maintained_misses",misses,"invalidations",invalidations);}
}
