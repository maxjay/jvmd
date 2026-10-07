package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B.3 fan-out from actual ContentTree deltas; no scan of stored proofs or source rows. */
@Tag("phase-3")
class BodyReverseTest {
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void semanticDeltasFindRangeWholeKindNAndDConsumersInTheBodiesGeneration(Digest digest) {
        var store = new InMemoryLocalStore();
        var tree = new ContentTree(digest);
        var a = digest.hash(new byte[] {1}); var b = digest.hash(new byte[] {2});
        var method = new ReverseIndex.Dependency(ReverseIndex.T, "q/Base", Keys.METHOD, "get");
        var whole = new ReverseIndex.Dependency(ReverseIndex.T, "q/Base", Keys.METHOD, "");
        var nested = new ReverseIndex.Dependency(ReverseIndex.N, "q/Base", Keys.TYPE, "Foo");
        var missing = new ReverseIndex.Dependency(ReverseIndex.D, "q/Missing", Keys.TYPE, "");
        var other = new ReverseIndex.Dependency(ReverseIndex.T, "q/Base", Keys.METHOD, "unread");
        byte[] first = ReverseIndex.bodyKey(method, a, Stage2Support.source("src/A.java")), second = ReverseIndex.bodyKey(method, a, Stage2Support.source("test/A.java"));
        byte[] overriding = ReverseIndex.bodyKey(whole, a, Stage2Support.source("src/Override.java")), unrelated = ReverseIndex.bodyKey(other, a, Stage2Support.source("src/Other.java"));
        byte[] named = ReverseIndex.bodyKey(nested, b, Stage2Support.source("src/N.java")), absent = ReverseIndex.bodyKey(missing, b, Stage2Support.source("src/D.java"));
        publish(digest, tree, store, a, List.of(first, second, overriding, unrelated));
        publish(digest, tree, store, b, List.of(named, absent));
        // Current keys can only be changed by root publication; legacy raw history is in a different namespace.
        assertThatThrownBy(() -> store.put(ReverseIndex.bodyKey(method, a, Stage2Support.source("stale/Old.java")), Entry.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        var header = method.key(a, Stage2Support.source("header/Only.java"));
        var headerRoot = tree.build(List.of(new Entry(header, Entry.NONE, digest.hash(Entry.NONE))), store);
        store.flush();
        store.putLocalRoot(digest, a, LocalRoot.encode(digest,
                LocalFormat.of(dev.jvmd.index.layer.machine.Format.of(digest, Runtime.version().feature())), headerRoot, a, a));
        store.put(new Codec.Writer().raw(new byte[]{'X','|','G','|'}).u8(method.form()).zstr(method.type())
                .u8(method.kind()).zstr(method.name()).id(a).zstr("legacy/Old.java").toBytes(), Entry.NONE);
        store.flush();
        var t = delta(digest, tree, store, Keys.memberKey("q/Base", Keys.METHOD, "get", "()Ljava/lang/Number;"), true);
        var n = delta(digest, tree, store, new Codec.Writer().zstr("Foo").u8(Keys.TYPE).zstr("q/Base").raw(Keys.typeKey("q/Base$Foo")).toBytes(), false);
        var d = delta(digest, tree, store, Keys.ownerKey("q/Missing"), false);
        var rootReadsA = store.watchReads(LocalStore.bodiesRootKey(a));
        var rootReadsB = store.watchReads(LocalStore.bodiesRootKey(b));
        int start = store.events().size();
        assertThat(ReverseIndex.bodyCandidates(digest, store, new ReverseIndex.Delta(t, n, d,
                new Diff.Result(List.of(), List.of())))).containsExactlyInAnyOrder(
                new ReverseIndex.Consumer(a, Stage2Support.source("src/A.java")), new ReverseIndex.Consumer(a, Stage2Support.source("test/A.java")), new ReverseIndex.Consumer(a, Stage2Support.source("src/Override.java")),
                new ReverseIndex.Consumer(b, Stage2Support.source("src/N.java")), new ReverseIndex.Consumer(b, Stage2Support.source("src/D.java")));
        assertThat(store.events().subList(start, store.events().size())).doesNotContain("read:F", "read:C", "prefix:F", "prefix:C", "read:LROOT");
        assertThat(rootReadsA.get()).isZero();
        assertThat(rootReadsB.get()).isZero();
        assertThat(store.get(first)).isEmpty();
        assertThat(first).startsWith(ReverseIndex.bodyPrefix(method));

        // Publication removes current keys atomically; the old tree still retains their empty values.
        publish(digest, tree, store, a, List.of(second, overriding, unrelated));
        assertThat(store.get(first)).isNull();
        assertThat(ReverseIndex.bodyConsumers(digest, store, method)).containsExactly(new ReverseIndex.Consumer(a, Stage2Support.source("test/A.java")));
    }

    @ParameterizedTest @MethodSource("digests")
    void allProofRangesAndExpectedZerosProduceSeparateEmptyConsumerKeys(Digest digest) {
        var id = digest.hash(new byte[] {1}); var zero = Identity.zero(digest.width());
        var proof = new Proof(new Proof.Header(id, id, id, id, id, null), List.of(new Proof.Type("p/Base", id, List.of(
                new Proof.Entry(new Proof.Range(Proof.T, "p/Base", Keys.FIELD, "missing"), zero),
                new Proof.Entry(new Proof.Range(Proof.N, "p/Base", Keys.TYPE, "missing"), zero)))), List.of("p/Missing"));
        var dependencies = ReverseIndex.dependencies(proof);
        assertThat(dependencies).hasSize(3);
        assertThat(dependencies.stream().map(d -> java.util.HexFormat.of().formatHex(ReverseIndex.bodyKey(d, id, Stage2Support.source("src/A.java")))).distinct()).hasSize(3);
        for (var dependency : dependencies)
            assertThat(ReverseIndex.bodyKey(dependency, id, Stage2Support.source("src/A.java"))).isNotEqualTo(ReverseIndex.bodyKey(dependency, id, Stage2Support.source("test/A.java")));
    }

    @ParameterizedTest @MethodSource("digests")
    void anOldRootCannotMakeAnUnscopedConsumerLookLikeACurrentUnit(Digest digest) {
        var store=new InMemoryLocalStore();var tree=new ContentTree(digest);var project=digest.hash(new byte[]{3});
        var dependency=new ReverseIndex.Dependency(ReverseIndex.T,"p/T",Keys.METHOD,"x");
        String format=LocalFormat.of(dev.jvmd.index.layer.machine.Format.of(digest,Runtime.version().feature()))
                .replace(";local="+LocalFormat.LAYOUT+";",";local=12;");
        byte[] header=new Codec.Writer().raw(new byte[]{'X','|','H','|'}).u8(dependency.form())
                .zstr(dependency.type()).u8(dependency.kind()).zstr(dependency.name()).id(project).zstr("same/Source.java").toBytes();
        byte[] body=new Codec.Writer().raw(new byte[]{'X','|','G','|'}).u8(dependency.form()).zstr(dependency.type())
                .u8(dependency.kind()).zstr(dependency.name()).id(project).zstr("same/Source.java").toBytes();
        store.put(header,Entry.NONE);store.put(body,Entry.NONE);
        var local=tree.build(List.of(new Entry(header,Entry.NONE,digest.hash(Entry.NONE))),store);
        var bodies=tree.build(List.of(new Entry(body,Entry.NONE,digest.hash(Entry.NONE))),store);
        store.put(LocalStore.localRootKey(project),LocalRoot.encode(digest,format,local,project,project));
        store.put(LocalStore.bodiesRootKey(project),new BodiesRoot(BodiesRoot.format(format),bodies.hash(),local.hash(),project,project).encode());
        store.flush();
        assertThat(ReverseIndex.consumers(digest,store,dependency)).isEmpty();
        assertThat(ReverseIndex.bodyConsumers(digest,store,dependency)).isEmpty();
    }

    static void publish(Digest digest, ContentTree tree, LocalStore store, Identity project, List<byte[]> keys) {
        var entries = keys.stream().sorted(Arrays::compareUnsigned).map(key -> new Entry(key, Entry.NONE, digest.hash(Entry.NONE))).toList();
        var root = tree.build(entries, store);
        var local = digest.hash(new byte[] {7});
        String format=LocalFormat.of(dev.jvmd.index.layer.machine.Format.of(digest,Runtime.version().feature()));
        store.flush();
        var result=new BodiesRoot(BodiesRoot.format(format), root.hash(), local, local, local);
        store.putBodiesRoot(digest,project,new LocalStore.BodyCommit(result.encode(),store.get(LocalStore.bodiesRootKey(project)),
                store.get(LocalStore.localRootKey(project)),store.get(MachineStore.ROOT_KEY),List.of()));
    }

    private static Diff.Result delta(Digest digest, ContentTree tree, InMemoryLocalStore store, byte[] key, boolean existing) {
        var before = tree.build(existing ? List.of(new Entry(key, Entry.NONE, digest.hash(key, new byte[] {1}))) : List.of(), store);
        var after = tree.build(List.of(new Entry(key, Entry.NONE, digest.hash(key, new byte[] {2}))), store);
        store.flush();
        return Diff.trees(digest, before, after, h -> store.get(MachineStore.nodeKey(h)));
    }
}
