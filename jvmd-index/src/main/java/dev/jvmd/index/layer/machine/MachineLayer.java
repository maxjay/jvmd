package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.Root;
import java.util.Optional;

/**
 * MACHINE reads. Holds the committed {@link MachineTree}; every identity a consumer needs is read
 * from it. A newly committed tree replaces the previous one.
 */
public final class MachineLayer {
    private volatile MachineTree tree=MachineTree.EMPTY;
    private volatile Root root;

    public MachineTree tree(){return tree;}
    /** The committed root, or empty before the first commit. */
    public Optional<Root> root(){return Optional.ofNullable(root);}
    public MachineLeaf leaf(String cacheKey){return tree.leaf(cacheKey);}
    public MachineLeaf leafAt(String location){return tree.leafAt(location);}

    /** Publish a tree whose root has been committed. */
    public synchronized void committed(MachineTree next){tree=next;root=next.root();}
}
