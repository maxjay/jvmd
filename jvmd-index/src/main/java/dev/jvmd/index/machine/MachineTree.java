package dev.jvmd.index.machine;

import dev.jvmd.core.CanonicalDigestWriter;
import dev.jvmd.core.tree.Aggregate;
import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.ClasspathSequence;
import java.util.*;

/**
 * The MACHINE tree: every leaf keyed by content cacheKey, with range sums over resolution and
 * documentation identity. The layer's aggregates are the range sums over the whole tree, so the
 * machine resolution identity changes when any artifact's resolution changes and only then.
 */
public final class MachineTree {
    public static final int FORMAT=1;
    public static final String RESOLUTION="resolution",DOCUMENTATION="documentation";
    public static final KeyedTree.Schema<MachineLeaf> SCHEMA=new KeyedTree.Schema<MachineLeaf>("machine-tree-v1",
            leaf->CanonicalDigestWriter.digest("machine-leaf-v1",leaf.encode()),
            List.of(new KeyedTree.Projection<MachineLeaf>(RESOLUTION,MachineLeaf::resolution),
                    new KeyedTree.Projection<MachineLeaf>(DOCUMENTATION,MachineLeaf::documentationIdentity)));

    private final KeyedTree<MachineLeaf> leaves;
    private final Map<String,String> locations;

    private MachineTree(KeyedTree<MachineLeaf> leaves){
        this.leaves=leaves;var paths=new HashMap<String,String>();
        leaves.forEach((key,leaf)->{for(var location:leaf.locations())paths.put(location.location(),key);});
        this.locations=Map.copyOf(paths);
    }

    public static MachineTree empty(){return new MachineTree(KeyedTree.empty(SCHEMA));}
    public static MachineTree of(KeyedTree<MachineLeaf> leaves){return new MachineTree(leaves);}
    public static MachineTree build(Collection<MachineLeaf> leaves){
        var entries=new HashMap<String,MachineLeaf>(leaves.size()*2);
        for(var leaf:leaves)if(entries.put(leaf.cacheKey(),leaf)!=null)throw new IllegalArgumentException("Duplicate machine leaf "+leaf.cacheKey());
        return new MachineTree(KeyedTree.build(SCHEMA,entries));
    }

    public KeyedTree<MachineLeaf> leaves(){return leaves;}
    public int size(){return leaves.size();}
    public Optional<MachineLeaf> leaf(String cacheKey){return Optional.ofNullable(leaves.get(cacheKey));}
    /** The leaf whose content is at a location, from the path table. */
    public Optional<MachineLeaf> leafAt(String location){var key=locations.get(location);return key==null?Optional.empty():leaf(key);}
    public Map<String,String> locations(){return locations;}

    public Aggregate resolution(){return new Aggregate(RESOLUTION,leaves.total(SCHEMA.projection(RESOLUTION)));}
    public Aggregate documentation(){return new Aggregate(DOCUMENTATION,leaves.total(SCHEMA.projection(DOCUMENTATION)));}
    public Root root(){return Root.of(FORMAT,leaves.rootHash(),List.of(resolution(),documentation()),leaves.size());}

    public MachineTree put(MachineLeaf leaf){return new MachineTree(leaves.put(leaf.cacheKey(),leaf));}
    public MachineTree remove(String cacheKey){return new MachineTree(leaves.remove(cacheKey));}

    /**
     * The ordered route of one module scope: its classpath as MACHINE leaf keys. The machine
     * identity is not an input, so a change to an artifact outside the route leaves it equal.
     */
    public ClasspathSequence route(List<String> orderedKeys){
        var entries=new ArrayList<ClasspathSequence.Entry>(orderedKeys.size());var seen=new HashSet<String>();
        for(String key:orderedKeys){
            Objects.requireNonNull(key);
            if(!seen.add(key))throw new IllegalArgumentException("Duplicate route entry: "+key);
            var leaf=leaves.get(key);if(leaf==null)throw new IllegalArgumentException("Unknown machine leaf: "+key);
            entries.add(new ClasspathSequence.Entry(key,leaf.resolution()));
        }
        return ClasspathSequence.of(entries);
    }
}
