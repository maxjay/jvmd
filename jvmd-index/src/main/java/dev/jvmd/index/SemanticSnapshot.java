package dev.jvmd.index;

import java.util.*;

/** Full detached semantic contribution for one attribution/admission unit. */
public record SemanticSnapshot(
        String unit,
        String sourceFile,
        String contentIdentity,
        Map<String,SemanticFact> facts,
        Map<String,SymbolDescription> descriptions,
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Set<String> dependencies) {
    public SemanticSnapshot {
        Objects.requireNonNull(unit);contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        facts=Map.copyOf(facts);descriptions=Map.copyOf(descriptions);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        dependencies=Set.copyOf(dependencies);
    }
}
