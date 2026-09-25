package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class SemanticProofDagTest {
    @TempDir Path root;

    private static Hash256 hash(String value){
        return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private static QueryProof.Dependency dependency(QueryProof.Key key,String identity){
        return new QueryProof.Dependency(key,hash(identity));
    }
    private static SemanticUpdatePolicy.ProofEvaluation evaluation(
            QueryProof.Key dependencyKey,String dependencyIdentity,
            QueryProof.Key output,String derivedIdentity){
        return new SemanticUpdatePolicy.ProofEvaluation(
                new QueryProof(List.of(dependency(dependencyKey,dependencyIdentity))),
                output,hash(derivedIdentity));
    }
    private static SemanticUpdatePolicy.ProofConsumer consumer(Path root,String file,String id){
        return new SemanticUpdatePolicy.ProofConsumer(root.resolve(file),id);
    }

    @Test void exactSymbolChangeEnqueuesOnlyDirectConsumerAndEqualDerivedProofStops()throws Exception{
        var live=new SemanticUpdatePolicy.Live();
        var exact=SemanticQueryProofs.exactSymbol("A#foo()I");
        var bOutput=new QueryProof.Key(QueryProof.Domain.HIERARCHY,"B");
        var cOutput=new QueryProof.Key(QueryProof.Domain.HIERARCHY,"C");
        var b=consumer(root,"B.java","B.semantic"),c=consumer(root,"C.java","C.semantic");

        live.proofs().register(b,evaluation(exact,"foo-v1",bOutput,"b-v1"));
        live.proofs().register(c,evaluation(bOutput,"b-v1",cOutput,"c-v1"));

        var calls=new ArrayList<SemanticUpdatePolicy.ProofConsumer>();
        var unchanged=live.proofs().propagate(Map.of(exact,hash("foo-v1")),consumer->{
            calls.add(consumer);throw new AssertionError("equal leaf must not enqueue a consumer");
        });
        assertThat(unchanged.recomputed()).isEmpty();
        assertThat(calls).isEmpty();

        var changed=live.proofs().propagate(Map.of(exact,hash("foo-v2")),consumer->{
            calls.add(consumer);
            if(consumer.equals(b))return Optional.of(evaluation(exact,"foo-v2",bOutput,"b-v1"));
            throw new AssertionError("equal B result must stop before C");
        });

        assertThat(changed.recomputed()).containsExactly(b);
        assertThat(changed.equal()).containsExactly(b);
        assertThat(changed.changed()).isEmpty();
        assertThat(calls).containsExactly(b);
    }

    @Test void overloadProofChangePropagatesThroughDerivedOutputButUnrelatedGroupDoesNot()throws Exception{
        var live=new SemanticUpdatePolicy.Live();
        var foo=SemanticQueryProofs.overloadGroup("A","foo");
        var bar=SemanticQueryProofs.overloadGroup("A","bar");
        var bOutput=new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,"derived:B");
        var cOutput=new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,"derived:C");
        var b=consumer(root,"B.java","B.call"),c=consumer(root,"C.java","C.call");

        live.proofs().register(b,evaluation(foo,"foo-v1",bOutput,"b-v1"));
        live.proofs().register(c,evaluation(bOutput,"b-v1",cOutput,"c-v1"));

        var unrelated=live.proofs().propagate(Map.of(bar,hash("bar-v2")),ignored->
                {throw new AssertionError("unrelated overload group must not enqueue consumers");});
        assertThat(unrelated.recomputed()).isEmpty();

        var calls=new ArrayList<SemanticUpdatePolicy.ProofConsumer>();
        var result=live.proofs().propagate(Map.of(foo,hash("foo-v2")),consumer->{
            calls.add(consumer);
            if(consumer.equals(b))return Optional.of(evaluation(foo,"foo-v2",bOutput,"b-v2"));
            if(consumer.equals(c))return Optional.of(evaluation(bOutput,"b-v2",cOutput,"c-v2"));
            throw new AssertionError(consumer);
        });

        assertThat(calls).containsExactly(b,c);
        assertThat(result.changed()).containsExactly(b,c);
        assertThat(result.equal()).isEmpty();
    }

    @Test void hierarchyDerivedFixedPointStopsBeforeDownstreamConsumer()throws Exception{
        var live=new SemanticUpdatePolicy.Live();
        var base=new QueryProof.Key(QueryProof.Domain.HIERARCHY,"Base");
        var middleOutput=new QueryProof.Key(QueryProof.Domain.HIERARCHY,"Middle");
        var downstreamOutput=new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,"Downstream.result");
        var middle=consumer(root,"Middle.java","hierarchy"),downstream=consumer(root,"Downstream.java","use-middle");

        live.proofs().register(middle,evaluation(base,"base-v1",middleOutput,"surface-v1"));
        live.proofs().register(downstream,evaluation(middleOutput,"surface-v1",downstreamOutput,"downstream-v1"));

        var downstreamCalls=new AtomicInteger();
        var result=live.proofs().propagate(Map.of(base,hash("base-v2")),consumer->{
            if(consumer.equals(middle))
                return Optional.of(evaluation(base,"base-v2",middleOutput,"surface-v1"));
            downstreamCalls.incrementAndGet();
            return Optional.of(evaluation(middleOutput,"surface-v1",downstreamOutput,"downstream-v1"));
        });

        assertThat(result.recomputed()).containsExactly(middle);
        assertThat(result.equal()).containsExactly(middle);
        assertThat(downstreamCalls).hasValue(0);
    }

    @Test void coarseClosureIsUsedOnlyBeyondExplicitProofCoverageOrWhenRecomputeUnavailable()throws Exception{
        var live=new SemanticUpdatePolicy.Live();
        Path a=root.resolve("A.java"),b=root.resolve("B.java"),c=root.resolve("C.java");
        live.resolve(value(a,"a-v1",Set.of()));
        live.resolve(value(b,"b-v1",Set.of(a)));
        live.resolve(value(c,"c-v1",Set.of(b)));

        var exact=SemanticQueryProofs.exactSymbol("A#value()I");
        var bOutput=new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,"derived:B");
        var bConsumer=new SemanticUpdatePolicy.ProofConsumer(b,"B.precise");
        live.proofs().register(bConsumer,evaluation(exact,"a-v1",bOutput,"b-v1"));
        live.proofCoverage(b,true);

        var precise=live.propagateProofChanges(a,Map.of(exact,hash("a-v2")),consumer->
                Optional.of(evaluation(exact,"a-v2",bOutput,"b-v1")));
        assertThat(precise.propagation().equal()).containsExactly(bConsumer);
        assertThat(precise.coarseReanalyze()).isEmpty();

        var fallback=live.propagateProofChanges(a,Map.of(exact,hash("a-v3")),consumer->Optional.empty());
        assertThat(fallback.propagation().fallback()).containsExactly(bConsumer);
        assertThat(fallback.coarseReanalyze()).containsExactly(c);
    }

    @Test void cyclesAndDuplicateOutputsAreRejected(){
        var live=new SemanticUpdatePolicy.Live();
        var a=consumer(root,"A.java","a"),b=consumer(root,"B.java","b");
        var aOut=new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,"derived:A");
        var bOut=new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,"derived:B");
        live.proofs().register(a,evaluation(bOut,"b-v1",aOut,"a-v1"));
        assertThatThrownBy(()->live.proofs().register(b,evaluation(aOut,"a-v1",bOut,"b-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DAG");
    }

    private FileSemanticContribution value(Path file,String api,Set<Path> dependencies){
        return new FileSemanticContribution(file,api,api,dependencies,Set.of(file.getFileName().toString()),Set.of());
    }
}
