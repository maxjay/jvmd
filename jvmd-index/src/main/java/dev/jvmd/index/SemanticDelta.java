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
        String apiIdentity,
        String namespaceIdentity,
        String documentationIdentity,
        Set<String> dependencies) {

    public SemanticDelta {
        Objects.requireNonNull(unit);contentIdentity=Objects.requireNonNullElse(contentIdentity,"");
        facts=Objects.requireNonNull(facts);
        added=List.copyOf(added);changed=List.copyOf(changed);removed=Set.copyOf(removed);
        apiIdentity=Objects.requireNonNullElse(apiIdentity,"");
        namespaceIdentity=Objects.requireNonNullElse(namespaceIdentity,"");
        documentationIdentity=Objects.requireNonNullElse(documentationIdentity,"");
        dependencies=Set.copyOf(dependencies);
    }

    public int factMutations(){return added.size()+changed.size()+removed.size();}
    public boolean emptyFacts(){return factMutations()==0;}

    public static SemanticDelta between(SemanticUnitState previous,SemanticSnapshot next,
                                        Function<String,SemanticFact> factLookup){
        Objects.requireNonNull(next);Objects.requireNonNull(factLookup);
        var previousFacts=previous==null?SemanticUnitMerkle.empty():previous.facts();
        var nextFacts=SemanticUnitMerkle.from(next.facts().values());
        var factDiff=previousFacts.diff(nextFacts);

        var added=new ArrayList<SemanticFact>(factDiff.added().size());
        for(String id:factDiff.added()){var fact=next.facts().get(id);if(fact!=null)added.add(fact);}
        var changed=new ArrayList<SemanticFact>(factDiff.changed().size());
        for(String id:factDiff.changed()){var fact=next.facts().get(id);if(fact!=null)changed.add(fact);}

        return new SemanticDelta(next.unit(),next.sourceFile(),next.contentIdentity(),nextFacts,added,changed,factDiff.removed(),
                next.apiIdentity(),next.namespaceIdentity(),next.documentationIdentity(),next.dependencies());
    }
}
