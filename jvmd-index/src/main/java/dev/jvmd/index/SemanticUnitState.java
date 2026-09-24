package dev.jvmd.index;

import java.util.*;

/** Compact retained state for one semantic admission unit; canonical facts live in the resident symbol table. */
public record SemanticUnitState(
        String unit,
        String sourceFile,
        String contentIdentity,
        Set<String> factIds,
        Set<String> descriptionIds,
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Set<String> dependencies) {

    public SemanticUnitState {
        Objects.requireNonNull(unit);
        contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        factIds=Set.copyOf(factIds);
        descriptionIds=Set.copyOf(descriptionIds);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        dependencies=Set.copyOf(dependencies);
    }
}
