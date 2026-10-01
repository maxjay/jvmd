package dev.jvmd.tests;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict task W1 gate: attributed memo logic lives in {@code AttributedMemos}, and
 * {@code Analyzer.java} stays at or below 2,500 lines (it must not grow in later workstreams).
 */
class AnalyzerSizeGateTest {
    @Test void analyzerStaysWithinItsLineBudget()throws Exception{
        var analyzer=TestSupport.repo().resolve("jvmd-analyzer/src/main/java/dev/jvmd/analyzer/Analyzer.java");
        long lines;try(var stream=Files.lines(analyzer)){lines=stream.count();}
        assertThat(lines).as("Analyzer.java lines").isLessThanOrEqualTo(2500);
        assertThat(Files.readString(analyzer)).as("memo logic belongs to AttributedMemos")
                .doesNotContain("attributedStaticKey(").doesNotContain("new SemanticMemoStore.MemoRecord(");
    }
}
