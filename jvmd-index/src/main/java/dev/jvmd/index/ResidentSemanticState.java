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
        Node(Entry entry,Node left,Node right){
            this.entry=entry;this.left=left;this.right=right;
            merkle=CanonicalDigestWriter.digest("resident-node-v1",left==null?EMPTY_HASH:left.merkle,entry.key(),entry.valueIdentity(),right==null?EMPTY_HASH:right.merkle);
        }
    }

    private Node root;
    private final Map<String,SemanticFact> symbols=new HashMap<>();
    private final Map<String,SymbolDescription> descriptions=new HashMap<>();
    private final Map<String,SemanticSnapshot> units=new HashMap<>();
    private final Map<String,Aggregate> memberAggregates=new HashMap<>();
    private Aggregate semanticAggregate=Aggregate.ZERO;
    private final Map<String,Set<String>> directSupers=new HashMap<>(),directSubs=new HashMap<>();
    private final Map<String,String> hierarchyApis=new HashMap<>();
    private final Map<String,String> staleUnits=new HashMap<>();
    private String freshnessMerkle=EMPTY;
    private long epoch,rangeEntriesRead,factMutations,treeNodesCreated,bulkBuilds;

    public synchronized SemanticDelta diff(SemanticSnapshot next){return SemanticDelta.between(units.get(next.unit()),next);}

    public synchronized SemanticDelta admit(SemanticSnapshot next){
        var delta=diff(next);apply(delta);return delta;
    }

    public synchronized void apply(SemanticDelta delta){
        var previous=units.get(delta.unit());boolean wasStale=staleUnits.containsKey(delta.unit());
        if(previous==null&&root==null&&symbols.isEmpty()&&units.isEmpty()&&delta.changed().isEmpty()&&delta.removed().isEmpty()){
            applyInitial(delta);return;
        }
        boolean transition=previous==null||!Objects.equals(previous.contentIdentity(),delta.contentIdentity())
                ||!Objects.equals(previous.apiIdentity(),delta.apiIdentity())
                ||!Objects.equals(previous.namespaceIdentity(),delta.namespaceIdentity())
                ||!Objects.equals(previous.documentationIdentity(),delta.documentationIdentity())
                ||!delta.emptyFacts()||!delta.descriptionsChanged().isEmpty()||!delta.descriptionsRemoved().isEmpty();
        if(!transition)return;

        var hierarchyAffected=new LinkedHashSet<String>();
        if(wasStale&&previous!=null)for(var fact:previous.facts().values())if(fact.typeDeclaration()){
            hierarchyAffected.add(fact.id());collectDescendants(fact.id(),hierarchyAffected);
        }
        for(String id:delta.removed()){
            var old=symbols.remove(id);if(old!=null){beforeHierarchyMutation(old,hierarchyAffected);removeFact(old);}
        }
        for(var entry:delta.changed().entrySet()){
            var old=symbols.get(entry.getKey());if(old!=null){beforeHierarchyMutation(old,hierarchyAffected);removeFact(old);}
            symbols.put(entry.getKey(),entry.getValue());addFact(entry.getValue());afterHierarchyMutation(entry.getValue(),hierarchyAffected);
        }
        for(var entry:delta.added().entrySet()){
            var old=symbols.put(entry.getKey(),entry.getValue());if(old!=null){beforeHierarchyMutation(old,hierarchyAffected);removeFact(old);}
            addFact(entry.getValue());afterHierarchyMutation(entry.getValue(),hierarchyAffected);
        }
        recomputeHierarchyApis(hierarchyAffected);
        if(staleUnits.remove(delta.unit())!=null)refreshFreshness();
        delta.descriptionsRemoved().forEach(descriptions::remove);
        descriptions.putAll(delta.descriptionsChanged());

        var facts=new LinkedHashMap<String,SemanticFact>();
        var desc=new LinkedHashMap<String,SymbolDescription>();
        if(previous!=null){facts.putAll(previous.facts());desc.putAll(previous.descriptions());}
        delta.removed().forEach(facts::remove);facts.putAll(delta.changed());facts.putAll(delta.added());
        delta.descriptionsRemoved().forEach(desc::remove);desc.putAll(delta.descriptionsChanged());
        units.put(delta.unit(),new SemanticSnapshot(delta.unit(),delta.sourceFile(),delta.contentIdentity(),facts,desc,
                delta.apiIdentity(),delta.namespaceIdentity(),delta.documentationIdentity(),delta.dependencies()));
        factMutations+=delta.factMutations();epoch++;
    }

    private void applyInitial(SemanticDelta delta){
        var ordered=new ArrayList<Entry>(delta.added().size());
        var hierarchyAffected=new LinkedHashSet<String>();
        for(var fact:delta.added().values()){
            symbols.put(fact.id(),fact);
            var contribution=contribution(fact);
            ordered.add(entry(fact,contribution));semanticAggregate=semanticAggregate.add(contribution);
            if(fact.member())memberAggregates.merge(fact.ownerId(),contribution,Aggregate::add);
            if(fact.typeDeclaration()){linkHierarchy(fact);hierarchyAffected.add(fact.id());}
        }
        ordered.sort(Comparator.comparing(Entry::key));
        root=bulkBuild(ordered);
        recomputeHierarchyApis(hierarchyAffected);
        descriptions.putAll(delta.descriptionsChanged());
        units.put(delta.unit(),new SemanticSnapshot(delta.unit(),delta.sourceFile(),delta.contentIdentity(),
                delta.added(),delta.descriptionsChanged(),delta.apiIdentity(),delta.namespaceIdentity(),
                delta.documentationIdentity(),delta.dependencies()));
        factMutations+=delta.factMutations();bulkBuilds++;epoch++;
    }

    public synchronized SemanticDelta removeUnit(String unit){
        var previous=units.get(unit);if(previous==null)return new SemanticDelta(unit,null,"",Map.of(),Map.of(),Set.of(),Map.of(),Set.of(),"","","",Set.of());
        var empty=new SemanticSnapshot(unit,previous.sourceFile(),"",Map.of(),Map.of(),"","","",Set.of());
        var delta=SemanticDelta.between(previous,empty);apply(delta);units.remove(unit);return delta;
    }

    /** Mark a source unit stale from mutation-maintained source identity without discarding reusable facts. */
    public synchronized boolean markSourceStale(String sourceFile,String contentIdentity){
        if(sourceFile==null)return false;
        String unit="source:"+sourceFile;var previous=units.get(unit);if(previous==null)return false;
        String content=Objects.requireNonNullElse(contentIdentity,"");
        if(content.equals(previous.contentIdentity())&&!staleUnits.containsKey(unit))return false;
        String old=staleUnits.put(unit,content);if(Objects.equals(old,content))return false;
        var affected=new LinkedHashSet<String>();
        for(var fact:previous.facts().values())if(fact.typeDeclaration()){
            affected.add(fact.id());collectDescendants(fact.id(),affected);
        }
        affected.forEach(hierarchyApis::remove);refreshFreshness();epoch++;return true;
    }

    /** Lost source-change history invalidates hierarchy identities without rebuilding facts. */
    public synchronized void markHierarchyUncertain(){
        if(units.isEmpty())return;
        String marker="<uncertain:"+(epoch+1)+">";
        for(String unit:units.keySet())staleUnits.put(unit,marker);
        hierarchyApis.clear();refreshFreshness();epoch++;
    }

    public synchronized SemanticFact symbol(String id){return symbols.get(id);}
    public synchronized SymbolDescription describe(String id){return descriptions.get(id);}
    public synchronized SemanticSnapshot unit(String unit){return units.get(unit);}

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
        String prefix=SemanticFact.memberPrefix(ownerId,namePrefix);
        return new MemberCursor(root,prefix,prefix+"\uffff");
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
    /** Constant-time validity identity for the effective API reachable from a receiver type. */
    public synchronized String hierarchyApi(String typeId){return hierarchyApis.getOrDefault(typeId,EMPTY);}

    public synchronized Identity identity(){
        String structural=root==null?EMPTY:root.merkle.hex();
        String merkle=Hashing.sha256((structural+"\0"+freshnessMerkle).getBytes(StandardCharsets.UTF_8));
        return new Identity(epoch,merkle,semanticAggregate.membershipIdentity(),semanticAggregate.apiIdentity(),
                semanticAggregate.namespaceIdentity(),semanticAggregate.documentationIdentity());
    }

    public synchronized void clear(){
        if(root==null&&symbols.isEmpty()&&descriptions.isEmpty()&&units.isEmpty())return;
        root=null;symbols.clear();descriptions.clear();units.clear();memberAggregates.clear();semanticAggregate=Aggregate.ZERO;directSupers.clear();directSubs.clear();hierarchyApis.clear();staleUnits.clear();freshnessMerkle=EMPTY;epoch++;
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
                Map.entry("semantic_tree_nodes_created",treeNodesCreated),Map.entry("semantic_bulk_builds",bulkBuilds));
    }

    private void refreshFreshness(){
        if(staleUnits.isEmpty()){freshnessMerkle=EMPTY;return;}
        var material=new StringBuilder("semantic-freshness-v1");
        staleUnits.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->
                material.append('\0').append(entry.getKey()).append('\0').append(entry.getValue()));
        freshnessMerkle=Hashing.sha256(material.toString().getBytes(StandardCharsets.UTF_8));
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
    private void recomputeHierarchyApis(Collection<String> affected){
        for(String typeId:affected){
            var type=symbols.get(typeId);
            if(type==null||!type.typeDeclaration()){hierarchyApis.remove(typeId);continue;}
            var reachable=new LinkedHashSet<String>();var queue=new ArrayDeque<String>();queue.add(typeId);
            BigInteger sum=BigInteger.ZERO;long count=0;
            while(!queue.isEmpty()){
                String current=queue.removeFirst();if(!reachable.add(current))continue;
                var declaration=symbols.get(current);
                if(declaration!=null&&declaration.typeDeclaration()){
                    sum=sum.add(point("hierarchy-type-api",declaration.apiIdentity().isBlank()?declaration.structuralSignature():declaration.apiIdentity())).mod(FIELD);count++;
                }
                var members=memberAggregates.get(current);
                if(members!=null){sum=sum.add(members.api().sum()).mod(FIELD);count+=members.api().cardinality();}
                for(String parent:directSupers.getOrDefault(current,Set.of())){
                    sum=sum.add(point("hierarchy-edge",current+"\0"+parent)).mod(FIELD);count++;queue.addLast(parent);
                }
            }
            String material="hierarchy-api-v1\0"+count+"\0"+String.format("%064x",sum);
            hierarchyApis.put(typeId,Hashing.sha256(material.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void addFact(SemanticFact fact){
        var contribution=contribution(fact);root=put(root,entry(fact,contribution));semanticAggregate=semanticAggregate.add(contribution);
        if(fact.member())memberAggregates.merge(fact.ownerId(),contribution,Aggregate::add);
    }

    private void removeFact(SemanticFact fact){
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

    private Node newNode(Entry entry,Node left,Node right){treeNodesCreated++;return new Node(entry,left,right);}

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
