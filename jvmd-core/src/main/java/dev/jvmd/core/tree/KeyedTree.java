package dev.jvmd.core.tree;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Persistent hash-priority treap over string keys, with a Merkle hash and one range sum per
 * projection in every node.
 *
 * <p>A node's priority is a digest of its key, so the shape of the tree depends only on the key
 * set: equal key sets and values give equal root hashes whatever the insertion order. Updates copy
 * one path, O(log n) expected, and leave every earlier version readable. Each node holds, per
 * projection, the additive set hash of (key, projected identity) over its subtree, so the identity
 * of any key range is read in O(log n) without listing the range.
 */
public final class KeyedTree<V> {
    /** One projected identity a range sum is kept for. A null projected value contributes nothing. */
    public record Projection<V>(String name,Function<V,Object> identity) {
        public Projection { Objects.requireNonNull(name);Objects.requireNonNull(identity); }
    }

    /**
     * What a tree needs from its values: the domain that separates its hashes from every other
     * tree, the identity hashed into the Merkle node, and the projections kept as range sums.
     */
    public record Schema<V>(String domain,Function<V,Hash256> identity,List<Projection<V>> projections) {
        public Schema {
            Objects.requireNonNull(domain);Objects.requireNonNull(identity);projections=List.copyOf(projections);
        }
        public int projection(String name){
            for(int i=0;i<projections.size();i++)if(projections.get(i).name().equals(name))return i;
            throw new IllegalArgumentException("Unknown projection: "+name);
        }
    }

    /** One persisted node: its hash, entry and the hashes of its children (empty when absent). */
    public record StoredNode(Hash256 hash,String key,byte[] value,Hash256 left,Hash256 right) {
        public StoredNode {
            Objects.requireNonNull(hash);Objects.requireNonNull(key);value=value.clone();
            Objects.requireNonNull(left);Objects.requireNonNull(right);
        }
        @Override public byte[] value(){return value.clone();}
    }

    /** One entry that differs between two trees; a missing side is null. */
    public record Change<V>(String key,V before,V after) { }

    private static final class Node<V> {
        final String key;final V value;final Hash256 priority;final Node<V> left,right;
        final Hash256 hash;final int size;final AlgebraicAccumulator.Value[] ranges;
        final String minKey,maxKey;
        Node(Schema<V> schema,String key,V value,Hash256 priority,Node<V> left,Node<V> right){
            this.key=key;this.value=value;this.priority=priority;this.left=left;this.right=right;
            Hash256 empty=emptyHash(schema);
            hash=CanonicalDigestWriter.digest(schema.domain()+"/node",left==null?empty:left.hash,key,
                    schema.identity().apply(value),right==null?empty:right.hash);
            size=1+(left==null?0:left.size)+(right==null?0:right.size);
            var projections=schema.projections();
            ranges=new AlgebraicAccumulator.Value[projections.size()];
            for(int i=0;i<ranges.length;i++){
                var sum=left==null?AlgebraicAccumulator.Value.ZERO:left.ranges[i];
                Object projected=projections.get(i).identity().apply(value);
                if(projected!=null)sum=sum.plus(AlgebraicAccumulator.contribution(projections.get(i).name(),key,projected));
                ranges[i]=right==null?sum:sum.plus(right.ranges[i]);
            }
            minKey=left==null?key:left.minKey;maxKey=right==null?key:right.maxKey;
        }
        AlgebraicAccumulator.Value self(Schema<V> schema,int projection){
            Object projected=schema.projections().get(projection).identity().apply(value);
            return projected==null?AlgebraicAccumulator.Value.ZERO
                    :AlgebraicAccumulator.contribution(schema.projections().get(projection).name(),key,projected);
        }
    }

    private final Schema<V> schema;
    private final Node<V> root;

    private KeyedTree(Schema<V> schema,Node<V> root){this.schema=schema;this.root=root;}

    public static <V> KeyedTree<V> empty(Schema<V> schema){return new KeyedTree<>(Objects.requireNonNull(schema),null);}

    /** Builds the tree from distinct keys in linear time after sorting; the result equals any insertion order. */
    public static <V> KeyedTree<V> build(Schema<V> schema,Map<String,V> entries){
        var keys=new ArrayList<>(entries.keySet());keys.sort(null);
        int size=keys.size();if(size==0)return empty(schema);
        var priorities=new Hash256[size];for(int i=0;i<size;i++)priorities[i]=priority(schema,keys.get(i));
        var left=new int[size];var right=new int[size];var stack=new int[size];
        Arrays.fill(left,-1);Arrays.fill(right,-1);int top=-1;
        for(int i=0;i<size;i++){
            int previous=-1;
            while(top>=0&&higher(priorities[i],keys.get(i),priorities[stack[top]],keys.get(stack[top])))previous=stack[top--];
            left[i]=previous;if(top>=0)right[stack[top]]=i;stack[++top]=i;
        }
        return new KeyedTree<>(schema,freeze(schema,keys,entries,priorities,left,right,stack[0]));
    }

    private static <V> Node<V> freeze(Schema<V> schema,List<String> keys,Map<String,V> entries,Hash256[] priorities,int[] left,int[] right,int index){
        if(index<0)return null;
        var leftNode=freeze(schema,keys,entries,priorities,left,right,left[index]);
        var rightNode=freeze(schema,keys,entries,priorities,left,right,right[index]);
        String key=keys.get(index);
        return new Node<>(schema,key,Objects.requireNonNull(entries.get(key)),priorities[index],leftNode,rightNode);
    }

    public Schema<V> schema(){return schema;}
    public int size(){return root==null?0:root.size;}
    public boolean isEmpty(){return root==null;}
    public Hash256 rootHash(){return root==null?emptyHash(schema):root.hash;}

    public V get(String key){
        var node=root;
        while(node!=null){int order=key.compareTo(node.key);if(order==0)return node.value;node=order<0?node.left:node.right;}
        return null;
    }
    public boolean contains(String key){return get(key)!=null;}

    public KeyedTree<V> put(String key,V value){
        Objects.requireNonNull(key);Objects.requireNonNull(value);
        return new KeyedTree<>(schema,insert(root,key,value,priority(schema,key)));
    }
    public KeyedTree<V> remove(String key){
        var next=delete(root,key);return next==root?this:new KeyedTree<>(schema,next);
    }

    /** Range sum of one projection over the whole tree. */
    public AlgebraicAccumulator.Value total(int projection){return root==null?AlgebraicAccumulator.Value.ZERO:root.ranges[projection];}
    /** Range sum of one projection over keys in [lower, upper], both inclusive, in O(log n). */
    public AlgebraicAccumulator.Value range(int projection,String lower,String upper){return range(root,projection,lower,upper);}
    /** Range sum of one projection over every key that starts with the prefix. */
    public AlgebraicAccumulator.Value prefix(int projection,String prefix){return range(projection,prefix,prefix+'￿');}

    /** Entries with keys in [lower, upper] in key order, read from this version of the tree. */
    public Cursor<V> cursor(String lower,String upper){return new Cursor<>(root,lower,upper);}
    public void forEach(BiConsumer<String,V> action){forEach(root,action);}
    public SortedMap<String,V> entries(){var result=new TreeMap<String,V>();forEach(result::put);return result;}

    /**
     * Entries whose key or value differs from {@code other}. Subtrees with equal hashes are skipped,
     * so the cost follows the number of changes, not the size of either tree.
     */
    public List<Change<V>> diff(KeyedTree<V> other){
        var result=new ArrayList<Change<V>>();diff(other.root,root,result);
        result.sort(Comparator.comparing(Change::key));return List.copyOf(result);
    }

    /** Every node of the tree, children before parents, with values encoded by the codec. */
    public List<StoredNode> nodes(Function<V,byte[]> codec){
        var result=new ArrayList<StoredNode>(size());nodes(root,codec,result);return List.copyOf(result);
    }

    /** Rebuilds the tree whose root hash is {@code rootHash} from its stored nodes. */
    public static <V> KeyedTree<V> read(Schema<V> schema,Hash256 rootHash,Function<Hash256,StoredNode> nodes,Function<byte[],V> codec){
        return new KeyedTree<>(schema,rootHash.equals(emptyHash(schema))?null:readNode(schema,rootHash,nodes,codec));
    }

    private static <V> Node<V> readNode(Schema<V> schema,Hash256 hash,Function<Hash256,StoredNode> nodes,Function<byte[],V> codec){
        var stored=Objects.requireNonNull(nodes.apply(hash),()->"Missing tree node "+hash);
        Hash256 empty=emptyHash(schema);
        var left=stored.left().equals(empty)?null:readNode(schema,stored.left(),nodes,codec);
        var right=stored.right().equals(empty)?null:readNode(schema,stored.right(),nodes,codec);
        return new Node<>(schema,stored.key(),codec.apply(stored.value()),priority(schema,stored.key()),left,right);
    }

    /** The root hash of an empty tree with this schema. */
    public static <V> Hash256 emptyHash(Schema<V> schema){return CanonicalDigestWriter.digest(schema.domain()+"/empty");}

    public static final class Cursor<V> {
        private final String lower,upper;private final ArrayDeque<Node<V>> stack=new ArrayDeque<>();
        private Cursor(Node<V> root,String lower,String upper){this.lower=lower;this.upper=upper;push(root);}
        private void push(Node<V> node){
            while(node!=null){
                if(node.key.compareTo(lower)<0){node=node.right;continue;}
                if(node.key.compareTo(upper)>0){node=node.left;continue;}
                stack.push(node);node=node.left;
            }
        }
        /** The next entry in key order, or null when the range is exhausted. */
        public Map.Entry<String,V> next(){
            if(stack.isEmpty())return null;
            var node=stack.pop();push(node.right);return Map.entry(node.key,node.value);
        }
    }

    private static Hash256 priority(Schema<?> schema,String key){return CanonicalDigestWriter.digest(schema.domain()+"/priority",key);}
    private static boolean higher(Hash256 a,String aKey,Hash256 b,String bKey){
        int compared=a.compareTo(b);return compared>0||compared==0&&aKey.compareTo(bKey)>0;
    }
    private static <V> boolean higher(Node<V> a,Node<V> b){return higher(a.priority,a.key,b.priority,b.key);}

    private Node<V> node(String key,V value,Hash256 priority,Node<V> left,Node<V> right){return new Node<>(schema,key,value,priority,left,right);}
    private Node<V> with(Node<V> node,Node<V> left,Node<V> right){return node(node.key,node.value,node.priority,left,right);}

    private Node<V> insert(Node<V> node,String key,V value,Hash256 priority){
        if(node==null)return node(key,value,priority,null,null);
        int order=key.compareTo(node.key);
        if(order==0)return node(key,value,node.priority,node.left,node.right);
        if(order<0){
            var left=insert(node.left,key,value,priority);
            return higher(left,node)?with(left,left.left,with(node,left.right,node.right)):with(node,left,node.right);
        }
        var right=insert(node.right,key,value,priority);
        return higher(right,node)?with(right,with(node,node.left,right.left),right.right):with(node,node.left,right);
    }
    private Node<V> delete(Node<V> node,String key){
        if(node==null)return null;int order=key.compareTo(node.key);
        if(order==0)return merge(node.left,node.right);
        if(order<0){var left=delete(node.left,key);return left==node.left?node:with(node,left,node.right);}
        var right=delete(node.right,key);return right==node.right?node:with(node,node.left,right);
    }
    private Node<V> merge(Node<V> left,Node<V> right){
        if(left==null)return right;if(right==null)return left;
        if(higher(left,right))return with(left,left.left,merge(left.right,right));
        return with(right,merge(left,right.left),right.right);
    }
    /** Splits into keys below, the entry at, and keys above {@code key}. Unchanged subtrees are shared. */
    private record Split<V>(Node<V> below,Node<V> at,Node<V> above) { }
    private Split<V> split(Node<V> node,String key){
        if(node==null)return new Split<>(null,null,null);
        int order=key.compareTo(node.key);
        if(order==0)return new Split<>(node.left,node,node.right);
        if(order<0){var part=split(node.left,key);return new Split<>(part.below,part.at,with(node,part.above,node.right));}
        var part=split(node.right,key);return new Split<>(with(node,node.left,part.below),part.at,part.above);
    }
    private void diff(Node<V> before,Node<V> after,List<Change<V>> result){
        if(before==after||before!=null&&after!=null&&before.hash.equals(after.hash))return;
        if(after==null){forEach(before,(key,value)->result.add(new Change<>(key,value,null)));return;}
        if(before==null){forEach(after,(key,value)->result.add(new Change<>(key,null,value)));return;}
        var part=split(before,after.key);
        if(part.at==null)result.add(new Change<>(after.key,null,after.value));
        else if(!schema.identity().apply(part.at.value).equals(schema.identity().apply(after.value)))result.add(new Change<>(after.key,part.at.value,after.value));
        diff(part.below,after.left,result);diff(part.above,after.right,result);
    }

    private AlgebraicAccumulator.Value range(Node<V> node,int projection,String lower,String upper){
        if(node==null||node.maxKey.compareTo(lower)<0||node.minKey.compareTo(upper)>0)return AlgebraicAccumulator.Value.ZERO;
        if(node.minKey.compareTo(lower)>=0&&node.maxKey.compareTo(upper)<=0)return node.ranges[projection];
        var result=range(node.left,projection,lower,upper);
        if(node.key.compareTo(lower)>=0&&node.key.compareTo(upper)<=0)result=result.plus(node.self(schema,projection));
        return result.plus(range(node.right,projection,lower,upper));
    }
    private static <V> void forEach(Node<V> node,BiConsumer<String,V> action){
        if(node==null)return;forEach(node.left,action);action.accept(node.key,node.value);forEach(node.right,action);
    }
    private void nodes(Node<V> node,Function<V,byte[]> codec,List<StoredNode> result){
        if(node==null)return;nodes(node.left,codec,result);nodes(node.right,codec,result);
        Hash256 empty=emptyHash(schema);
        result.add(new StoredNode(node.hash,node.key,codec.apply(node.value),node.left==null?empty:node.left.hash,node.right==null?empty:node.right.hash));
    }
}
