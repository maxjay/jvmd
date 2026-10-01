package dev.jvmd.core;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** Two wrapping 64-bit lanes plus cardinality form a commutative group over contributions. */
@Tag("phase-1")
class AccumulatorAlgebraTest {
    private static List<AlgebraicAccumulator.Value> contributions(Random random,int count){
        var result=new ArrayList<AlgebraicAccumulator.Value>();
        for(int i=0;i<count;i++)result.add(AlgebraicAccumulator.contribution("d","k"+random.nextInt(50),new Id128(random.nextLong(),random.nextLong())));
        return result;
    }
    private static AlgebraicAccumulator.Value fold(List<AlgebraicAccumulator.Value> values){
        var result=AlgebraicAccumulator.Value.ZERO;for(var value:values)result=result.plus(value);return result;
    }

    @Test void sumIsIndependentOfOrder(){
        var random=new Random(11);
        for(int round=0;round<50;round++){
            var values=contributions(random,1+random.nextInt(40));var shuffled=new ArrayList<>(values);Collections.shuffle(shuffled,random);
            assertThat(fold(shuffled)).isEqualTo(fold(values));
            assertThat(fold(shuffled).identity("d")).isEqualTo(fold(values).identity("d"));
        }
    }

    @Test void minusUndoesPlus(){
        var random=new Random(12);
        for(int round=0;round<200;round++){
            var x=fold(contributions(random,random.nextInt(10)));var c=contributions(random,1).getFirst();
            assertThat(x.plus(c).minus(c)).isEqualTo(x);
        }
        var accumulator=new AlgebraicAccumulator("d");var empty=accumulator.identity();
        accumulator.add("k",new Id128(1,2));accumulator.replace("k",new Id128(1,2),"k",new Id128(3,4));accumulator.remove("k",new Id128(3,4));
        assertThat(accumulator.identity()).isEqualTo(empty);assertThat(accumulator.cardinality()).isZero();
    }

    @Test void identityBindsCardinalityEvenWhenLanesAreEqual(){
        var one=new AlgebraicAccumulator.Value(7,9,1);var two=new AlgebraicAccumulator.Value(7,9,2);
        assertThat(one.identity("d")).isNotEqualTo(two.identity("d"));
        assertThat(one.identity("d")).isNotEqualTo(one.identity("e"));
        assertThatThrownBy(()->AlgebraicAccumulator.Value.ZERO.minus(one)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lanesWrapInsteadOfOverflowing(){
        var max=new AlgebraicAccumulator.Value(Long.MAX_VALUE,-1,1);
        var wrapped=max.plus(max);
        assertThat(wrapped.a()).isEqualTo(-2L);assertThat(wrapped.b()).isEqualTo(-2L);
        assertThat(wrapped.minus(max)).isEqualTo(max);
    }

    @Test void rangeSumsEqualTheFold(){
        var random=new Random(13);var values=contributions(random,64);
        for(int split=0;split<=values.size();split++)
            assertThat(fold(values.subList(0,split)).plus(fold(values.subList(split,values.size())))).isEqualTo(fold(values));
    }

    @Test void contributionMatchesTheVarargsEncoding(){
        var key="k";var value=new Id128(5,6);
        var expected=IdentityEncoder.of("aggregate-contribution-v3","d",key,value);
        var contribution=AlgebraicAccumulator.contribution("d",key,value);
        assertThat(new Id128(contribution.a(),contribution.b())).isEqualTo(expected);
        assertThat(contribution.identity("d")).isEqualTo(IdentityEncoder.of("aggregate-v3","d",1L,contribution.a(),contribution.b()));
    }
}
