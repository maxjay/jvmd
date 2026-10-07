package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/** Streams provisional immutable content; only selected references become reachable at BROOT. */
public final class BodyGeneration implements LocalStore {
    private final ContentTree tree;
    private final LocalStore store;
    private final Identity project;
    private final LocalRoot local;
    private final byte[] localBytes, previousBytes, machineBytes;
    private final BodiesRoot previous;
    private final Map<byte[],Entry> pending = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
    private final NodeSink nodes;
    private boolean committed;
    private Root root;

    private BodyGeneration(ContentTree tree, LocalStore store, Identity project, LocalRoot local) {
        this.tree=tree; this.store=store; this.project=project; this.local=local;
        localBytes=store.get(LocalStore.localRootKey(project));
        if(localBytes==null || !LocalRoot.decode(tree.digest(),localBytes).equals(local))
            throw new IllegalStateException("LOCAL root changed before body planning");
        previousBytes=store.get(LocalStore.bodiesRootKey(project));
        machineBytes=store.get(MachineStore.ROOT_KEY);
        previous=previousBytes==null || !LocalRoot.formatOf(previousBytes).equals(BodiesRoot.format(local.format()))
                ? null : BodiesRoot.decode(previousBytes,tree.digest().width());
        nodes=new dev.jvmd.boot.cold.stage1.Written().throughShared(store);
    }
    public static BodyGeneration begin(ContentTree tree,LocalStore store,Identity project,LocalRoot local) {
        return new BodyGeneration(tree,store,project,local);
    }
    public Root root() { if(!committed)throw new IllegalStateException("Bodies are not committed");return root; }

    public byte[] local(byte[] key) {
        var entry=tree.get(local.local().hash(),this::node,key);
        return entry==null?null:local(entry);
    }
    /** The caller obtained this witness from the selected LOCAL traversal. */
    byte[] local(Entry entry) {
        return RootedRecords.value(tree,store::get,entry);
    }
    /** Small immutable selection reference; does not read its payload. */
    public Entry reference(byte[] key) {
        open();
        var entry=pending.get(key);
        if(entry!=null)return entry;
        if(previous==null || tag(key,"U"))return null;
        return tree.get(previous.bodiesRoot(),this::node,key);
    }
    public Entry record(byte[] key,byte[] value) { put(key,value);return pending.get(key); }

    /** Previous references for one changed unit; callers need not scan the bodies inventory or fetch class bytes. */
    public List<Entry> previousUnit(SourceUnit unit) {
        open();
        if(previous==null)return List.of();
        var value=BodyRecords.read(tree,store,previous.bodiesRoot(),LocalStore.bodySelectionKey(project));
        if(value==null)return List.of();
        var state=BodySelection.State.decode(value,tree.digest().width());
        var entry=tree.get(state.units().hash(),this::node,unit.encode());
        if(entry==null)return List.of();
        var manifest=DefinerIndex.decodeRoot(entry.value(),tree.digest().width());
        var entries=new ArrayList<Entry>();tree.forEach(manifest.hash(),this::node,entries::add);
        return List.copyOf(entries);
    }

    @Override public byte[] get(byte[] key) {
        open();
        var entry=pending.get(key);
        if(entry!=null)return BodyRecords.value(tree,store,entry);
        if(machine(key) || tag(key,"S") || tag(key,"ST") || tag(key,"PROC") || tag(key,"BV") || tag(key,"PE"))return store.get(key);
        if(!body(key) && !tag(key,"U"))return local(key);
        if(previous==null)return null;
        if(tag(key,"U")) {
            if(key.length!=2+tree.digest().width())throw new IllegalArgumentException("Invalid uses key");
            var result=LocalStore.resultKey(Identity.of(Arrays.copyOfRange(key,2,key.length)));
            return tree.get(previous.bodiesRoot(),this::node,result)==null?null:store.get(key);
        }
        return BodyRecords.read(tree,store,previous.bodiesRoot(),key);
    }
    private byte[] node(Identity hash) {
        var bytes=store.get(MachineStore.nodeKey(hash));
        if(bytes==null)throw new IllegalStateException("Missing tree node");return bytes;
    }
    @Override public synchronized void put(byte[] key,byte[] value) {
        open();
        if(tag(key,"S") || tag(key,"ST")) { store.put(key,value);store.flush();return; }
        if(!body(key) && !tag(key,"U"))throw new IllegalArgumentException("Not a body record or shared stub");
        var hash=tree.digest().hash(value);
        if(tag(key,"CF") && (key.length!=3+tree.digest().width()
                || !hash.equals(Identity.of(Arrays.copyOfRange(key,3,key.length)))))
            throw new IllegalArgumentException("Class bytes do not match their content key");
        var entry=new Entry(key.clone(),Entry.NONE,hash);
        var earlier=pending.get(key);
        if(earlier!=null) {
            if(!earlier.h().equals(hash))throw new IllegalStateException("Conflicting body records in one generation");
            return;
        }
        var prior=reference(key);
        if(tag(key,"U")) {
            var uses=getPreviousUses(key);
            if(uses!=null) {
                if(!tree.digest().hash(uses).equals(hash))throw new IllegalStateException("Conflicting content-addressed uses record");
                prior=entry;
            }
        }
        if(prior!=null && (tag(key,"RS") || tag(key,"CF")) && !prior.h().equals(hash))
            throw new IllegalStateException("Conflicting content-addressed body record");
        if(prior==null || !prior.h().equals(hash)) {
            if(!ReverseIndex.isBodyKey(key))store.put(tag(key,"CF")?key:LocalStore.bodyValueKey(hash),value);
            // Flush before sharing the reference across workers. Class payloads never enter a project-sized buffer.
            store.flush();
        }
        pending.put(entry.key(),entry);
    }
    @Override public void observeProcessor(Identity path,String processor,ProcessorRecords.Capability observation) {
        open();store.observeProcessor(path,processor,observation);
    }
    /** Constructors own node encoding/hashing. Run-scoped deduplication needs no per-node storage probe. */
    @Override public synchronized void write(Node node) { open();nodes.write(node);nodes.flush(); }
    @Override public void flush() { open();nodes.flush(); }

    /** Compatibility cold selection; fresh values become immutable references before publication. */
    public BodiesRoot commit(Map<byte[],byte[]> selected) {
        var references=new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
        selected.forEach((key,value)->references.put(key,record(key,value)));
        return commitReferences(references);
    }
    /** Compatibility complete selection, represented as one explicit compilation selection. */
    public synchronized BodiesRoot commitReferences(Map<byte[],Entry> selected) {
        selected.forEach((key,entry)->{if(!Arrays.equals(key,entry.key()))throw new IllegalArgumentException("Invalid body selection");});
        return commitUnitsFull(selected.isEmpty()?Map.of():Map.of(new SourceUnit("",0,""),selected.values()));
    }
    /** Cold callers select every unit; only this entry point enumerates prior unit names to detect removed units. */
    public synchronized BodiesRoot commitUnitsFull(Map<SourceUnit,? extends Collection<Entry>> selected) {
        return commitUnits(selected,Set.of(),true);
    }
    /** Delta callers supply changed and retired units only. An empty path is reserved for a scope's OUT selection. */
    public synchronized BodiesRoot commitUnitsDelta(Map<SourceUnit,? extends Collection<Entry>> changed,Set<SourceUnit> retired) {
        return commitUnits(changed,retired,false);
    }
    private BodiesRoot commitUnits(Map<SourceUnit,? extends Collection<Entry>> changed,Set<SourceUnit> retired,boolean complete) {
        open();
        checkRoots();
        for(var entries:changed.values())for(var entry:entries) {
            if((!body(entry.key()) && !tag(entry.key(),"U")) || tag(entry.key(),"BM"))throw new IllegalArgumentException("Invalid body selection");
            var available=reference(entry.key());
            if(available==null || !available.h().equals(entry.h()))throw new IllegalArgumentException("Unverified body reference");
        }
        var selectionKey=LocalStore.bodySelectionKey(project);
        var oldSelection=previous==null?null:tree.get(previous.bodiesRoot(),this::node,selectionKey);
        var state=oldSelection==null?null:BodySelection.State.decode(BodyRecords.value(tree,store,oldSelection),tree.digest().width());
        var admissionKey=LocalStore.bodyAdmissionKey(project);
        var oldAdmission=previous==null?null:tree.get(previous.bodiesRoot(),this::node,admissionKey);
        if(!complete && oldSelection!=null && oldAdmission==null)
            throw new IllegalStateException("Body admission selector missing; cold attribution required");
        var admissionBefore=oldAdmission==null?null:DefinerIndex.decodeRoot(BodyRecords.value(tree,store,oldAdmission),tree.digest().width());
        var delta=BodySelection.apply(tree,this::node,this,state,changed,retired,complete);
        var admission=BodyAdmission.apply(tree,this::node,this,admissionBefore,changed,retired,complete);
        var removed=new ArrayList<>(delta.removed());var added=new ArrayList<>(delta.added());
        var selectionBytes=delta.state().units().count()==0?null:delta.state().encode();
        Entry selection=selectionBytes==null?null:record(selectionKey,selectionBytes);
        if(oldSelection==null?selection!=null:selection==null || !oldSelection.h().equals(selection.h())) {
            if(oldSelection!=null)removed.add(oldSelection);
            if(selection!=null)added.add(selection);
        }
        var admissionBytes=delta.state().units().count()==0?null:DefinerIndex.encodeRoot(admission);
        Entry admissionSelection=admissionBytes==null?null:record(admissionKey,admissionBytes);
        if(oldAdmission==null?admissionSelection!=null:admissionSelection==null || !oldAdmission.h().equals(admissionSelection.h())) {
            if(oldAdmission!=null)removed.add(oldAdmission);
            if(admissionSelection!=null)added.add(admissionSelection);
        }
        synchronized(store) {
            checkRoots();
            Root base=local.local();
            if(previous!=null) {
                var beforeLocal=tree.root(previous.localRoot(),this::node);var beforeBodies=tree.root(previous.bodiesRoot(),this::node);
                var stage2=Diff.trees(tree.digest(),beforeLocal,local.local(),this::node);
                base=apply(beforeBodies,stage2.removed(),stage2.added());
            }
            var finished=apply(base,removed,added);
            var result=new BodiesRoot(BodiesRoot.format(local.format()),finished.hash(),local.local().hash(),local.machineRoot(),local.modelHash());
            var bindings=new ArrayList<byte[][]>();var addedKeys=new TreeSet<byte[]>(Arrays::compareUnsigned);
            for(var entry:added)addedKeys.add(entry.key());
            for(var entry:removed)if((tag(entry.key(),"C") || tag(entry.key(),"OUT") || tag(entry.key(),"BM")) && !addedKeys.contains(entry.key()))
                bindings.add(new byte[][]{entry.key(),null});
            for(var entry:added)if(!tag(entry.key(),"CF") && !ReverseIndex.isBodyKey(entry.key()))
                bindings.add(new byte[][]{entry.key(),Arrays.equals(entry.key(),selectionKey)?selectionBytes
                        :Arrays.equals(entry.key(),admissionKey)?admissionBytes:BodyRecords.value(tree,store,entry)});
            for(var entry:delta.uses()) {
                var value=BodyRecords.value(tree,store,entry);
                var existing=getPreviousUses(entry.key());
                if(existing!=null && !Arrays.equals(existing,value))throw new IllegalStateException("Conflicting content-addressed uses record");
                if(existing==null)bindings.add(new byte[][]{entry.key(),value});
            }
            if(previous==null || !Arrays.equals(result.encode(),previousBytes) || !bindings.isEmpty()) {
                flush();store.sync();
                store.putBodiesRoot(tree.digest(),project,new BodyCommit(result.encode(),previousBytes,localBytes,machineBytes,List.copyOf(bindings)));
            }
            root=finished;committed=true;pending.clear();return result;
        }
    }
    private void checkRoots() {
        if(!Arrays.equals(localBytes,store.get(LocalStore.localRootKey(project)))
                || !Arrays.equals(previousBytes,store.get(LocalStore.bodiesRootKey(project)))
                || !Arrays.equals(machineBytes,store.get(MachineStore.ROOT_KEY)))
            throw new IllegalStateException("Committed roots changed during body attribution");
    }
    private byte[] getPreviousUses(byte[] key) {
        if(previous==null)return null;
        var aci=Identity.of(Arrays.copyOfRange(key,2,key.length));
        return tree.get(previous.bodiesRoot(),this::node,LocalStore.resultKey(aci))==null?null:store.get(key);
    }
    private Root apply(Root base,List<Entry> removed,List<Entry> added) {
        // Conservation is independently tested, not recomputed on the production hot path.
        return tree.apply(base,removed.stream().map(Entry::key).toList(),added,this::node,this);
    }
    private boolean machine(byte[] key) { return key.length==tree.digest().width()+1 && (key[0]=='N' || key[0]=='L')
            || tag(key,"AL") || Arrays.equals(key,MachineStore.ROOT_KEY); }
    private static boolean body(byte[] key) { return BodyRecords.isBody(key); }
    private static boolean tag(byte[] key,String tag) {return BodyRecords.tag(key,tag);}
    private void open() { if(committed)throw new IllegalStateException("Bodies generation already committed"); }
    @Override public void forEachKey(byte[] prefix,java.util.function.Consumer<byte[]> action) {throw new UnsupportedOperationException("Plan records through the selected root");}
    @Override public void putLeaf(Identity id,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putAnnotationLeaf(Identity id,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putPath(String path,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putRoot(byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write MACHINE");}
    @Override public void putLocalRoot(dev.jvmd.core.hash.Digest digest,Identity project,byte[] bytes) {throw new UnsupportedOperationException("Stage 3 does not write LOCAL");}
    @Override public void putBodiesRoot(dev.jvmd.core.hash.Digest digest,Identity project,BodyCommit commit) {throw new UnsupportedOperationException("Use commit");}
    @Override public void sync() {throw new UnsupportedOperationException("Use commit");}
    @Override public boolean hasRoot() {return store.hasRoot();}
}
