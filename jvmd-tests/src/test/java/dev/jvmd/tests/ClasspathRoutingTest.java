package dev.jvmd.tests;

import dev.jvmd.index.ClasspathRouting;
import dev.jvmd.index.ClasspathRouting.*;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** Architecture §17–18 and Phase 13: the left-biased union monoid and routing equivalences. */
class ClasspathRoutingTest {
    private static List<Slot> randomClasspath(Random random,int slots,int universe){
        var result=new ArrayList<Slot>();
        for(int i=0;i<slots;i++){
            var names=new HashSet<String>();int count=random.nextInt(universe/2+1);
            for(int j=0;j<count;j++)names.add("p.T"+random.nextInt(universe));
            result.add(new Slot("artifact:g:a"+i+":1|a"+i+".jar",names));
        }
        return result;
    }

    @Test void leftBiasedUnionIsAMonoid(){
        var random=new Random(5);
        for(int trial=0;trial<300;trial++){
            var slots=randomClasspath(random,3,20);
            var l=Routing.of(slots.get(0));var r=Routing.of(slots.get(1));var q=Routing.of(slots.get(2));
            assertThat(l.then(r).then(q)).isEqualTo(l.then(r.then(q)));
            assertThat(Routing.EMPTY.then(l)).isEqualTo(l);assertThat(l.then(Routing.EMPTY)).isEqualTo(l);
            var union=new HashSet<>(l.domain());union.addAll(r.domain());
            assertThat(l.then(r).domain()).as("existence is a projection").isEqualTo(union);
        }
    }

    @Test void flatFoldAndFilteredSearchAgreeWithFirstWinnerSearch(){
        var random=new Random(9);
        for(int trial=0;trial<100;trial++){
            var classpath=randomClasspath(random,1+random.nextInt(12),60);
            var routing=ClasspathRouting.fold(classpath);
            var composed=classpath.stream().map(Routing::of).reduce(Routing.EMPTY,Routing::then);
            assertThat(routing).isEqualTo(composed);
            var filters=new HashMap<String,Filter>();for(var slot:classpath)filters.put(slot.key(),Filter.of(slot,10));
            for(int i=0;i<80;i++){
                String name="p.T"+i;var expected=ClasspathRouting.search(classpath,name);
                assertThat(routing.winner(name)).isEqualTo(expected);
                var filtered=ClasspathRouting.filteredSearch(classpath,filters,name);
                assertThat(filtered.winner()).isEqualTo(expected);
                assertThat(filtered.canonicalChecks()).isLessThanOrEqualTo(classpath.size());
            }
        }
    }

    @Test void unboundOrMissingFiltersFallBackToCanonicalChecks(){
        var a=new Slot("artifact:g:a:1|a.jar",Set.of("p.A"));var b=new Slot("artifact:g:b:1|b.jar",Set.of("p.B"));
        // A filter built for another slot must never be trusted for this one.
        var filters=Map.of(a.key(),Filter.of(b,10));
        var result=ClasspathRouting.filteredSearch(List.of(a,b),filters,"p.A");
        assertThat(result.winner()).contains(a.key());
        assertThat(ClasspathRouting.filteredSearch(List.of(a,b),Map.of(),"p.B").canonicalChecks()).isEqualTo(2);
    }
}
