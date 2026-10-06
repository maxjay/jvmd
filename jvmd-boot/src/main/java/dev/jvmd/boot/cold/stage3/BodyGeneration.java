package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.BodiesRoot;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** One Stage 3 generation: rooted reads, worker publication into a shared buffer, then one BROOT commit. */
public final class BodyGeneration implements LocalStore {
    private final ContentTree tree;
    private final LocalStore store;
    private final Identity project;
    private final LocalRoot local;
    private final byte[] localBytes;
    private final byte[] previousBytes;
    private final byte[] machineBytes;
    private final BodiesRoot previous;
    private final Map<byte[],byte[]> pending=new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
    private boolean committed;
    private Root root;

    private BodyGeneration(ContentTree tree,LocalStore store,Identity project,LocalRoot local) {
        this.tree=tree;this.store=store;this.project=project;this.local=local;
        localBytes=store.get(LocalStore.localRootKey(project));
        if(localBytes==null || !LocalRoot.decode(tree.digest(),localBytes).equals(local))
            throw new IllegalStateException("LOCAL root changed before body planning");
        previousBytes=store.get(LocalStore.bodiesRootKey(project));
        machineBytes=store.get(MachineStore.ROOT_KEY);
        // Incompatible bodies formats are cold inputs. Their records are never consulted.
        previous=previousBytes==null || !LocalRoot.formatOf(previousBytes).equals(BodiesRoot.format(local.format()))
                ? null : BodiesRoot.decode(previousBytes,tree.digest().width());
    }

    public static BodyGeneration begin(ContentTree tree,LocalStore store,Identity project,LocalRoot local) {
        return new BodyGeneration(tree,store,project,local);
    }
    public Root root() { if(!committed)throw new IllegalStateException("Bodies are not committed");return root; }

    /** Only stage-2 records in the selected LOCAL root can supply current compilation inputs. */
    public byte[] local(byte[] key) { return rooted(local.local().hash(),key); }

    @Override public byte[] get(byte[] key) {
        open();
        var value=pending.get(key);if(value!=null)return value.clone();
        if(machine(key) || tag(key,"S") || tag(key,"ST") || tag(key,"PROC"))return store.get(key);
        if(!body(key) && !tag(key,"U"))return local(key);
        if(previous==null)return null;
        if(tag(key,"U")) {
            if(key.length!=2+tree.digest().width())throw new IllegalArgumentException("Invalid uses key");
            // U is derivable and outside the tree. Its exact ACI must first be admitted by an RS in the previous BROOT.
            var result=LocalStore.resultKey(Identity.of(Arrays.copyOfRange(key,2,key.length)));
            return rooted(previous.bodiesRoot(),result)==null ? null : store.get(key);
        }
        return rooted(previous.bodiesRoot(),key);
    }

    private byte[] rooted(Identity hash,byte[] key) {
        var entry=tree.get(hash,this::node,key);if(entry==null)return null;
        var value=store.get(key);
        if(value==null || !tree.digest().hash(value).equals(entry.h()))throw new IllegalStateException("Rooted record digest mismatch");
        return value;
    }
    private byte[] node(Identity hash) {
        var key=MachineStore.nodeKey(hash);var bytes=pending.get(key);if(bytes==null)bytes=store.get(key);
        if(bytes==null)throw new IllegalStateException("Missing tree node");return bytes;
    }

    @Override public synchronized void put(byte[] key,byte[] value) {
        open();if(!body(key) && !tag(key,"U") && !tag(key,"S") && !tag(key,"ST"))throw new IllegalArgumentException("Not a body record or shared stub");
        checkContent(key,value);
        var existing=pending.putIfAbsent(key.clone(),value.clone());
        if(existing!=null && !Arrays.equals(existing,value))throw new IllegalStateException("Conflicting body records in one generation");
    }
    @Override public void observeProcessor(Identity path, String processor, dev.jvmd.index.layer.local.ProcessorRecords.Capability observation) {
        open(); store.observeProcessor(path, processor, observation);
    }
    private void checkContent(byte[] key,byte[] value) {
        if(tag(key,"CF") && (key.length!=3+tree.digest().width()
                || !tree.digest().hash(value).equals(Identity.of(Arrays.copyOfRange(key,3,key.length)))))
            throw new IllegalArgumentException("Class bytes do not match their content key");
    }
    @Override public synchronized void write(Node node) {
        open();if(!tree.digest().hash(node.bytes()).equals(node.hash()))throw new IllegalArgumentException("Node digest mismatch");
        pending.putIfAbsent(MachineStore.nodeKey(node.hash()),node.bytes().clone());
    }
    @Override public void flush() { open(); } // Workers publish only to this generation until the driver selects complete scopes.

    /**
     * Current records are the driver's complete selection, including reused content and excluding rejected scope results.
     * Provisional worker results absent from this selection are not published. U is written but never rooted.
     */
    public synchronized BodiesRoot commit(Map<byte[],byte[]> selected) {
        open();
        var current=new TreeMap<byte[],byte[]>(Arrays::compareUnsigned);
        selected.forEach((key,value)->{
            if(!body(key) && !tag(key,"U"))throw new IllegalArgumentException("Not a body record");
            checkContent(key,value);current.put(key.clone(),value.clone());
        });
        synchronized(store) {
            if(!Arrays.equals(localBytes,store.get(LocalStore.localRootKey(project)))
                    || !Arrays.equals(previousBytes,store.get(LocalStore.bodiesRootKey(project)))
                    || !Arrays.equals(machineBytes,store.get(MachineStore.ROOT_KEY)))
                throw new IllegalStateException("Committed roots changed during body attribution");
            Root base=local.local();var old=new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
            if(previous!=null) {
                var beforeLocal=root(previous.localRoot());var beforeBodies=root(previous.bodiesRoot());
                var stage2=Diff.trees(tree.digest(),beforeLocal,local.local(),this::node);
                base=apply(beforeBodies,stage2.removed(),stage2.added());
                var bodyRecords=Diff.trees(tree.digest(),beforeLocal,beforeBodies,this::node);
                if(!bodyRecords.removed().isEmpty())throw new IllegalStateException("Previous BROOT replaced LOCAL entries");
                for(var entry:bodyRecords.added()) {
                    if(!body(entry.key()))throw new IllegalStateException("Unexpected previous bodies entry");
                    old.put(entry.key(),entry);
                }
            }
            var removed=new ArrayList<Entry>();var added=new ArrayList<Entry>();
            for(var entry:old.values())if(!current.containsKey(entry.key()))removed.add(entry);
            for(var entry:current.entrySet())if(!tag(entry.getKey(),"U")) {
                var now=new Entry(entry.getKey(),Entry.NONE,tree.digest().hash(entry.getValue()));
                var before=old.get(entry.getKey());
                if(before==null || !before.h().equals(now.h())) { if(before!=null)removed.add(before);added.add(now); }
            }
            var finished=apply(base,removed,added);
            var result=new BodiesRoot(BodiesRoot.format(local.format()),finished.hash(),local.local().hash(),local.machineRoot(),local.modelHash());
            // Do not probe unrooted CF/RS/U. A cold generation publishes its computed bytes; a rerun deduplicates via its root.
            var writes=new TreeMap<byte[],byte[]>(Arrays::compareUnsigned);
            for(var entry:current.entrySet()) {
                var existing=previousValue(entry.getKey());
                if(existing==null)writes.put(entry.getKey(),entry.getValue());
                else if(!Arrays.equals(existing,entry.getValue())) {
                    if(tag(entry.getKey(),"CF") || tag(entry.getKey(),"RS") || tag(entry.getKey(),"U"))
                        throw new IllegalStateException("Conflicting content-addressed body record");
                    writes.put(entry.getKey(),entry.getValue());
                }
            }
            for(var entry:pending.entrySet())if(machine(entry.getKey()) || tag(entry.getKey(),"S") || tag(entry.getKey(),"ST")) {
                var existing=store.get(entry.getKey());
                if(existing==null)writes.put(entry.getKey(),entry.getValue());
                else if(!Arrays.equals(existing,entry.getValue()))throw new IllegalStateException("Shared derivation content changed");
            }
            writes.forEach(store::put);store.flush();store.sync();store.putBodiesRoot(project,result.encode());
            root=finished;committed=true;pending.clear();return result;
        }
    }

    private byte[] previousValue(byte[] key) {
        if(previous==null)return null;
        if(tag(key,"U")) {
            var aci=Identity.of(Arrays.copyOfRange(key,2,key.length));
            return rooted(previous.bodiesRoot(),LocalStore.resultKey(aci))==null?null:store.get(key);
        }
        if(tag(key,"RS") || tag(key,"CF"))return rooted(previous.bodiesRoot(),key);
        // A prior interrupted publication may have changed a mutable C/X/OUT value without committing BROOT.
        // Never serve it through get(); fresh publication can repair it after proving the old key's membership.
        return tree.get(previous.bodiesRoot(),this::node,key)==null?null:store.get(key);
    }
    private Root apply(Root base,List<Entry> removed,List<Entry> added) {
        var sum=base.sum();for(var entry:removed)sum=tree.sums().subtract(sum,entry.h());
        for(var entry:added)sum=tree.sums().add(sum,entry.h());
        var result=tree.apply(base,removed.stream().map(Entry::key).toList(),added,this::node,this);
        if(!result.sum().equals(sum))throw new IllegalStateException("Bodies tree conservation failed");return result;
    }
    private Root root(Identity hash) {
        var bytes=node(hash);if(!tree.digest().hash(bytes).equals(hash))throw new IllegalStateException("Root node digest mismatch");
        var sum=tree.sums().zero();int count=0;int level=Node.level(bytes);
        if(level==0)for(var entry:Node.entries(bytes,tree.digest().width())) {sum=tree.sums().add(sum,entry.h());count++;}
        else for(var child:Node.children(bytes,tree.digest().width())) {sum=tree.sums().add(sum,child.sum());count=Math.addExact(count,child.count());}
        return new Root(hash,sum,count,level);
    }
    private boolean machine(byte[] key) { return key.length==tree.digest().width()+1 && (key[0]=='N' || key[0]=='L')
            || tag(key,"AL") || Arrays.equals(key,MachineStore.ROOT_KEY); }
    private static boolean body(byte[] key) { return tag(key,"C") || tag(key,"RS") || tag(key,"CF") || tag(key,"OUT")
            || starts(key,"X|G|") || starts(key,"X|D|"); }
    private static boolean tag(byte[] key,String tag) {return starts(key,tag+"|");}
    private static boolean starts(byte[] key,String prefix) {
        var bytes=prefix.getBytes(StandardCharsets.US_ASCII);
        return key.length>=bytes.length && Arrays.equals(key,0,bytes.length,bytes,0,bytes.length);
    }
    private void open() { if(committed)throw new IllegalStateException("Bodies generation already committed"); }
    @Override public void forEachKey(byte[] prefix,java.util.function.Consumer<byte[]> action) {
        throw new UnsupportedOperationException("Plan records through the selected root");
    }
    @Override public void putLeaf(Identity id,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putAnnotationLeaf(Identity id,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putPath(String path,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putRoot(byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putLocalRoot(Identity project,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write LOCAL");}
    @Override public void putBodiesRoot(Identity project,byte[] bytes) {throw new UnsupportedOperationException("Use commit");}
    @Override public void sync() {throw new UnsupportedOperationException("Use commit");}
    @Override public boolean hasRoot() {return store.hasRoot();}
}
