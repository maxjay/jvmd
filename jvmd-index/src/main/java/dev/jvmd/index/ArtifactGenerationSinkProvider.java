package dev.jvmd.index;

import java.nio.file.Path;

/** Service-provider boundary for optional immutable artifact generation backends. */
public interface ArtifactGenerationSinkProvider {
    String backend();
    default int priority(){return 0;}
    ArtifactGenerationSink open(Path root,long maxEstimatedBytes)throws Exception;
}
