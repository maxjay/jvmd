package dev.jvmd.core.tree;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.Hash256;
import java.io.*;
import java.util.*;

/**
 * Keyed hash-priority treap with Merkle hashes and subtree range sums.
 *
 * <p>Each key's priority is a hash of its encoded bytes, so the shape of a tree is a function of its
 * key set alone: equal key sets give equal shapes, and equal entries give equal root hashes, in any
 * build or update order. A node's hash covers its key, its value's projected identity and both
 * child hashes, so two trees are compared by skipping every subtree whose hash is equal.
 *
 * <p>Every node also carries the additive range sum ({@link AlgebraicAccumulator}) of its subtree's
 * range identities and the subtree's key bounds. The sum over any key range is therefore read from
 * O(log n) nodes without listing the entries inside it.
 *
 * <p>Trees are persistent: an update copies one root-to-leaf path and shares everything else. A tree
 * read from stored nodes loads each node the first time a traversal reaches it.
 */
public final class KeyedTree<K,V> {
    /** What a tree's keys and values supply: order, canonical bytes and projected identities. */
    public interface Spec<K,V> {
        /** Names this tree's node hashes and range sums. */
        String domain();
        Comparator<? super K> order();
        byte[] encodeKey(K key);
        K decodeKey(byte[] bytes)throws IOException;
        byte[] encodeValue(V value)throws IOException;
        V decodeValue(byte[] bytes)throws IOException;
        /** The value identity hashed into its node. */
        Hash256 identity(V value);
        /** The identity summed into range sums, or null when this tree keeps no range sums. */
        default Hash256 rangeIdentity(V value){return null;}
    }

    /** A stored node by hash. Returns null only for a hash that was never written. */
    @FunctionalInterface
    public interface NodeSource { byte[] node(Hash256 hash)throws IOException; }

    /** Receives each node of a tree once, children before parents. */
    @FunctionalInterface
    public interface NodeSink { void node(Hash256 hash,byte[] encoded)throws Exception; }

    public static final Hash256 EMPTY=CanonicalDigestWriter.digest("keyed-tree-empty-v1");

    private final Spec<K,V> spec;
    private final Node<K,V> root;

    private KeyedTree(Spec<K,V> spec,Node<K,V> root){this.spec=Objects.requireNonNull(spec);this.root=root;}

    public static <K,V> KeyedTree<K,V> empty(Spec<K,V> spec){return new KeyedTree<>(spec,null);}

    /**
     * Build from entries in strictly ascending key order with one stack pass. The result has the
     * shape incremental inserts of the same keys would give, without copying any path.
     */
    public static <K,V> KeyedTree<K,V> build(Spec<K,V> spec,List<? extends Map.Entry<K,V>> sorted){
        int size=sorted.size();if(size==0)return empty(spec);
        var entries=new ArrayList<Entry<K,V>>(size);
        for(int i=0;i<size;i++){
            var item=sorted.get(i);var entry=Entry.of(spec,item.getKey(),item.getValue());
            if(i>0&&spec.order().compare(entries.get(i-1).key,entry.key)>=0)
                throw new IllegalArgumentException("Bulk tree entries must be in strictly ascending key order");
            entries.add(entry);
        }
        var left=new int[size];var right=new int[size];var stack=new int[size];
        Arrays.fill(left,-1);Arrays.fill(right,-1);int top=-1;
        for(int i=0;i<size;i++){
            int previous=-1;
            while(top>=0&&higher(spec,entries.get(i),entries.get(stack[top])))previous=stack[top--];
            left[i]=previous;if(top>=0)right[stack[top]]=i;stack[++top]=i;
        }
        return new KeyedTree<>(spec,freeze(spec,entries,left,right,stack[0]));
    }

    /** A tree whose nodes are read from storage as traversals reach them. */
    public static <K,V> KeyedTree<K,V> read(Spec<K,V> spec,Hash256 root,NodeSource source)throws IOException{
        Objects.requireNonNull(root);Objects.requireNonNull(source);
        return new KeyedTree<>(spec,root.equals(EMPTY)?null:Node.load(spec,source,root));
    }

    public Spec<K,V> spec(){return spec;}
    public boolean isEmpty(){return root==null;}
    public long size(){return root==null?0:root.size;}
    public Hash256 rootHash(){return root==null?EMPTY:root.hash;}
    /** Range sum over every entry. */
    public AlgebraicAccumulator.Value rangeSum(){return root==null?AlgebraicAccumulator.Value.ZERO:root.range;}

    public V get(K key){
        var node=root;
        while(node!=null){
            int compare=spec.order().compare(key,node.entry.key);
            if(compare==0)return node.entry.value;
            node=compare<0?node.left():node.right();
        }
        return null;
    }
    public boolean contains(K key){return get(key)!=null;}

    public KeyedTree<K,V> put(K key,V value){
        Objects.requireNonNull(key);Objects.requireNonNull(value);
        return new KeyedTree<>(spec,put(root,Entry.of(spec,key,value)));
    }

    public KeyedTree<K,V> remove(K key){
        if(root==null)return this;
        var next=remove(root,key);return next==root?this:new KeyedTree<>(spec,next);
    }

    /** Range sum over keys in {@code [lower, upper]}, from O(log n) nodes. */
    public AlgebraicAccumulator.Value range(K lower,K upper){return range(root,lower,upper);}

    /** Entries with keys in {@code [lower, upper]}, in key order, over this tree's snapshot. */
    public Cursor<K,V> cursor(K lower,K upper){return new Cursor<>(spec,root,lower,upper);}

    /** Every entry in key order. */
    public List<Map.Entry<K,V>> entries(){
        var result=new ArrayList<Map.Entry<K,V>>((int)Math.min(Integer.MAX_VALUE,size()));
        collect(root,result);return result;
    }

    /** Write every node, children before parents, each with its subtree range sum and key bounds. */
    public void writeNodes(NodeSink sink)throws Exception{
        if(root!=null)write(root,sink);
    }

    /** In-order cursor over a key range of one tree snapshot. */
    public static final class Cursor<K,V> {
        private final Spec<K,V> spec;private final K lower,upper;
        private final ArrayDeque<Node<K,V>> stack=new ArrayDeque<>();
        private Cursor(Spec<K,V> spec,Node<K,V> root,K lower,K upper){this.spec=spec;this.lower=lower;this.upper=upper;push(root);}
        private void push(Node<K,V> node){
            while(node!=null){
                if(spec.order().compare(node.entry.key,lower)<0){node=node.right();continue;}
                if(spec.order().compare(node.entry.key,upper)>0){node=node.left();continue;}
                stack.push(node);node=node.left();
            }
        }
        /** The next entry, or null when the range is exhausted. */
        public Map.Entry<K,V> next(){
            if(stack.isEmpty())return null;
            var node=stack.pop();push(node.right());return Map.entry(node.entry.key,node.entry.value);
        }
    }

    private static final class Entry<K,V> {
        final K key;final byte[] keyBytes;final V value;final Hash256 identity,priority;final AlgebraicAccumulator.Value contribution;
        private Entry(K key,byte[] keyBytes,V value,Hash256 identity,Hash256 priority,AlgebraicAccumulator.Value contribution){
            this.key=key;this.keyBytes=keyBytes;this.value=value;this.identity=identity;this.priority=priority;this.contribution=contribution;
        }
        static <K,V> Entry<K,V> of(Spec<K,V> spec,K key,V value){return of(spec,key,spec.encodeKey(key),value);}
        static <K,V> Entry<K,V> of(Spec<K,V> spec,K key,byte[] bytes,V value){
            Hash256 range=spec.rangeIdentity(value);
            return new Entry<>(key,bytes,value,Objects.requireNonNull(spec.identity(value)),priority(bytes),
                    range==null?AlgebraicAccumulator.Value.ZERO:AlgebraicAccumulator.contribution(spec.domain()+"/range",bytes,range));
        }
    }

    private static final class Node<K,V> {
        final Entry<K,V> entry;final Hash256 hash,leftHash,rightHash;
        final AlgebraicAccumulator.Value range;final K min,max;final long size;
        private final Spec<K,V> spec;private final NodeSource source;
        private volatile Node<K,V> left,right;

        Node(Spec<K,V> spec,Entry<K,V> entry,Node<K,V> left,Node<K,V> right){
            this.spec=spec;this.source=null;this.entry=entry;this.left=left;this.right=right;
            leftHash=left==null?null:left.hash;rightHash=right==null?null:right.hash;
            hash=hash(spec,leftHash,entry,rightHash);
            var sum=entry.contribution;
            if(left!=null)sum=left.range.plus(sum);
            if(right!=null)sum=sum.plus(right.range);
            range=sum;min=left==null?entry.key:left.min;max=right==null?entry.key:right.max;
            size=1+(left==null?0:left.size)+(right==null?0:right.size);
        }
        private Node(Spec<K,V> spec,NodeSource source,Hash256 hash,Entry<K,V> entry,Hash256 leftHash,Hash256 rightHash,
                     AlgebraicAccumulator.Value range,K min,K max,long size){
            this.spec=spec;this.source=source;this.hash=hash;this.entry=entry;this.leftHash=leftHash;this.rightHash=rightHash;
            this.range=range;this.min=min;this.max=max;this.size=size;
        }
        Node<K,V> left(){
            var value=left;if(value!=null||leftHash==null)return value;
            return left=loadChild(leftHash);
        }
        Node<K,V> right(){
            var value=right;if(value!=null||rightHash==null)return value;
            return right=loadChild(rightHash);
        }
        private Node<K,V> loadChild(Hash256 child){
            try{return load(spec,source,child);}catch(IOException e){throw new UncheckedIOException(e);}
        }

        static <K,V> Node<K,V> load(Spec<K,V> spec,NodeSource source,Hash256 hash)throws IOException{
            byte[] bytes=source.node(hash);if(bytes==null)throw new IOException("Missing tree node "+hash);
            try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
                byte[] keyBytes=readBytes(in);K key=spec.decodeKey(keyBytes);V value=spec.decodeValue(readBytes(in));
                Hash256 left=readHash(in),right=readHash(in);
                var range=new AlgebraicAccumulator.Value(new java.math.BigInteger(1,readBytes(in)),in.readLong());
                K min=spec.decodeKey(readBytes(in)),max=spec.decodeKey(readBytes(in));long size=in.readLong();
                var entry=Entry.of(spec,key,keyBytes,value);
                return new Node<>(spec,source,hash,entry,left,right,range,min,max,size);
            }
        }

        byte[] encode()throws IOException{
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)){
                writeBytes(out,entry.keyBytes);writeBytes(out,spec.encodeValue(entry.value));
                writeHash(out,leftHash);writeHash(out,rightHash);
                writeBytes(out,range.sum().toByteArray());out.writeLong(range.cardinality());
                writeBytes(out,spec.encodeKey(min));writeBytes(out,spec.encodeKey(max));out.writeLong(size);
            }
            return bytes.toByteArray();
        }
    }

    private static <K,V> Hash256 hash(Spec<K,V> spec,Hash256 left,Entry<K,V> entry,Hash256 right){
        return CanonicalDigestWriter.digest("keyed-tree-node-v1",spec.domain(),left==null?EMPTY:left,entry.keyBytes,entry.identity,right==null?EMPTY:right);
    }
    private static Hash256 priority(byte[] keyBytes){return CanonicalDigestWriter.digest("keyed-tree-priority-v1",keyBytes);}
    private static <K,V> boolean higher(Spec<K,V> spec,Entry<K,V> a,Entry<K,V> b){
        int compared=a.priority.compareTo(b.priority);
        return compared>0||compared==0&&spec.order().compare(a.key,b.key)>0;
    }

    private static <K,V> Node<K,V> freeze(Spec<K,V> spec,List<Entry<K,V>> entries,int[] left,int[] right,int index){
        if(index<0)return null;
        var leftNode=freeze(spec,entries,left,right,left[index]);
        var rightNode=freeze(spec,entries,left,right,right[index]);
        return new Node<>(spec,entries.get(index),leftNode,rightNode);
    }

    private Node<K,V> node(Entry<K,V> entry,Node<K,V> left,Node<K,V> right){return new Node<>(spec,entry,left,right);}

    private Node<K,V> put(Node<K,V> node,Entry<K,V> entry){
        if(node==null)return node(entry,null,null);
        int compare=spec.order().compare(entry.key,node.entry.key);
        if(compare==0)return node(entry,node.left(),node.right());
        if(compare<0){
            var next=node(node.entry,put(node.left(),entry),node.right());
            return higher(spec,next.left().entry,next.entry)?rotateRight(next):next;
        }
        var next=node(node.entry,node.left(),put(node.right(),entry));
        return higher(spec,next.right().entry,next.entry)?rotateLeft(next):next;
    }

    private Node<K,V> remove(Node<K,V> node,K key){
        if(node==null)return null;
        int compare=spec.order().compare(key,node.entry.key);
        if(compare==0)return merge(node.left(),node.right());
        if(compare<0){var left=remove(node.left(),key);return left==node.left()?node:node(node.entry,left,node.right());}
        var right=remove(node.right(),key);return right==node.right()?node:node(node.entry,node.left(),right);
    }

    private Node<K,V> merge(Node<K,V> left,Node<K,V> right){
        if(left==null)return right;if(right==null)return left;
        if(higher(spec,left.entry,right.entry))return node(left.entry,left.left(),merge(left.right(),right));
        return node(right.entry,merge(left,right.left()),right.right());
    }

    private Node<K,V> rotateRight(Node<K,V> node){
        var top=node.left();var lower=node(node.entry,top.right(),node.right());return node(top.entry,top.left(),lower);
    }
    private Node<K,V> rotateLeft(Node<K,V> node){
        var top=node.right();var lower=node(node.entry,node.left(),top.left());return node(top.entry,lower,top.right());
    }

    private AlgebraicAccumulator.Value range(Node<K,V> node,K lower,K upper){
        var order=spec.order();
        if(node==null||order.compare(node.max,lower)<0||order.compare(node.min,upper)>0)return AlgebraicAccumulator.Value.ZERO;
        if(order.compare(node.min,lower)>=0&&order.compare(node.max,upper)<=0)return node.range;
        var result=range(node.left(),lower,upper);
        if(order.compare(node.entry.key,lower)>=0&&order.compare(node.entry.key,upper)<=0)result=result.plus(node.entry.contribution);
        return result.plus(range(node.right(),lower,upper));
    }

    private static <K,V> void collect(Node<K,V> node,List<Map.Entry<K,V>> result){
        var stack=new ArrayDeque<Node<K,V>>();
        while(node!=null||!stack.isEmpty()){
            while(node!=null){stack.push(node);node=node.left();}
            node=stack.pop();result.add(Map.entry(node.entry.key,node.entry.value));node=node.right();
        }
    }

    private static <K,V> void write(Node<K,V> node,NodeSink sink)throws Exception{
        var left=node.left();if(left!=null)write(left,sink);
        var right=node.right();if(right!=null)write(right,sink);
        sink.node(node.hash,node.encode());
    }

    private static void writeBytes(DataOutputStream out,byte[] value)throws IOException{out.writeInt(value.length);out.write(value);}
    private static byte[] readBytes(DataInputStream in)throws IOException{
        int length=in.readInt();if(length<0)throw new IOException("Negative tree node field");
        byte[] value=in.readNBytes(length);if(value.length!=length)throw new EOFException();return value;
    }
    private static void writeHash(DataOutputStream out,Hash256 hash)throws IOException{
        if(hash==null){out.writeBoolean(false);return;}
        out.writeBoolean(true);out.write(hash.bytes());
    }
    private static Hash256 readHash(DataInputStream in)throws IOException{
        if(!in.readBoolean())return null;
        byte[] value=in.readNBytes(Hash256.BYTES);if(value.length!=Hash256.BYTES)throw new EOFException();return new Hash256(value);
    }

    /** Spec base for trees keyed by strings in natural order. */
    public abstract static class StringKeys<V> implements Spec<String,V> {
        private final String domain;
        protected StringKeys(String domain){this.domain=Objects.requireNonNull(domain);}
        @Override public final String domain(){return domain;}
        @Override public final Comparator<? super String> order(){return Comparator.naturalOrder();}
        @Override public final byte[] encodeKey(String key){return key.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        @Override public final String decodeKey(byte[] bytes){return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
    }
}
