package dev.jvmd.tests;

import dev.jvmd.core.Hash256;
import dev.jvmd.analyzer.NamespaceResolutionProofs;
import dev.jvmd.index.QueryProof;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@Tag("phase-1")
class NamespaceResolutionProofsTest {
    private static Hash256 hash(String value){return Hash256.sha256(value.getBytes(StandardCharsets.UTF_8));}

    @Test void currentPackageWinnerStopsBeforeWildcardAndJavaLang(){
        var plan=NamespaceResolutionProofs.plan(
                "package p; import q.*; class Use {}", "Api", "p.Api");
        assertThat(plan.domains()).containsExactly("p.Api");
    }

    @Test void explicitImportDefinesTheExactSearchDomain(){
        var plan=NamespaceResolutionProofs.plan(
                "package p; import a.Api; import b.*; class Use {}", "Api", "a.Api");
        assertThat(plan.domains()).containsExactly("a.Api");
    }

    @Test void wildcardLookupCapturesCurrentPackageJavaLangAndEveryOnDemandPackage(){
        var plan=NamespaceResolutionProofs.plan(
                "package p; import b.*; import a.*; class Use {}", "Api", "a.Api");
        assertThat(plan.domains()).containsExactly("p.Api","a.Api","b.Api","java.lang.Api");
    }

    @Test void javaLangIsAnExplicitProofDomainWhenLookupReachesOnDemandImports(){
        var plan=NamespaceResolutionProofs.plan(
                "import q.*; class Use {}", "String", "java.lang.String");
        assertThat(plan.domains()).containsExactly("String","java.lang.String","q.String");
    }

    @Test void negativeLookupCapturesOnlySearchedDomainsAndChangesWhenOneBecomesResolvable()throws Exception{
        var plan=NamespaceResolutionProofs.plan(
                "package p; import a.*; class Use {}", "Missing", null);
        Map<String,Hash256> identities=new HashMap<>();
        NamespaceResolutionProofs.Lookup lookup=binary->Optional.ofNullable(identities.get(binary));

        var before=new QueryProof(NamespaceResolutionProofs.dependencies(plan,lookup));
        var repeated=new QueryProof(NamespaceResolutionProofs.dependencies(plan,lookup));
        assertThat(before).isEqualTo(repeated);
        assertThat(before.dependencies().stream()
                .filter(value->value.key().domain()==QueryProof.Domain.NEGATIVE_RESOLUTION)
                .map(value->value.key().value()))
                .containsExactly("Missing@a.Missing","Missing@java.lang.Missing","Missing@p.Missing");

        identities.put("a.Missing",hash("a-missing-v1"));
        var after=new QueryProof(NamespaceResolutionProofs.dependencies(plan,lookup));
        var difference=before.diff(after);
        assertThat(difference.added()).isEmpty();
        assertThat(difference.removed()).isEmpty();
        assertThat(difference.changed()).extracting(change->change.key().value())
                .containsExactlyInAnyOrder("Missing@a.Missing","type:a.Missing");
    }

    @Test void resolvedWildcardWinnerLeavesOtherSearchedDomainsAsNegativeEvidence()throws Exception{
        var plan=NamespaceResolutionProofs.plan(
                "package p; import a.*; import b.*; class Use {}", "Api", "a.Api");
        var proof=new QueryProof(NamespaceResolutionProofs.dependencies(plan,binary->Optional.empty()));
        assertThat(proof.dependencies().stream()
                .filter(value->value.key().domain()==QueryProof.Domain.NEGATIVE_RESOLUTION)
                .map(value->value.key().value()))
                .containsExactly("Api@b.Api","Api@java.lang.Api","Api@p.Api");
    }
}
