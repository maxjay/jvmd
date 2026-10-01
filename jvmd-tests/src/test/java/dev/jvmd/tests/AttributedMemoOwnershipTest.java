package dev.jvmd.tests;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** Attributed memo keys and records are built by {@code AttributedMemos}; {@code Analyzer} only calls it. */
class AttributedMemoOwnershipTest {
    @Test void analyzerDoesNotBuildMemoKeysOrRecords()throws Exception{
        var analyzer=TestSupport.repo().resolve("jvmd-analyzer/src/main/java/dev/jvmd/analyzer/Analyzer.java");
        assertThat(Files.readString(analyzer)).as("memo logic belongs to AttributedMemos")
                .doesNotContain("attributedStaticKey(").doesNotContain("new SemanticMemoStore.MemoRecord(");
    }
}
