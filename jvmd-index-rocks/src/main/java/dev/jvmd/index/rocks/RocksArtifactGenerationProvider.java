package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.Path;

/** Production ServiceLoader entry for RocksDB SST artifact generations. */
public final class RocksArtifactGenerationProvider implements ArtifactGenerationSinkProvider {
    @Override public String backend(){return "rocksdb-sst";}
    @Override public int priority(){return 100;}
    @Override public ArtifactGenerationSink open(Path root,long maxEstimatedBytes)throws Exception{
        var migration=new RocksMigrationManager(root);
        String generation="format-"+ArtifactIndexFormat.FORMAT_VERSION+"-jdk"+Runtime.version().feature()+"-"+ArtifactIndexFormat.INDEXER_VERSION;
        return new RocksArtifactGenerationSink(migration.candidate(generation),maxEstimatedBytes,migration,generation);
    }
}
