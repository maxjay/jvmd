package dev.jvmd.index;

import java.util.Map;

/** Owns the lifetime of one production index and exposes its focused services. */
public interface IndexStorage extends AutoCloseable {
    IndexStore store();
    ArtifactInventory inventory();
    IndexSemanticState semanticState();
    ArtifactAdmission admission();
    Map<String,Object> status();
}
