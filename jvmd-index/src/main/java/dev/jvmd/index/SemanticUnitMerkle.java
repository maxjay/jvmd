package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Compact retained Merkle membership for one semantic unit.
 *
 * Facts remain canonically owned by ResidentSemanticState. This structure stores only symbol-id
 * references and their already-computed fact fingerprints. A deterministic 256-way first level
 * keeps add/remove alignment stable and lets diffs skip unchanged buckets by Merkle identity.
 */
public final class SemanticUnitMerkle {
    private static final Hash256 EMPTY=CanonicalDigestWriter.digest("semantic-unit-empty-v1");
    private static final SemanticUnitMerkle EMPTY_STATE=new SemanticUnitMerkle(Map.of(),EMPTY,0);

    public record Diff(Set<String> added,Set<String> changed,Set<String> removed,int bucketsVisited,int entriesCompared) {
        public Diff {
            added=Set.copyOf(added);changed=Set.copyOf(changed);removed=Set.copyOf(removed);
        }
        public boolean empty(){return added.isEmpty()&&changed.isEmpty()&&removed.isEmpty();}
    }

    private static final class Bucket {
        final int index;
        final String[] ids;
        final Hash256[] identities;
        final Hash256 root;
        Bucket(int index,String[] ids,Hash256[] identities){
            this.index=index;this.ids=ids;this.identities=identities;
            Hash256 value=CanonicalDigestWriter.digest("semantic-unit-bucket-v1",index,ids.length);
            for(int i=0;i<ids.length;i++)value=CanonicalDigestWriter.digest("semantic-unit-bucket-entry-v1",value,ids[i],identities[i]);
            root=value;
        }
    }

    private final Map<Integer,Bucket> buckets;
    private final Hash256 root;
    private final int size;

    private SemanticUnitMerkle(Map<Integer,Bucket> buckets,Hash256 root,int size){
        this.buckets=Map.copyOf(buckets);this.root=root;this.size=size;
    }

    public static SemanticUnitMerkle empty(){return EMPTY_STATE;}

    public static SemanticUnitMerkle from(Collection<SemanticFact> facts){
        if(facts.isEmpty())return empty();
        var grouped=new HashMap<Integer,ArrayList<SemanticFact>>();
        for(var fact:facts)grouped.computeIfAbsent(bucket(fact.id()),_->new ArrayList<>()).add(fact);
        var ordered=new TreeMap<Integer,Bucket>();int size=0;
        for(var group:grouped.entrySet()){
            group.getValue().sort(Comparator.comparing(SemanticFact::id));
            var ids=new String[group.getValue().size()];var identities=new Hash256[ids.length];
            for(int i=0;i<ids.length;i++){var fact=group.getValue().get(i);ids[i]=fact.id();identities[i]=fact.factIdentity();}
            ordered.put(group.getKey(),new Bucket(group.getKey(),ids,identities));size+=ids.length;
        }
        Hash256 root=CanonicalDigestWriter.digest("semantic-unit-root-v1",size,ordered.size());
        for(var entry:ordered.entrySet())root=CanonicalDigestWriter.digest("semantic-unit-root-entry-v1",root,entry.getKey(),entry.getValue().root);
        return new SemanticUnitMerkle(ordered,root,size);
    }

    public Hash256 root(){return root;}
    public int size(){return size;}

    public boolean contains(String id){
        var bucket=buckets.get(bucket(id));if(bucket==null)return false;
        return Arrays.binarySearch(bucket.ids,id)>=0;
    }

    public void forEachId(Consumer<String> consumer){
        buckets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
            for(String id:entry.getValue().ids)consumer.accept(id);
        });
    }

    public String findId(Predicate<String> predicate){
        for(var entry:new TreeMap<>(buckets).entrySet())for(String id:entry.getValue().ids)if(predicate.test(id))return id;
        return null;
    }

    public Set<String> ids(){
        var result=new LinkedHashSet<String>(size);forEachId(result::add);return Set.copyOf(result);
    }

    public Diff diff(SemanticUnitMerkle next){
        Objects.requireNonNull(next);
        if(root.equals(next.root)&&size==next.size)return new Diff(Set.of(),Set.of(),Set.of(),0,0);
        var added=new LinkedHashSet<String>(),changed=new LinkedHashSet<String>(),removed=new LinkedHashSet<String>();
        var keys=new TreeSet<Integer>();keys.addAll(buckets.keySet());keys.addAll(next.buckets.keySet());
        int bucketsVisited=0,entriesCompared=0;
        for(int key:keys){
            var before=buckets.get(key),after=next.buckets.get(key);
            if(before!=null&&after!=null&&before.root.equals(after.root)&&before.ids.length==after.ids.length)continue;
            bucketsVisited++;
            if(before==null){Collections.addAll(added,after.ids);continue;}
            if(after==null){Collections.addAll(removed,before.ids);continue;}
            int i=0,j=0;
            while(i<before.ids.length||j<after.ids.length){
                entriesCompared++;
                if(i>=before.ids.length){added.add(after.ids[j++]);continue;}
                if(j>=after.ids.length){removed.add(before.ids[i++]);continue;}
                int order=before.ids[i].compareTo(after.ids[j]);
                if(order<0){removed.add(before.ids[i++]);continue;}
                if(order>0){added.add(after.ids[j++]);continue;}
                if(!before.identities[i].equals(after.identities[j]))changed.add(before.ids[i]);
                i++;j++;
            }
        }
        return new Diff(added,changed,removed,bucketsVisited,entriesCompared);
    }

    private static int bucket(String id){
        int hash=id.hashCode();hash^=hash>>>16;return hash&0xff;
    }
}
