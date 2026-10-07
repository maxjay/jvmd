package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.*;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import dev.jvmd.index.rocks.layer.Generation;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class CurrentBodyReverseTest {
    @TempDir Path directory;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }
    static final ReverseIndex.Dependency T=new ReverseIndex.Dependency(ReverseIndex.T,"q/K",Keys.FIELD,"VALUE");

    static class Meter implements java.lang.reflect.InvocationHandler {
        final LocalStore delegate; int gets,hits;
        Meter(LocalStore delegate) {this.delegate=delegate;}
        LocalStore view() {return (LocalStore)java.lang.reflect.Proxy.newProxyInstance(LocalStore.class.getClassLoader(),new Class<?>[]{LocalStore.class},this);}
        @SuppressWarnings("unchecked") public Object invoke(Object proxy,java.lang.reflect.Method method,Object[] args) throws Throwable {
            if(method.getName().equals("get"))gets++;
            if(method.getName().equals("forEachKey")) {
                var action=(java.util.function.Consumer<byte[]>)args[1];
                args[1]=(java.util.function.Consumer<byte[]>)key->{hits++;action.accept(key);};
            }
            try {return method.invoke(delegate,args);} catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
        }
    }
    @ParameterizedTest @MethodSource("digests")
    void f06CurrentBodyPrefixCostDoesNotGrowWithRetiredConsumers(Digest digest) throws Exception {
        var disk=Generation.of(directory,Format.of(digest,Stage2Support.FEATURE));
        try(var machine=disk.create()) {machine.putRoot(new byte[]{1});}
        var tree=new ContentTree(digest);var project=digest.hash(new byte[]{31});
        var live=ReverseIndex.bodyKey(T,project,Stage2Support.source("live.java"));
        try(var rocks=disk.openLocal()) {
            for(LocalStore store:List.of(new InMemoryLocalStore(),rocks)) {
                for(int round=0;round<12;round++) {
                    var keys=new ArrayList<byte[]>();keys.add(live);
                    for(int i=0;i<500;i++)keys.add(ReverseIndex.bodyKey(T,project,Stage2Support.source("old-"+round+"-"+i+".java")));
                    BodyReverseTest.publish(digest,tree,store,project,keys);
                    assertThat(ReverseIndex.bodyConsumers(digest,store,T)).hasSize(501);
                    BodyReverseTest.publish(digest,tree,store,project,List.of(live));
                    var meter=new Meter(store);
                    assertThat(ReverseIndex.bodyConsumers(digest,meter.view(),T)).containsExactly(new ReverseIndex.Consumer(project,Stage2Support.source("live.java")));
                    assertThat(meter.hits).isOne();assertThat(meter.gets).isZero();
                }
                var historical=BodiesRoot.decode(store.get(LocalStore.bodiesRootHistoryKey(project,1)),digest.width());
                var old=tree.root(historical.bodiesRoot(),h->store.get(MachineStore.nodeKey(h)));
                tree.verify(old,h->store.get(MachineStore.nodeKey(h)));
                assertThat(old.count()).isEqualTo(501);
                assertThat(new Codec.Reader(store.get(LocalStore.bodiesSequenceKey(project))).count()).isEqualTo(23);
            }
        }
        try(var store=disk.openLocal()) {
            var meter=new Meter(store);
            assertThat(ReverseIndex.bodyConsumers(digest,meter.view(),T)).hasSize(1);
            assertThat(meter.hits).isOne();assertThat(meter.gets).isZero();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void f02BodyProjectionDeltasDistinguishPositiveNFromHeaderZeroPredicates(Digest digest) {
        var store=new InMemoryLocalStore();var tree=new ContentTree(digest);var project=digest.hash(new byte[]{8});
        var n=new ReverseIndex.Dependency(ReverseIndex.N,"q/K",Keys.TYPE,"Inner");
        var d=new ReverseIndex.Dependency(ReverseIndex.D,"q/K",Keys.TYPE,"");
        BodyReverseTest.publish(digest,tree,store,project,List.of(ReverseIndex.bodyKey(n,project,Stage2Support.source("N.java")),
                ReverseIndex.bodyKey(d,project,Stage2Support.source("D.java")),ReverseIndex.bodyKey(T,project,Stage2Support.source("T.java"))));
        var none=new Diff.Result(List.of(),List.of());
        var nk=Keys.nameKey("Inner",Keys.TYPE,"q/K",Keys.typeKey("q/K$Inner"));
        var old=new Entry(nk,Entry.NONE,digest.hash(new byte[]{1}));var next=new Entry(nk,Entry.NONE,digest.hash(new byte[]{2}));
        var presence=new Diff.Result(List.of(new Entry(Keys.ownerKey("q/K"),Entry.NONE,old.h())),List.of(new Entry(Keys.ownerKey("q/K"),Entry.NONE,next.h())));
        assertThat(ReverseIndex.bodyCandidates(digest,store,new ReverseIndex.Delta(none,new Diff.Result(List.of(old),List.of(next)),presence,none)))
                .extracting(ReverseIndex.Consumer::path).containsExactly("N.java");
        var a=digest.hash(new byte[]{4});var b=digest.hash(new byte[]{5});var c=digest.hash(new byte[]{6});
        var before=new Entry(Keys.ownerKey("q/K"),new Codec.Writer().id(a).u32(3).id(a).id(b).id(c).toBytes(),old.h());
        var losers=new Entry(before.key(),new Codec.Writer().id(a).u32(3).id(a).id(c).id(b).toBytes(),old.h());
        assertThat(ReverseIndex.bodyCandidates(digest,store,new ReverseIndex.Delta(none,none,none,new Diff.Result(List.of(before),List.of(losers))))).isEmpty();
        var winner=new Entry(before.key(),new Codec.Writer().id(b).u32(3).id(b).id(a).id(c).toBytes(),old.h());
        var candidates=ReverseIndex.bodyCandidates(digest,store,new ReverseIndex.Delta(none,none,none,new Diff.Result(List.of(before),List.of(winner))));
        assertThat(candidates).extracting(ReverseIndex.Consumer::path).containsExactly("N.java","T.java");
    }
}
