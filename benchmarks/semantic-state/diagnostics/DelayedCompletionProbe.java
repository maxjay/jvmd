import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.Json;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Diagnostic only: withhold watcher delivery to isolate cached-input validation from event timing. */
public final class DelayedCompletionProbe {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    var results = new LinkedHashMap<String, Object>();
    for (boolean initiallyPresent : List.of(true, false)) {
      Path workspace = Files.createDirectories(root.resolve(initiallyPresent ? "existing" : "added"));
      Path api = workspace.resolve("Api.java");
      if (initiallyPresent) Files.writeString(api, "class Api { int getPets(){return 1;} }");
      String text = "class Use { Object call(Api api){return api.get();} }";
      Path use = Files.writeString(workspace.resolve("Use.java"), text);
      try (var analyzer = new Analyzer()) {
        analyzer.configure(new Analyzer.Context("probe:completion:1", "25", List.of(),
            List.of(workspace), "probe", Map.of()), null, 256L * 1024 * 1024);
        var first = complete(analyzer, use, text);
        holdEvents(analyzer);
        if (initiallyPresent) {
          var time = Files.getLastModifiedTime(api);
          Files.writeString(api, "class Api { String getPets(){return \"changed\";} }");
          Files.setLastModifiedTime(api, time);
        } else Files.writeString(api, "class Api { String getPets(){return \"added\";} }");
        var second = complete(analyzer, use, text);
        boolean current = second.path("items").findValuesAsText("label").stream()
            .anyMatch(label -> label.contains("getPets") && label.contains("java.lang.String"));
        results.put(initiallyPresent ? "changed_dependency" : "new_source", Map.of(
            "first", first, "after_disk_change", second, "observes_current_source", current,
            "completion_computations", analyzer.status().get("completion_computations"),
            "completion_cache_hits", analyzer.status().get("completion_cache_hits")));
      }
    }
    Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), results);
  }

  private static com.fasterxml.jackson.databind.JsonNode complete(
      Analyzer analyzer, Path file, String text) throws Exception {
    var outcome = analyzer.completion(file, text, 0, text.indexOf("api.get") + 7, 100, 0);
    if (!outcome.warnings().isEmpty()) throw new AssertionError(outcome.warnings());
    return Json.MAPPER.valueToTree(outcome.result());
  }

  private static Field field(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static void holdEvents(Analyzer analyzer) throws Exception {
    var compiler = field(analyzer, "compiler").get(analyzer);
    var manager = field(compiler, "manager").get(compiler);
    var watcherField = field(manager, "sourceWatcher");
    var delegate = (WatchService) watcherField.get(manager);
    if (delegate == null) throw new AssertionError("Probe requires a source watcher");
    watcherField.set(manager, new WatchService() {
      public WatchKey poll() { return null; }
      public WatchKey poll(long timeout, TimeUnit unit) { return null; }
      public WatchKey take() { throw new UnsupportedOperationException("No blocking probe waits"); }
      public void close() throws java.io.IOException { delegate.close(); }
    });
  }
}
