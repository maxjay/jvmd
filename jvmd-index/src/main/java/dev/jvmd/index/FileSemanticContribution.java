package dev.jvmd.index;

import java.nio.file.Path;
import java.util.*;

/**
 * Canonical detached semantic facts for one source file.
 *
 * <p>Persistence, invalidation and publication consume this value instead of defining parallel
 * per-file semantic records. A complete contribution replaces every set, including empty sets.
 * Focused and failed analysis must use the corresponding SemanticUpdatePolicy completeness,
 * never masquerade as a complete replacement. The unresolved target "*" records an error for
 * which javac supplied no stable target; every API export change can affect that file.
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
