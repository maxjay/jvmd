package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.Root;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * MACHINE reads. Holds the committed {@link MachineTree}; every identity a consumer needs is read
 * from it. A newly committed tree replaces the previous one. A tree committed by an earlier process
 * is read from its store the first time something reads it; until then only its root is held.
 */
public final class MachineLayer {
    private volatile MachineTree tree=MachineTree.EMPTY;
    private volatile Root root;
    private volatile Callable<MachineTree> stored;

    public MachineTree tree(){
        if(stored!=null)synchronized(this){
            if(stored!=null){
                try{tree=stored.call();}catch(Exception unreadable){throw new IllegalStateException("Committed MACHINE tree unreadable",unreadable);}
                stored=null;
            }
        }
        return tree;
    }
    /** The committed root, or empty before the first commit. */
    public Optional<Root> root(){return Optional.ofNullable(root);}
    public MachineLeaf leaf(String cacheKey){return tree().leaf(cacheKey);}
    public MachineLeaf leafAt(String location){return tree().leafAt(location);}

    /** Publish a tree whose root has been committed. */
    public synchronized void committed(MachineTree next){tree=next;root=next.root();stored=null;}

    /** Publish a root committed earlier, whose tree {@code read} reads from its store when first needed. */
    public synchronized void committed(Root committed,Callable<MachineTree> read){root=committed;tree=MachineTree.EMPTY;stored=read;}
}
