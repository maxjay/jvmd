package dev.jvmd.index;

import java.util.*;

/** Bounded semantic mutation detached from javac-owned state. */
public record SemanticDelta(
        String unit,
        String sourceFile,
        String contentIdentity,
        Map<String,SemanticFact> added,
        Map<String,SemanticFact> changed,
        Set<String> removed,
        Map<String,SymbolDescription> descriptionsChanged,
        Set<String> descriptionsRemoved,
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Set<String> dependencies) {

    public SemanticDelta {
        Objects.requireNonNull(unit);contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        added=Map.copyOf(added);changed=Map.copyOf(changed);removed=Set.copyOf(removed);
        descriptionsChanged=Map.copyOf(descriptionsChanged);descriptionsRemoved=Set.copyOf(descriptionsRemoved);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        dependencies=Set.copyOf(dependencies);
    }

    public int factMutations(){return added.size()+changed.size()+removed.size();}
    public boolean emptyFacts(){return factMutations()==0;}

    public static SemanticDelta between(SemanticSnapshot previous,SemanticSnapshot next){
        Objects.requireNonNull(next);
        var oldFacts=previous==null?Map.<String,SemanticFact>of():previous.facts();
        var added=new LinkedHashMap<String,SemanticFact>(),changed=new LinkedHashMap<String,SemanticFact>();
        for(var entry:next.facts().entrySet()){
            var old=oldFacts.get(entry.getKey());
            if(old==null)added.put(entry.getKey(),entry.getValue());
            else if(!old.equals(entry.getValue()))changed.put(entry.getKey(),entry.getValue());
        }
        var removed=new LinkedHashSet<String>(oldFacts.keySet());removed.removeAll(next.facts().keySet());

        var oldDescriptions=previous==null?Map.<String,SymbolDescription>of():previous.descriptions();
        var descriptionsChanged=new LinkedHashMap<String,SymbolDescription>();
        for(var entry:next.descriptions().entrySet())
            if(!entry.getValue().equals(oldDescriptions.get(entry.getKey())))descriptionsChanged.put(entry.getKey(),entry.getValue());
        var descriptionsRemoved=new LinkedHashSet<String>(oldDescriptions.keySet());descriptionsRemoved.removeAll(next.descriptions().keySet());

        return new SemanticDelta(next.unit(),next.sourceFile(),next.contentIdentity(),added,changed,removed,
                descriptionsChanged,descriptionsRemoved,next.apiIdentity(),next.namespaceIdentity(),
                next.documentationIdentity(),next.dependencies());
    }
}
