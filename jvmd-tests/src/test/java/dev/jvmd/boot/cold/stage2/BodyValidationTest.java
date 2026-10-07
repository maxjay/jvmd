package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.BodyGeneration;
import dev.jvmd.boot.cold.stage3.BodyValidation;
import dev.jvmd.core.hash.*;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import dev.jvmd.index.rocks.layer.Generation;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Candidate join, rooted receipt ancestry, current-X churn and actual Rocks close/reopen. */
@Tag("phase-3")
class BodyValidationTest {
    @TempDir java.nio.file.Path directory;
    static Stream<Digest> digests() {return Stream.of(Sha256.INSTANCE,new Digests.Sha3());}
    static final SourceUnit USE=new SourceUnit("app",0,"src/Use.java");
    static final class Fixture extends ProofIndexTest.Fixture {
        final Identity project;
        Fixture(Digest digest) {super(digest);project=digest.hash(new byte[]{4});}
        ProofIndexTest.State state(int value,int other) {
            return state(leaf(List.of(fact("p/Api",Keys.TYPE,"",1),fact("p/Api",Keys.FIELD,"VALUE",value),
                    fact("p/Api",Keys.FIELD,"OTHER",other))),List.of());
        }
        LocalRoot publish(ProofIndexTest.State state) {return publish(store,state);}
        LocalRoot publish(LocalStore target,ProofIndexTest.State state) {
            var values=new TreeMap<byte[],byte[]>(Arrays::compareUnsigned);
            values.put(LocalStore.sourceLeafKey(project,"app",0),new SourceLeaf(state.own().k(),empty.hash()).encode());
            values.put(LocalStore.routeKey(project,"app",0),state.route().encode());
            var entries=new ArrayList<Entry>();
            values.forEach((key,value)->{target.put(LocalStore.bodyValueKey(digest.hash(value)),value);entries.add(new Entry(key,Entry.NONE,digest.hash(value)));});
            var root=tree.build(entries,target);target.flush();
            var bytes=LocalRoot.encode(digest,"fixture;local=22",root,project,project);
            target.putLocalRoot(digest,project,bytes);return LocalRoot.decode(digest,bytes);
        }
        List<Entry> select(BodyGeneration generation,SourceUnit unit,ProofIndexTest.State state,String name) {
            var proof=proof(state,List.of(ProofIndexTest.t("p/Api",Keys.FIELD,name)),List.of());
            var index=index(state,proof);var entries=new ArrayList<Entry>();
            entries.add(generation.record(LocalStore.proofIndexKey(project,unit),index.encode()));
            for(var q:ReverseIndex.dependencies(proof))entries.add(generation.record(ReverseIndex.bodyKey(q,project,unit),Entry.NONE));
            return entries;
        }
        BodyValidation plan(LocalStore target,Collection<SourceUnit> direct,BodyValidation.Work work) {
            return new BodyValidation(tree,target,project,"app",0,direct,work);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void reverseJoinWorkDependsOnCurrentFanoutNotUnrelatedFilesOrRetiredHistory(Digest digest) {
        for(boolean affected:List.of(false,true))for(int population:new int[]{64,512,4096}) {
            var f=new Fixture(digest);var a=f.state(1,1);var b=f.state(affected?2:1,affected?1:2);
            var local=f.publish(a);var generation=BodyGeneration.begin(f.tree,f.store,f.project,local);
            var selected=new TreeMap<SourceUnit,List<Entry>>();var retired=new TreeSet<SourceUnit>();
            selected.put(USE,f.select(generation,USE,a,"VALUE"));
            var other=f.proof(a,List.of(ProofIndexTest.t("p/Api",Keys.METHOD,"unread")),List.of());
            var otherIndex=f.index(a,other);
            for(int i=0;i<population;i++) {
                var unit=new SourceUnit("app",0,"src/"+i+"/Use.java");
                selected.put(unit,List.of(generation.record(LocalStore.proofIndexKey(f.project,unit),otherIndex.encode()),
                        generation.record(ReverseIndex.bodyKey(ReverseIndex.dependencies(other).iterator().next(),f.project,unit),Entry.NONE)));
                var stale=new SourceUnit("app",0,"retired/"+i+"/Use.java");retired.add(stale);
                selected.put(stale,f.select(generation,stale,a,affected?"VALUE":"OTHER"));
            }
            var historical=generation.commitUnitsFull(selected);
            BodyGeneration.begin(f.tree,f.store,f.project,local).commitUnitsDelta(Map.of(),retired);
            f.publish(b);
            var work=new BodyValidation.Work();var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
            long writtenNodes=f.store.nodeWriteCount(),writtenRecords=f.store.recordWriteCount();
            long allocated=bean.getCurrentThreadAllocatedBytes();
            var plan=f.plan(f.store,List.of(),work);
            if(affected) {
                assertThat(plan.candidates()).containsExactly(USE);
                assertThat(plan.check(USE,f.inputs,null,null).reusable()).isFalse();
            } else assertThat(plan.candidates()).isEmpty();
            allocated=bean.getCurrentThreadAllocatedBytes()-allocated;
            assertThat(f.store.nodeWriteCount()).isEqualTo(writtenNodes);assertThat(f.store.recordWriteCount()).isEqualTo(writtenRecords);
            assertThat(work.receipts).isEqualTo(affected?1:0);
            assertThat(work.reverse.hits).isEqualTo(affected?1:0);
            assertThat(work.transitions).isOne();assertThat(work.proofs.proofEntries).isEqualTo(affected?1:0);
            // Historical membership remains rooted, but cannot enter the current prefix lookup.
            assertThat(f.tree.get(historical.bodiesRoot(),h->f.store.get(MachineStore.nodeKey(h)),
                    ReverseIndex.bodyKey(new ReverseIndex.Dependency(ReverseIndex.T,"p/Api",Keys.FIELD,affected?"VALUE":"OTHER"),f.project,retired.first()))).isNotNull();
            System.out.printf(Locale.ROOT,"F05-CANDIDATES %s affected=%s files=%d retired=%d prefixes=%d hits=%d keyBytes=%d receipts=%d receiptBytes=%d setupReads=%d setupBytes=%d setupNodes=%d prepareReads=%d prepareBytes=%d proofNodes=%d allocated=%d writes=0%n",
                    digest.name(),affected,population+1,population,work.reverse.prefixes,work.reverse.hits,work.reverse.keyBytes,work.receipts,work.receiptBytes,
                    work.setupReads,work.setupBytes,work.setupNodeReads,work.proofs.recordReads,work.proofs.recordBytes,work.proofs.proofNodeReads,allocated);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void retainedReceiptsUseTheirActualOldBindingAfterTwoPublicationsAndRocksReopen(Digest digest) {
        var f=new Fixture(digest);var a=f.state(1,1);var b=f.state(1,2);var c=f.state(2,2);
        // Seed immutable MACHINE derivations only; publish both rooted states through the real store.
        var disk=Generation.of(directory,Format.of(digest,Runtime.version().feature()));
        try(var machine=disk.create()) {machine.putRoot(new byte[]{1});}
        byte[] original;
        try(var store=disk.openLocal()) {
            f.store.snapshot().forEach(store::put);store.flush();
            var local=f.publish(store,a);var gen=BodyGeneration.begin(f.tree,store,f.project,local);
            var entries=f.select(gen,USE,a,"VALUE");f.store.flush();
            // capture() above emits its immutable query nodes into the fixture sink.
            f.store.snapshot().forEach((key,value)->{if(key.length==digest.width()+1 && key[0]=='N')store.put(key,value);});store.flush();
            var cold=gen.commitUnitsFull(Map.of(USE,entries));
            original=BodyRecords.read(f.tree,store,cold.bodiesRoot(),LocalStore.proofIndexKey(f.project,USE));
            local=f.publish(store,b);
            var plan=f.plan(store,List.of(),new BodyValidation.Work());assertThat(plan.candidates()).isEmpty();
            BodyGeneration.begin(f.tree,store,f.project,local).commitUnitsDelta(Map.of(),Set.of());
        }
        try(var store=disk.openLocal()) {
            var selected=BodiesRoot.decode(store.get(LocalStore.bodiesRootKey(f.project)),digest.width());
            assertThat(BodyRecords.read(f.tree,store,selected.bodiesRoot(),LocalStore.proofIndexKey(f.project,USE))).isEqualTo(original);
            f.publish(store,c);var work=new BodyValidation.Work();var plan=f.plan(store,List.of(USE,USE),work);
            assertThat(plan.candidates()).containsExactly(USE);var result=plan.check(USE,f.inputs,null,null);
            assertThat(result.reusable()).isFalse();assertThat(result.previous().binding()).isEqualTo(f.binding(a));
            assertThat(work.transitions).isEqualTo(2); // B->C discovery, A->C receipt validation
            assertThat(work.directUnits).isOne();assertThat(work.receipts).isOne();
            assertThat(BodyRecords.read(f.tree,store,selected.bodiesRoot(),LocalStore.proofIndexKey(f.project,USE))).isEqualTo(original);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void fixedInputChangesRejectRetainedReceiptsBeforeHistoricalPreparation(Digest digest) {
        var f=new Fixture(digest);var a=f.state(1,1);var b=f.state(1,2);var c=f.state(1,3);
        var local=f.publish(a);var gen=BodyGeneration.begin(f.tree,f.store,f.project,local);
        var cold=gen.commitUnitsFull(Map.of(USE,f.select(gen,USE,a,"VALUE")));
        var original=BodyRecords.read(f.tree,f.store,cold.bodiesRoot(),LocalStore.proofIndexKey(f.project,USE));
        local=f.publish(b);
        BodyGeneration.begin(f.tree,f.store,f.project,local).commitUnitsDelta(Map.of(),Set.of());
        f.publish(c);
        var changed=digest.hash(new byte[]{97});
        for(var inputs:List.of(
                new ProofIndex.Inputs("Renamed.java",f.inputs.source(),f.inputs.options()),
                new ProofIndex.Inputs(f.inputs.basename(),changed,f.inputs.options()),
                new ProofIndex.Inputs(f.inputs.basename(),f.inputs.source(),changed),
                new ProofIndex.Inputs(f.inputs.basename(),f.inputs.source(),f.inputs.options(),"other compiler"))) {
            var work=new BodyValidation.Work();var plan=f.plan(f.store,List.of(USE),work);
            long reads=work.proofs.recordReads,writes=f.store.recordWriteCount(),nodes=f.store.nodeWriteCount();
            long setupReads=work.setupReads,setupBytes=work.setupBytes;
            var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
            long allocated=bean.getCurrentThreadAllocatedBytes();
            var checked=plan.check(USE,inputs,null,null);
            allocated=bean.getCurrentThreadAllocatedBytes()-allocated;
            assertThat(checked.reusable()).isFalse();
            assertThat(checked.previous().binding()).isEqualTo(f.binding(a));
            assertThat(checked.previous().encode()).isEqualTo(original);
            assertThat(work.transitions).isOne(); // B->C discovery only, no A->C preparation
            assertThat(work.fixedInputRejections).isOne();
            assertThat(work.proofs.recordReads).isEqualTo(reads);
            assertThat(work.proofs.proofNodeReads).isZero();
            assertThat(f.store.recordWriteCount()).isEqualTo(writes);
            assertThat(f.store.nodeWriteCount()).isEqualTo(nodes);
            System.out.printf(Locale.ROOT,"Q15 %s basenameChanged=%s sourceChanged=%s optionsChanged=%s compilerChanged=%s transitions=%d historicalReads=%d proofNodes=%d selectedReads=%d selectedBytes=%d receiptBytes=%d allocated=%d writes=0%n",
                    digest.name(),!inputs.basename().equals(f.inputs.basename()),!inputs.source().equals(f.inputs.source()),
                    !inputs.options().equals(f.inputs.options()),!inputs.compiler().equals(f.inputs.compiler()),work.transitions,
                    work.proofs.recordReads-reads,work.proofs.proofNodeReads,work.setupReads-setupReads,work.setupBytes-setupBytes,work.receiptBytes,allocated);
        }
        // Matching inputs still require A->C, despite the later accepted B baseline.
        var work=new BodyValidation.Work();var plan=f.plan(f.store,List.of(USE),work);
        assertThat(plan.check(USE,f.inputs,null,null).reusable()).isTrue();
        assertThat(work.transitions).isEqualTo(2);
        assertThat(work.fixedInputRejections).isZero();
    }

    @ParameterizedTest @MethodSource("digests")
    void pureProviderOrderChangeReachesPositiveAndMemberAbsenceConsumers(Digest digest) {
        var f=new Fixture(digest);var empty=f.leaf(List.of());
        var first=f.leaf(List.of(f.fact("q/Base",Keys.TYPE,"",1),f.fact("q/Base",Keys.FIELD,"VALUE",1)));
        var second=f.leaf(List.of(f.fact("q/Base",Keys.TYPE,"",1),f.fact("q/Base",Keys.FIELD,"VALUE",2),f.fact("q/Base$Inner",Keys.TYPE,"",1)));
        var a=f.state(empty,List.of(first,second));var b=f.state(empty,List.of(second,first));
        var local=f.publish(a);var gen=BodyGeneration.begin(f.tree,f.store,f.project,local);
        var selections=new TreeMap<SourceUnit,List<Entry>>();
        for(var range:List.of(ProofIndexTest.t("q/Base",Keys.FIELD,"VALUE"),ProofIndexTest.n("q/Base","Inner"))) {
            var unit=new SourceUnit("app",0,"src/"+range.form()+"/Use.java");
            var proof=f.proof(a,List.of(range),List.of());var index=f.index(a,proof);
            selections.put(unit,List.of(gen.record(LocalStore.proofIndexKey(f.project,unit),index.encode()),
                    gen.record(ReverseIndex.bodyKey(ReverseIndex.dependencies(proof).iterator().next(),f.project,unit),Entry.NONE)));
        }
        gen.commitUnitsFull(selections);f.publish(b);
        var work=new BodyValidation.Work();var plan=f.plan(f.store,List.of(),work);
        assertThat(plan.candidates()).containsExactlyElementsOf(selections.keySet());
        for(var unit:plan.candidates())assertThat(plan.check(unit,f.inputs,null,null).reusable()).isFalse();
        assertThat(work.receipts).isEqualTo(2);assertThat(work.transitions).isOne();
        assertThat(f.tree.root(first.k(),id->f.store.get(MachineStore.nodeKey(id))).hash()).isEqualTo(first.k());
    }

    @ParameterizedTest @MethodSource("digests")
    void disappearedProviderSelectsZeroQueriesAndPublicationRacesRejectThePlan(Digest digest) {
        var f=new Fixture(digest);var a=f.state(1,1);var gone=f.state(f.leaf(List.of()),List.of());
        var local=f.publish(a);var gen=BodyGeneration.begin(f.tree,f.store,f.project,local);
        var n=new SourceUnit("app",0,"src/N.java");
        var nProof=f.proof(a,List.of(ProofIndexTest.n("p/Api","Missing")),List.of());
        var nIndex=f.index(a,nProof);
        var nEntries=List.of(gen.record(LocalStore.proofIndexKey(f.project,n),nIndex.encode()),
                gen.record(ReverseIndex.bodyKey(ReverseIndex.dependencies(nProof).iterator().next(),f.project,n),Entry.NONE));
        gen.commitUnitsFull(Map.of(USE,f.select(gen,USE,a,"missing"),n,nEntries));
        var unchanged=f.plan(f.store,List.of(USE),new BodyValidation.Work());
        assertThat(unchanged.check(USE,f.inputs,null,null).reusable()).isTrue();
        assertThat(unchanged.check(USE,new ProofIndex.Inputs("Use.java",digest.hash(new byte[]{9}),f.inputs.options()),null,null).reusable()).isFalse();
        f.publish(gone);
        assertThatThrownBy(unchanged::checkCurrent).hasMessageContaining("changed");
        var work=new BodyValidation.Work();var plan=f.plan(f.store,List.of(),work);
        assertThat(plan.candidates()).containsExactlyInAnyOrder(USE,n);
        for(var unit:plan.candidates())assertThat(plan.check(unit,f.inputs,null,null).reusable()).isFalse();
        // Restore the same LOCAL bytes; the sequence still invalidates the old plan (ABA).
        f.publish(a);assertThatThrownBy(unchanged::checkCurrent).hasMessageContaining("changed");
    }
}
