package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.*;

/**
 * Scope-local candidate/validation primitive over a previously accepted BROOT. The caller supplies
 * direct source/options/processor-input candidates and attributes invalid selections before publication.
 * Untouched receipts stay at their original bindings. This is not a filesystem watcher or a publisher.
 */
public final class BodyValidation {
    public static final class Work {
        public final ProofIndex.Work proofs=new ProofIndex.Work();
        public final ReverseIndex.Work reverse=new ReverseIndex.Work();
        public long setupReads, setupBytes, setupNodeReads, receipts, receiptBytes, transitions, directUnits, fixedInputRejections;
        public long admissionUnits, admissionNodeReads, admissionBytes;
    }
    public record Checked(ProofIndex previous, boolean reusable) { }
    private final ContentTree tree;
    private final LocalStore store;
    private final Identity project;
    private final String module;
    private final int scope;
    private final Work work;
    private final byte[][] anchorKeys, anchorValues;
    private final BodiesRoot previous;
    private final ProofIndex.Binding after;
    private final Map<ProofIndex.Binding,ProofIndex.Transition> transitions=new HashMap<>();
    private final Set<SourceUnit> candidates;

    /**
     * Direct must include the complete non-T/N/D/M input delta (source,
     * generated/configuration inputs, compiler/options and processor admission), not a file inventory.
     * This primitive does not infer those changes or certify units omitted from that caller-owned set.
     * A scope must already have an accepted cold/incremental baseline. Missing or incompatible baselines
     * require cold attribution, not an inferred empty candidate set.
     */
    public BodyValidation(ContentTree tree, LocalStore store, Identity project, String module, int scope,
                          Collection<SourceUnit> direct, Work work) {
        var bridge=NativeReaderCapture.bridgeStatus();
        if(bridge!=null)throw new IllegalStateException("Unsupported compiler reuse: "+bridge);
        this.tree=tree;this.store=store;this.project=project;this.module=module;this.scope=scope;this.work=Objects.requireNonNull(work);
        new SourceUnit(module,scope,"");
        // Monotone publication sequences also detect a root ABA during current-X lookup.
        anchorKeys=new byte[][]{LocalStore.bodiesSequenceKey(project),LocalStore.localSequenceKey(project),
                LocalStore.bodiesRootKey(project),LocalStore.localRootKey(project),MachineStore.ROOT_KEY};
        anchorValues=Arrays.stream(anchorKeys).map(this::record).toArray(byte[][]::new);
        if(anchorValues[2]==null || anchorValues[3]==null)throw new IllegalStateException("Body validation needs an accepted baseline");
        previous=BodiesRoot.decode(anchorValues[2],tree.digest().width());
        var local=LocalRoot.decode(tree.digest(),anchorValues[3]);
        if(!previous.format().equals(BodiesRoot.format(local.format())))throw new IllegalStateException("Incompatible body baseline");
        var before=binding(previous.localRoot());after=binding(local.local().hash());
        var selected=new TreeSet<SourceUnit>();
        for(var unit:direct) {requireScope(unit);selected.add(unit);}
        work.directUnits+=selected.size();
        selectUnadmitted(selected);
        selected.addAll(ReverseIndex.bodyCandidates(store,transition(before),project,module,scope,work.reverse));
        candidates=Collections.unmodifiableSet(selected);
        checkCurrent();
    }

    public Set<SourceUnit> candidates() {return candidates;}
    public ProofIndex.Binding binding() {return after;}

    private void selectUnadmitted(Set<SourceUnit> selected) {
        var value=rooted(previous.bodiesRoot(),LocalStore.bodyAdmissionKey(project));
        if(value==null) {
            if(rooted(previous.bodiesRoot(),LocalStore.bodySelectionKey(project))!=null)
                throw new IllegalStateException("Body admission selector missing; cold attribution required");
            return; // no selected units
        }
        var root=DefinerIndex.decodeRoot(value,tree.digest().width());
        if(root.count()==0)return;
        var prefix=new dev.jvmd.core.tree.Codec.Writer().zstr(module).u8(scope).toBytes();
        tree.forEach(root.hash(),hash->{
            var bytes=record(MachineStore.nodeKey(hash));work.admissionNodeReads++;
            if(bytes!=null)work.admissionBytes+=bytes.length;return bytes;
        },prefix,entry->{
            var in=new dev.jvmd.core.tree.Codec.Reader(entry.key());var unit=SourceUnit.decode(in);
            if(in.remaining()!=0 || entry.value().length!=tree.digest().width())
                throw new IllegalStateException("Invalid body admission selector");
            requireScope(unit);selected.add(unit);work.admissionUnits++;
        });
    }

    /** Only candidates are opened. The original receipt's binding, never just the last step, anchors validation. */
    public Checked check(SourceUnit unit, ProofIndex.Inputs inputs, ProcessorRecords.Context processor, ProcessorRecords.Body observations) {
        requireScope(unit);
        if(!candidates.contains(unit))throw new IllegalArgumentException("Unit is not in the candidate set");
        checkCurrent();
        var bytes=rooted(previous.bodiesRoot(),LocalStore.proofIndexKey(project,unit));
        work.receipts++;
        if(bytes==null)return new Checked(null,false);
        work.receiptBytes+=bytes.length;
        var index=ProofIndex.decode(bytes,tree.digest().width());
        // A changed fixed input already requires attribution. Preserve the old receipt for output
        // bookkeeping, but do not open its historical binding or replay processor validation.
        boolean eligible=index.eligible(inputs);
        if(!eligible)work.fixedInputRejections++;
        boolean valid=eligible && index.advance(transition(index.binding()),inputs,processor,observations)!=null;
        checkCurrent();
        return new Checked(index,valid);
    }

    /** Call before using a plan; BodyGeneration additionally checks its own roots at commit. */
    public void checkCurrent() {
        for(int i=0;i<anchorKeys.length;i++)if(!Arrays.equals(anchorValues[i],record(anchorKeys[i])))
            throw new IllegalStateException("Committed roots changed during body validation");
    }
    private void requireScope(SourceUnit unit) {
        if(!unit.module().equals(module) || unit.scope()!=scope || unit.path().isEmpty())
            throw new IllegalArgumentException("Expected a source in the selected scope");
    }
    private ProofIndex.Transition transition(ProofIndex.Binding before) {
        return transitions.computeIfAbsent(before,b->{
            work.transitions++;return ProofIndex.Transition.between(tree,b,after,store::get,work.proofs);
        });
    }
    private ProofIndex.Binding binding(Identity root) {
        var source=rooted(root,LocalStore.sourceLeafKey(project,module,scope));
        var route=rooted(root,LocalStore.routeKey(project,module,scope));
        if(source==null || route==null)throw new IllegalStateException("Body validation needs an existing scope binding");
        var own=SourceLeaf.decode(source,tree.digest().width());
        var leaf=MachineLeaf.decode(record(MachineStore.leafKey(own.k())),tree.digest().width());
        return ProofIndex.Binding.capture(tree,leaf,Route.decode(route,tree.digest().width()),this::record);
    }
    private byte[] rooted(Identity root,byte[] key) {
        var entry=tree.get(root,id->{work.setupNodeReads++;return record(MachineStore.nodeKey(id));},key);
        return entry==null?null:RootedRecords.value(tree,this::record,entry);
    }
    private byte[] record(byte[] key) {
        var bytes=store.get(key);work.setupReads++;if(bytes!=null)work.setupBytes+=bytes.length;return bytes;
    }
}
