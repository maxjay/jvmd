package dev.jvmd.tests;

import dev.jvmd.analyzer.Parser;
import dev.jvmd.core.Json;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: structured syntax diagnostics from DiagnosticListener. */
@Tag("phase-1")
class SyntaxDiagnosticsTest {
    @Test void brokenSourceHasCodesAndLiveProvenance() throws Exception {
        try (var parser = new Parser()) {
            var result = parser.overview(Path.of("Broken.java"), "class Broken { void ok() {} void bad() { int x = ; } }", 1, 100);
            var diagnostics = Json.MAPPER.valueToTree(result.result()).path("diagnostics");
            assertThat(diagnostics.isEmpty()).isFalse();
            assertThat(diagnostics.get(0).path("code").asText()).startsWith("compiler.err.");
            assertThat(diagnostics.get(0).path("source").asText()).isEqualTo("live");
            assertThat(diagnostics.get(0).path("tier").asInt()).isZero();
        }
    }
    @Test void editingAPreviouslyParsedFileDoesNotRetainOldDiagnostics() throws Exception {
        try (var parser = new Parser()) {
            Path path=Path.of("Edited.java");String broken="class Edited { int value = ; }";
            var first=parser.overview(path,broken,1,10);
            assertThat(Json.MAPPER.valueToTree(first.result()).path("diagnostics").isEmpty()).isFalse();
            var fixed=parser.overview(path,broken.replace("= ;","=0;"),1,10);
            assertThat(Json.MAPPER.valueToTree(fixed.result()).path("diagnostics").isEmpty()).isTrue();
            assertThat(Json.MAPPER.valueToTree(parser.overview(path,broken,1,10).result()).path("diagnostics").isEmpty()).isFalse();
        }
    }
}
