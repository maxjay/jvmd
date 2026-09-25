package dev.jvmd.index;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import dev.jvmd.core.Hashing;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Resident ordered semantic state.
 *
 * The content-derived treap is a prolly-style persistent ordered Merkle tree: key hashes determine
 * structure, path-copy updates recalculate only structural Merkle identity, semantic-domain
 * aggregates are maintained once outside the tree, and exact symbols share the same canonical facts.
 */
public final class ResidentSemanticState {
    private static final BigInteger FIELD=new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",16);
    private static final String EMPTY=Hashing.sha256(new byte[0]);
    private static final Hash256 EMPTY_HASH=Hash256.fromHex(EMPTY);

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

    private record Entry(String key,SemanticFact fact,Hash256 valueIdentity,Aggregate contribution,BigInteger priority) { }
    private static final class Node {
        final Entry entry;final Node left,right;final Hash256 merkle;
        final AlgebraicAccumulator.Value resolutionRange;
        final String minKey,maxKey;
        Node(Entry entry,Node left,Node right){
            this.entry=entry;this.left=left;this.right=right;
            merkle=CanonicalDigestWriter.digest("resident-node-v1",left==null?EMPTY_HASH:left.merkle,entry.key(),entry.valueIdentity(),right==null?EMPTY_HASH:right.merkle);
            var self=AlgebraicAccumulator.contribution("semantic-member-range-v1",entry.key(),entry.fact().resolutionIdentity());
            resolutionRange=(left==null?AlgebraicAccumulator.Value.ZERO:left.resolutionRange)
                    .plus(self).plus(right==null?AlgebraicAccumulator.Value.ZERO:right.resolutionRange);
            minKey=left==null?entry.key():left.minKey;
            maxKey=right==null?entry.key():right.maxKey;
        }
    }

    private Node root;
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
        return SemanticDelta.between(units.get(next.unit()),next,symbols::get);
    }

    public synchronized SemanticDelta admit(SemanticSnapshot next){
        var delta=diff(next);apply(delta);return delta;
    }

    public synchronized void apply(SemanticDelta delta){
        var previous=units.get(delta.unit());
        boolean generationStale=previous!=null&&previous.uncertaintyGeneration()!=uncertaintyGeneration;
        boolean wasStale=staleUnits.containsKey(delta.unit())||generationStale;
        if(previous==null&&root==null&&symbols.isEmpty()&&units.isEmpty()&&delta.changed().isEmpty()&&delta.removed().isEmpty()){
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
        var ordered=new ArrayList<Entry>(delta.added().size());
        var hierarchyAffected=new LinkedHashSet<String>();
        for(var fact:delta.added()){
            symbols.put(fact.id(),fact);indexType(fact);
            var contribution=contribution(fact);
            ordered.add(entry(fact,contribution));semanticAggregate=semanticAggregate.add(contribution);
            if(fact.member())memberAggregates.merge(fact.ownerId(),contribution,Aggregate::add);
            if(fact.typeDeclaration()){linkHierarchy(fact);hierarchyAffected.add(fact.id());}
        }
        ordered.sort(Comparator.comparing(Entry::key));
        root=bulkBuild(ordered);
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
        private final String lower,upper;
        private final ArrayDeque<Node> stack=new ArrayDeque<>();
        private MemberCursor(Node snapshot,String lower,String upper){this.lower=lower;this.upper=upper;push(snapshot);}
        private void push(Node node){
            while(node!=null){
                int low=node.entry.key().compareTo(lower),high=node.entry.key().compareTo(upper);
                if(low<0){node=node.right;continue;}
                if(high>0){node=node.left;continue;}
                stack.push(node);node=node.left;
            }
        }
        /** Returns the next ordered fact, or null when the requested range is exhausted. */
        public SemanticFact next(){
            synchronized(ResidentSemanticState.this){
                if(stack.isEmpty())return null;
                var node=stack.pop();push(node.right);rangeEntriesRead++;return node.entry.fact();
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
        return resolutionRange(root,prefix,prefix+"\uffff").identity("semantic-member-range-v1");
    }
    /** Resolution-only identity for the exact overload group of one member name. */
    public synchronized Hash256 overloadGroupIdentity(String ownerId,String name){
        String prefix=SemanticFact.memberPrefix(ownerId,Objects.requireNonNullElse(name,""))+"\0";
        var exact=resolutionRange(root,prefix,prefix+"\uffff").identity("semantic-member-range-v1");
        return CanonicalDigestWriter.digest("semantic-overload-group-v1",exact);
    }
    /** Constant-time validity identity for the effective API reachable from a receiver type. */
    public synchronized String hierarchyApi(String typeId){
        return CanonicalDigestWriter.digest("hierarchy-validity-v2",uncertaintyGeneration,
                hierarchyApis.getOrDefault(typeId,EMPTY)).hex();
    }

    public synchronized Identity identity(){
        String structural=root==null?EMPTY:root.merkle.hex();
        String merkle=CanonicalDigestWriter.digest("resident-state-v2",structural,freshnessIdentity()).hex();
        return new Identity(epoch,merkle,semanticAggregate.membershipIdentity(),semanticAggregate.apiIdentity(),
                semanticAggregate.namespaceIdentity(),semanticAggregate.documentationIdentity());
    }

    public synchronized void clear(){
        if(root==null&&symbols.isEmpty()&&units.isEmpty())return;
        root=null;symbols.clear();typesByFqn.clear();typesBySimpleName.clear();units.clear();memberAggregates.clear();semanticAggregate=Aggregate.ZERO;directSupers.clear();directSubs.clear();hierarchyApis.clear();staleUnits.clear();staleAggregate=new AlgebraicAccumulator("semantic-stale-v2");uncertaintyGeneration=0;epoch++;
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
        var contribution=contribution(fact);root=put(root,entry(fact,contribution));semanticAggregate=semanticAggregate.add(contribution);
        if(fact.member())memberAggregates.merge(fact.ownerId(),contribution,Aggregate::add);
    }

    private void removeFact(SemanticFact fact){
        unindexType(fact);
        var contribution=contribution(fact);root=remove(root,fact.orderedKey());semanticAggregate=semanticAggregate.subtract(contribution);
        if(fact.member())memberAggregates.compute(fact.ownerId(),(_,old)->{
            if(old==null)return null;var next=old.subtract(contribution);return next.equals(Aggregate.ZERO)?null:next;
        });
    }

    private static Entry entry(SemanticFact fact,Aggregate contribution){
        String key=fact.orderedKey();return new Entry(key,fact,fact.factIdentity(),contribution,point("priority",key));
    }

    private static Aggregate contribution(SemanticFact fact){
        return new Aggregate(
                AlgebraicAccumulator.contribution("membership",fact.id(),"present"),
                AlgebraicAccumulator.contribution("api",fact.id(),fact.apiIdentity().isBlank()?fact.structuralSignature():fact.apiIdentity()),
                AlgebraicAccumulator.contribution("namespace",fact.id(),fact.namespaceIdentity().isBlank()?fact.packageName()+"\0"+fact.name()+"\0"+fact.kind():fact.namespaceIdentity()),
                AlgebraicAccumulator.contribution("documentation",fact.id(),fact.documentationIdentity()));
    }

    private static BigInteger point(String domain,String value){
        return Hash256.sha256((domain+"\0"+Objects.requireNonNullElse(value,"")).getBytes(StandardCharsets.UTF_8)).unsignedInteger().mod(FIELD);
    }

    private static AlgebraicAccumulator.Value resolutionRange(Node node,String lower,String upper){
        if(node==null||node.maxKey.compareTo(lower)<0||node.minKey.compareTo(upper)>0)
            return AlgebraicAccumulator.Value.ZERO;
        if(node.minKey.compareTo(lower)>=0&&node.maxKey.compareTo(upper)<=0)return node.resolutionRange;
        var result=resolutionRange(node.left,lower,upper);
        if(node.entry.key().compareTo(lower)>=0&&node.entry.key().compareTo(upper)<=0)
            result=result.plus(AlgebraicAccumulator.contribution("semantic-member-range-v1",
                    node.entry.key(),node.entry.fact().resolutionIdentity()));
        return result.plus(resolutionRange(node.right,lower,upper));
    }

    private Node newNode(Entry entry,Node left,Node right){return new Node(entry,left,right);}

    /**
     * Build the deterministic priority treap in linear structural time from already ordered facts.
     * The Cartesian-tree shape is exactly the shape produced by incremental inserts with the same
     * strict priority comparison, but without persistent path-copying during first admission.
     */
    private Node bulkBuild(List<Entry> ordered){
        int size=ordered.size();if(size==0)return null;
        var left=new int[size];var right=new int[size];var stack=new int[size];
        Arrays.fill(left,-1);Arrays.fill(right,-1);int top=-1;
        for(int i=0;i<size;i++){
            int previous=-1;
            while(top>=0&&ordered.get(stack[top]).priority().compareTo(ordered.get(i).priority())<0)previous=stack[top--];
            left[i]=previous;if(top>=0)right[stack[top]]=i;stack[++top]=i;
        }
        return freezeBulk(ordered,left,right,stack[0]);
    }

    private Node freezeBulk(List<Entry> ordered,int[] left,int[] right,int index){
        if(index<0)return null;
        var leftNode=freezeBulk(ordered,left,right,left[index]);
        var rightNode=freezeBulk(ordered,left,right,right[index]);
        return newNode(ordered.get(index),leftNode,rightNode);
    }

    private Node put(Node node,Entry entry){
        if(node==null)return newNode(entry,null,null);
        int compare=entry.key().compareTo(node.entry.key());
        if(compare==0)return newNode(entry,node.left,node.right);
        if(compare<0){
            var next=newNode(node.entry,put(node.left,entry),node.right);
            return next.left.entry.priority().compareTo(next.entry.priority())>0?rotateRight(next):next;
        }
        var next=newNode(node.entry,node.left,put(node.right,entry));
        return next.right.entry.priority().compareTo(next.entry.priority())>0?rotateLeft(next):next;
    }

    private Node remove(Node node,String key){
        if(node==null)return null;int compare=key.compareTo(node.entry.key());
        if(compare==0)return merge(node.left,node.right);
        return compare<0?newNode(node.entry,remove(node.left,key),node.right):newNode(node.entry,node.left,remove(node.right,key));
    }

    private Node merge(Node left,Node right){
        if(left==null)return right;if(right==null)return left;
        if(left.entry.priority().compareTo(right.entry.priority())>0)return newNode(left.entry,left.left,merge(left.right,right));
        return newNode(right.entry,merge(left,right.left),right.right);
    }

    private Node rotateRight(Node node){
        var top=node.left;var lower=newNode(node.entry,top.right,node.right);return newNode(top.entry,top.left,lower);
    }
    private Node rotateLeft(Node node){
        var top=node.right;var lower=newNode(node.entry,node.left,top.left);return newNode(top.entry,lower,top.right);
    }

}
