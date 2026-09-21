package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;

/**
 * Canonical detached semantic facts for one source file.
 *
 * <p>Persistence, invalidation and publication consume this value instead of defining parallel
 * per-file semantic records.
 */
public record FileSemanticContribution(
        Path file,
        String sourceHash,
        String apiFingerprint,
        Set<Path> dependencies,
        Set<String> exportedNames,
        Set<String> unresolvedTargets) {
    public FileSemanticContribution {
        file=Objects.requireNonNull(file).toAbsolutePath().normalize();
        sourceHash=Objects.requireNonNull(sourceHash);
        apiFingerprint=Objects.requireNonNull(apiFingerprint);
        dependencies=dependencies==null?Set.of():dependencies.stream()
                .map(path->path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        exportedNames=exportedNames==null?Set.of():Set.copyOf(exportedNames);
        unresolvedTargets=unresolvedTargets==null?Set.of():Set.copyOf(unresolvedTargets);
    }
}
