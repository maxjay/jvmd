package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.*;
import dev.jvmd.index.SemanticMemoStore.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Architecture §68–81, §106–107 and the memo reuse theorem (§74). */
class SemanticMemoStoreTest {
    @TempDir Path root;
    private static final Function F=new Function("attributed-signatures",1);
    private static Hash256 hash(String value){return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));}
    private static QueryProof.Key exact(String id){return new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,id);}
    private static final String A="maven g/a 1 p/A#",B="maven g/a 1 p/B#";
    private static Certificate certificate(String a,String b){
        return new Certificate(new QueryProof(List.of(new QueryProof.Dependency(exact(A),hash(a)),new QueryProof.Dependency(exact(B),hash(b)))));
    }
    private static CurrentIdentities current(Map<QueryProof.Key,Hash256> values){return key->Optional.ofNullable(values.get(key));}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}

    /**
     * Resolving a certificate dependency can restore or attribute another unit, and other threads
     * (the background writer) must still publish meanwhile: a lookup never holds the store while it
     * asks for current identities.
     */
    @Test void dependencyResolutionDoesNotHoldTheStore()throws Exception{
        var store=new SemanticMemoStore(root);
        var key=StaticKey.of(F,"content-1");var other=StaticKey.of(F,"content-2");
        store.put(new MemoRecord(key,certificate("a1","b1"),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.present(bytes("R1"))));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var hit=store.lookup(key,dependency->{
                executor.submit(()->{store.put(new MemoRecord(other,Certificate.empty(),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.present(bytes("R2"))));return null;})
                        .get(10,java.util.concurrent.TimeUnit.SECONDS);
                return Optional.of(dependency.equals(exact(A))?hash("a1"):hash("b1"));
            });
            assertThat(hit).isInstanceOf(Lookup.Hit.class);
        }
        assertThat(store.lookup(other,ignored->Optional.empty())).isInstanceOf(Lookup.Hit.class);
    }

    @Test void reusableOnlyWhenStaticKeyAndEveryDependencyAreEstablishedEqual()throws Exception{
        var store=new SemanticMemoStore(root);
        var key=StaticKey.of(F,"content-1","platform-1","classpath-context-1");
        store.put(new MemoRecord(key,certificate("a1","b1"),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.present(bytes("R1"))));

        var hit=store.lookup(key,current(Map.of(exact(A),hash("a1"),exact(B),hash("b1"))));
        assertThat(hit).isInstanceOfSatisfying(Lookup.Hit.class,value->
                assertThat(value.record().result()).isEqualTo(Result.present(bytes("R1"))));

        assertThat(store.lookup(key,current(Map.of(exact(A),hash("a2"),exact(B),hash("b1")))))
                .isInstanceOfSatisfying(Lookup.Miss.class,miss->assertThat(miss.reason()).startsWith("stale-dependency"));
        // UNKNOWN current evidence is never treated as equal.
        assertThat(store.lookup(key,current(Map.of(exact(A),hash("a1")))))
                .isInstanceOfSatisfying(Lookup.Miss.class,miss->assertThat(miss.reason()).startsWith("unknown-dependency"));
        // A different static context (e.g. another classpath context) never matches.
        assertThat(store.lookup(StaticKey.of(F,"content-1","platform-1","classpath-context-2"),current(Map.of())))
                .isInstanceOf(Lookup.Miss.class);
        assertThat(store.lookup(StaticKey.of(new Function(F.name(),2),"content-1","platform-1","classpath-context-1"),current(Map.of())))
                .isInstanceOf(Lookup.Miss.class);
        assertThat(store.status()).containsEntry("memo_hits",1L).containsEntry("memo_stale_certificates",1L)
                .containsEntry("memo_unknown_dependencies",1L);
    }

    @Test void negativeRecordsAreAbsentWhileMissingRecordsAreUnknown()throws Exception{
        var store=new SemanticMemoStore(root);
        var negative=StaticKey.of(F,"lookup","p.Missing");
        store.put(new MemoRecord(negative,Certificate.empty(),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.absent()));
        assertThat(store.lookup(negative,current(Map.of())))
                .isInstanceOfSatisfying(Lookup.Hit.class,hit->assertThat(hit.record().result()).isInstanceOf(Result.Absent.class));
        assertThat(store.lookup(StaticKey.of(F,"lookup","p.Unobserved"),current(Map.of()))).isInstanceOf(Lookup.Miss.class);
    }

    @Test void completenessIsRestoredExactlyAndUnknownIsNeverMemoised()throws Exception{
        var store=new SemanticMemoStore(root);
        var key=StaticKey.of(F,"partial");
        store.put(new MemoRecord(key,Certificate.empty(),Coverage.PRECISE,SemanticCompleteness.PARTIAL,Result.present(bytes("gap"))));
        var restarted=new SemanticMemoStore(root);
        var hit=(Lookup.Hit)restarted.lookup(key,current(Map.of()));
        assertThat(hit.record().completeness()).isEqualTo(SemanticCompleteness.PARTIAL);
        assertThat(hit.record().coverage()).isEqualTo(Coverage.PRECISE);
        assertThatThrownBy(()->new MemoRecord(key,Certificate.empty(),Coverage.PRECISE,SemanticCompleteness.UNKNOWN,Result.absent()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void certificatesRejectProcessLocalAndPhysicalKeys(){
        for(var key:List.of(
                new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"source:/home/me/repo/A.java"),
                new QueryProof.Key(QueryProof.Domain.HIERARCHY,"document:receiver"),
                new QueryProof.Key(QueryProof.Domain.CLASSPATH_SEARCH,"workspace:session-7"),
                new QueryProof.Key(QueryProof.Domain.DOCUMENT_SCOPE,"/home/me/repo/A.java#12"),
                new QueryProof.Key(QueryProof.Domain.NAMESPACE,"visible"),
                // W3: the whole-roots content fallback and the global namespace leaf are not persistable.
                new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"source-roots-content:g:a:1|main"),
                new QueryProof.Key(QueryProof.Domain.NAMESPACE,"source-roots:g:a:1|main"),
                new QueryProof.Key(QueryProof.Domain.NAMESPACE,"package:/home/me/repo|p"),
                new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-unit:/home/me/repo/src/main/java|p/A.java"),
                new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,"local 0123456789ab_10_x")))
            assertThatThrownBy(()->new Certificate(new QueryProof(List.of(new QueryProof.Dependency(key,hash("x"))))))
                    .as(key.toString()).isInstanceOf(IllegalArgumentException.class);
        for(var key:List.of(exact(A),SemanticQueryProofs.memberRange(A,"get"),SemanticQueryProofs.overloadGroup(A,"run"),
                new QueryProof.Key(QueryProof.Domain.HIERARCHY,A),new QueryProof.Key(QueryProof.Domain.NAMESPACE,"type:p.A"),
                new QueryProof.Key(QueryProof.Domain.NEGATIVE_RESOLUTION,"List@p.List"),
                new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"type:p.A"),
                new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-unit:g:a:1|src/main/java|p/A.java"),
                new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-source:g:a:1|src/main/java|p/A.java"),
                new QueryProof.Key(QueryProof.Domain.RESOLUTION_PATH,"logical-binary:g:a:1|src/main/java|p/A.java"),
                new QueryProof.Key(QueryProof.Domain.NAMESPACE,"package:g:a:1|main|p.q"),
                new QueryProof.Key(QueryProof.Domain.NAMESPACE,"package:g:a:1|main|"),
                new QueryProof.Key(QueryProof.Domain.CLASSPATH_SEARCH,"binary:p.A")))
            assertThat(PersistableProofKeys.persistable(key)).as(key.toString()).isTrue();
    }

    @Test void multipleVariantsPerStaticKeyAreRetainedAndBounded()throws Exception{
        var store=new SemanticMemoStore(root,2);
        var key=StaticKey.of(F,"branchy");
        store.put(new MemoRecord(key,certificate("a1","b1"),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.present(bytes("R1"))));
        store.put(new MemoRecord(key,certificate("a2","b1"),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.present(bytes("R2"))));
        assertThat(((Lookup.Hit)store.lookup(key,current(Map.of(exact(A),hash("a1"),exact(B),hash("b1"))))).record().result())
                .isEqualTo(Result.present(bytes("R1")));
        assertThat(((Lookup.Hit)store.lookup(key,current(Map.of(exact(A),hash("a2"),exact(B),hash("b1"))))).record().result())
                .isEqualTo(Result.present(bytes("R2")));
        Thread.sleep(20);
        store.put(new MemoRecord(key,certificate("a3","b1"),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.present(bytes("R3"))));
        long retained;try(var files=Files.walk(root)){retained=files.filter(path->path.toString().endsWith(".memo")).count();}
        assertThat(retained).isEqualTo(2);
    }

    @Test void entriesAreIndependentlyValidAndCorruptionIsOnlyAMiss()throws Exception{
        var store=new SemanticMemoStore(root);
        var valid=StaticKey.of(F,"valid");var stale=StaticKey.of(F,"stale");var corrupt=StaticKey.of(F,"corrupt");var missing=StaticKey.of(F,"missing");
        for(var key:List.of(valid,stale,corrupt))
            store.put(new MemoRecord(key,certificate("a1","b1"),Coverage.PRECISE,SemanticCompleteness.COMPLETE,Result.present(bytes(key.toString()))));
        Path record;try(var files=Files.walk(root)){record=files.filter(path->path.toString().endsWith(".memo")&&path.toString().contains(corrupt.identity().hex())).findFirst().orElseThrow();}
        byte[] content=Files.readAllBytes(record);content[content.length/2]^=1;Files.write(record,content);

        var now=current(Map.of(exact(A),hash("a1"),exact(B),hash("b1")));
        var changed=current(Map.of(exact(A),hash("a9"),exact(B),hash("b1")));
        assertThat(store.lookup(valid,now)).isInstanceOf(Lookup.Hit.class);
        assertThat(store.lookup(stale,changed)).isInstanceOf(Lookup.Miss.class);
        assertThat(store.lookup(corrupt,now)).isInstanceOfSatisfying(Lookup.Miss.class,miss->assertThat(miss.reason()).isEqualTo("corrupt"));
        assertThat(store.lookup(missing,now)).isInstanceOf(Lookup.Miss.class);
        assertThat(Files.exists(record)).as("corrupt record is discarded").isFalse();
        assertThat(store.status()).containsEntry("memo_corrupt_records",1L);
    }

    @Test void garbageCollectionIsManagementOnly()throws Exception{
        var store=new SemanticMemoStore(root);
        for(int i=0;i<10;i++)store.put(new MemoRecord(StaticKey.of(F,"gc",i),Certificate.empty(),Coverage.PRECISE,
                SemanticCompleteness.COMPLETE,Result.present(new byte[1000])));
        assertThat(store.garbageCollect(3500)).isGreaterThan(0);
        int hits=0;for(int i=0;i<10;i++)if(store.lookup(StaticKey.of(F,"gc",i),current(Map.of())) instanceof Lookup.Hit)hits++;
        assertThat(hits).isBetween(1,3);
    }
}
