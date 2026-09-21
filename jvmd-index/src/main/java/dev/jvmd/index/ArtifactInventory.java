package dev.jvmd.index;

import java.util.Set;

/** Repository scan membership and completion; incomplete scans must never be reconciled. */
public interface ArtifactInventory {
    long beginScan()throws Exception;
    void observe(long scanGeneration,IndexStore.ArtifactInput input)throws Exception;
    Set<String> completeScan(long scanGeneration)throws Exception;
}
