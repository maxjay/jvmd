package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.Root;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * MACHINE reads. Holds the committed {@link MachineTree}; every identity a consumer needs is read
 * from it. A newly committed tree replaces the previous one and is announced to subscribers, which
 * receive both trees so they can apply only what changed.
 */
public final class MachineLayer {
    /** A committed tree and the tree it replaced. */
    public record Change(MachineTree previous,MachineTree current) { }

    private volatile MachineTree tree=MachineTree.EMPTY;
    private volatile Root root;
    private final List<Consumer<Change>> subscribers=new CopyOnWriteArrayList<>();

    public MachineTree tree(){return tree;}
    /** The committed root, or empty before the first commit. */
    public Optional<Root> root(){return Optional.ofNullable(root);}
    public MachineLeaf leaf(String cacheKey){return tree.leaf(cacheKey);}
    public MachineLeaf leafAt(String location){return tree.leafAt(location);}

    /** Receive every later commit; the subscriber is first called with the current tree. */
    public void subscribe(Consumer<Change> subscriber){
        subscribers.add(subscriber);
        synchronized(this){subscriber.accept(new Change(MachineTree.EMPTY,tree));}
    }

    /** Publish a tree whose root has been committed. */
    public synchronized void committed(MachineTree next){
        var previous=tree;tree=next;root=next.root();
        for(var subscriber:subscribers)subscriber.accept(new Change(previous,next));
    }
}
