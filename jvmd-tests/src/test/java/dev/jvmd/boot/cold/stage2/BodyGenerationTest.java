package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.BodyGeneration;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class BodyGenerationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }
    private static TreeMap<byte[],byte[]> records() { return new TreeMap<>(Arrays::compareUnsigned); }
    private record State(ContentTree tree,InMemoryLocalStore store,Identity project,LocalRoot local) { }
    private State local(Digest digest) {
        var tree=new ContentTree(digest);var store=new InMemoryLocalStore();var project=digest.hash(new byte[]{1});
        return local(tree,store,project,Map.of(LocalStore.fileKey(project, Stage2Support.source("A.java")),new byte[]{2}));
    }
    private State local(ContentTree tree,InMemoryLocalStore store,Identity project,Map<byte[],byte[]> values) {
        var sorted=records();sorted.putAll(values);sorted.forEach((key,value)->{
            store.put(key,value);store.put(LocalStore.bodyValueKey(tree.digest().hash(value)),value);
        });store.flush();
        var root=tree.build(sorted.entrySet().stream().map(e->new Entry(e.getKey(),Entry.NONE,tree.digest().hash(e.getValue()))).toList(),store);
        store.flush();store.sync();
        store.putLocalRoot(tree.digest(),project,LocalRoot.encode(tree.digest(),"fixture;local=11",root,project,project));
        return new State(tree,store,project,LocalRoot.decode(tree.digest(),store.get(LocalStore.localRootKey(project))));
    }
    private void sameTree(State state,Root actual,Map<byte[],byte[]> values) {
        var sorted=records();sorted.putAll(values);
        var selection=state.tree().get(actual.hash(),h->state.store().get(MachineStore.nodeKey(h)),LocalStore.bodySelectionKey(state.project()));
        if(selection!=null)sorted.put(selection.key(),BodyRecords.value(state.tree(),state.store(),selection));
        var expected=state.tree().build(sorted.entrySet().stream().map(e->new Entry(e.getKey(),Entry.NONE,state.tree().digest().hash(e.getValue()))).toList(),state.store());
        state.store().flush();assertThat(actual).isEqualTo(expected);
        state.tree().verify(actual,h->state.store().get(MachineStore.nodeKey(h)));
    }

    @ParameterizedTest @MethodSource("digests")
    void f03EveryPublicationCutPreservesAnEntireOldOrNewSnapshot(Digest digest) {
        var s=local(digest);var key=LocalStore.proofKey(s.project(),Stage2Support.source("A.java"));
        var dep=new ReverseIndex.Dependency(ReverseIndex.T,"p/T",dev.jvmd.index.layer.machine.Keys.FIELD,"x");
        var oldKey=ReverseIndex.bodyKey(dep,s.project(),Stage2Support.source("Old.java"));
        var newKey=ReverseIndex.bodyKey(dep,s.project(),Stage2Support.source("New.java"));
        var old=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(Map.of(key,new byte[]{1},oldKey,Entry.NONE));
        var operations=new java.util.ArrayList<String>();
        var baseline=s.store().copy();
        var counted=faultView(baseline,(name,after)->{if(!after)operations.add(name);});
        BodyGeneration.begin(s.tree(),counted,s.project(),s.local()).commit(Map.of(key,new byte[]{2},newKey,Entry.NONE));
        assertThat(operations).contains("put","flush","write","sync","putBodiesRoot");
        for(int cut=0;cut<operations.size();cut++)for(boolean after:new boolean[]{false,true}) {
            var store=s.store().copy();int stop=cut;var at=new java.util.concurrent.atomic.AtomicInteger();
            var fault=faultView(store,(name,finished)->{
                if(finished==after && at.getAndIncrement()==stop)throw new IllegalStateException("cut "+name);
            });
            assertThatThrownBy(()->BodyGeneration.begin(s.tree(),fault,s.project(),s.local()).commit(Map.of(key,new byte[]{2},newKey,Entry.NONE)))
                    .hasMessageStartingWith("cut ");
            var reopened=store.copy(); // drop the unfinished thread-local batch, as restart would
            var accepted=BodiesRoot.decode(reopened.get(LocalStore.bodiesRootKey(s.project())),digest.width());
            boolean isOld=Arrays.equals(accepted.encode(),old.encode());
            assertThat(BodyRecords.read(s.tree(),reopened,accepted.bodiesRoot(),key)).containsExactly(isOld?1:2);
            assertThat(BodyRecords.read(s.tree(),reopened,old.bodiesRoot(),key)).containsExactly(1);
            assertThat(reopened.get(key)).containsExactly(isOld?1:2);
            assertThat(ReverseIndex.bodyConsumers(digest,reopened,dep)).extracting(ReverseIndex.Consumer::path)
                    .containsExactly(isOld?"Old.java":"New.java");
        }
    }

    private static LocalStore faultView(LocalStore delegate,java.util.function.BiConsumer<String,Boolean> boundary) {
        return (LocalStore)java.lang.reflect.Proxy.newProxyInstance(LocalStore.class.getClassLoader(),new Class<?>[]{LocalStore.class},
                (proxy,method,args)->{
                    boolean mutation=List.of("put","write","flush","sync","putBodiesRoot").contains(method.getName());
                    if(mutation)boundary.accept(method.getName(),false);
                    Object result;
                    try {result=method.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    if(mutation)boundary.accept(method.getName(),true);
                    return result;
                });
    }

    @ParameterizedTest @MethodSource("digests")
    void f03ConcurrentPublishersCompareRootsInsideTheAtomicTransition(Digest digest) throws Exception {
        var s=local(digest);var key=LocalStore.proofKey(s.project(),Stage2Support.source("A.java"));
        var old=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(Map.of(key,new byte[]{1}));
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var commits=new java.util.concurrent.atomic.AtomicInteger();var rejected=new java.util.concurrent.atomic.AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int value:new int[]{2,3}) {
                var view=faultView(s.store(),(name,after)->{
                    if(name.equals("putBodiesRoot") && !after)try {barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);}
                    catch(Exception failure){throw new RuntimeException(failure);}
                });
                var generation=BodyGeneration.begin(s.tree(),view,s.project(),s.local());
                tasks.add(executor.submit(()->{
                    try {generation.commit(Map.of(key,new byte[]{(byte)value}));commits.incrementAndGet();}
                    catch(IllegalStateException failure) {assertThat(failure).hasMessageContaining("changed");rejected.incrementAndGet();}
                }));
            }
            for(var task:tasks)task.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(commits.get()).isOne();assertThat(rejected.get()).isOne();
        assertThat(BodyRecords.read(s.tree(),s.store(),old.bodiesRoot(),key)).containsExactly(1);
        var current=BodiesRoot.decode(s.store().get(LocalStore.bodiesRootKey(s.project())),digest.width());
        assertThat(BodyRecords.read(s.tree(),s.store(),current.bodiesRoot(),key)).isEqualTo(s.store().get(key));
    }

    @ParameterizedTest @MethodSource("digests")
    void f03InterruptedPublicationLeavesTheOldProofReadable(Digest digest) {
        var s=local(digest);var key=LocalStore.proofKey(s.project(),Stage2Support.source("A.java"));
        var old=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(Map.of(key,new byte[]{1}));
        var fault=(LocalStore)java.lang.reflect.Proxy.newProxyInstance(LocalStore.class.getClassLoader(),new Class<?>[]{LocalStore.class},
                (proxy,method,args)->{
                    if(method.getName().equals("putBodiesRoot"))throw new IllegalStateException("injected publication cut");
                    try {return method.invoke(s.store(),args);}
                    catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
                });
        assertThatThrownBy(()->BodyGeneration.begin(s.tree(),fault,s.project(),s.local()).commit(Map.of(key,new byte[]{2})))
                .hasMessageContaining("injected");
        assertThat(s.store().get(LocalStore.bodiesRootKey(s.project()))).isEqualTo(old.encode());
        assertThat(old.current(s.local())).isTrue();
        assertThat(BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).get(key)).containsExactly(1);
    }

    @ParameterizedTest @MethodSource("digests")
    void coldReadsIgnoreUnrootedResultsAndPublicationCommitsTheRootLast(Digest digest) {
        var s=local(digest);var aci=digest.hash(new byte[]{3});var key=LocalStore.resultKey(aci);
        s.store().put(key,new byte[]{99});s.store().flush();var reads=s.store().watchReads(key);
        var generation=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        assertThat(generation.get(key)).isNull();assertThat(reads.get()).isZero();
        generation.put(key,new byte[]{4});generation.flush();
        assertThat(generation.get(key)).containsExactly(4);assertThat(reads.get()).isZero();
        var current=records();current.put(key,new byte[]{4});current.put(LocalStore.usesKey(aci),new byte[]{5});
        var result=generation.commit(current);
        assertThat(result.current(s.local())).isTrue();assertThat(reads.get()).isZero();
        assertThat(s.store().get(LocalStore.localRootKey(s.project()))).isEqualTo(LocalRoot.encode(digest,s.local().format(),s.local().local(),s.local().machineRoot(),s.local().modelHash()));
        var expected=records();expected.put(LocalStore.fileKey(s.project(), Stage2Support.source("A.java")),new byte[]{2});expected.put(key,new byte[]{4});
        sameTree(s,generation.root(),expected);
        assertThat(s.tree().get(result.bodiesRoot(),h->s.store().get(MachineStore.nodeKey(h)),LocalStore.usesKey(aci))).isNull();
        var events=s.store().events();int committed=events.indexOf("putBodiesRoot");assertThat(events.get(committed-1)).isEqualTo("sync");
        assertThatThrownBy(()->generation.put(key,new byte[]{6})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->generation.get(key)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void rerunCarriesTheLocalDiffAndRemovesExactlyStaleBodiesRecords(Digest digest) {
        var s=local(digest);var first=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        var old=records();old.put(LocalStore.proofKey(s.project(), Stage2Support.source("A.java")),new byte[]{3});old.put(LocalStore.resultKey(digest.hash(new byte[]{3})),new byte[]{4});
        var before=first.commit(old);var beforeBytes=before.encode();
        var changed=local(s.tree(),s.store(),s.project(),Map.of(LocalStore.fileKey(s.project(), Stage2Support.source("B.java")),new byte[]{5}));
        var generation=BodyGeneration.begin(s.tree(),s.store(),s.project(),changed.local());
        var current=records();current.put(LocalStore.proofKey(s.project(), Stage2Support.source("B.java")),new byte[]{6});
        var after=generation.commit(current);assertThat(after.current(changed.local())).isTrue();
        var expected=records();expected.putAll(current);expected.put(LocalStore.fileKey(s.project(), Stage2Support.source("B.java")),new byte[]{5});
        sameTree(changed,generation.root(),expected);
        assertThat(s.store().get(LocalStore.bodiesRootHistoryKey(s.project(),1))).isEqualTo(beforeBytes);
        for(var key:old.keySet())assertThat(s.tree().get(after.bodiesRoot(),h->s.store().get(MachineStore.nodeKey(h)),key)).isNull();
    }

    @ParameterizedTest @MethodSource("digests")
    void repeatedContentIsReadThroughThePreviousRootAndNotRewritten(Digest digest) {
        var s=local(digest);var aci=digest.hash(new byte[]{3});var current=records();
        var result=LocalStore.resultKey(aci);var uses=LocalStore.usesKey(aci);var bytes=new byte[]{4};var cf=LocalStore.classFileKey(digest.hash(bytes));
        current.put(result,new byte[]{5});current.put(cf,bytes);current.put(uses,new byte[]{6});
        var first=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(current);
        long writes=s.store().recordWriteCount();var again=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        assertThat(again.get(result)).containsExactly(5);assertThat(again.get(uses)).containsExactly(6);assertThat(again.get(cf)).isEqualTo(bytes);
        assertThat(again.commit(current)).isEqualTo(first);assertThat(s.store().recordWriteCount()).isEqualTo(writes);
    }

    @ParameterizedTest @MethodSource("digests")
    void membershipDoesNotAcceptChangedRecordBytesOrAnUnrelatedOldLocalRecord(Digest digest) {
        var s=local(digest);var key=LocalStore.proofKey(s.project(), Stage2Support.source("A.java"));
        BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(Map.of(key,new byte[]{3}));
        s.store().put(key,new byte[]{4});s.store().flush();
        var generation=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        assertThat(generation.get(key)).containsExactly(3); // Mutable current bindings are not snapshot values.
        var immutable=LocalStore.bodyValueKey(digest.hash(new byte[]{3}));
        s.store().put(immutable,new byte[]{4});s.store().flush();
        assertThatThrownBy(()->generation.get(key)).isInstanceOf(IllegalStateException.class).hasMessageContaining("digest");
        s.store().put(immutable,new byte[]{3});s.store().flush();
        generation.commit(Map.of(key,new byte[]{3}));
        var localKey=LocalStore.fileKey(s.project(), Stage2Support.source("A.java"));
        var changed=local(s.tree(),s.store(),s.project(),Map.of(LocalStore.fileKey(s.project(), Stage2Support.source("B.java")),new byte[]{5}));
        assertThat(BodyGeneration.begin(s.tree(),s.store(),s.project(),changed.local()).get(localKey)).isNull();
    }

    @ParameterizedTest @MethodSource("digests")
    void movingLocalRootAbortsBeforeAnyBodyRecordIsWritten(Digest digest) {
        var s=local(digest);var generation=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        var key=LocalStore.proofKey(s.project(), Stage2Support.source("A.java"));generation.put(key,new byte[]{3});
        local(s.tree(),s.store(),s.project(),Map.of(LocalStore.fileKey(s.project(), Stage2Support.source("B.java")),new byte[]{4}));
        long writes=s.store().recordWriteCount();
        assertThatThrownBy(()->generation.commit(Map.of(key,new byte[]{3}))).isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        assertThat(s.store().recordWriteCount()).isEqualTo(writes);assertThat(s.store().get(LocalStore.bodiesRootKey(s.project()))).isNull();
    }

    @ParameterizedTest @MethodSource("digests")
    void incompatibleBodiesAreColdAndContentConflictsCannotPartiallyPublish(Digest digest) {
        var s=local(digest);var aci=digest.hash(new byte[]{3});var key=LocalStore.resultKey(aci);
        var first=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(Map.of(key,new byte[]{4}));
        var next=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());long writes=s.store().recordWriteCount();
        var changes=records();changes.put(LocalStore.proofKey(s.project(), Stage2Support.source("new.java")),new byte[]{5});changes.put(key,new byte[]{6});
        assertThatThrownBy(()->next.commit(changes)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting");
        // Unreachable immutable provisional bytes are permitted; no current binding or accepted root may move.
        assertThat(s.store().get(LocalStore.proofKey(s.project(),Stage2Support.source("new.java")))).isNull();
        assertThat(s.store().get(LocalStore.bodiesRootKey(s.project()))).isEqualTo(first.encode());
        assertThat(BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).get(key)).containsExactly(4);
        var incompatible=new BodiesRoot("old;bodies=3",first.bodiesRoot(),first.localRoot(),first.machineRoot(),first.modelHash());
        s.store().putBodiesRoot(digest,s.project(),new LocalStore.BodyCommit(incompatible.encode(),first.encode(),
                s.store().get(LocalStore.localRootKey(s.project())),s.store().get(MachineStore.ROOT_KEY),List.of()));var watched=s.store().watchReads(key);
        var cold=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());assertThat(cold.get(key)).isNull();
        cold.commit(Map.of());assertThat(watched.get()).isZero();assertThat(cold.root()).isEqualTo(s.local().local());
        assertThat(s.store().get(LocalStore.bodiesRootHistoryKey(s.project(),2))).isEqualTo(incompatible.encode());
    }

    @ParameterizedTest @MethodSource("digests")
    void f08FreshClassPayloadsAreStreamedAndSelectionNeverFetchesThemAgain(Digest digest) throws Exception {
        var tree=new ContentTree(digest);var disk=dev.jvmd.index.rocks.layer.Generation.of(directory,
                dev.jvmd.index.layer.machine.Format.of(digest,Runtime.version().feature()));
        Root empty;
        try(var machine=disk.create()) {empty=tree.build(List.of(),machine);machine.flush();machine.putRoot(new byte[]{1});}
        var project=digest.hash(new byte[]{62});
        var encoded=LocalRoot.encode(digest,"fixture;local="+LocalFormat.LAYOUT,empty,project,project);
        try(var store=disk.openLocal()) {
            store.putLocalRoot(digest,project,encoded);var local=LocalRoot.decode(digest,encoded);
            var cfReads=new java.util.concurrent.atomic.AtomicInteger();
            var counted=(LocalStore)java.lang.reflect.Proxy.newProxyInstance(LocalStore.class.getClassLoader(),new Class<?>[]{LocalStore.class},
                    (proxy,method,args)->{
                        if(method.getName().equals("get") && BodyRecords.tag((byte[])args[0],"CF"))cfReads.incrementAndGet();
                        try {return method.invoke(store,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
            for(int size:new int[]{16,64}) {
                var generation=BodyGeneration.begin(tree,counted,project,local);
                var selected=new TreeMap<byte[],Entry>(Arrays::compareUnsigned);
                var payloads=new java.util.ArrayList<java.lang.ref.WeakReference<byte[]>>();
                for(int i=0;i<size;i++)payloads.add(streamClass(digest,generation,selected,i+size*100));
                // The store is RocksDB, so this tests generation retention independently of an in-memory byte store.
                System.gc();
                assertThat(payloads.stream().filter(ref->ref.get()==null).count()).isGreaterThanOrEqualTo(size-1L);
                cfReads.set(0);generation.commitReferences(selected);assertThat(cfReads.get()).isZero();
                System.out.println("F08 "+digest.getClass().getSimpleName()+" emitted MiB="+size+" commit CF reads="+cfReads.get());
            }
        }
    }

    private static java.lang.ref.WeakReference<byte[]> streamClass(Digest digest,BodyGeneration generation,Map<byte[],Entry> selected,int ordinal) {
        var bytes=new byte[1_048_576];java.nio.ByteBuffer.wrap(bytes).putInt(ordinal);
        var key=LocalStore.classFileKey(digest.hash(bytes));selected.put(key,generation.record(key,bytes));
        return new java.lang.ref.WeakReference<>(bytes);
    }

    @ParameterizedTest @MethodSource("digests")
    void rocksReopensBothBodyGenerationsAndTheirUntouchedLocalRoot(Digest digest) throws Exception {
        var tree=new ContentTree(digest);var format=dev.jvmd.index.layer.machine.Format.of(digest,Runtime.version().feature());
        var disk=dev.jvmd.index.rocks.layer.Generation.of(directory,format);Root empty;
        try(var machine=disk.create()) {
            empty=tree.build(List.of(),machine);machine.flush();machine.sync();
            machine.putRoot(dev.jvmd.index.layer.machine.MachineTree.encodeRoot(digest,format,empty));
        }
        var project=digest.hash(new byte[]{1});var local=LocalRoot.decode(digest,LocalRoot.encode(digest,"fixture;local=11",empty,project,project));
        var encodedLocal=LocalRoot.encode(digest,local.format(),local.local(),local.machineRoot(),local.modelHash());byte[] first;
        var key=LocalStore.proofKey(project, Stage2Support.source("A.java"));
        try(var store=disk.openLocal()) {
            store.putLocalRoot(digest,project,encodedLocal);
            first=BodyGeneration.begin(tree,store,project,local).commit(Map.of(key,new byte[]{2})).encode();
        }
        try(var store=disk.openLocal()) {
            assertThat(store.get(LocalStore.bodiesRootKey(project))).isEqualTo(first);
            BodyGeneration.begin(tree,store,project,local).commit(Map.of(key,new byte[]{3}));
        }
        try(var store=disk.openLocal()) {
            assertThat(store.get(LocalStore.bodiesRootHistoryKey(project,1))).isEqualTo(first);
            assertThat(store.get(LocalStore.localRootKey(project))).isEqualTo(encodedLocal);
            assertThat(store.get(key)).containsExactly(3);
            var current=BodiesRoot.decode(store.get(LocalStore.bodiesRootKey(project)),digest.width());assertThat(current.current(local)).isTrue();
            assertThat(tree.get(current.bodiesRoot(),h->store.get(MachineStore.nodeKey(h)),key).h()).isEqualTo(digest.hash(new byte[]{3}));
            assertThat(BodyRecords.read(tree,store,BodiesRoot.decode(first,digest.width()).bodiesRoot(),key)).containsExactly(2);
            assertThat(BodyRecords.read(tree,store,current.bodiesRoot(),key)).containsExactly(3);
        }
    }
}
