package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.Path;

/** Opens the production index in its validated, recoverable Rocks format generation. */
public final class RocksIndexStorageProvider implements IndexStorageProvider {
    @Override public IndexStorage open(Path root,long maxEstimatedBytes)throws Exception{
        var migration=new RocksMigrationManager(root);
        String generation="format-"+ArtifactIndexFormat.FORMAT_VERSION+"-jdk"+Runtime.version().feature()+"-"+ArtifactIndexFormat.INDEXER_VERSION;
        return new RocksIndexStorage(migration.candidate(generation),maxEstimatedBytes,migration,generation);
    }
}
