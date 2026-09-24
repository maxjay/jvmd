package dev.jvmd.index;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.math.BigInteger;
import java.util.*;

/**
 * Persistent ordered classpath identity.
 *
 * Logical position is represented by sequence structure and subtree cardinality rather than being
 * copied into each leaf. That keeps a same-position artifact update to path-copying O(log N) while
 * the Merkle root still commits to exact order. Entry keys must be stable for one logical classpath
 * slot across content generations.
 */
public final class ClasspathSequence {
    private static final Hash256 EMPTY=CanonicalDigestWriter.digest("classpath-sequence-empty-v1");

    public record Entry(String key,Hash256 semanticIdentity) {
        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(semanticIdentity);
            if(key.isBlank())throw new IllegalArgumentException("Classpath entry key must not be blank");
        }
    }

    /** Half-open changed interval in the previous and current ordered sequence. */
    public record Interval(int previousStart,int previousEnd,int currentStart,int currentEnd) {
        public Interval {
            if(previousStart<0||previousEnd<previousStart||currentStart<0||currentEnd<currentStart)
                throw new IllegalArgumentException("Invalid classpath diff interval");
        }
    }

    public record Difference(List<Interval> intervals,int merkleNodesCompared,boolean structuralFallback) {
        public Difference {
            intervals=List.copyOf(intervals);
            if(merkleNodesCompared<0)throw new IllegalArgumentException("Negative Merkle comparison count");
        }
        public boolean equal(){return intervals.isEmpty();}
    }

    private record Item(Entry entry,BigInteger priority) { }
    private record Split(Node left,Node right) { }
    private static final class Counter { int value; }
    private static final class Node {
        final Item item;
        final Node left,right;
        final int size;
        final Hash256 merkle;

        Node(Item item,Node left,Node right){
            this.item=item;this.left=left;this.right=right;
            int leftSize=size(left),rightSize=size(right);
            size=1+leftSize+rightSize;
            // left/right cardinality plus ordered child identities bind the logical sequence positions.
            merkle=CanonicalDigestWriter.digest("classpath-sequence-node-v1",
                    leftSize,identity(left),item.entry().key(),item.entry().semanticIdentity(),
                    rightSize,identity(right));
        }
    }

    private final Node root;

    private ClasspathSequence(Node root){this.root=root;}

    public static ClasspathSequence empty(){return new ClasspathSequence(null);}

    public static ClasspathSequence of(Collection<Entry> entries){
        Objects.requireNonNull(entries);
        var values=new ArrayList<Item>(entries.size());
        var keys=new HashSet<String>();
        for(var entry:entries){
            Objects.requireNonNull(entry);
            if(!keys.add(entry.key()))throw new IllegalArgumentException("Duplicate classpath entry key: "+entry.key());
            values.add(item(entry));
        }
        return new ClasspathSequence(bulkBuild(values));
    }

    public int size(){return size(root);}
    public boolean isEmpty(){return root==null;}
    public Hash256 identity(){return identity(root);}

    public Entry get(int index){
        checkIndex(index,size());
        return get(root,index).item.entry();
    }

    public List<Entry> entries(){
        var result=new ArrayList<Entry>(size());
        collect(root,result);
        return List.copyOf(result);
    }

    /**
     * Replace one logical slot. When the stable slot key is unchanged only the root-to-leaf path is
     * rebuilt. A key replacement uses remove+insert so the canonical priority tree is re-established.
     */
    public ClasspathSequence replace(int index,Entry entry){
        Objects.requireNonNull(entry);checkIndex(index,size());
        Entry previous=get(index);
        if(previous.equals(entry))return this;
        if(previous.key().equals(entry.key()))return new ClasspathSequence(replaceSameKey(root,index,item(entry)));
        if(containsKey(root,entry.key()))throw new IllegalArgumentException("Duplicate classpath entry key: "+entry.key());
        return new ClasspathSequence(insertNode(removeNode(root,index),index,item(entry)));
    }

    public ClasspathSequence insert(int index,Entry entry){
        Objects.requireNonNull(entry);checkPosition(index,size());
        if(containsKey(root,entry.key()))throw new IllegalArgumentException("Duplicate classpath entry key: "+entry.key());
        return new ClasspathSequence(insertNode(root,index,item(entry)));
    }

    public ClasspathSequence remove(int index){
        checkIndex(index,size());
        return new ClasspathSequence(removeNode(root,index));
    }

    /** Move an entry to its final index in the resulting sequence. */
    public ClasspathSequence move(int from,int to){
        checkIndex(from,size());checkIndex(to,size());
        if(from==to)return this;
        Entry value=get(from);
        Node without=removeNode(root,from);
        return new ClasspathSequence(insertNode(without,to,item(value)));
    }

    /**
     * Merkle diff. Content-only changes retain the canonical tree shape and recurse only through
     * unequal subtrees. Structural edits fall back to one minimal changed ordered interval bounded
     * by the longest equal prefix and suffix.
     */
    public Difference diff(ClasspathSequence current){
        Objects.requireNonNull(current);
        var compared=new Counter();
        if(identity().equals(current.identity()))return new Difference(List.of(),1,false);

        var changed=new ArrayList<Integer>();
        if(diffSameShape(root,current.root,0,changed,compared))
            return new Difference(intervals(changed),compared.value,false);

        var previousEntries=entries();
        var currentEntries=current.entries();
        int prefix=0;
        int common=Math.min(previousEntries.size(),currentEntries.size());
        while(prefix<common&&previousEntries.get(prefix).equals(currentEntries.get(prefix)))prefix++;

        int suffix=0;
        while(suffix<previousEntries.size()-prefix&&suffix<currentEntries.size()-prefix
                &&previousEntries.get(previousEntries.size()-1-suffix)
                        .equals(currentEntries.get(currentEntries.size()-1-suffix)))suffix++;

        return new Difference(List.of(new Interval(
                prefix,previousEntries.size()-suffix,
                prefix,currentEntries.size()-suffix)),compared.value,true);
    }

    private static boolean diffSameShape(Node previous,Node current,int offset,List<Integer> changed,Counter compared){
        compared.value++;
        if(previous==current)return true;
        if(previous==null||current==null)return previous==current;
        if(previous.merkle.equals(current.merkle))return true;
        int previousLeft=size(previous.left),currentLeft=size(current.left);
        if(previous.size!=current.size||previousLeft!=currentLeft
                ||!previous.item.entry().key().equals(current.item.entry().key()))return false;
        if(!diffSameShape(previous.left,current.left,offset,changed,compared))return false;
        if(!previous.item.entry().semanticIdentity().equals(current.item.entry().semanticIdentity()))
            changed.add(offset+previousLeft);
        return diffSameShape(previous.right,current.right,offset+previousLeft+1,changed,compared);
    }

    private static List<Interval> intervals(List<Integer> changed){
        if(changed.isEmpty())return List.of();
        var result=new ArrayList<Interval>();
        int start=changed.getFirst(),previous=start;
        for(int i=1;i<changed.size();i++){
            int next=changed.get(i);
            if(next==previous+1){previous=next;continue;}
            result.add(new Interval(start,previous+1,start,previous+1));
            start=previous=next;
        }
        result.add(new Interval(start,previous+1,start,previous+1));
        return List.copyOf(result);
    }

    private static Node replaceSameKey(Node node,int index,Item replacement){
        int left=size(node.left);
        if(index<left)return new Node(node.item,replaceSameKey(node.left,index,replacement),node.right);
        if(index==left)return new Node(replacement,node.left,node.right);
        return new Node(node.item,node.left,replaceSameKey(node.right,index-left-1,replacement));
    }

    private static Node insertNode(Node root,int index,Item item){
        Split split=split(root,index);
        return merge(merge(split.left,new Node(item,null,null)),split.right);
    }

    private static Node removeNode(Node root,int index){
        Split prefix=split(root,index);
        Split removed=split(prefix.right,1);
        return merge(prefix.left,removed.right);
    }

    /** Split by ordered cardinality: left contains exactly {@code count} entries. */
    private static Split split(Node node,int count){
        if(node==null)return new Split(null,null);
        int left=size(node.left);
        if(count<=left){
            Split split=split(node.left,count);
            return new Split(split.left,new Node(node.item,split.right,node.right));
        }
        Split split=split(node.right,count-left-1);
        return new Split(new Node(node.item,node.left,split.left),split.right);
    }

    private static Node merge(Node left,Node right){
        if(left==null)return right;if(right==null)return left;
        if(comparePriority(left.item,right.item)>0)
            return new Node(left.item,left.left,merge(left.right,right));
        return new Node(right.item,merge(left,right.left),right.right);
    }

    private static Node bulkBuild(List<Item> ordered){
        int count=ordered.size();if(count==0)return null;
        var left=new int[count];var right=new int[count];var stack=new int[count];
        Arrays.fill(left,-1);Arrays.fill(right,-1);int top=-1;
        for(int i=0;i<count;i++){
            int previous=-1;
            while(top>=0&&comparePriority(ordered.get(stack[top]),ordered.get(i))<0)previous=stack[top--];
            left[i]=previous;if(top>=0)right[stack[top]]=i;stack[++top]=i;
        }
        return freezeBulk(ordered,left,right,stack[0]);
    }

    private static Node freezeBulk(List<Item> ordered,int[] left,int[] right,int index){
        if(index<0)return null;
        return new Node(ordered.get(index),
                freezeBulk(ordered,left,right,left[index]),
                freezeBulk(ordered,left,right,right[index]));
    }

    private static Item item(Entry entry){
        return new Item(entry,CanonicalDigestWriter.digest("classpath-sequence-priority-v1",entry.key()).unsignedInteger());
    }

    private static int comparePriority(Item first,Item second){
        int compared=first.priority().compareTo(second.priority());
        return compared!=0?compared:first.entry().key().compareTo(second.entry().key());
    }

    private static Node get(Node node,int index){
        int left=size(node.left);
        if(index<left)return get(node.left,index);
        if(index==left)return node;
        return get(node.right,index-left-1);
    }

    private static boolean containsKey(Node node,String key){
        if(node==null)return false;
        return node.item.entry().key().equals(key)||containsKey(node.left,key)||containsKey(node.right,key);
    }

    private static void collect(Node node,List<Entry> output){
        if(node==null)return;
        collect(node.left,output);output.add(node.item.entry());collect(node.right,output);
    }

    private static int size(Node node){return node==null?0:node.size;}
    private static Hash256 identity(Node node){return node==null?EMPTY:node.merkle;}

    private static void checkIndex(int index,int size){
        if(index<0||index>=size)throw new IndexOutOfBoundsException(index+" outside [0,"+size+")");
    }
    private static void checkPosition(int index,int size){
        if(index<0||index>size)throw new IndexOutOfBoundsException(index+" outside [0,"+size+"]");
    }
}
