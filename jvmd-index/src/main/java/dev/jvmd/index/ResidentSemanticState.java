package dev.jvmd.index;

import dev.jvmd.core.Hashing;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Resident ordered semantic state.
 *
 * The content-derived treap is a prolly-style persistent ordered Merkle tree: key hashes determine
 * structure, every path-copy update recalculates both Merkle identity and algebraic domain
 * aggregates, and exact symbols are indexed directly from the same canonical facts.
 */
public final class ResidentSemanticState {
    private static final BigInteger FIELD=new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",16);
    private static final String EMPTY=Hashing.sha256(new byte[0]);

    public record Aggregate(BigInteger membership,BigInteger api,BigInteger namespace,BigInteger documentation) {
        static final Aggregate ZERO=new Aggregate(BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO);
        Aggregate add(Aggregate other){return new Aggregate(
                membership.add(other.membership).mod(FIELD),api.add(other.api).mod(FIELD),
                namespace.add(other.namespace).mod(FIELD),documentation.add(other.documentation).mod(FIELD));}
        Aggregate subtract(Aggregate other){return new Aggregate(
                membership.subtract(other.membership).mod(FIELD),api.subtract(other.api).mod(FIELD),
                namespace.subtract(other.namespace).mod(FIELD),documentation.subtract(other.documentation).mod(FIELD));}
        public String membershipIdentity(){return hex(membership);}
        public String apiIdentity(){return hex(api);}
        public String namespaceIdentity(){return hex(namespace);}
        public String documentationIdentity(){return hex(documentation);}
        private static String hex(BigInteger value){return String.format("%064x",value);}
    }

    public record Identity(long epoch,String merkleRoot,String membership,String api,String namespace,String documentation) { }

    private record Entry(String key,String symbolId,String valueIdentity,Aggregate contribution) { }
    private static final class Node {
        final Entry entry;final Node left,right;final BigInteger priority;final String merkle,min,max;final Aggregate aggregate;final int size;
        Node(Entry entry,Node left,Node right){
            this.entry=entry;this.left=left;this.right=right;priority=point("priority",entry.key());
            min=left==null?entry.key():left.min;max=right==null?entry.key():right.max;
            aggregate=(left==null?Aggregate.ZERO:left.aggregate).add(entry.contribution()).add(right==null?Aggregate.ZERO:right.aggregate);
            size=1+(left==null?0:left.size)+(right==null?0:right.size);
            String material=(left==null?EMPTY:left.merkle)+"\0"+entry.key()+"\0"+entry.valueIdentity()+"\0"+(right==null?EMPTY:right.merkle);
            merkle=Hashing.sha256(material.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Node root;
    private final Map<String,SemanticFact> symbols=new HashMap<>();
    private final Map<String,SymbolDescription> descriptions=new HashMap<>();
    private final Map<String,SemanticSnapshot> units=new HashMap<>();
    private final Map<String,Aggregate> memberAggregates=new HashMap<>();
    private long epoch,rangeEntriesRead,factMutations;

    public synchronized SemanticDelta diff(SemanticSnapshot next){return SemanticDelta.between(units.get(next.unit()),next);}

    public synchronized SemanticDelta admit(SemanticSnapshot next){
        var delta=diff(next);apply(delta);return delta;
    }

    public synchronized void apply(SemanticDelta delta){
        var previous=units.get(delta.unit());
        boolean transition=previous==null||!Objects.equals(previous.contentIdentity(),delta.contentIdentity())
                ||!Objects.equals(previous.apiIdentity(),delta.apiIdentity())
                ||!Objects.equals(previous.namespaceIdentity(),delta.namespaceIdentity())
                ||!Objects.equals(previous.documentationIdentity(),delta.documentationIdentity())
                ||!delta.emptyFacts()||!delta.descriptionsChanged().isEmpty()||!delta.descriptionsRemoved().isEmpty();
        if(!transition)return;

        for(String id:delta.removed()){
            var old=symbols.remove(id);if(old!=null)removeFact(old);
        }
        for(var entry:delta.changed().entrySet()){
            var old=symbols.get(entry.getKey());if(old!=null)removeFact(old);
            symbols.put(entry.getKey(),entry.getValue());addFact(entry.getValue());
        }
        for(var entry:delta.added().entrySet()){
            var old=symbols.put(entry.getKey(),entry.getValue());if(old!=null)removeFact(old);
            addFact(entry.getValue());
        }
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

    public synchronized SemanticDelta removeUnit(String unit){
        var previous=units.get(unit);if(previous==null)return new SemanticDelta(unit,null,"",Map.of(),Map.of(),Set.of(),Map.of(),Set.of(),"","","",Set.of());
        var empty=new SemanticSnapshot(unit,previous.sourceFile(),"",Map.of(),Map.of(),"","","",Set.of());
        var delta=SemanticDelta.between(previous,empty);apply(delta);units.remove(unit);return delta;
    }

    public synchronized SemanticFact symbol(String id){return symbols.get(id);}
    public synchronized SymbolDescription describe(String id){return descriptions.get(id);}
    public synchronized SemanticSnapshot unit(String unit){return units.get(unit);}

    public synchronized List<SemanticFact> members(String ownerId,String namePrefix,int limit){
        if(limit<=0)return List.of();
        String prefix=SemanticFact.memberPrefix(ownerId,namePrefix);var entries=new ArrayList<Entry>(Math.min(limit,64));
        range(root,prefix,prefix+"\uffff",limit,entries);
        rangeEntriesRead+=entries.size();
        var result=new ArrayList<SemanticFact>(entries.size());
        for(var entry:entries){var fact=symbols.get(entry.symbolId());if(fact!=null)result.add(fact);}
        return List.copyOf(result);
    }

    public synchronized Aggregate memberAggregate(String ownerId){
        return memberAggregates.getOrDefault(ownerId,Aggregate.ZERO);
    }

    public synchronized Identity identity(){
        var aggregate=root==null?Aggregate.ZERO:root.aggregate;
        return new Identity(epoch,root==null?EMPTY:root.merkle,aggregate.membershipIdentity(),aggregate.apiIdentity(),
                aggregate.namespaceIdentity(),aggregate.documentationIdentity());
    }

    public synchronized void clear(){
        if(root==null&&symbols.isEmpty()&&descriptions.isEmpty()&&units.isEmpty())return;
        root=null;symbols.clear();descriptions.clear();units.clear();memberAggregates.clear();epoch++;
    }

    public synchronized Map<String,Object> status(){
        var identity=identity();
        return Map.ofEntries(
                Map.entry("semantic_epoch",epoch),Map.entry("semantic_root",identity.merkleRoot()),
                Map.entry("semantic_membership",identity.membership()),Map.entry("semantic_api",identity.api()),
                Map.entry("semantic_namespace",identity.namespace()),Map.entry("semantic_documentation",identity.documentation()),
                Map.entry("semantic_facts",symbols.size()),Map.entry("semantic_tree_entries",root==null?0:root.size),
                Map.entry("semantic_units",units.size()),Map.entry("semantic_member_aggregates",memberAggregates.size()),
                Map.entry("semantic_fact_mutations",factMutations),Map.entry("semantic_tree_range_entries_read",rangeEntriesRead));
    }

    private void addFact(SemanticFact fact){
        var contribution=contribution(fact);root=put(root,new Entry(fact.orderedKey(),fact.id(),valueIdentity(fact),contribution));
        if(fact.member())memberAggregates.merge(fact.ownerId(),contribution,Aggregate::add);
    }

    private void removeFact(SemanticFact fact){
        root=remove(root,fact.orderedKey());if(fact.member())memberAggregates.compute(fact.ownerId(),(_,old)->{
            if(old==null)return null;var next=old.subtract(contribution(fact));return next.equals(Aggregate.ZERO)?null:next;
        });
    }

    private static Aggregate contribution(SemanticFact fact){
        return new Aggregate(
                point("membership",fact.id()),
                point("api",fact.apiIdentity().isBlank()?fact.structuralSignature():fact.apiIdentity()),
                point("namespace",fact.namespaceIdentity().isBlank()?fact.packageName()+"\0"+fact.name()+"\0"+fact.kind():fact.namespaceIdentity()),
                point("documentation",fact.documentationIdentity()));
    }

    private static String valueIdentity(SemanticFact fact){
        String value=String.join("\0",
                fact.id(),Objects.toString(fact.ownerId(),""),fact.name(),fact.kind(),fact.structuralSignature(),
                Objects.toString(fact.erasedDescriptor(),""),String.join(",",fact.modifiers().stream().sorted().toList()),
                Objects.toString(fact.sourceFile(),""),fact.packageName(),fact.namePath(),Objects.toString(fact.fqn(),""),
                fact.type().display(),String.join(",",fact.typeParameters()),
                String.join(",",fact.directSupertypes().stream().map(SemanticType::display).toList()),
                String.join(",",fact.parameterNames()),Boolean.toString(fact.varargs()),fact.apiIdentity(),fact.namespaceIdentity(),fact.documentationIdentity());
        return Hashing.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static BigInteger point(String domain,String value){
        byte[] bytes=Hashing.sha256((domain+"\0"+Objects.requireNonNullElse(value,"")).getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);
        return new BigInteger(new String(bytes,StandardCharsets.US_ASCII),16).mod(FIELD);
    }

    private static Node put(Node node,Entry entry){
        if(node==null)return new Node(entry,null,null);
        int compare=entry.key().compareTo(node.entry.key());
        if(compare==0)return new Node(entry,node.left,node.right);
        if(compare<0){
            var next=new Node(node.entry,put(node.left,entry),node.right);
            return next.left.priority.compareTo(next.priority)>0?rotateRight(next):next;
        }
        var next=new Node(node.entry,node.left,put(node.right,entry));
        return next.right.priority.compareTo(next.priority)>0?rotateLeft(next):next;
    }

    private static Node remove(Node node,String key){
        if(node==null)return null;int compare=key.compareTo(node.entry.key());
        if(compare==0)return merge(node.left,node.right);
        return compare<0?new Node(node.entry,remove(node.left,key),node.right):new Node(node.entry,node.left,remove(node.right,key));
    }

    private static Node merge(Node left,Node right){
        if(left==null)return right;if(right==null)return left;
        if(left.priority.compareTo(right.priority)>0)return new Node(left.entry,left.left,merge(left.right,right));
        return new Node(right.entry,merge(left,right.left),right.right);
    }

    private static Node rotateRight(Node node){
        var top=node.left;var lower=new Node(node.entry,top.right,node.right);return new Node(top.entry,top.left,lower);
    }
    private static Node rotateLeft(Node node){
        var top=node.right;var lower=new Node(node.entry,node.left,top.left);return new Node(top.entry,lower,top.right);
    }

    private static void range(Node node,String lower,String upper,int limit,List<Entry> output){
        if(node==null||output.size()>=limit||node.max.compareTo(lower)<0||node.min.compareTo(upper)>0)return;
        range(node.left,lower,upper,limit,output);
        if(output.size()<limit&&node.entry.key().compareTo(lower)>=0&&node.entry.key().compareTo(upper)<=0)output.add(node.entry);
        range(node.right,lower,upper,limit,output);
    }
}
