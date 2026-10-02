package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Root;
import dev.jvmd.index.ResidentSemanticState;
import dev.jvmd.index.SemanticReadView;
import dev.jvmd.index.SemanticReadViews;
import dev.jvmd.index.SemanticSnapshot;
import java.nio.file.Path;
import java.util.*;

/**
 * LOCAL reads for one project. While the LOCAL cold boot runs it holds the files built so far; a
 * type declared by a file that is not built is UNKNOWN, never absent, so no lower layer answers for
 * it. Once the LOCAL root is committed it holds every file.
 */
public final class LocalLayer {
    private final ResidentSemanticState semantic=new ResidentSemanticState();
    private final Map<Path,LocalFile> built=new HashMap<>();
    /** Files not built yet, with the top-level binary name their path declares. */
    private final Map<Path,String> pending=new HashMap<>();
    /** Top-level binary names of files that are not built, or could not be attributed completely. */
    private final Map<String,Integer> unknownTypes=new HashMap<>();
    private volatile LocalTree tree;
    private volatile Root root;

    /** A layer whose files are all still to be built: each file with the top-level binary name its path declares. */
    public LocalLayer(Map<Path,String> files){
        files.forEach((file,binaryName)->{pending.put(file.toAbsolutePath().normalize(),binaryName);unknownTypes.merge(binaryName,1,Integer::sum);});
    }

    /** Add a built file and the declarations attributed from it. */
    public synchronized void admit(Path file,LocalFile leaf,SemanticSnapshot declarations){
        file=file.toAbsolutePath().normalize();
        semantic.admit(declarations);built.put(file,leaf);settle(file,true);
    }

    /** A file whose attribution was not complete: it is done, but what it declares stays UNKNOWN. */
    public synchronized void incomplete(Path file){settle(file.toAbsolutePath().normalize(),false);}

    /** The boot stopped: every file still to be built is done, and what it declares stays UNKNOWN. */
    public synchronized void abandon(){
        for(Path file:List.copyOf(pending.keySet()))settle(file,false);
    }

    private void settle(Path file,boolean known){
        String binaryName=pending.remove(file);
        if(binaryName!=null&&known)unknownTypes.computeIfPresent(binaryName,(name,count)->count==1?null:count-1);
        notifyAll();
    }

    /** Whether {@code file} is still to be built. */
    public synchronized boolean pending(Path file){return pending.containsKey(file.toAbsolutePath().normalize());}
    public synchronized int pendingCount(){return pending.size();}

    /** Wait until {@code file} is no longer pending. */
    public synchronized void await(Path file)throws InterruptedException{
        file=file.toAbsolutePath().normalize();while(pending.containsKey(file))wait();
    }

    /**
     * Whether project source declares {@code binaryName}: a built declaration, or a file that is not
     * built (or not completely attributed) whose path names it. Lower layers do not answer for it.
     */
    public synchronized boolean ownsBinary(String binaryName){
        if(binaryName==null||binaryName.isBlank())return false;
        if(semantic.type(binaryName)!=null)return true;
        String name=binaryName.replace('$','.');
        for(String top=name;;){
            if(unknownTypes.containsKey(top))return true;
            int dot=top.lastIndexOf('.');if(dot<0)return false;top=top.substring(0,dot);
        }
    }

    /** The LOCAL declarations built so far. */
    public SemanticReadView view(){return SemanticReadViews.resident(semantic,SemanticReadView.Origin.LOCAL);}

    /** Publish a tree whose root has been committed. */
    public void committed(LocalTree next){tree=next;root=next.root();}
    public Optional<LocalTree> tree(){return Optional.ofNullable(tree);}
    public Optional<Root> root(){return Optional.ofNullable(root);}

    public synchronized Map<String,Object> status(){
        return Map.of("built",built.size(),"pending",pending.size(),"unknown_types",unknownTypes.size(),
                "root",root==null?"":root.identity().hex());
    }
}
