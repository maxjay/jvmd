package dev.jvmd.tests.oracle;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.local.Proof;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class ReadOracleCoverageTest {
    private static final Identity ZERO = Identity.zero(Sha256.INSTANCE.width());
    private static final Identity NONZERO = Sha256.INSTANCE.hash(new byte[]{1});

    private static Proof proof(List<Proof.Entry> entries, List<String> absent) {
        return new Proof(new Proof.Header(NONZERO, NONZERO, NONZERO, NONZERO, NONZERO, null),
                entries.isEmpty() ? List.of() : List.of(new Proof.Type("p/Lib", NONZERO, entries)), absent);
    }
    private static Proof.Entry entry(int form, int kind, String name, Identity sum) {
        return new Proof.Entry(new Proof.Range(form, "p/Lib", kind, name), sum);
    }

    @Test void aggregateIdentitiesCannotCoverUnobservedTypesOrAbsences() {
        assertThat(ReadOracleTrace.uncovered(proof(List.of(), List.of()), List.of("p/Lib"),
                List.of(new ReadOracleTrace.Missing("D", "p/Missing", ""))))
                .containsExactly("LOAD p/Lib", "ABSENT Missing[kind=D, owner=p/Missing, name=]");
    }

    @Test void aNamedRangeCoversLoadedStateButNonzeroCannotCoverAbsence() {
        var missing = new ReadOracleTrace.Missing("METHOD", "p/Lib", "run");
        var proof = proof(List.of(entry(Proof.T, 2, "run", NONZERO)), List.of());
        assertThat(ReadOracleTrace.uncovered(proof, List.of("p/Lib"), List.of(missing)))
                .containsExactly("ABSENT " + missing);
    }

    @Test void ordinaryTypeEntriesCannotCoverModuleQualifiedDescriptorReads() {
        var type = new Proof.Type("java/base/module-info", NONZERO,
                List.of(new Proof.Entry(new Proof.Range(Proof.T, "java/base/module-info", 0, ""), NONZERO)));
        var proof = new Proof(new Proof.Header(NONZERO, NONZERO, NONZERO, NONZERO, NONZERO, null),
                List.of(type), List.of());
        assertThat(ReadOracleTrace.uncovered(proof, List.of("java/base/module-info"), List.of("java.base"), List.of()))
                .containsExactly("MODULE java.base");
    }

    @Test void zeroMustHaveTheExactOwnerNamespaceAndNameOrAnEmptyWholeKind() {
        var proof = proof(List.of(entry(Proof.T, 1, "value", ZERO), entry(Proof.T, 2, "", ZERO),
                entry(Proof.N, 0, "Nested", ZERO)), List.of("p/Missing"));
        var misses = List.of(new ReadOracleTrace.Missing("FIELD", "p/Lib", "value"),
                new ReadOracleTrace.Missing("METHOD", "p/Lib", "run"),
                new ReadOracleTrace.Missing("N", "p/Lib", "Nested"),
                new ReadOracleTrace.Missing("D", "p/Missing", ""));
        assertThat(ReadOracleTrace.uncovered(proof, List.of("p/Lib"), misses)).isEmpty();
        assertThat(ReadOracleTrace.uncovered(proof, List.of(), List.of(
                new ReadOracleTrace.Missing("FIELD", "p/Lib", "Nested"),
                new ReadOracleTrace.Missing("METHOD", "q/Other", "run"),
                new ReadOracleTrace.Missing("N", "p/Lib", "Other")))) .hasSize(3);
    }
}
