package dev.jvmd.index;

import java.util.*;
import java.util.function.Function;

/** Bounded semantic mutation detached from javac-owned state. */
public record SemanticDelta(
        String unit,
        String sourceFile,
        String contentIdentity,
        List<SemanticFact> added,
        List<SemanticFact> changed,
        Set<String> removed,
        List<SymbolDescription> descriptionsChanged,
        Set<String> descriptionsRemoved,
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Set<String> dependencies) {

    public SemanticDelta {
        Objects.requireNonNull(unit);contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        added=List.copyOf(added);changed=List.copyOf(changed);removed=Set.copyOf(removed);
        descriptionsChanged=List.copyOf(descriptionsChanged);descriptionsRemoved=Set.copyOf(descriptionsRemoved);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        dependencies=Set.copyOf(dependencies);
    }

    public int factMutations(){return added.size()+changed.size()+removed.size();}
    public boolean emptyFacts(){return factMutations()==0;}

    public static SemanticDelta between(SemanticUnitState previous,SemanticSnapshot next,
                                        Function<String,SemanticFact> factLookup,
                                        Function<String,SymbolDescription> descriptionLookup){
        Objects.requireNonNull(next);Objects.requireNonNull(factLookup);Objects.requireNonNull(descriptionLookup);
        var oldFactIds=previous==null?Set.<String>of():previous.factIds();
        var added=new ArrayList<SemanticFact>();var changed=new ArrayList<SemanticFact>();
        for(var entry:next.facts().entrySet()){
            var old=oldFactIds.contains(entry.getKey())?factLookup.apply(entry.getKey()):null;
            if(old==null)added.add(entry.getValue());
            else if(!old.factIdentity().equals(entry.getValue().factIdentity()))changed.add(entry.getValue());
        }
        var removed=new LinkedHashSet<String>(oldFactIds);removed.removeAll(next.facts().keySet());

        var oldDescriptionIds=previous==null?Set.<String>of():previous.descriptionIds();
        var descriptionsChanged=new ArrayList<SymbolDescription>();
        for(var entry:next.descriptions().entrySet()){
            var old=oldDescriptionIds.contains(entry.getKey())?descriptionLookup.apply(entry.getKey()):null;
            if(!entry.getValue().equals(old))descriptionsChanged.add(entry.getValue());
        }
        var descriptionsRemoved=new LinkedHashSet<String>(oldDescriptionIds);descriptionsRemoved.removeAll(next.descriptions().keySet());

        return new SemanticDelta(next.unit(),next.sourceFile(),next.contentIdentity(),added,changed,removed,
                descriptionsChanged,descriptionsRemoved,next.apiIdentity(),next.namespaceIdentity(),
                next.documentationIdentity(),next.dependencies());
    }
}
