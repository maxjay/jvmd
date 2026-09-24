package dev.jvmd.index;

import java.util.*;

/** Cheap semantic projection used for discovery before optional description enrichment. */
public record CompletionCandidate(
        String id,
        String name,
        String kind,
        String structuralSignature,
        String declaringType,
        String sourceFile,
        Set<String> modifiers,
        String label,
        List<ParameterLabel> parameters) {
    public CompletionCandidate {
        Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(kind);
        structuralSignature=Objects.requireNonNullElse(structuralSignature,"");
        modifiers=Set.copyOf(modifiers);label=Objects.requireNonNullElse(label,name);parameters=List.copyOf(parameters);
    }
    public record ParameterLabel(int start,int end) { }
}
