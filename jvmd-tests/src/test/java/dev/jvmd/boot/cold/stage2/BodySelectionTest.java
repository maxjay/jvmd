package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.BodyGeneration;
import dev.jvmd.core.hash.*;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class BodySelectionTest {
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }
    private record State(ContentTree tree,InMemoryLocalStore store,Identity project,LocalRoot local) {
        BodyGeneration begin() {return BodyGeneration.begin(tree,store,project,local);}
    }
    private State state(Digest digest) {
        var tree=new ContentTree(digest);var store=new InMemoryLocalStore();var project=digest.hash(new byte[]{99});
        var root=tree.build(List.of(),store);store.flush();
        store.putLocalRoot(digest,project,LocalRoot.encode(digest,"fixture;local="+LocalFormat.LAYOUT,root,project,project));
        return new State(tree,store,project,LocalRoot.decode(digest,store.get(LocalStore.localRootKey(project))));
    }
    private static byte[] proof(State state,SourceUnit unit) {return LocalStore.proofKey(state.project(),unit);}
    private static List<Entry> refs(BodyGeneration generation,State state,SourceUnit unit,int value,byte[] shared) {
        return List.of(generation.record(proof(state,unit),new Codec.Writer().u32(value).toBytes()),
                generation.record(LocalStore.classFileKey(state.tree().digest().hash(shared)),shared));
    }

    @ParameterizedTest @MethodSource("digests")
    void deltaPreservesSharedRecordsUntilTheLastOwnerAndMatchesIndependentFullPublication(Digest digest) {
        var s=state(digest);var a=new SourceUnit("app",0,"A.java");var b=new SourceUnit("app",0,"B.java");var c=new SourceUnit("app",0,"C.java");
        byte[] shared={1,2,3};var cf=LocalStore.classFileKey(digest.hash(shared));
        var first=s.begin();var old=first.commitUnitsFull(Map.of(a,refs(first,s,a,1,shared),b,refs(first,s,b,2,shared)));
        var second=s.begin();assertThat(second.previousUnit(a)).hasSize(2);
        var current=second.commitUnitsDelta(Map.of(c,refs(second,s,c,3,shared)),Set.of(a));
        assertThat(BodyRecords.read(s.tree(),s.store(),current.bodiesRoot(),proof(s,a))).isNull();
        assertThat(BodyRecords.read(s.tree(),s.store(),current.bodiesRoot(),cf)).containsExactly(shared);
        assertThat(BodyRecords.read(s.tree(),s.store(),old.bodiesRoot(),proof(s,a))).isNotNull();
        var oracle=state(digest);var full=oracle.begin();
        var expected=full.commitUnitsFull(Map.of(b,refs(full,oracle,b,2,shared),c,refs(full,oracle,c,3,shared)));
        assertThat(current.bodiesRoot()).isEqualTo(expected.bodiesRoot());
        var removed=s.begin().commitUnitsDelta(Map.of(),Set.of(b));
        assertThat(BodyRecords.read(s.tree(),s.store(),removed.bodiesRoot(),cf)).containsExactly(shared);
        var empty=s.begin().commitUnitsDelta(Map.of(),Set.of(c));
        assertThat(empty.bodiesRoot()).isEqualTo(s.local().local().hash());
        assertThat(s.store().get(cf)).containsExactly(shared); // immutable history is not collected here
    }

    @ParameterizedTest @MethodSource("digests")
    void conflictingSelectionsCannotPublishAndNoOpDeltaDoesNoInventoryWork(Digest digest) {
        var s=state(digest);var a=new SourceUnit("app",0,"A.java");var b=new SourceUnit("app",0,"B.java");
        var key=LocalStore.outputKey(s.project(),"app",0);
        var first=s.begin();var before=first.commitUnitsFull(Map.of(a,List.of(first.record(key,new byte[]{1}))));
        var conflict=s.begin();
        assertThatThrownBy(()->conflict.commitUnitsDelta(Map.of(b,List.of(conflict.record(key,new byte[]{2}))),Set.of()))
                .hasMessageContaining("Conflicting values selected");
        assertThat(s.store().get(LocalStore.bodiesRootKey(s.project()))).isEqualTo(before.encode());
        var noop=s.begin();long writes=s.store().recordWriteCount();int events=s.store().events().size();
        assertThat(noop.commitUnitsDelta(Map.of(),Set.of())).isEqualTo(before);
        assertThat(s.store().recordWriteCount()).isEqualTo(writes);
        assertThat(s.store().events().subList(events,s.store().events().size())).doesNotContain("putBodiesRoot");
    }

    @ParameterizedTest @MethodSource("digests")
    void f11OneUnitDeltaDoesNotReadTheOtherUnitManifestsOrClassPayloads(Digest digest) {
        for(int size:new int[]{128,1024,8192}) {
            var s=state(digest);var first=s.begin();var selected=new TreeMap<SourceUnit,List<Entry>>();byte[] payload=new byte[64*1024];
            for(int i=0;i<size;i++) {
                var unit=new SourceUnit("app",0,String.format("File%05d.java",i));
                selected.put(unit,refs(first,s,unit,i,payload));
            }
            first.commitUnitsFull(selected);
            var next=s.begin();var unit=selected.firstKey();
            var entries=List.of(next.record(proof(s,unit),new byte[]{42}),
                    Objects.requireNonNull(next.reference(LocalStore.classFileKey(digest.hash(payload)))));
            int start=s.store().events().size();var after=next.commitUnitsDelta(Map.of(unit,entries),Set.of());
            var events=s.store().events().subList(start,s.store().events().size());
            long reads=events.stream().filter(e->e.equals("read:N")).count();
            assertThat(events).doesNotContain("read:CF");
            var node=hNode(s,after.bodiesRoot());int depth=Node.level(node)+1;
            assertThat(reads).as("size=%s depth=%s",size,depth).isLessThan(110L*depth);
            assertThat(events.stream().filter(e->e.equals("read:BV")).count()).isLessThanOrEqualTo(4);
            System.out.println("F11 "+digest.getClass().getSimpleName()+" units="+size+" node reads="+reads);
        }
    }
    private static byte[] hNode(State state,Identity hash) {return state.store().get(MachineStore.nodeKey(hash));}
}
