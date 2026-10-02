package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;

/** Owns the lifetime of one production index and exposes its focused services. */
public interface IndexStorage extends AutoCloseable {
    IndexStore store();
    ArtifactInventory inventory();
    IndexSemanticState semanticState();
    ArtifactAdmission admission();
    Map<String,Object> status();
    /**
     * Whether this storage already holds a complete repository scan for its index format: a scan
     * that finished, validated and was activated, by this process or an earlier one. False on a
     * first start, after a scan that was interrupted or faulted, and when the format changed.
     */
    boolean scanCompleted();

    static IndexStorage open(Path root,long maxEstimatedBytes)throws Exception{
        Objects.requireNonNull(root);
        // Old settings must fail visibly, never silently select a different data store.
        for(String property:List.of("jvmd.index.store.backend","jvmd.index.read.backend","jvmd.index.generation.backend")){
            String value=System.getProperty(property);
            if(value!=null&&!value.equals("rocksdb-sst")&&!(property.equals("jvmd.index.generation.backend")&&value.equals("auto")))
                throw new IllegalArgumentException(property+"="+value+" is no longer supported; Rocks is the sole production backend. Remove this setting and rebuild the index from source artifacts. Existing SQLite files are left untouched.");
        }
        var providers=ServiceLoader.load(IndexStorageProvider.class).stream().map(ServiceLoader.Provider::get).toList();
        if(providers.size()!=1)throw new IllegalStateException("Expected one Rocks index storage provider, found "+providers.size());
        return providers.getFirst().open(root,maxEstimatedBytes);
    }
}
