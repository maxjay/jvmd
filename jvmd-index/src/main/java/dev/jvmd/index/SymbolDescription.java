package dev.jvmd.index;

import java.util.*;

/** Expensive/descriptive semantic data loaded only after a symbol is selected. */
public record SymbolDescription(
        String id,
        String documentation,
        String detailedSignature,
        DeclarationLocation declaration,
        String documentationIdentity) {
    public SymbolDescription {
        Objects.requireNonNull(id);
        documentation=Objects.requireNonNullElse(documentation,"");
        detailedSignature=Objects.requireNonNullElse(detailedSignature,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
    }

    public record DeclarationLocation(String file,int start,int end) { }
}
