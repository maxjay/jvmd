package dev.jvmd.index;

import java.util.*;

/** Compact retained state for one semantic admission unit; canonical facts live in the resident symbol table. */
public record SemanticUnitState(
        String unit,
        String sourceFile,
        String contentIdentity,
        SemanticUnitMerkle facts,
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Set<String> dependencies) {

    public SemanticUnitState {
        Objects.requireNonNull(unit);
        contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        facts=Objects.requireNonNull(facts);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        dependencies=Set.copyOf(dependencies);
    }

    public Set<String> factIds(){return facts.ids();}
}
