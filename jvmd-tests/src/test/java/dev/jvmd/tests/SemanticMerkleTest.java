package dev.jvmd.tests;

import static org.assertj.core.api.Assertions.*;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.WorkspaceBindings;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("phase-4")
class SemanticMerkleTest {
  @TempDir Path root;

  @Test
  void keyedIdentityIsIndependentOfHistoryAndBindsKeysAndDomains() {
    var expected = SemanticNode.empty("module-api");
    var keys = new ArrayList<String>();
    for (int i = 0; i < 100; i++) {
      keys.add("file" + i);
      expected =
          expected.withChild("file" + i, SemanticNode.leaf("file-api", List.of("value" + i), null));
    }
    var shuffled = SemanticNode.empty("module-api");
    Collections.shuffle(keys, new Random(17));
    for (String key : keys) shuffled = shuffled.withChild(key, expected.children().get(key));
    assertThat(shuffled.identity()).isEqualTo(expected.identity());
    var changed =
        shuffled.withChild("file40", null).withChild("file40", expected.children().get("file40"));
    assertThat(changed.identity()).isEqualTo(expected.identity());
    assertThat(changed.children().get("file12")).isSameAs(expected.children().get("file12"));
    assertThat(expected.withChild("file40", expected.children().get("file40"))).isSameAs(expected);
    assertThat(SemanticNode.empty("module-api").withChild("a", expected).identity())
        .isNotEqualTo(SemanticNode.empty("module-api").withChild("b", expected).identity());
    assertThat(SemanticNode.leaf("declaration-api", List.of("ab", "c"), null).identity())
        .isNotEqualTo(SemanticNode.leaf("declaration-api", List.of("a", "bc"), null).identity())
        .isNotEqualTo(SemanticNode.leaf("declaration-api", List.of("c", "ab"), null).identity())
        .isNotEqualTo(SemanticNode.leaf("file-api", List.of("ab", "c"), null).identity());
    var identity = expected.identity();
    assertThat(identity)
        .isNotEqualTo(
            new SemanticNode.Identity(
                identity.domain(), identity.schema(), "future-accumulator", identity.digest()));
  }

  @Test
  void persistentMapMatchesTreeMapAndKeepsOldReaders() {
    var random = new Random(12);
    var map = PersistentMap.<String, Integer>empty();
    var expected = new TreeMap<String, Integer>();
    for (int i = 0; i < 2000; i++) {
      String key = "key" + random.nextInt(200);
      var prior = map;
      var copy = new TreeMap<>(expected);
      if (random.nextBoolean()) {
        map = map.with(key, i);
        expected.put(key, i);
      } else {
        map = map.without(key);
        expected.remove(key);
      }
      assertThat(map).isEqualTo(expected);
      assertThat(prior).isEqualTo(copy);
    }
  }

  @Test
  void implementationAndPresentationEditsReuseApiWhileReferencesRefresh() throws Exception {
    Path file = root.resolve("Api.java");
    String original =
        "class Api { private static class Hidden { int x; } int value(int input){int local=1;"
            + " return input+local;} }";
    try (var analyzer = analyzer(root, Map.of())) {
      var first = capture(analyzer, file, original);
      var second =
          capture(
              analyzer,
              file,
              "/** shifted */\n"
                  + original
                      .replace("int x;", "String y;")
                      .replace("input", "argument")
                      .replace("local", "renamed"));
      assertThat(second.api()).isSameAs(first.api());
      assertThat(second.api().root()).isSameAs(first.api().root());
      assertThat(second.occurrences()).isNotEqualTo(first.occurrences());
      assertThat(second.symbols().values()).anyMatch(row -> "renamed".equals(row.get("name")));
      assertThat(second.api().declarations().values())
          .noneMatch(contract -> contract.kind().equals("LOCAL_VARIABLE"));
      var changed = capture(analyzer, file, original.replace("int value", "long value"));
      assertThat(changed.api().root().identity()).isNotEqualTo(first.api().root().identity());
    }
  }

  @Test
  void inheritedMembersFromPrivateOwnersRemainPartOfTheContract() throws Exception {
    Path file = root.resolve("Api.java");
    String original =
        "class Api { private static class Hidden { public int value(){return 1;} } public static"
            + " class Exposed extends Hidden {} }";
    try (var analyzer = analyzer(root, Map.of())) {
      var first = capture(analyzer, file, original);
      var changed =
          capture(analyzer, file, original.replace("public int value", "public long value"));
      assertThat(changed.api().root().identity()).isNotEqualTo(first.api().root().identity());
    }
  }

  @Test
  void constantsBoundsAnnotationsAndParameterOrderChangeIdentity() throws Exception {
    Path file = root.resolve("Api.java");
    String original =
        "class Api<T extends Number> { static final int N=1; int value(String a, Integer b){return"
            + " N;} }";
    try (var analyzer = analyzer(root, Map.of())) {
      var first = capture(analyzer, file, original).api().root().identity();
      for (String changed :
          List.of(
              original.replace("N=1", "N=2"),
              original.replace("extends Number", "extends Object"),
              original.replace("int value", "@Deprecated int value"),
              original.replace("String a, Integer b", "Integer a, String b"))) {
        assertThat(capture(analyzer, file, changed).api().root().identity()).isNotEqualTo(first);
      }
    }
  }

  @Test
  void emptyStringConstantDiffersFromNonConstantInitializer() throws Exception {
    Path file = root.resolve("Api.java");
    try (var analyzer = analyzer(root, Map.of())) {
      var constant =
          capture(analyzer, file, "class Api { static final String VALUE=\"\"; }")
              .api()
              .root()
              .identity();
      var nonConstant =
          capture(analyzer, file, "class Api { static final String VALUE=new String(); }")
              .api()
              .root()
              .identity();
      assertThat(nonConstant).isNotEqualTo(constant);
    }
  }

  @Test
  void moduleAndWorkspaceRootsStopOnBodyEditsAndOldNavigationIsIsolated() throws Exception {
    Path firstRoot = Files.createDirectories(root.resolve("first"));
    Path secondRoot = Files.createDirectories(root.resolve("second"));
    Path api = firstRoot.resolve("Api.java"), user = secondRoot.resolve("User.java");
    Files.writeString(
        api, "class Api { static Number a(){return 1;} static Number b(){return 2;} }");
    Files.writeString(user, "class User { Object read(){return Api.a();} }");
    var documents = new Documents();
    var coordinates =
        Map.of(
            firstRoot.toUri().toString(),
            "test:first:1",
            secondRoot.toUri().toString(),
            "test:second:1");
    try (var analyzer = new Analyzer();
        var cache = new WorkspaceBindings()) {
      analyzer.configure(
          new Analyzer.Context(
              "test:app:1", "25", List.of(), List.of(firstRoot, secondRoot), "ctx", coordinates),
          null,
          256L * 1024 * 1024);
      analyzer.documents(documents);
      WorkspaceBindings.SourceFiles files = () -> List.of(api, user);
      var first =
          cache.getBatch(
              files, List.of(), documents, "ctx", 64L * 1024 * 1024, analyzer::bindingsBatch);
      var old = Json.MAPPER.valueToTree(first);
      Files.writeString(user, "class User { Object read(){return Api.b();} }");
      var body =
          cache.getBatch(
              files, List.of(), documents, "ctx", 64L * 1024 * 1024, analyzer::bindingsBatch);
      assertThat(first.diagnostics()).noneMatch(problem -> problem.kind().equals("ERROR"));
      assertThat(body.diagnostics()).noneMatch(problem -> problem.kind().equals("ERROR"));
      assertThat(body.apiRoot()).isSameAs(first.apiRoot());
      assertThat(body.edges())
          .anyMatch(edge -> edge.dst().endsWith("Api#b().") && edge.kind().equals("calls"));
      assertThat(cache.status()).containsEntry("last_reanalysed_files", 1);
      assertThat((com.fasterxml.jackson.databind.JsonNode) Json.MAPPER.valueToTree(first))
          .isEqualTo(old);
      Files.writeString(
          api, "class Api { static Integer a(){return 1;} static Number b(){return 2;} }");
      var changed =
          cache.getBatch(
              files, List.of(), documents, "ctx", 64L * 1024 * 1024, analyzer::bindingsBatch);
      assertThat(changed.apiRoot().identity()).isNotEqualTo(body.apiRoot().identity());
      assertThat(changed.apiRoot().children().get("test:second:1"))
          .isSameAs(body.apiRoot().children().get("test:second:1"));
      try (var clean = new WorkspaceBindings()) {
        var rebuilt =
            clean.getBatch(
                files, List.of(), documents, "ctx", 64L * 1024 * 1024, analyzer::bindingsBatch);
        assertThat(changed.apiRoot().identity()).isEqualTo(rebuilt.apiRoot().identity());
        assertThat((com.fasterxml.jackson.databind.JsonNode) Json.MAPPER.valueToTree(changed))
            .isEqualTo(Json.MAPPER.valueToTree(rebuilt));
      }
    }
  }

  @Test
  void completeDependenciesReplaceEdgesAndPartialObservationsRemainConservative() {
    var graph = new DependencyGraph<String>();
    graph.record("user", Set.of("old"), true);
    graph.record("user", Set.of("new"), false);
    assertThat(graph.affected(Set.of("old"))).contains("user");
    graph.record("user", Set.of("new"), true);
    assertThat(graph.affected(Set.of("old"))).doesNotContain("user");
    assertThat(graph.affected(Set.of("new"))).contains("user");
  }

  @Test
  void publicationCannotAdoptAnEpochObservedAfterItsInputRead() throws Exception {
    Path file = root.resolve("A.java");
    Files.writeString(file, "class A {}");
    long[] epoch = {0};
    var documents = new Documents();
    WorkspaceBindings.Validation validation =
        () -> new WorkspaceBindings.ValidationToken("ctx", 0, Map.of("root", epoch[0]), Map.of());
    WorkspaceBindings.BatchLoader loader =
        values ->
            Map.of(
                file,
                new CompilerPool.Outcome<>(
                    2,
                    new Bindings.Snapshot(Map.of(), List.of(), List.of(), Set.of()),
                    List.of(),
                    List.of()));
    try (var cache = new WorkspaceBindings()) {
      cache.getBatch(
          () -> List.of(file), List.of(), documents, "ctx", 1024 * 1024, validation, loader);
      epoch[0]++;
      WorkspaceBindings.SourceFiles sources =
          () -> {
            epoch[0]++;
            return List.of(file);
          };
      assertThat(cache.peek(sources, List.of(), documents, "ctx", validation)).isNull();
      assertThat(cache.status()).containsEntry("fast_validation_ready", false);
    }
  }

  private Analyzer analyzer(Path source, Map<String, String> coordinates) throws Exception {
    var analyzer = new Analyzer();
    analyzer.configure(
        new Analyzer.Context("test:app:1", "25", List.of(), List.of(source), "ctx", coordinates),
        null,
        256L * 1024 * 1024);
    analyzer.documents(new Documents());
    return analyzer;
  }

  private static Bindings.Snapshot capture(Analyzer analyzer, Path file, String text)
      throws Exception {
    Files.writeString(file, text);
    var outcome = analyzer.bindings(file, text, null);
    assertThat(outcome.tier()).isEqualTo(2);
    assertThat(outcome.warnings()).isEmpty();
    assertThat(outcome.diagnostics()).noneMatch(problem -> problem.kind().equals("ERROR"));
    return outcome.result();
  }
}
