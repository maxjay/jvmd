package dev.jvmd.index;

import dev.jvmd.core.Hash256;
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
        String editorLabel,
        List<ParameterLabel> parameters,
        Hash256 resolutionIdentity) {
    public CompletionCandidate(String id,String name,String kind,String structuralSignature,String declaringType,
                               String sourceFile,Set<String> modifiers,String label,List<ParameterLabel> parameters){
        this(id,name,kind,structuralSignature,declaringType,sourceFile,modifiers,label,label,parameters,null);
    }
    public CompletionCandidate(String id,String name,String kind,String structuralSignature,String declaringType,
                               String sourceFile,Set<String> modifiers,String label,String editorLabel,List<ParameterLabel> parameters){
        this(id,name,kind,structuralSignature,declaringType,sourceFile,modifiers,label,editorLabel,parameters,null);
    }
    public CompletionCandidate {
        Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(kind);
        structuralSignature=Objects.requireNonNullElse(structuralSignature,"");
        modifiers=Set.copyOf(modifiers);label=Objects.requireNonNullElse(label,name);
        editorLabel=Objects.requireNonNullElse(editorLabel,label);parameters=List.copyOf(parameters);
    }
    public record ParameterLabel(int start,int end) { }

    /** Editor-facing Java type spelling; semantic identity remains fully qualified in SemanticType. */
    public static String typeLabel(SemanticType type,boolean compactNested){
        return switch(type){
            case SemanticType.Primitive value -> value.name();
            case SemanticType.Declared value -> {
                String name=value.name();int split=Math.max(name.lastIndexOf('.'),name.lastIndexOf((char)36));
                String simple=name.substring(split+1);
                if(value.arguments().isEmpty())yield simple;
                var arguments=compactNested
                        ?value.arguments().stream().map(argument->typeLabel(argument,true)).toList()
                        :value.arguments().stream().map(SemanticType::display).toList();
                yield simple+"<"+String.join(compactNested?",":", ",arguments)+">";
            }
            case SemanticType.Variable value -> value.name();
            case SemanticType.Array value -> typeLabel(value.component(),compactNested)+"[]";
            case SemanticType.Wildcard value -> value.extendsBound()!=null
                    ?"? extends "+typeLabel(value.extendsBound(),compactNested)
                    :value.superBound()!=null?"? super "+typeLabel(value.superBound(),compactNested):"?";
            case SemanticType.Intersection value -> String.join(" & ",
                    value.bounds().stream().map(bound->typeLabel(bound,compactNested)).toList());
            case SemanticType.Executable value -> value.display();
            case SemanticType.Unknown value -> value.text();
        };
    }
}
