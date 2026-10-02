package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.index.ArtifactIndexFormat;
import java.util.*;

/** Where {@link ArtifactBuilder} publishes an artifact's immutable stores. */
public interface ArtifactPublisher {
    /** Publish an artifact's facts, class references and semantic tree nodes as one immutable store. */
    void publish(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences,
                 KeyedTree<String,MachineTree.ArtifactSymbol> semanticTree)throws Exception;
    /** Publish the documentation joined from sources; returns its store key. */
    String publishDocumentation(ArtifactIndexFormat.Key binary,String sourceSha256,
                                Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception;
}
