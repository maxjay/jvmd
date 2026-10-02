package dev.jvmd.index.machine;

import dev.jvmd.core.Hash256;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.ClasspathSequence;
import java.util.*;

/** Reads of the committed MACHINE layer. Every identity comes from its tree. */
public final class MachineLayer {
    private final MachineTree tree;

    public MachineLayer(MachineTree tree){this.tree=Objects.requireNonNull(tree);}

    public Root root(){return tree.root();}
    public int leaves(){return tree.size();}
    public Optional<MachineLeaf> leaf(String cacheKey){return tree.leaf(cacheKey);}
    public Optional<MachineLeaf> leafAt(String location){return tree.leafAt(location);}
    public Hash256 resolutionIdentity(){return tree.resolution().identity();}
    public Hash256 documentationIdentity(){return tree.documentation().identity();}
    public ClasspathSequence route(List<String> orderedKeys){return tree.route(orderedKeys);}
}
