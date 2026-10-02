package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.index.ResidentSemanticState;
import dev.jvmd.index.SemanticFact;
import java.util.*;

/**
 * LOCAL declarations ordered by owner, then member, with resolution range sums. Each file has its
 * own tree; the layer's tree holds every file's declarations. Both are bulk-built from sorted
 * entries, so equal declarations give equal roots whatever order they were attributed in.
 */
public final class LocalSemanticTree {
    private LocalSemanticTree(){}

    /** The tree of one file's declarations. */
    public static KeyedTree<String,SemanticFact> file(Collection<SemanticFact> facts){
        return build(List.of(facts));
    }

    /**
     * The layer's tree over the declarations of every file, given in logical path order. A
     * declaration attributed from two files is kept from the first.
     */
    public static KeyedTree<String,SemanticFact> build(List<? extends Collection<SemanticFact>> files){
        var sorted=new TreeMap<String,SemanticFact>();
        for(var facts:files)for(var fact:facts)sorted.putIfAbsent(fact.orderedKey(),fact);
        return KeyedTree.build(ResidentSemanticState.FACTS,List.copyOf(sorted.entrySet()));
    }
}
