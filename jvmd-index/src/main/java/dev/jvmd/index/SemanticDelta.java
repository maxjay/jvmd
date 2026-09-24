package dev.jvmd.index;

import java.util.*;
import java.util.function.Function;

/** Bounded semantic mutation detached from javac-owned state. */
public record SemanticDelta(
        String unit,
        String sourceFile,
        String contentIdentity,
        SemanticUnitMerkle facts,
        List<SemanticFact> added,
        List<SemanticFact> changed,
        Set<String> removed,
        List<SymbolDescription> descriptionsChanged,
        Set<String> descriptionsRemoved,
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Set<String> dependencies,
        int factBucketsVisited,
        int factEntriesCompared) {

    public SemanticDelta {
        Objects.requireNonNull(unit);contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        facts=Objects.requireNonNull(facts);
        added=List.copyOf(added);changed=List.copyOf(changed);removed=Set.copyOf(removed);
        descriptionsChanged=List.copyOf(descriptionsChanged);descriptionsRemoved=Set.copyOf(descriptionsRemoved);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        dependencies=Set.copyOf(dependencies);
        if(factBucketsVisited<0||factEntriesCompared<0)throw new IllegalArgumentException("negative diff counters");
    }

    public int factMutations(){return added.size()+changed.size()+removed.size();}
    public boolean emptyFacts(){return factMutations()==0;}

    public static SemanticDelta between(SemanticUnitState previous,SemanticSnapshot next,
                                        Function<String,SemanticFact> factLookup,
                                        Function<String,SymbolDescription> descriptionLookup){
        Objects.requireNonNull(next);Objects.requireNonNull(factLookup);Objects.requireNonNull(descriptionLookup);
        var previousFacts=previous==null?SemanticUnitMerkle.empty():previous.facts();
        var nextFacts=SemanticUnitMerkle.from(next.facts().values());
        var factDiff=previousFacts.diff(nextFacts);

        var added=new ArrayList<SemanticFact>(factDiff.added().size());
        for(String id:factDiff.added()){var fact=next.facts().get(id);if(fact!=null)added.add(fact);}
        var changed=new ArrayList<SemanticFact>(factDiff.changed().size());
        for(String id:factDiff.changed()){var fact=next.facts().get(id);if(fact!=null)changed.add(fact);}

        var oldDescriptionIds=previous==null?Set.<String>of():previous.descriptionIds();
        var descriptionsChanged=new ArrayList<SymbolDescription>();
        for(var entry:next.descriptions().entrySet()){
            var old=oldDescriptionIds.contains(entry.getKey())?descriptionLookup.apply(entry.getKey()):null;
            if(!entry.getValue().equals(old))descriptionsChanged.add(entry.getValue());
        }
        var descriptionsRemoved=new LinkedHashSet<String>(oldDescriptionIds);descriptionsRemoved.removeAll(next.descriptions().keySet());

        return new SemanticDelta(next.unit(),next.sourceFile(),next.contentIdentity(),nextFacts,added,changed,factDiff.removed(),
                descriptionsChanged,descriptionsRemoved,next.apiIdentity(),next.namespaceIdentity(),
                next.documentationIdentity(),next.dependencies(),factDiff.bucketsVisited(),factDiff.entriesCompared());
    }
}
