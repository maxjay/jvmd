package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.index.QueryProof;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class QueryProofTest {
    private static Hash256 hash(String value){
        return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private static QueryProof.Dependency dependency(QueryProof.Domain domain,String key,String identity){
        return new QueryProof.Dependency(domain,key,hash(identity));
    }

    @Test void proofIdentityIsCanonicalAcrossInputOrder(){
        var exact=dependency(QueryProof.Domain.EXACT_SYMBOL,"A#foo().","foo-v1");
        var range=dependency(QueryProof.Domain.MEMBER_RANGE,"A\0get\0get\uffff","get-range-v1");
        var hierarchy=dependency(QueryProof.Domain.HIERARCHY,"A","hierarchy-v1");

        var first=new QueryProof(List.of(range,exact,hierarchy));
        var second=new QueryProof(List.of(hierarchy,range,exact));

        assertThat(first).isEqualTo(second);
        assertThat(first.identity()).isEqualTo(second.identity());
        assertThat(first.dependencies()).containsExactly(exact,range,hierarchy);
        assertThatThrownBy(()->first.dependencies().add(exact)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void proofIdentitySeparatesSemanticDomains(){
        var identity=hash("same");
        var symbol=new QueryProof(List.of(new QueryProof.Dependency(QueryProof.Domain.EXACT_SYMBOL,"same-key",identity)));
        var namespace=new QueryProof(List.of(new QueryProof.Dependency(QueryProof.Domain.NAMESPACE,"same-key",identity)));

        assertThat(symbol.identity()).isNotEqualTo(namespace.identity());
        assertThat(symbol.proves(QueryProof.Domain.EXACT_SYMBOL,"same-key",identity)).isTrue();
        assertThat(symbol.proves(QueryProof.Domain.NAMESPACE,"same-key",identity)).isFalse();
    }

    @Test void changedValueChangesCanonicalProofIdentity(){
        var before=new QueryProof(List.of(dependency(QueryProof.Domain.RECEIVER,"receiver","v1")));
        var after=new QueryProof(List.of(dependency(QueryProof.Domain.RECEIVER,"receiver","v2")));

        assertThat(before.identity()).isNotEqualTo(after.identity());
        assertThat(before.diff(after).changed()).containsExactly(
                new QueryProof.Change(new QueryProof.Key(QueryProof.Domain.RECEIVER,"receiver"),hash("v1"),hash("v2")));
    }

    @Test void diffIdentifiesAddedChangedAndRemovedDependenciesDeterministically(){
        var oldSymbol=dependency(QueryProof.Domain.EXACT_SYMBOL,"A#one().","symbol-v1");
        var namespace=dependency(QueryProof.Domain.NAMESPACE,"foo.bar","namespace-v1");
        var hierarchy=dependency(QueryProof.Domain.HIERARCHY,"A","hierarchy-v1");
        var changedSymbol=dependency(QueryProof.Domain.EXACT_SYMBOL,"A#one().","symbol-v2");
        var overload=dependency(QueryProof.Domain.OVERLOAD_GROUP,"A#one","overload-v1");

        var before=new QueryProof(List.of(namespace,hierarchy,oldSymbol));
        var after=new QueryProof(List.of(overload,changedSymbol,hierarchy));
        var difference=before.diff(after);

        assertThat(difference.equal()).isFalse();
        assertThat(difference.added()).containsExactly(overload);
        assertThat(difference.removed()).containsExactly(namespace);
        assertThat(difference.changed()).containsExactly(
                new QueryProof.Change(oldSymbol.key(),oldSymbol.identity(),changedSymbol.identity()));
        assertThat(after.diff(new QueryProof(List.of(hierarchy,changedSymbol,overload))).equal()).isTrue();
    }

    @Test void duplicateSemanticKeysAreRejectedEvenWhenIdentityMatches(){
        var dependency=dependency(QueryProof.Domain.CLASSPATH,"position:3","artifact-v1");
        assertThatThrownBy(()->new QueryProof(List.of(dependency,dependency)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate proof dependency");
    }

    @Test void emptyProofIsStableAndDetached(){
        var first=QueryProof.empty();
        var second=QueryProof.empty();

        assertThat(first.identity()).isEqualTo(second.identity());
        assertThat(first.dependencies()).isEmpty();
        assertThat(first.diff(second).equal()).isTrue();
    }
}
