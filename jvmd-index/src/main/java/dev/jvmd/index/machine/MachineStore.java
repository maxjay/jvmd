package dev.jvmd.index.machine;

import dev.jvmd.index.ArtifactIndexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Persistence of MACHINE: each leaf's immutable facts, the tree, its path table and root. */
public interface MachineStore {
    /** Publishes one leaf's facts as its immutable record set, keyed by the facts' cacheKey. */
    void ingest(ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception;
    /** Publishes documentation joined for a binary leaf and returns its documentation key. */
    String ingestDocumentation(String binaryCacheKey,ArtifactIndexFormat.Key sourceKey,
                               Map<String,Map<String,Object>> members,int unmatched)throws Exception;
    /** Commits the tree: leaves, nodes and path table in one synced batch, then the root. */
    void commit(MachineTree tree)throws Exception;
    /** The committed tree, or empty when no root is committed. */
    Optional<MachineTree> load()throws Exception;
}
