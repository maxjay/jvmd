package dev.jvmd.core;

import java.util.*;

/** Immutable AVL map. Updates share untouched subtrees; reads and updates are O(log n). */
public final class PersistentMap<K extends Comparable<? super K>,V> extends AbstractMap<K,V> {
    private record Node<K,V>(K key,V value,Node<K,V> left,Node<K,V> right,int height,int size){}
    private final Node<K,V> root;
    private PersistentMap(Node<K,V> root){this.root=root;}
    public static <K extends Comparable<? super K>,V> PersistentMap<K,V> empty(){return new PersistentMap<>(null);}
    private static int height(Node<?,?> n){return n==null?0:n.height;}
    private static int size(Node<?,?> n){return n==null?0:n.size;}
    private Node<K,V> node(K key,V value,Node<K,V> left,Node<K,V> right){return new Node<>(key,value,left,right,1+Math.max(height(left),height(right)),1+size(left)+size(right));}
    private Node<K,V> right(Node<K,V> n){var l=n.left;return node(l.key,l.value,l.left,node(n.key,n.value,l.right,n.right));}
    private Node<K,V> left(Node<K,V> n){var r=n.right;return node(r.key,r.value,node(n.key,n.value,n.left,r.left),r.right);}
    private Node<K,V> balance(Node<K,V> n){
        if(height(n.left)-height(n.right)>1){if(height(n.left.left)<height(n.left.right))n=node(n.key,n.value,left(n.left),n.right);return right(n);}
        if(height(n.right)-height(n.left)>1){if(height(n.right.right)<height(n.right.left))n=node(n.key,n.value,n.left,right(n.right));return left(n);}
        return n;
    }
    private Node<K,V> put(Node<K,V> n,K key,V value){
        if(n==null)return node(key,value,null,null);int c=key.compareTo(n.key);
        if(c==0)return value==n.value?n:node(key,value,n.left,n.right);
        if(c<0){var child=put(n.left,key,value);return child==n.left?n:balance(node(n.key,n.value,child,n.right));}
        var child=put(n.right,key,value);return child==n.right?n:balance(node(n.key,n.value,n.left,child));
    }
    private Node<K,V> remove(Node<K,V> n,K key){
        if(n==null)return null;int c=key.compareTo(n.key);
        if(c<0){var child=remove(n.left,key);return child==n.left?n:balance(node(n.key,n.value,child,n.right));}
        if(c>0){var child=remove(n.right,key);return child==n.right?n:balance(node(n.key,n.value,n.left,child));}
        if(n.left==null)return n.right;if(n.right==null)return n.left;
        var next=n.right;while(next.left!=null)next=next.left;
        return balance(node(next.key,next.value,n.left,remove(n.right,next.key)));
    }
    public PersistentMap<K,V> with(K key,V value){Objects.requireNonNull(key);Objects.requireNonNull(value);var next=put(root,key,value);return next==root?this:new PersistentMap<>(next);}
    public PersistentMap<K,V> without(K key){var next=remove(root,key);return next==root?this:new PersistentMap<>(next);}
    @Override public int size(){return size(root);}
    @Override @SuppressWarnings("unchecked") public V get(Object key){
        K typed=(K)key;for(var n=root;n!=null;){int c=typed.compareTo(n.key);if(c==0)return n.value;n=c<0?n.left:n.right;}return null;
    }
    @Override public boolean containsKey(Object key){return get(key)!=null;}
    @Override public Set<Entry<K,V>> entrySet(){return new AbstractSet<>(){
        @Override public int size(){return PersistentMap.this.size();}
        @Override public Iterator<Entry<K,V>> iterator(){return new Iterator<>(){
            final ArrayDeque<Node<K,V>> stack=new ArrayDeque<>();{push(root);}
            private void push(Node<K,V> n){while(n!=null){stack.push(n);n=n.left;}}
            public boolean hasNext(){return !stack.isEmpty();}
            public Entry<K,V> next(){if(stack.isEmpty())throw new NoSuchElementException();var n=stack.pop();push(n.right);return Map.entry(n.key,n.value);}
        };}
    };}
}
