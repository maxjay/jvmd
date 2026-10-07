package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.machine.*;
import java.util.*;
import java.util.function.Function;

/** Effective per-class reader views, selected once in actual classpath order and rooted in RT. */
public final class ReaderBinding {
    public static final int PRESENT=0, ABSENT_MEMBER=1, ABSENT_OWNER=2, FAILED=3, UNSUPPORTED=4;
    private ReaderBinding() { }
    public static Identity build(ContentTree tree,MachineLeaf own,Bound bound,Function<byte[],byte[]> records,NodeSink sink) {
        return build(tree,own,null,bound,records,sink);
    }
    public static Identity build(ContentTree tree,MachineLeaf own,Identity ownReader,Bound bound,Function<byte[],byte[]> records,NodeSink sink) {
        Function<Identity,byte[]> nodes=id->records.apply(MachineStore.nodeKey(id));
        var entries=new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
        if(ownReader==null)unknown(tree,own,nodes,entries);
        else known(tree,ownReader,nodes,entries);
        for(var origin:bound.bindings()) {
            // The platform image is independently fixed by the compiler task's system input.
            if(origin.entry() instanceof RouteEntry.Jrt)continue;
            if(origin.reader()==null)unknown(tree,MachineLeaf.decode(records.apply(MachineStore.leafKey(origin.k())),tree.digest().width()),nodes,entries);
            else known(tree,origin.reader(),nodes,entries);
        }
        var root=tree.build(entries.values(),sink);sink.flush();return root.hash();
    }
    private static void known(ContentTree tree,Identity root,Function<Identity,byte[]> nodes,Map<byte[],Entry> entries) {
        tree.forEach(root,nodes,e->{
            byte[] value=new Codec.Writer().u8(1).raw(e.value()).toBytes();
            entries.putIfAbsent(e.key(),new Entry(e.key(),value,tree.digest().hash(e.key(),value)));
        });
    }
    private static void unknown(ContentTree tree,MachineLeaf leaf,Function<Identity,byte[]> nodes,Map<byte[],Entry> entries) {
        tree.forEach(leaf.oHash(),nodes,e->{
            var key=ReaderImage.owner(Keys.ownerOf(e.key()));var value=new byte[]{0};
            entries.putIfAbsent(key,new Entry(key,value,tree.digest().hash(key,value)));
        });
    }
    public static byte[] local(ContentTree tree,Identity binding,String owner,int operation,String name,Function<Identity,byte[]> nodes) {
        if(binding==null)return new byte[]{UNSUPPORTED};
        var selected=tree.get(binding,nodes,ReaderImage.owner(owner));
        if(selected==null)return new byte[]{ABSENT_OWNER};
        var in=new Codec.Reader(selected.value());if(in.u8()==0)return new byte[]{UNSUPPORTED};
        var entry=tree.get(in.id(tree.digest().width()),nodes,ReaderImage.key(operation,name));
        if(entry==null)return new byte[]{ABSENT_MEMBER};
        if(operation==ReaderImage.RECIPE && entry.value()[0]==0)return new byte[]{UNSUPPORTED};
        return new Codec.Writer().u8(PRESENT).raw(entry.value()).toBytes();
    }
    public static Identity answer(ContentTree tree,Identity binding,ReverseIndex.Dependency query,Function<Identity,byte[]> nodes) {
        if(query.universe()!=0 || !query.module().isEmpty())return null;
        var bytes=local(tree,binding,query.type(),query.kind(),query.name(),nodes);
        return bytes[0]==UNSUPPORTED?null:tree.digest().hash(bytes);
    }
}
