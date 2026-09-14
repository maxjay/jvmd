package dev.jvmd.tests;

import dev.jvmd.analyzer.Parser;
import dev.jvmd.core.Json;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: parse tier 0 declaration overview and source lines. */
@Tag("phase-1")
class ParseOverviewTest {
    @Test void nestedDeclarationsWithoutMethodBodies() throws Exception {
        try (var parser = new Parser()) {
            var result = parser.overview(Path.of("Example.java"), "class Example {\n String name;\n int answer() { int local = 42; return local; }\n}", 1, 100);
            assertThat(result.tier()).isZero();
            var symbols = Json.MAPPER.valueToTree(result.result()).path("symbols");
            assertThat(symbols).hasSize(3);
            assertThat(symbols.get(2).path("name").asText()).isEqualTo("answer");
            assertThat(symbols.get(2).path("line").asInt()).isEqualTo(3);
            assertThat(symbols.toString()).doesNotContain("return local");
        }
    }
}
