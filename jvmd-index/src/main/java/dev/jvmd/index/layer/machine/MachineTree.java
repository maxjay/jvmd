package dev.jvmd.index.layer.machine;

import dev.jvmd.core.Hash256;
import dev.jvmd.core.tree.Aggregate;
import dev.jvmd.core.tree.KeyedTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.ArtifactIndexFormat;
import dev.jvmd.index.ClasspathSequence;
import java.io.*;
import java.util.*;

/**
 * The MACHINE trees and aggregates. The artifact tree holds one {@link MachineLeaf} per distinct
 * content, keyed by cacheKey; each leaf names the root of its own semantic tree, which orders the
 * artifact's declarations by owner, then member, and carries resolution range sums. The resolution
 * and documentation aggregates sum each leaf's projected identity, and the path table maps every
 * location to its leaf. Instances are immutable; {@link #put} returns the updated tree.
 */
public final class MachineTree {
    public static final int FORMAT=1;
    public static final String RESOLUTION="machine-resolution-v1",DOCUMENTATION="machine-documentation-v1";

    public static final KeyedTree.Spec<String,MachineLeaf> LEAVES=new KeyedTree.StringKeys<>("machine-artifacts-v1"){
        @Override public byte[] encodeValue(MachineLeaf value){return value.encode();}
        @Override public MachineLeaf decodeValue(byte[] bytes)throws IOException{return MachineLeaf.decode(bytes);}
        @Override public Hash256 identity(MachineLeaf value){return value.identity();}
    };

    /** One declaration of an artifact: its symbol id in the artifact's store and its resolution identity. */
    public record ArtifactSymbol(int id,Hash256 resolution) {
        public ArtifactSymbol { Objects.requireNonNull(resolution); }
    }
    public static final KeyedTree.Spec<String,ArtifactSymbol> SYMBOLS=new KeyedTree.StringKeys<>("machine-symbols-v1"){
        @Override public byte[] encodeValue(ArtifactSymbol value){
            var bytes=new byte[4+Hash256.BYTES];int id=value.id();
            bytes[0]=(byte)(id>>>24);bytes[1]=(byte)(id>>>16);bytes[2]=(byte)(id>>>8);bytes[3]=(byte)id;
            System.arraycopy(value.resolution().bytes(),0,bytes,4,Hash256.BYTES);return bytes;
        }
        @Override public ArtifactSymbol decodeValue(byte[] bytes)throws IOException{
            if(bytes.length!=4+Hash256.BYTES)throw new IOException("Invalid artifact symbol node value");
            int id=((bytes[0]&0xff)<<24)|((bytes[1]&0xff)<<16)|((bytes[2]&0xff)<<8)|(bytes[3]&0xff);
            return new ArtifactSymbol(id,new Hash256(Arrays.copyOfRange(bytes,4,bytes.length)));
        }
        @Override public Hash256 identity(ArtifactSymbol value){return value.resolution();}
        @Override public Hash256 rangeIdentity(ArtifactSymbol value){return value.resolution();}
    };

    public static final MachineTree EMPTY=new MachineTree(KeyedTree.empty(LEAVES),Aggregate.empty(RESOLUTION),
            Aggregate.empty(DOCUMENTATION),Collections.emptyNavigableMap());

    private final KeyedTree<String,MachineLeaf> leaves;
    private final Aggregate resolution,documentation;
    private final NavigableMap<String,String> paths;

    private MachineTree(KeyedTree<String,MachineLeaf> leaves,Aggregate resolution,Aggregate documentation,NavigableMap<String,String> paths){
        this.leaves=leaves;this.resolution=resolution;this.documentation=documentation;this.paths=Collections.unmodifiableNavigableMap(paths);
    }

    /** Bulk-build from leaves in any order; the result depends only on the leaves. */
    public static MachineTree build(Collection<MachineLeaf> values){
        var sorted=new TreeMap<String,MachineLeaf>();var resolution=Aggregate.empty(RESOLUTION);var documentation=Aggregate.empty(DOCUMENTATION);
        var paths=new TreeMap<String,String>();
        for(var leaf:values){
            String key=leaf.cacheKey();
            if(sorted.put(key,leaf)!=null)throw new IllegalArgumentException("Duplicate MACHINE leaf "+key);
            resolution=resolution.add(key,leaf.resolution());documentation=documentation.add(key,leaf.documentation());
            for(var path:leaf.paths())if(paths.put(path.location(),key)!=null)
                throw new IllegalArgumentException("MACHINE path in two leaves: "+path.location());
        }
        return new MachineTree(KeyedTree.build(LEAVES,List.copyOf(sorted.entrySet())),resolution,documentation,paths);
    }

    /** Add a leaf, or replace the leaf with the same key, updating the aggregates and path table. */
    public MachineTree put(MachineLeaf leaf){
        String key=leaf.cacheKey();var previous=leaves.get(key);
        var nextPaths=new TreeMap<>(paths);
        if(previous!=null)for(var path:previous.paths())nextPaths.remove(path.location());
        for(var path:leaf.paths()){
            String owner=nextPaths.put(path.location(),key);
            if(owner!=null&&!owner.equals(key))throw new IllegalArgumentException("MACHINE path in two leaves: "+path.location());
        }
        return new MachineTree(leaves.put(key,leaf),
                resolution.replace(key,previous==null?null:previous.resolution(),key,leaf.resolution()),
                documentation.replace(key,previous==null?null:previous.documentation(),key,leaf.documentation()),nextPaths);
    }

    public KeyedTree<String,MachineLeaf> tree(){return leaves;}
    public MachineLeaf leaf(String cacheKey){return leaves.get(cacheKey);}
    /** The leaf whose content is at {@code location}, or null. */
    public MachineLeaf leafAt(String location){String key=paths.get(location);return key==null?null:leaves.get(key);}
    /** Every location, in order, with the cacheKey of its leaf. */
    public NavigableMap<String,String> paths(){return paths;}
    public List<MachineLeaf> leaves(){return leaves.entries().stream().map(Map.Entry::getValue).toList();}
    public long size(){return leaves.size();}
    public Aggregate resolution(){return resolution;}
    public Aggregate documentation(){return documentation;}

    public Root root(){return new Root(FORMAT,leaves.rootHash(),List.of(resolution,documentation),leaves.size());}

    /**
     * The ordered sequence of the given leaves, each with its resolution identity. A change to a
     * leaf outside the sequence leaves the sequence identity equal.
     */
    public ClasspathSequence sequence(List<String> cacheKeys){
        var entries=new ArrayList<ClasspathSequence.Entry>(cacheKeys.size());var seen=new HashSet<String>();
        for(String key:cacheKeys){
            if(!seen.add(key))throw new IllegalArgumentException("Duplicate MACHINE leaf in a sequence: "+key);
            var leaf=leaves.get(key);if(leaf==null)throw new IllegalArgumentException("Unknown MACHINE leaf: "+key);
            entries.add(new ClasspathSequence.Entry(key,leaf.resolution()));
        }
        return ClasspathSequence.of(entries);
    }

    /** The semantic tree key of an artifact declaration: owner, then member. */
    public static String symbolKey(ArtifactIndexFormat.SymbolRecord symbol,ArtifactIndexFormat.SymbolRecord owner){
        if(owner!=null)return "member\0"+owner.key()+"\0"+symbol.name()+"\0"+symbol.key();
        int dot=symbol.fqn().lastIndexOf('.');String pkg=dot<0?"":symbol.fqn().substring(0,dot);
        return "type\0"+pkg+"\0"+symbol.name()+"\0"+symbol.key();
    }

    /** An artifact's semantic tree, bulk-built from its facts. */
    public static KeyedTree<String,ArtifactSymbol> semanticTree(ArtifactIndexFormat.ArtifactData facts){
        var byId=new HashMap<Integer,ArtifactIndexFormat.SymbolRecord>();for(var symbol:facts.symbols())byId.put(symbol.id(),symbol);
        var sorted=new TreeMap<String,ArtifactSymbol>();
        for(var symbol:facts.symbols()){
            var owner=symbol.ownerId()<0?null:byId.get(symbol.ownerId());
            sorted.put(symbolKey(symbol,owner),new ArtifactSymbol(symbol.id(),symbol.resolution().identity()));
        }
        return KeyedTree.build(SYMBOLS,List.copyOf(sorted.entrySet()));
    }
}
