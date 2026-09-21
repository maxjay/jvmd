package dev.jvmd.index;

import java.nio.file.Path;

/** Module boundary for opening the production Rocks storage lifetime. */
public interface IndexStorageProvider {
    IndexStorage open(Path root,long maxEstimatedBytes)throws Exception;
}
