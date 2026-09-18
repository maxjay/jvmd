package dev.jvmd.dist;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.RequestScope;
import dev.jvmd.resolver.Resolution;
import java.nio.file.Path;
import java.util.*;

/** Constructs each main/test module context once per request, including processor preparation. */
final class WorkspaceContextManager {
    @FunctionalInterface interface Factory { Analyzer.Context create(Path file) throws Exception; }
    private long constructions;
    Analyzer.Context context(Path file, Resolution graph, Factory factory) throws Exception {
        String key=key(file,graph);
        return RequestScope.memo(List.of(this,key),()->{constructions++;return factory.create(file);});
    }
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
    Map<String,Object> status(){return Map.of("context_constructions",constructions);}
}
