package dev.jvmd.index;

/** Bounds the memory of artifacts being parsed and published at once. */
@FunctionalInterface
public interface ArtifactAdmission {
    /** Wait until {@code estimatedBytes} fits in the budget; closing the permit returns it. */
    AutoCloseable acquire(long estimatedBytes)throws Exception;
}
