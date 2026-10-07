package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Arrange;
import dev.jvmd.core.hash.*;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Independent synthetic semantic maps isolate validation work; BodyProofTest supplies native provider regressions. */
@Tag("phase-3")
class ProofIndexTest {
    static Stream<Digest> digests() {return Stream.of(Sha256.INSTANCE,new Digests.Sha3());}
    record State(MachineLeaf own,Route route) { }
    static class Fixture {
        final Digest digest;final ContentTree tree;final InMemoryLocalStore store=new InMemoryLocalStore();
        final Root empty;final ProofIndex.Inputs inputs;
        Fixture(Digest digest) {
            this.digest=digest;tree=new ContentTree(digest);empty=tree.build(List.of(),store);store.flush();
            inputs=new ProofIndex.Inputs("Use.java",digest.hash(new byte[]{1}),digest.hash(new byte[]{2}));
        }
        Entry fact(String owner,int kind,String name,int value) {
            byte[] key=kind==Keys.TYPE?Keys.typeKey(owner):Keys.memberKey(owner,kind,name,kind==Keys.FIELD?"I":"()I");
            var bytes=new byte[]{(byte)value};return new Entry(key,bytes,digest.hash(key,bytes));
        }
        MachineLeaf leaf(List<Entry> facts) {
            var tEntries=facts.stream().sorted((a,b)->Arrays.compareUnsigned(a.key(),b.key())).toList();
            var t=tree.build(tEntries,store);var nEntries=new ArrayList<Entry>();
            var owners=new TreeMap<String,Identity>();
            for(var e:tEntries) {
                var m=Keys.Member.decode(e.key());owners.merge(m.owner(),e.h(),tree.sums()::add);
                int nested=m.owner().lastIndexOf('$');String outer=m.kind()==Keys.TYPE && nested>=0?m.owner().substring(0,nested):"";
                String name=m.kind()==Keys.TYPE?m.owner().substring(Math.max(nested,m.owner().lastIndexOf('/'))+1):m.name();
                nEntries.add(new Entry(Keys.nameKey(name,m.kind(),outer,e.key()),e.key(),e.h()));
            }
            nEntries.sort((a,b)->Arrays.compareUnsigned(a.key(),b.key()));
            var n=tree.build(nEntries,store);var oEntries=new ArrayList<Entry>();
            owners.forEach((name,sum)->oEntries.add(new Entry(Keys.ownerKey(name),Entry.NONE,sum)));
            var o=tree.build(oEntries,store);
            var leaf=new MachineLeaf(t.hash(),t.sum(),n.hash(),n.level(),empty.hash(),empty.sum(),empty.level(),
                    o.hash(),o.level(),facts.size(),owners.size(),0);
            store.put(MachineStore.leafKey(leaf.k()),leaf.encode());store.flush();return leaf;
        }
        State state(MachineLeaf own,List<MachineLeaf> leaves) {
            var byK=new HashMap<Identity,MachineLeaf>();leaves.forEach(l->byK.put(l.k(),l));
            var sorted=byK.keySet().stream().sorted().toList();
            var set=tree.build(sorted.stream().map(k->new Entry(k.bytes(),Entry.NONE,digest.hash(k.view()))).toList(),store);store.flush();
            var external=DefinerIndex.fold(tree,sorted,set,null,byK::get,h->store.get(MachineStore.nodeKey(h)));
            var siblings=DefinerIndex.fold(tree,List.of(),empty,null,byK::get,h->store.get(MachineStore.nodeKey(h)));
            var dd=DefinerIndex.disjoint(digest,tree,external,h->store.get(MachineStore.nodeKey(h)),store);
            var ds=DefinerIndex.disjoint(digest,tree,siblings,h->store.get(MachineStore.nodeKey(h)),store);
            var sequence=leaves.stream().map(MachineLeaf::k).toList();
            var dc=DefinerIndex.conflicts(digest,tree,external,siblings,sequence,store);
            var route=new ContentList(digest).build(leaves.stream().map(l->new Entry(l.k().bytes(),Entry.NONE,l.r())).toList(),store);
            store.put(LocalStore.disjointKey(set.hash()),DefinerIndex.encodeRoot(dd));
            store.put(LocalStore.siblingKey(empty.hash()),DefinerIndex.encodeRoot(ds));
            store.put(LocalStore.conflictsKey(route.hash()),DefinerIndex.encodeRoot(dc));store.flush();
            return new State(own,new Route(List.of(),route.hash(),route.sum(),set.hash(),empty.hash()));
        }
        Proof proof(State state,List<Proof.Range> ranges,List<String> absent) {
            return Arrange.proof(tree,state.own(),state.route(),null,ranges,absent,store::get);
        }
        ProofIndex index(State state,Proof proof) {
            return ProofIndex.capture(tree,state.own(),state.route(),proof,inputs,store::get,store);
        }
        ProofIndex.Binding binding(State state) {return ProofIndex.Binding.capture(tree,state.own(),state.route(),store::get);}
        ProofIndex.Transition delta(State a,State b,ProofIndex.Work work) {
            return ProofIndex.Transition.between(tree,binding(a),binding(b),store::get,work);
        }
        void agrees(State a,State b,Proof proof) {
            var indexed=ProofIndex.decode(index(a,proof).encode(),digest.width());
            var work=new ProofIndex.Work();
            boolean expected=proof.valid(tree,b.own(),b.route(),null,store::get);
            var next=indexed.advance(delta(a,b,work),inputs,null,null);
            assertThat(next!=null).isEqualTo(expected);
            if(next!=null) {
                assertThat(next.aci()).isEqualTo(proof.aci(digest,inputs.basename(),inputs.source(),inputs.options()));
                assertThat(next.queries()).isEqualTo(indexed.queries());
                assertThat(next.binding()).isEqualTo(binding(b));
            }
        }
    }
    static Proof.Range t(String owner,int kind,String name) {return new Proof.Range(Proof.T,owner,kind,name);}
    static Proof.Range n(String owner,String name) {return new Proof.Range(Proof.N,owner,Keys.TYPE,name);}

    @ParameterizedTest @MethodSource("digests")
    void routePermutationsReplacementDeletionDuplicatesAndOwnShadowingMatchFullValidation(Digest digest) {
        var f=new Fixture(digest);
        var empty=f.leaf(List.of());
        var a=f.leaf(List.of(f.fact("q/Base",Keys.TYPE,"",1),f.fact("q/Base",Keys.FIELD,"VALUE",1)));
        var b=f.leaf(List.of(f.fact("q/Base",Keys.TYPE,"",1),f.fact("q/Base",Keys.FIELD,"VALUE",2),f.fact("q/Base$Inner",Keys.TYPE,"",1)));
        var c=f.leaf(List.of(f.fact("q/Other",Keys.TYPE,"",1)));
        var equal=f.leaf(List.of(f.fact("q/Base",Keys.TYPE,"",1),f.fact("q/Base",Keys.FIELD,"VALUE",1),f.fact("q/Other",Keys.TYPE,"",1)));
        var routes=List.of(List.<MachineLeaf>of(),List.of(a),List.of(b),List.of(a,b),List.of(b,a),List.of(a,c),
                List.of(c,a),List.of(equal),List.of(a,a,b),List.of(b,a,a),List.of(c,a,b),List.of(a,b,c));
        var states=new ArrayList<State>();
        for(var own:List.of(empty,a))for(var route:routes)states.add(f.state(own,route));
        int checks=0;
        for(var before:states) {
            boolean present=new DefinerIndex.Reader(f.tree,before.own(),before.route(),f.store::get).definer("q/Base")!=null;
            var proof=present?f.proof(before,List.of(t("q/Base",Keys.TYPE,""),t("q/Base",Keys.FIELD,"VALUE"),
                    t("q/Base",Keys.FIELD,""),n("q/Base","Inner"),t("q/Base",Keys.METHOD,"missing")),List.of("q/Missing"))
                    :f.proof(before,List.of(),List.of("q/Base","q/Missing"));
            for(var after:states) {f.agrees(before,after,proof);checks++;}
            if(present)for(var after:states) {
                f.agrees(before,after,f.proof(before,List.of(n("q/Base","Missing")),List.of()));
                f.agrees(before,after,f.proof(before,List.of(t("q/Base",Keys.METHOD,"missing")),List.of()));
            }
        }
        assertThat(checks).isEqualTo(576);
    }

    @ParameterizedTest @MethodSource("digests")
    void receiptMustAdvanceFromItsActualBindingAndCannotReuseChangedSourceOptionsOrProcessorAdmission(Digest digest) {
        var f=new Fixture(digest);
        var a=f.state(f.leaf(List.of(f.fact("p/A",Keys.TYPE,"",1),f.fact("p/A",Keys.FIELD,"value",1))),List.of());
        var b=f.state(f.leaf(List.of(f.fact("p/A",Keys.TYPE,"",1),f.fact("p/A",Keys.FIELD,"value",1),f.fact("p/A",Keys.FIELD,"other",2))),List.of());
        var c=f.state(f.leaf(List.of(f.fact("p/A",Keys.TYPE,"",1),f.fact("p/A",Keys.FIELD,"value",2))),List.of());
        var proof=f.proof(a,List.of(t("p/A",Keys.FIELD,"value")),List.of("p/Missing"));
        var first=f.index(a,proof);var next=first.advance(f.delta(a,b,new ProofIndex.Work()),f.inputs,null,null);
        assertThat(next).isNotNull();
        assertThat(ProofIndex.decode(next.encode(),digest.width()).advance(f.delta(b,c,new ProofIndex.Work()),f.inputs,null,null)).isNull();
        assertThatThrownBy(()->first.advance(f.delta(b,b,new ProofIndex.Work()),f.inputs,null,null)).isInstanceOf(IllegalArgumentException.class);
        var noChange=f.delta(a,a,new ProofIndex.Work());
        assertThat(first.advance(noChange,new ProofIndex.Inputs("Other.java",f.inputs.source(),f.inputs.options()),null,null)).isNull();
        assertThat(first.advance(noChange,new ProofIndex.Inputs("Use.java",digest.hash(new byte[]{3}),f.inputs.options()),null,null)).isNull();
        assertThat(first.advance(noChange,new ProofIndex.Inputs("Use.java",f.inputs.source(),digest.hash(new byte[]{3})),null,null)).isNull();
        assertThat(first.advance(noChange,new ProofIndex.Inputs("Use.java",f.inputs.source(),f.inputs.options(),"different"),null,null)).isNull();
        assertThatThrownBy(()->f.index(a,proof.rejectReuse())).isInstanceOf(IllegalArgumentException.class);
        byte[] corrupt=first.encode();corrupt[0]=9;
        assertThatThrownBy(()->ProofIndex.decode(corrupt,digest.width())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.index(b,proof)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void zeroOrOneChangedReferencedQueryDoesNotDecodeTheUnrelatedProof(Digest digest) {
        for(boolean referenceChange:List.of(false,true))for(boolean external:List.of(false,true)) {
            long maxNodes=0;int compactBytes=-1;
            for(int count:new int[]{128,1024,8192}) {
                var f=new Fixture(digest);var facts=new ArrayList<Entry>();var ranges=new ArrayList<Proof.Range>();
                var absent=new ArrayList<String>();
                for(int i=0;i<count;i++) {
                    String name=String.format(Locale.ROOT,"p/T%05d",i);
                    facts.add(f.fact(name,Keys.TYPE,"",1));ranges.add(t(name,Keys.TYPE,""));
                    absent.add(name+"Missing");
                }
                facts.add(f.fact("z/Touch",Keys.TYPE,"",1));facts.add(f.fact("z/Touch",Keys.FIELD,"read",1));
                facts.add(f.fact("z/Touch",Keys.FIELD,"write",1));
                ranges.add(t("z/Touch",Keys.FIELD,referenceChange?"write":"read"));
                var old=f.leaf(facts);
                facts.removeLast();facts.add(f.fact("z/Touch",Keys.FIELD,"write",2));var edited=f.leaf(facts);var empty=f.leaf(List.of());
                var a=f.state(external?empty:old,external?List.of(old):List.of());
                var b=f.state(external?empty:edited,external?List.of(edited):List.of());
                var index=f.index(a,f.proof(a,ranges,absent));
                var bytes=index.encode();if(compactBytes<0)compactBytes=bytes.length;else assertThat(bytes.length).isEqualTo(compactBytes);
                var work=new ProofIndex.Work();
                var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
                long allocated=bean.getCurrentThreadAllocatedBytes();
                // Includes frontier preparation, persisted-header decoding, intersection and current-answer reads.
                var decoded=ProofIndex.decode(bytes,digest.width());
                var after=ProofIndex.Binding.capture(f.tree,b.own(),b.route(),key->{
                    var value=f.store.get(key);work.recordReads++;work.recordBytes+=value.length;return value;
                });
                var transition=ProofIndex.Transition.between(f.tree,decoded.binding(),after,f.store::get,work);
                var next=decoded.advance(transition,f.inputs,null,null);
                allocated=bean.getCurrentThreadAllocatedBytes()-allocated;
                assertThat(next!=null).isEqualTo(!referenceChange);
                assertThat(work.proofEntries).isEqualTo(referenceChange?1:0);
                assertThat(work.changedQueries).isEqualTo(2); // exact FIELD/write plus whole FIELD kind
                assertThat(work.proofQueries).isBetween(1L,2L);
                assertThat(work.proofNodeReads).isLessThan(16);
                assertThat(work.nodeReads).isLessThan(200);
                maxNodes=Math.max(maxNodes,work.nodeReads);
                System.out.printf(Locale.ROOT,"F05 %s external=%s referenced=%s proof=%d receipt=%d prepareReads=%d prepareBytes=%d proofNodes=%d proofBytes=%d matched=%d allocated=%d%n",
                        digest.getClass().getSimpleName(),external,referenceChange,count*2+1,bytes.length,work.recordReads,work.recordBytes,
                        work.proofNodeReads,work.proofNodeBytes,work.proofEntries,allocated);
            }
            assertThat(maxNodes).isLessThan(200);
        }
    }
}
