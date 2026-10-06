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
        return local(tree,store,project,Map.of(LocalStore.fileKey(project,"A.java"),new byte[]{2}));
    }
    private State local(ContentTree tree,InMemoryLocalStore store,Identity project,Map<byte[],byte[]> values) {
        var sorted=records();sorted.putAll(values);sorted.forEach(store::put);store.flush();
        var root=tree.build(sorted.entrySet().stream().map(e->new Entry(e.getKey(),Entry.NONE,tree.digest().hash(e.getValue()))).toList(),store);
        store.flush();store.sync();
        store.putLocalRoot(project,LocalRoot.encode(tree.digest(),"fixture;local=11",root,project,project));
        return new State(tree,store,project,LocalRoot.decode(tree.digest(),store.get(LocalStore.localRootKey(project))));
    }
    private void sameTree(State state,Root actual,Map<byte[],byte[]> values) {
        var sorted=records();sorted.putAll(values);
        var expected=state.tree().build(sorted.entrySet().stream().map(e->new Entry(e.getKey(),Entry.NONE,state.tree().digest().hash(e.getValue()))).toList(),state.store());
        state.store().flush();assertThat(actual).isEqualTo(expected);
        state.tree().verify(actual,h->state.store().get(MachineStore.nodeKey(h)));
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
        var expected=records();expected.put(LocalStore.fileKey(s.project(),"A.java"),new byte[]{2});expected.put(key,new byte[]{4});
        sameTree(s,generation.root(),expected);
        assertThat(s.tree().get(result.bodiesRoot(),h->s.store().get(MachineStore.nodeKey(h)),LocalStore.usesKey(aci))).isNull();
        var events=s.store().events();int committed=events.indexOf("putBodiesRoot");assertThat(events.get(committed-1)).isEqualTo("sync");
        assertThatThrownBy(()->generation.put(key,new byte[]{6})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->generation.get(key)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void rerunCarriesTheLocalDiffAndRemovesExactlyStaleBodiesRecords(Digest digest) {
        var s=local(digest);var first=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        var old=records();old.put(LocalStore.proofKey(s.project(),"A.java"),new byte[]{3});old.put(LocalStore.resultKey(digest.hash(new byte[]{3})),new byte[]{4});
        var before=first.commit(old);var beforeBytes=before.encode();
        var changed=local(s.tree(),s.store(),s.project(),Map.of(LocalStore.fileKey(s.project(),"B.java"),new byte[]{5}));
        var generation=BodyGeneration.begin(s.tree(),s.store(),s.project(),changed.local());
        var current=records();current.put(LocalStore.proofKey(s.project(),"B.java"),new byte[]{6});
        var after=generation.commit(current);assertThat(after.current(changed.local())).isTrue();
        var expected=records();expected.putAll(current);expected.put(LocalStore.fileKey(s.project(),"B.java"),new byte[]{5});
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
        var s=local(digest);var key=LocalStore.proofKey(s.project(),"A.java");
        BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(Map.of(key,new byte[]{3}));
        s.store().put(key,new byte[]{4});s.store().flush();
        var generation=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        assertThatThrownBy(()->generation.get(key)).isInstanceOf(IllegalStateException.class).hasMessageContaining("digest");
        generation.commit(Map.of(key,new byte[]{3})); // A fresh result repairs a mutable record left by an interrupted publication.
        assertThat(s.store().get(key)).containsExactly(3);
        var localKey=LocalStore.fileKey(s.project(),"A.java");
        var changed=local(s.tree(),s.store(),s.project(),Map.of(LocalStore.fileKey(s.project(),"B.java"),new byte[]{5}));
        assertThat(BodyGeneration.begin(s.tree(),s.store(),s.project(),changed.local()).get(localKey)).isNull();
    }

    @ParameterizedTest @MethodSource("digests")
    void movingLocalRootAbortsBeforeAnyBodyRecordIsWritten(Digest digest) {
        var s=local(digest);var generation=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());
        var key=LocalStore.proofKey(s.project(),"A.java");generation.put(key,new byte[]{3});
        local(s.tree(),s.store(),s.project(),Map.of(LocalStore.fileKey(s.project(),"B.java"),new byte[]{4}));
        long writes=s.store().recordWriteCount();
        assertThatThrownBy(()->generation.commit(Map.of(key,new byte[]{3}))).isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        assertThat(s.store().recordWriteCount()).isEqualTo(writes);assertThat(s.store().get(LocalStore.bodiesRootKey(s.project()))).isNull();
    }

    @ParameterizedTest @MethodSource("digests")
    void incompatibleBodiesAreColdAndContentConflictsCannotPartiallyPublish(Digest digest) {
        var s=local(digest);var aci=digest.hash(new byte[]{3});var key=LocalStore.resultKey(aci);
        var first=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local()).commit(Map.of(key,new byte[]{4}));
        var next=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());long writes=s.store().recordWriteCount();
        var changes=records();changes.put(LocalStore.proofKey(s.project(),"new.java"),new byte[]{5});changes.put(key,new byte[]{6});
        assertThatThrownBy(()->next.commit(changes)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting");
        assertThat(s.store().recordWriteCount()).isEqualTo(writes);assertThat(s.store().get(LocalStore.bodiesRootKey(s.project()))).isEqualTo(first.encode());
        var incompatible=new BodiesRoot("old;bodies=3",first.bodiesRoot(),first.localRoot(),first.machineRoot(),first.modelHash());
        s.store().putBodiesRoot(s.project(),incompatible.encode());var watched=s.store().watchReads(key);
        var cold=BodyGeneration.begin(s.tree(),s.store(),s.project(),s.local());assertThat(cold.get(key)).isNull();
        cold.commit(Map.of());assertThat(watched.get()).isZero();assertThat(cold.root()).isEqualTo(s.local().local());
        assertThat(s.store().get(LocalStore.bodiesRootHistoryKey(s.project(),2))).isEqualTo(incompatible.encode());
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
        var key=LocalStore.proofKey(project,"A.java");
        try(var store=disk.openLocal()) {
            store.putLocalRoot(project,encodedLocal);
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
        }
    }
}
