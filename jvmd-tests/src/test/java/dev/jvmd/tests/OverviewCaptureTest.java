package dev.jvmd.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Documents;
import dev.jvmd.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

@Tag("phase-4")
class OverviewCaptureTest {
  @TempDir Path root;

  @Test
  void preservesSourceOrderDepthUnresolvedRowsAndSparseProtocol() throws Exception {
    Path file = root.resolve("Example.java");
    String source =
        """
        class Example<T extends Number> {
          Missing missing;
          /** Return the input. */
          T echo(T input) { class Local {} return nonexistent(); }
          Missing broken(Missing input) { return input; }
          class Nested { int field; }
        }
        """;
    Files.writeString(file, source);
    try (var analyzer = analyzer()) {
      var shallow = symbols(analyzer, file, source, 0);
      assertThat(names(shallow)).containsExactly("Example");
      var rows = symbols(analyzer, file, source, 1);
      assertThat(names(rows)).containsExactly("Example", "missing", "echo", "broken", "Nested");
      assertThat(names(symbols(analyzer, file, source, 2)))
          .containsExactly("Example", "missing", "echo", "broken", "Nested", "field");
      var fields =
          List.of(
              "name",
              "kind",
              "signature",
              "name_path",
              "scip",
              "resolved",
              "gav",
              "modifiers",
              "file",
              "source_file",
              "line",
              "character",
              "start",
              "end",
              "source_start",
              "source_end",
              "name_start",
              "name_end",
              "range",
              "name_range",
              "doc",
              "fqn",
              "declaring",
              "parameters",
              "erased_descriptor",
              "body_start",
              "body_end");
      for (var row : rows) {
        assertThat(source.substring(row.path("name_start").asInt(), row.path("name_end").asInt()))
            .isEqualTo(row.path("name").asText());
        var keys = new ArrayList<String>();
        row.fieldNames().forEachRemaining(keys::add);
        keys.remove("signature_complete");
        assertThat(keys).containsExactlyInAnyOrderElementsOf(fields);
      }
      assertThat(rows.get(1).path("body_start").asInt()).isEqualTo(-1);
      assertThat(rows.get(2).path("doc").asText()).contains("Return the input.");
      assertThat(rows.get(2).path("scip").asText()).contains("echo(java.lang.Number).");
      assertThat(rows.get(3).path("resolved").asBoolean()).isFalse();
      assertThat(rows.get(3).path("scip").isNull()).isTrue();
    }
  }

  @Test
  void updatesDocumentationAndPositionsWithoutChangingDeclarationIdentity() throws Exception {
    Path file = root.resolve("Example.java");
    String original = "record Example(int value) { /** Old doc. */ Example { } }";
    Files.writeString(file, original);
    try (var analyzer = analyzer()) {
      var before = symbols(analyzer, file, original, 1);
      String changed = "\n\n" + original.replace("Old doc.", "Current doc.");
      Files.writeString(file, changed);
      var after = symbols(analyzer, file, changed, 1);
      assertThat(names(after)).isEqualTo(names(before));
      boolean constructor = false;
      for (int i = 0; i < after.size(); i++) {
        var row = after.get(i);
        assertThat(row.path("scip")).isEqualTo(before.get(i).path("scip"));
        assertThat(changed.substring(row.path("name_start").asInt(), row.path("name_end").asInt()))
            .isEqualTo(row.path("name").asText());
        if (row.path("kind").asText().equals("ctor")) {
          constructor = true;
          assertThat(row.path("doc").asText()).contains("Current doc.");
          assertThat(row.path("name_start").asInt()).isEqualTo(changed.indexOf("Example {"));
        }
      }
      assertThat(constructor).isTrue();
    }
  }

  private Analyzer analyzer() throws Exception {
    var analyzer = new Analyzer();
    analyzer.configure(
        new Analyzer.Context(
            "test:overview:1", "25", List.of(), List.of(root), "overview", Map.of()),
        null,
        256L * 1024 * 1024);
    analyzer.documents(new Documents());
    return analyzer;
  }

  private static JsonNode symbols(Analyzer analyzer, Path file, String text, int depth)
      throws Exception {
    var envelope = analyzer.overview(file, text, depth, 1000, 0);
    assertThat(envelope.tier()).isEqualTo(1);
    assertThat(envelope.warnings()).isEmpty();
    return Json.MAPPER.valueToTree(envelope.result()).path("symbols");
  }

  private static List<String> names(JsonNode rows) {
    var names = new ArrayList<String>();
    rows.forEach(row -> names.add(row.path("name").asText()));
    return names;
  }
}
