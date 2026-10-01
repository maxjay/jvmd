package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.*;
import dev.jvmd.index.SemanticUpdatePolicy.ProofConsumer;
import dev.jvmd.index.SemanticUpdatePolicy.ProofEvaluation;
import dev.jvmd.index.SemanticUpdatePolicy.Recomputation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Architecture §19–24: height-ordered propagation over semantic result identities.
 *
 * Each consumer computes {@code result = f(inputs)} through a tiny in-memory model, so the test
 * checks both the scheduling order and that the converged state equals a from-scratch evaluation.
 */
class ProofDagOrderingTest {
    private static Hash256 hash(String value){return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));}
    private static QueryProof.Key leaf(String name){return new QueryProof.Key(QueryProof.Domain.EXACT_SYMBOL,name);}
    private static QueryProof.Key out(String name){return new QueryProof.Key(QueryProof.Domain.WORKSPACE,"out:"+name);}

    /** A small semantic model: leaves have values, each node's result is a function of its inputs. */
    private static final class Model {
        final SemanticUpdatePolicy.ProofDag dag=new SemanticUpdatePolicy.ProofDag();
        final Map<QueryProof.Key,String> leaves=new HashMap<>();
        final Map<String,List<QueryProof.Key>> inputs=new LinkedHashMap<>();
        final Map<String,Function<List<String>,String>> functions=new HashMap<>();
        final Map<String,String> results=new HashMap<>();
        final List<String> calls=new ArrayList<>();

        ProofConsumer consumer(String name){return new ProofConsumer(Path.of("/w",name+".java"),name);}
        void node(String name,Function<List<String>,String> function,QueryProof.Key... dependencies){
            inputs.put(name,List.of(dependencies));functions.put(name,function);
        }
        String value(QueryProof.Key key){
            if(key.domain()==QueryProof.Domain.WORKSPACE)return results.get(key.value().substring(4));
            return leaves.get(key);
        }
        ProofEvaluation evaluate(String name){
            var values=new ArrayList<String>();var dependencies=new ArrayList<QueryProof.Dependency>();
            for(var key:inputs.get(name)){
                String value=Objects.requireNonNull(value(key),"unsettled input "+key+" for "+name);
                values.add(value);dependencies.add(new QueryProof.Dependency(key,hash(value)));
            }
            String result=functions.get(name).apply(values);results.put(name,result);
            return new ProofEvaluation(new QueryProof(dependencies),out(name),hash(result));
        }
        /** Initial from-scratch registration in dependency order. */
        void registerAll(){
            var pending=new ArrayList<>(inputs.keySet());
            while(!pending.isEmpty()){
                for(var name:new ArrayList<>(pending)){
                    boolean ready=inputs.get(name).stream().allMatch(key->value(key)!=null);
                    if(ready){dag.register(consumer(name),evaluate(name));pending.remove(name);}
                }
            }
        }
        SemanticUpdatePolicy.ProofPropagation change(Map<String,String> values)throws Exception{
            var changed=new TreeMap<QueryProof.Key,Hash256>();
            values.forEach((key,value)->{leaves.put(leaf(key),value);changed.put(leaf(key),hash(value));});
            return dag.propagate(changed,consumer->{calls.add(consumer.id());return Optional.of(evaluate(consumer.id()));});
        }
        /** Expected converged results by recomputing everything from scratch. */
        Map<String,String> scratch(){
            var copy=new Model();copy.leaves.putAll(leaves);copy.inputs.putAll(inputs);copy.functions.putAll(functions);
            copy.registerAll();return copy.results;
        }
    }
    private static Function<List<String>,String> concat(String name){return values->name+"("+String.join(",",values)+")";}
    private static Function<List<String>,String> constant(String value){return ignored->value;}

    @Test void linearChainRecomputesEachConsumerOnceInDependencyOrder()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",concat("A"),leaf("L"));
        model.node("B",concat("B"),out("A"));
        model.node("C",concat("C"),out("B"));
        model.registerAll();
        assertThat(model.dag.height(model.consumer("C"))).hasValue(2);

        var result=model.change(Map.of("L","2"));
        assertThat(model.calls).containsExactly("A","B","C");
        assertThat(result.changed()).hasSize(3);
        assertThat(result.recomputations()).isEqualTo(3);
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void equalDepthDiamondRecomputesJoinOnceAfterBothBranches()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("B",concat("B"),leaf("L"));
        model.node("C",concat("C"),leaf("L"));
        model.node("D",concat("D"),out("B"),out("C"));
        model.registerAll();

        var result=model.change(Map.of("L","2"));
        assertThat(model.calls).containsExactly("B","C","D");
        assertThat(result.recomputations()).isEqualTo(3);
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void unequalPathLengthsDoNotRecomputeTheJoinTwice()throws Exception{
        // FIFO would recompute D after A (short path) and again after C (long path).
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",concat("A"),leaf("L"));
        model.node("B",concat("B"),out("A"));
        model.node("C",concat("C"),out("B"));
        model.node("D",concat("D"),leaf("L"),out("A"),out("C"));
        model.registerAll();
        assertThat(model.dag.height(model.consumer("D"))).hasValue(3);

        var result=model.change(Map.of("L","2"));
        assertThat(model.calls).containsExactly("A","B","C","D");
        assertThat(model.calls.stream().filter("D"::equals)).hasSize(1);
        assertThat(result.recomputations()).isEqualTo(4);
        assertThat(result.reordered()).isZero();
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void multipleChangedRootsSettleBeforeTheirSharedConsumer()throws Exception{
        var model=new Model();model.leaves.put(leaf("L1"),"1");model.leaves.put(leaf("L2"),"1");
        model.node("A",concat("A"),leaf("L1"));
        model.node("B",concat("B"),leaf("L2"));
        model.node("B2",concat("B2"),out("B"));
        model.node("D",concat("D"),out("A"),out("B2"));
        model.registerAll();

        var result=model.change(Map.of("L1","2","L2","2"));
        assertThat(model.calls.stream().filter("D"::equals)).hasSize(1);
        assertThat(model.calls.indexOf("D")).isGreaterThan(model.calls.indexOf("B2"));
        assertThat(result.recomputations()).isEqualTo(4);
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void intermediateResultThatRecomputesEqualStopsPropagation()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",constant("stable"),leaf("L"));
        model.node("B",concat("B"),out("A"));
        model.registerAll();

        var result=model.change(Map.of("L","2"));
        assertThat(model.calls).containsExactly("A");
        assertThat(result.equal()).containsExactly(model.consumer("A"));
        assertThat(result.changed()).isEmpty();
        // The certificate changed even though the result did not: certificate ≠ result identity.
        var a=model.dag.evaluation(model.consumer("A")).orElseThrow();
        assertThat(a.dependencies().identity(QueryProof.Domain.EXACT_SYMBOL,"L")).contains(hash("2"));
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void intermediateResultThatRecomputesChangedContinuesDownstream()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",concat("A"),leaf("L"));
        model.node("B",constant("stable-b"),out("A"));
        model.node("C",concat("C"),out("B"));
        model.registerAll();

        var result=model.change(Map.of("L","2"));
        assertThat(model.calls).containsExactly("A","B");
        assertThat(result.changed()).containsExactly(model.consumer("A"));
        assertThat(result.equal()).containsExactly(model.consumer("B"));
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void dependencyAddedDuringRecomputationWaitsForItsPendingProducer()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        // "Adder" sorts before "Producer" at equal height. On recompute it discovers Producer's output.
        model.node("Producer",concat("P"),leaf("L"));
        model.node("Adder",concat("Adder"),leaf("L"));
        model.registerAll();
        model.inputs.put("Adder",List.of(leaf("L"),out("Producer")));

        var result=model.change(Map.of("L","2"));
        assertThat(result.reordered()).isEqualTo(1);
        assertThat(model.calls.getLast()).isEqualTo("Adder");
        assertThat(model.calls.lastIndexOf("Adder")).isGreaterThan(model.calls.indexOf("Producer"));
        assertThat(model.dag.height(model.consumer("Adder"))).hasValue(1);
        var adder=model.dag.evaluation(model.consumer("Adder")).orElseThrow();
        assertThat(adder.dependencies().identity(QueryProof.Domain.WORKSPACE,"out:Producer"))
                .contains(hash(model.results.get("Producer")));
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void dependencyRemovedDuringRecomputationNoLongerSchedulesTheConsumer()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");model.leaves.put(leaf("M"),"1");
        model.node("A",concat("A"),leaf("M"));
        model.node("D",concat("D"),leaf("L"),out("A"));
        model.registerAll();
        assertThat(model.dag.height(model.consumer("D"))).hasValue(1);

        model.inputs.put("D",List.of(leaf("L")));
        model.change(Map.of("L","2"));
        assertThat(model.dag.height(model.consumer("D"))).hasValue(0);
        assertThat(model.dag.consumers(out("A"))).isEmpty();

        model.calls.clear();
        model.change(Map.of("M","2"));
        assertThat(model.calls).containsExactly("A");
        assertThat(model.results).isEqualTo(model.scratch());
    }

    @Test void cycleAttemptDuringRecomputationRetiresTheConsumerConservatively()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",concat("A"),leaf("L"));
        model.node("B",concat("B"),out("A"));
        model.registerAll();
        var a=model.consumer("A");

        var result=model.dag.propagate(Map.of(leaf("L"),hash("2")),consumer->Optional.of(
                new ProofEvaluation(new QueryProof(List.of(new QueryProof.Dependency(out("B"),hash("x")))),out("A"),hash("a2"))));
        assertThat(result.fallback()).contains(a,model.consumer("B"));
        assertThat(model.dag.evaluation(a)).isEmpty();
        assertThatThrownBy(()->model.dag.register(model.consumer("B"),new ProofEvaluation(
                new QueryProof(List.of(new QueryProof.Dependency(out("B2"),hash("x")))),out("B2"),hash("b"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unknownLeafRetiresConsumersTransitivelyWithoutRecomputingOrComparing()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",concat("A"),leaf("L"));
        model.node("B",concat("B"),out("A"));
        model.registerAll();

        var result=model.dag.propagateObserved(Map.of(leaf("L"),Optional.empty()),consumer->{
            throw new AssertionError("UNKNOWN evidence must not be recomputed against");
        });
        assertThat(result.deferred()).containsExactly(model.consumer("A"),model.consumer("B"));
        assertThat(result.invalidated()).hasSize(2);
        assertThat(model.dag.size()).isZero();
    }

    @Test void deferredRecomputationRetiresDownstreamUntilTheOwnerReRegisters()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",concat("A"),leaf("L"));
        model.node("B",concat("B"),out("A"));
        model.registerAll();

        var result=model.dag.propagateObserved(Map.of(leaf("L"),Optional.of(hash("2"))),
                consumer->Recomputation.deferred("needs javac"));
        assertThat(result.deferred()).containsExactly(model.consumer("A"),model.consumer("B"));
        assertThat(result.recomputations()).isEqualTo(1);
    }

    @Test void equalLeafEnqueuesNothing()throws Exception{
        var model=new Model();model.leaves.put(leaf("L"),"1");
        model.node("A",concat("A"),leaf("L"));
        model.registerAll();
        var result=model.change(Map.of("L","1"));
        assertThat(result.recomputed()).isEmpty();
        assertThat(model.calls).isEmpty();
    }

    @Test void randomDagsConvergeToFromScratchResultsWithAtMostOneRecomputationPerConsumer()throws Exception{
        var random=new Random(7);
        for(int trial=0;trial<200;trial++){
            var model=new Model();
            int leaves=1+random.nextInt(3),nodes=2+random.nextInt(12);
            for(int i=0;i<leaves;i++)model.leaves.put(leaf("L"+i),"0");
            for(int i=0;i<nodes;i++){
                var deps=new LinkedHashSet<QueryProof.Key>();
                int count=1+random.nextInt(3);
                for(int j=0;j<count;j++){
                    if(i>0&&random.nextBoolean())deps.add(out(String.format("N%02d",random.nextInt(i))));
                    else deps.add(leaf("L"+random.nextInt(leaves)));
                }
                String name=String.format("N%02d",i);
                boolean saturating=random.nextInt(4)==0;
                model.node(name,saturating?values->name+":"+(values.hashCode()&1):concat(name),deps.toArray(QueryProof.Key[]::new));
            }
            model.registerAll();
            var change=new HashMap<String,String>();
            for(int i=0;i<leaves;i++)if(random.nextBoolean())change.put("L"+i,Integer.toString(1+random.nextInt(3)));
            var result=model.change(change);
            assertThat(model.results).as("trial %d",trial).isEqualTo(model.scratch());
            assertThat(result.recomputations()).as("trial %d",trial).isEqualTo(new HashSet<>(model.calls).size());
            assertThat(result.reordered()).isZero();
        }
    }
}
