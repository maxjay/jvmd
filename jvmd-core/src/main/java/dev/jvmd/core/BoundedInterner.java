package dev.jvmd.core;

import java.util.*;

/** Structural equals, not digest equality, decides canonicalization. Eviction affects reuse only. */
public final class BoundedInterner<T> {
    private final int capacity;
    private final LinkedHashMap<T,T> entries=new LinkedHashMap<>(16,.75f,true);
    public BoundedInterner(int capacity){if(capacity<1)throw new IllegalArgumentException("Positive capacity required");this.capacity=capacity;}
    public synchronized T intern(T value){
        Objects.requireNonNull(value);T found=entries.get(value);if(found!=null)return found;
        entries.put(value,value);while(entries.size()>capacity)entries.remove(entries.keySet().iterator().next());return value;
    }
    public synchronized int size(){return entries.size();}
}
