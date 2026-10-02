package dev.jvmd.index;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.Hashing;
import dev.jvmd.core.tree.KeyedTree;
import java.io.IOException;
import java.util.*;

/**
 * Resident ordered semantic state: a {@link KeyedTree} of declaration facts ordered by owner, then
 * member, whose nodes carry resolution range sums. Member-range and overload-group identities are
 * read from those sums. Semantic-domain aggregates are maintained once outside the tree, and exact
 * symbols share the same canonical facts.
 */
public final class ResidentSemanticState {
    private static final String EMPTY=Hashing.sha256(new byte[0]);
    /** Facts ordered by {@link SemanticFact#orderedKey()}; ranges sum resolution identities. */
    public static final KeyedTree.Spec<String,SemanticFact> FACTS=new KeyedTree.StringKeys<>("semantic-facts-v1"){
        @Override public byte[] encodeValue(SemanticFact value)throws IOException{return FactCodec.encode(value);}
        @Override public SemanticFact decodeValue(byte[] bytes)throws IOException{return FactCodec.decode(bytes,SemanticFact.class);}
        @Override public Hash256 identity(SemanticFact value){return value.factIdentity();}
        @Override public Hash256 rangeIdentity(SemanticFact value){return value.resolutionIdentity();}
    };

    public record Aggregate(AlgebraicAccumulator.Value membership,AlgebraicAccumulator.Value api,
                            AlgebraicAccumulator.Value namespace,AlgebraicAccumulator.Value documentation) {
        static final Aggregate ZERO=new Aggregate(AlgebraicAccumulator.Value.ZERO,AlgebraicAccumulator.Value.ZERO,
                AlgebraicAccumulator.Value.ZERO,AlgebraicAccumulator.Value.ZERO);
        Aggregate add(Aggregate other){return new Aggregate(membership.plus(other.membership),api.plus(other.api),
                namespace.plus(other.namespace),documentation.plus(other.documentation));}
        Aggregate subtract(Aggregate other){return new Aggregate(membership.minus(other.membership),api.minus(other.api),
                namespace.minus(other.namespace),documentation.minus(other.documentation));}
        public String membershipIdentity(){return membership.identity("membership").hex();}
        public String apiIdentity(){return api.identity("api").hex();}
        public String namespaceIdentity(){return namespace.identity("namespace").hex();}
        public String documentationIdentity(){return documentation.identity("documentation").hex();}
    }

    public record Identity(long epoch,String merkleRoot,String membership,String api,String namespace,String documentation) { }

    private KeyedTree<String,SemanticFact> root=KeyedTree.empty(FACTS);
    private final Map<String,SemanticFact> symbols=new HashMap<>();
    private final Map<String,String> typesByFqn=new HashMap<>();
    private final Map<String,Set<String>> typesBySimpleName=new HashMap<>();
    private final Map<String,SemanticUnitState> units=new HashMap<>();
    private final Map<String,Aggregate> memberAggregates=new HashMap<>();
    private Aggregate semanticAggregate=Aggregate.ZERO;
    private final Map<String,Set<String>> directSupers=new HashMap<>(),directSubs=new HashMap<>();
    private final Map<String,String> hierarchyApis=new HashMap<>();
    private final Map<String,String> staleUnits=new HashMap<>();
    private AlgebraicAccumulator staleAggregate=new AlgebraicAccumulator("semantic-stale-v2");
    private long uncertaintyGeneration;
    private long epoch,rangeEntriesRead,factMutations;

    public synchronized SemanticDelta diff(SemanticSnapshot next){
        return SemanticDelta.between(units.get(next.unit()),next);
    }

    public synchronized SemanticDelta admit(SemanticSnapshot next){
        var delta=diff(next);apply(delta);return delta;
    }

    public synchronized void apply(SemanticDelta delta){
        var previous=units.get(delta.unit());
        boolean generationStale=previous!=null&&previous.uncertaintyGeneration()!=uncertaintyGeneration;
        boolean wasStale=staleUnits.containsKey(delta.unit())||generationStale;
        if(previous==null&&root.isEmpty()&&symbols.isEmpty()&&units.isEmpty()&&delta.changed().isEmpty()&&delta.removed().isEmpty()){
            applyInitial(delta);return;
        }
        boolean transition=previous==null||!Objects.equals(previous.contentIdentity(),delta.contentIdentity())
                ||!Objects.equals(previous.apiIdentity(),delta.apiIdentity())
                ||!Objects.equals(previous.namespaceIdentity(),delta.namespaceIdentity())
                ||!Objects.equals(previous.documentationIdentity(),delta.documentationIdentity())
                ||generationStale||!delta.emptyFacts();
        if(!transition)return;

        var hierarchyAffected=new LinkedHashSet<String>();
        if(wasStale&&previous!=null)previous.facts().forEachId(id->{
            var fact=symbols.get(id);if(fact!=null&&fact.typeDeclaration()){
                hierarchyAffected.add(fact.id());collectDescendants(fact.id(),hierarchyAffected);
            }
        });
        for(String id:delta.removed()){
            var old=symbols.remove(id);if(old!=null){beforeHierarchyMutation(old,hierarchyAffected);removeFact(old);}
        }
        for(var fact:delta.changed()){
            var old=symbols.get(fact.id());if(old!=null){beforeHierarchyMutation(old,hierarchyAffected);removeFact(old);}
            symbols.put(fact.id(),fact);addFact(fact);afterHierarchyMutation(fact,hierarchyAffected);
        }
        for(var fact:delta.added()){
            var old=symbols.put(fact.id(),fact);if(old!=null){beforeHierarchyMutation(old,hierarchyAffected);removeFact(old);}
            addFact(fact);afterHierarchyMutation(fact,hierarchyAffected);
        }
        clearExplicitStale(delta.unit());
        units.put(delta.unit(),nextUnitState(delta));
        recomputeHierarchyApis(hierarchyAffected);
        factMutations+=delta.factMutations();epoch++;
    }

    private void applyInitial(SemanticDelta delta){
        var ordered=new TreeMap<String,SemanticFact>();
        var hierarchyAffected=new LinkedHashSet<String>();
        for(var fact:delta.added()){
            symbols.put(fact.id(),fact);indexType(fact);
            var contribution=contribution(fact);
            ordered.put(fact.orderedKey(),fact);semanticAggregate=semanticAggregate.add(contribution);
            if(fact.member())memberAggregates.merge(fact.ownerId(),contribution,Aggregate::add);
            if(fact.typeDeclaration()){linkHierarchy(fact);hierarchyAffected.add(fact.id());}
        }
        root=KeyedTree.build(FACTS,List.copyOf(ordered.entrySet()));
        units.put(delta.unit(),nextUnitState(delta));
        recomputeHierarchyApis(hierarchyAffected);
        factMutations+=delta.factMutations();epoch++;
    }

    public synchronized SemanticDelta removeUnit(String unit){
        var previous=units.get(unit);
        if(previous==null)return new SemanticDelta(unit,null,"",SemanticUnitMerkle.empty(),List.of(),List.of(),Set.of(),"","","",Set.of(),SemanticCompleteness.UNKNOWN);
        var removed=previous.facts().ids();
        var delta=new SemanticDelta(unit,previous.sourceFile(),"",SemanticUnitMerkle.empty(),List.of(),List.of(),removed,"","","",Set.of(),previous.completeness());
        apply(delta);units.remove(unit);return delta;
    }

    /** Mark a source unit stale from mutation-maintained source identity without discarding reusable facts. */
    public synchronized boolean markSourceStale(String sourceFile,String contentIdentity){
        if(sourceFile==null)return false;
        String unit="source:"+sourceFile;var previous=units.get(unit);if(previous==null)return false;
        String content=Objects.requireNonNullElse(contentIdentity,"");
        if(content.equals(previous.contentIdentity())&&!staleUnits.containsKey(unit)
                &&previous.uncertaintyGeneration()==uncertaintyGeneration)return false;
        String old=staleUnits.put(unit,content);if(Objects.equals(old,content))return false;
        if(old==null)staleAggregate.add(unit,content);else staleAggregate.replace(unit,old,unit,content);
        var affected=new LinkedHashSet<String>();
        previous.facts().forEachId(id->{
            var fact=symbols.get(id);if(fact!=null&&fact.typeDeclaration()){
                affected.add(fact.id());collectDescendants(fact.id(),affected);
            }
        });
        affected.forEach(hierarchyApis::remove);epoch++;return true;
    }

    /** Lost source-change history invalidates every retained unit with one generation fence. */
    public synchronized void markHierarchyUncertain(){
        if(units.isEmpty())return;
        uncertaintyGeneration++;epoch++;
    }

    public synchronized SemanticFact symbol(String id){return symbols.get(id);}
    /** Direct maintained hierarchy edges for the canonical type, without walking ancestors. */
    public synchronized List<String> directSupertypeIds(String typeId){
        var values=new ArrayList<>(directSupers.getOrDefault(typeId,Set.of()));values.sort(String::compareTo);return List.copyOf(values);
    }
    public synchronized SemanticUnitState unit(String unit){return units.get(unit);}
    /** Resolve a canonical declaration to the retained semantic unit that owns it. */
    public synchronized String unitForFact(String id){
        var fact=symbols.get(id);if(fact==null)return null;
        if(fact.sourceFile()!=null&&!fact.sourceFile().isBlank()){
            String sourceUnit="source:"+fact.sourceFile();
            if(units.containsKey(sourceUnit))return sourceUnit;
        }
        String typeUnit="type:"+id;
        if(units.containsKey(typeUnit))return typeUnit;
        for(var entry:units.entrySet())if(entry.getValue().facts().contains(id))return entry.getKey();
        return null;
    }
    public synchronized String unitForType(String fqn){
        String id=typesByFqn.get(fqn);return id==null?null:unitForFact(id);
    }
    /** Exact retained type declaration by canonical binary/FQN identity. */
    public synchronized SemanticFact type(String fqn){
        String id=typesByFqn.get(fqn);return id==null?null:symbols.get(id);
    }
    /** Bounded maintained simple-name lookup; ambiguity is preserved for Java resolution filtering. */
    public synchronized List<SemanticFact> typesByName(String simpleName){
        var ids=typesBySimpleName.getOrDefault(Objects.requireNonNullElse(simpleName,""),Set.of());
        if(ids.isEmpty())return List.of();
        return ids.stream().map(symbols::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing((SemanticFact fact)->Objects.requireNonNullElse(fact.fqn(),"")).thenComparing(SemanticFact::id))
                .toList();
    }
    public synchronized boolean unitCurrent(String unit,String contentIdentity){
        var state=units.get(unit);if(state==null||staleUnits.containsKey(unit)
                ||state.uncertaintyGeneration()!=uncertaintyGeneration)return false;
        return contentIdentity==null||Objects.equals(contentIdentity,state.contentIdentity());
    }
    /** Completeness is authoritative only while the owning semantic unit is current. */
    public synchronized SemanticCompleteness completeness(String factId){
        String unit=unitForFact(factId);if(unit==null||!unitCurrent(unit,null))return SemanticCompleteness.UNKNOWN;
        var state=units.get(unit);return state==null?SemanticCompleteness.UNKNOWN:state.completeness();
    }
    public synchronized SemanticFact unitType(String unit,String fqn){
        var state=units.get(unit);if(state==null)return null;
        String id=state.facts().findId(candidate->{
            var fact=symbols.get(candidate);return fact!=null&&fact.typeDeclaration()&&Objects.equals(fqn,fact.fqn());
        });
        return id==null?null:symbols.get(id);
    }

    /**
     * Immutable range cursor over one owner/prefix slice. The cursor captures the persistent root
     * visible when it is created, so later mutations cannot change the sequence already being read.
     */
    public final class MemberCursor {
        private final KeyedTree.Cursor<String,SemanticFact> cursor;
        private MemberCursor(KeyedTree<String,SemanticFact> snapshot,String lower,String upper){cursor=snapshot.cursor(lower,upper);}
        /** Returns the next ordered fact, or null when the requested range is exhausted. */
        public SemanticFact next(){
            synchronized(ResidentSemanticState.this){
                var entry=cursor.next();if(entry==null)return null;
                rangeEntriesRead++;return entry.getValue();
            }
        }
    }

    public synchronized MemberCursor memberCursor(String ownerId,String namePrefix){
        return memberCursor(ownerId,namePrefix,null);
    }

    /** Resume a member range strictly after a previously returned ordered fact key. */
    public synchronized MemberCursor memberCursor(String ownerId,String namePrefix,String afterOrderedKey){
        String prefix=SemanticFact.memberPrefix(ownerId,namePrefix);
        String lower=afterOrderedKey==null?prefix:afterOrderedKey+"\0";
        if(!lower.startsWith(prefix))throw new IllegalArgumentException("Member cursor does not belong to requested owner/prefix");
        return new MemberCursor(root,lower,prefix+"\uffff");
    }

    public List<SemanticFact> members(String ownerId,String namePrefix,int limit){
        if(limit<=0)return List.of();
        var cursor=memberCursor(ownerId,namePrefix);var result=new ArrayList<SemanticFact>(Math.min(limit,64));
        SemanticFact fact;while(result.size()<limit&&(fact=cursor.next())!=null)result.add(fact);
        return List.copyOf(result);
    }

    public synchronized Aggregate memberAggregate(String ownerId){
        return memberAggregates.getOrDefault(ownerId,Aggregate.ZERO);
    }
    /** Resolution-only identity for one direct owner/name-prefix domain. */
    public synchronized Hash256 memberRangeIdentity(String ownerId,String namePrefix){
        String prefix=SemanticFact.memberPrefix(ownerId,Objects.requireNonNullElse(namePrefix,""));
        return root.range(prefix,prefix+"\uffff").identity("semantic-member-range-v1");
    }
    /** Resolution-only identity for the exact overload group of one member name. */
    public synchronized Hash256 overloadGroupIdentity(String ownerId,String name){
        String prefix=SemanticFact.memberPrefix(ownerId,Objects.requireNonNullElse(name,""))+"\0";
        var exact=root.range(prefix,prefix+"\uffff").identity("semantic-member-range-v1");
        return CanonicalDigestWriter.digest("semantic-overload-group-v1",exact);
    }
    /** Constant-time validity identity for the effective API reachable from a receiver type. */
    public synchronized String hierarchyApi(String typeId){
        return CanonicalDigestWriter.digest("hierarchy-validity-v2",uncertaintyGeneration,
                hierarchyApis.getOrDefault(typeId,EMPTY)).hex();
    }

    /**
     * Restart-stable hierarchy identity derived beneath the runtime uncertainty fence (§34).
     *
     * {@link #hierarchyApi} folds in the process-local {@code uncertaintyGeneration} so a fence can
     * invalidate every in-process hierarchy identity in O(1). That value must never be persisted.
     * This identity is a pure function of the composed hierarchy API, and is available only while
     * the type and every retained ancestor belong to current (unfenced, non-stale) units; otherwise
     * it is UNKNOWN (empty).
     */
    public synchronized Optional<Hash256> stableHierarchyIdentity(String typeId){
        var fact=symbols.get(typeId);if(fact==null||!fact.typeDeclaration())return Optional.empty();
        String api=hierarchyApis.get(typeId);if(api==null)return Optional.empty();
        var queue=new ArrayDeque<String>();queue.add(typeId);var seen=new HashSet<String>();
        while(!queue.isEmpty()){
            String current=queue.removeFirst();if(!seen.add(current))continue;
            if(symbols.containsKey(current)){
                String unit=unitForFact(current);
                if(unit==null||!unitCurrent(unit,null))return Optional.empty();
            }
            queue.addAll(directSupers.getOrDefault(current,Set.of()));
        }
        return Optional.of(CanonicalDigestWriter.digest("hierarchy-semantic-v1",api));
    }

    /** O(1) uncertainty fence generation for proof-backed cache keys. */
    public synchronized long uncertaintyGeneration(){return uncertaintyGeneration;}

    public synchronized Identity identity(){
        String structural=root.rootHash().hex();
        String merkle=CanonicalDigestWriter.digest("resident-state-v2",structural,freshnessIdentity()).hex();
        return new Identity(epoch,merkle,semanticAggregate.membershipIdentity(),semanticAggregate.apiIdentity(),
                semanticAggregate.namespaceIdentity(),semanticAggregate.documentationIdentity());
    }

    public synchronized void clear(){
        if(root.isEmpty()&&symbols.isEmpty()&&units.isEmpty())return;
        root=KeyedTree.empty(FACTS);symbols.clear();typesByFqn.clear();typesBySimpleName.clear();units.clear();memberAggregates.clear();semanticAggregate=Aggregate.ZERO;directSupers.clear();directSubs.clear();hierarchyApis.clear();staleUnits.clear();staleAggregate=new AlgebraicAccumulator("semantic-stale-v2");uncertaintyGeneration=0;epoch++;
    }

    /** Conservative retained-size estimate used only for semantic cache budgeting/retirement. */
    public synchronized long estimatedBytes(){
        long factCount=symbols.size();
        long unitRefs=units.values().stream().mapToLong(unit->unit.facts().size()).sum();
        long hierarchyEdges=directSupers.values().stream().mapToLong(Set::size).sum();
        return factCount*960L+unitRefs*48L+units.size()*256L+memberAggregates.size()*256L
                +hierarchyEdges*64L+hierarchyApis.size()*160L+staleUnits.size()*128L;
    }

    public synchronized Map<String,Object> status(){
        var identity=identity();
        return Map.ofEntries(
                Map.entry("semantic_epoch",epoch),Map.entry("semantic_root",identity.merkleRoot()),
                Map.entry("semantic_membership",identity.membership()),Map.entry("semantic_api",identity.api()),
                Map.entry("semantic_namespace",identity.namespace()),Map.entry("semantic_documentation",identity.documentation()),
                Map.entry("semantic_facts",symbols.size()),Map.entry("semantic_tree_entries",symbols.size()),
                Map.entry("semantic_units",units.size()),Map.entry("semantic_member_aggregates",memberAggregates.size()),
                Map.entry("semantic_hierarchy_aggregates",hierarchyApis.size()),Map.entry("semantic_stale_units",staleUnits.size()),
                Map.entry("semantic_fact_mutations",factMutations),Map.entry("semantic_tree_range_entries_read",rangeEntriesRead),
                Map.entry("semantic_descriptions",0),Map.entry("semantic_estimated_bytes",estimatedBytes()),
                Map.entry("semantic_stale_aggregate",staleAggregate.identity().hex()),Map.entry("semantic_stale_aggregate_cardinality",staleAggregate.cardinality()),
                Map.entry("semantic_uncertainty_generation",uncertaintyGeneration));
    }

    private SemanticUnitState nextUnitState(SemanticDelta delta){
        return new SemanticUnitState(delta.unit(),delta.sourceFile(),delta.contentIdentity(),delta.facts(),
                delta.apiIdentity(),delta.namespaceIdentity(),delta.documentationIdentity(),delta.dependencies(),delta.completeness(),uncertaintyGeneration);
    }

    private String freshnessIdentity(){
        return CanonicalDigestWriter.digest("semantic-freshness-v2",staleAggregate.identity(),
                staleAggregate.cardinality(),uncertaintyGeneration).hex();
    }
    private void clearExplicitStale(String unit){
        String old=staleUnits.remove(unit);
        if(old!=null)staleAggregate.remove(unit,old);
    }

    private void beforeHierarchyMutation(SemanticFact fact,Set<String> affected){
        if(fact.member()&&fact.ownerId()!=null){affected.add(fact.ownerId());collectDescendants(fact.ownerId(),affected);}
        if(fact.typeDeclaration()){affected.add(fact.id());collectDescendants(fact.id(),affected);unlinkHierarchy(fact.id());}
    }
    private void afterHierarchyMutation(SemanticFact fact,Set<String> affected){
        if(fact.typeDeclaration()){linkHierarchy(fact);affected.add(fact.id());collectDescendants(fact.id(),affected);}
        if(fact.member()&&fact.ownerId()!=null){affected.add(fact.ownerId());collectDescendants(fact.ownerId(),affected);}
    }
    private void collectDescendants(String root,Set<String> result){
        var queue=new ArrayDeque<String>();queue.add(root);
        while(!queue.isEmpty()){
            String current=queue.removeFirst();
            for(String child:directSubs.getOrDefault(current,Set.of()))if(result.add(child))queue.addLast(child);
        }
    }
    private static Set<String> superIds(SemanticFact fact){
        var result=new LinkedHashSet<String>();
        for(var type:fact.directSupertypes()){
            if(type instanceof SemanticType.Declared declared)result.add(declared.symbolId());
            else if(type instanceof SemanticType.Intersection intersection)for(var bound:intersection.bounds())
                if(bound instanceof SemanticType.Declared declared)result.add(declared.symbolId());
        }
        return Set.copyOf(result);
    }
    private void unlinkHierarchy(String typeId){
        var old=directSupers.remove(typeId);
        if(old!=null)for(String parent:old){
            var children=directSubs.get(parent);
            if(children!=null){
                var next=new LinkedHashSet<>(children);next.remove(typeId);
                if(next.isEmpty())directSubs.remove(parent);else directSubs.put(parent,Set.copyOf(next));
            }
        }
        hierarchyApis.remove(typeId);
    }
    private void linkHierarchy(SemanticFact fact){
        var parents=superIds(fact);directSupers.put(fact.id(),parents);
        for(String parent:parents){
            var children=new LinkedHashSet<>(directSubs.getOrDefault(parent,Set.of()));
            children.add(fact.id());directSubs.put(parent,Set.copyOf(children));
        }
    }
    /**
     * Compose hierarchy validity from direct DAG dependencies. A batch memoizes each affected
     * owner once, so a base API edit propagates through descendants without repeatedly walking
     * every transitive ancestor closure.
     */
    private void recomputeHierarchyApis(Collection<String> affected){
        var affectedSet=new LinkedHashSet<String>(affected);
        var memo=new HashMap<String,String>();var visiting=new HashSet<String>();
        for(String typeId:affectedSet)composeHierarchyApi(typeId,affectedSet,memo,visiting);
    }

    private String composeHierarchyApi(String typeId,Set<String> affected,Map<String,String> memo,Set<String> visiting){
        String cached=memo.get(typeId);if(cached!=null)return cached;
        if(!affected.contains(typeId)){
            cached=hierarchyApis.get(typeId);if(cached!=null)return cached;
        }
        var type=symbols.get(typeId);
        if(type==null||!type.typeDeclaration()){hierarchyApis.remove(typeId);memo.put(typeId,EMPTY);return EMPTY;}
        if(!visiting.add(typeId))return EMPTY;

        String typeApi=type.apiIdentity().isBlank()?type.structuralSignature():type.apiIdentity();
        var members=memberAggregates.getOrDefault(typeId,Aggregate.ZERO);
        var parentParts=new ArrayList<Object>();
        var parents=new ArrayList<>(directSupers.getOrDefault(typeId,Set.of()));parents.sort(String::compareTo);
        for(String parent:parents){
            parentParts.add(new Object[]{parent,composeHierarchyApi(parent,affected,memo,visiting)});
        }
        visiting.remove(typeId);
        String value=CanonicalDigestWriter.digest("hierarchy-api-v2",typeId,typeApi,
                members.apiIdentity(),members.api().cardinality(),parentParts).hex();
        hierarchyApis.put(typeId,value);memo.put(typeId,value);return value;
    }

    private void indexType(SemanticFact fact){
        if(!fact.typeDeclaration()||fact.fqn()==null||fact.fqn().isBlank())return;
        typesByFqn.put(fact.fqn(),fact.id());
        var ids=new TreeSet<>(typesBySimpleName.getOrDefault(fact.name(),Set.of()));
        ids.add(fact.id());typesBySimpleName.put(fact.name(),Set.copyOf(ids));
    }
    private void unindexType(SemanticFact fact){
        if(!fact.typeDeclaration()||fact.fqn()==null||fact.fqn().isBlank())return;
        typesByFqn.remove(fact.fqn(),fact.id());
        var ids=new TreeSet<>(typesBySimpleName.getOrDefault(fact.name(),Set.of()));
        ids.remove(fact.id());
        if(ids.isEmpty())typesBySimpleName.remove(fact.name());
        else typesBySimpleName.put(fact.name(),Set.copyOf(ids));
    }
    private void addFact(SemanticFact fact){
        indexType(fact);
        var contribution=contribution(fact);root=root.put(fact.orderedKey(),fact);semanticAggregate=semanticAggregate.add(contribution);
        if(fact.member())memberAggregates.merge(fact.ownerId(),contribution,Aggregate::add);
    }

    private void removeFact(SemanticFact fact){
        unindexType(fact);
        var contribution=contribution(fact);root=root.remove(fact.orderedKey());semanticAggregate=semanticAggregate.subtract(contribution);
        if(fact.member())memberAggregates.compute(fact.ownerId(),(_,old)->{
            if(old==null)return null;var next=old.subtract(contribution);return next.equals(Aggregate.ZERO)?null:next;
        });
    }

    private static Aggregate contribution(SemanticFact fact){
        return new Aggregate(
                AlgebraicAccumulator.contribution("membership",fact.id(),"present"),
                AlgebraicAccumulator.contribution("api",fact.id(),fact.apiIdentity().isBlank()?fact.structuralSignature():fact.apiIdentity()),
                AlgebraicAccumulator.contribution("namespace",fact.id(),fact.namespaceIdentity().isBlank()?fact.packageName()+"\0"+fact.name()+"\0"+fact.kind():fact.namespaceIdentity()),
                AlgebraicAccumulator.contribution("documentation",fact.id(),fact.documentationIdentity()));
    }
}
