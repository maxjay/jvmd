package dev.jvmd.index.layer.local;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.SemanticFact;
import java.util.*;

/**
 * What a LOCAL commit covers: the file tree, the semantic tree over every file's declarations, the
 * module graph (reactor modules and the edges between them) and one route per module scope.
 */
public record LocalTree(LocalFileTree files,KeyedTree<String,SemanticFact> semantic,SortedMap<String,List<String>> modules,
                        SortedMap<String,Route> routes) {
    public static final int FORMAT=1;

    public LocalTree {
        Objects.requireNonNull(files);Objects.requireNonNull(semantic);
        var graph=new TreeMap<String,List<String>>();modules.forEach((module,edges)->graph.put(module,List.copyOf(new TreeSet<>(edges))));
        modules=Collections.unmodifiableSortedMap(graph);routes=Collections.unmodifiableSortedMap(new TreeMap<>(routes));
    }

    /** Covers the file tree (and through it each file's semantic tree), the layer's semantic tree, the module graph and the routes. */
    public Hash256 treeHash(){
        var routeIdentities=new ArrayList<Object>();for(var route:routes.values())routeIdentities.add(new Object[]{route.key(),route.identity()});
        var graph=new ArrayList<Object>();modules.forEach((module,edges)->graph.add(new Object[]{module,edges}));
        return CanonicalDigestWriter.digest("local-tree-v1",files.tree().rootHash(),semantic.rootHash(),graph,routeIdentities);
    }

    public Root root(){return new Root(FORMAT,treeHash(),files.aggregates(),files.size());}
}
