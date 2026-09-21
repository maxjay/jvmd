package dev.jvmd.index;

import java.nio.file.Path;

/** Bounds concurrent parsing and publication memory for an artifact. */
@FunctionalInterface
public interface ArtifactAdmission {
    AutoCloseable acquireArtifact(Path path)throws Exception;
}
