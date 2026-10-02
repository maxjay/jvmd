package dev.jvmd.index;

import java.nio.file.Path;

/** Bounds the memory of artifacts being parsed and published at once. */
public interface ArtifactAdmission {
    /** Wait until {@code estimatedBytes} fits in the budget; closing the permit returns it. */
    AutoCloseable acquire(long estimatedBytes)throws Exception;
    /** Admission for an artifact estimated from its file. TEMPORARY(warm-boot): only the existing scan uses it. */
    AutoCloseable acquireArtifact(Path path)throws Exception;
}
