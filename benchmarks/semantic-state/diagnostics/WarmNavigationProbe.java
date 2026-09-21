import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import jdk.jfr.Recording;

/** Diagnostic steady-state profile; these timings never enter the paired acceptance campaign. */
public final class WarmNavigationProbe {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Path source = Files.createDirectories(root.resolve("src"));
    Files.writeString(source.resolve("Root.java"), NavigationBenchmark.rootSource("Number", 1));
    for (int i = 0; i < 31; i++) {
      Files.writeString(source.resolve("User" + i + ".java"),
          "class User" + i + " { Object read(){return " + (i < 16 ? "Root.value()" : i) + ";} }");
    }
    var config = new Config(Path.of(System.getProperty("java.home")), null,
        root.resolve("repository"), 3, Duration.ofHours(4), 512, false,
        root.resolve("state"), root.resolve("daemon.sock"));
    try (var app = new Application(config); var recording = new Recording()) {
      String session = NavigationBenchmark.request(app, "session.open", Map.of("root", source.toString()))
          .path("session").asText();
      var parameters = Map.<String, Object>of("session", session, "ref", "Root/value()",
          "direction", "in", "limit", 1000);
      for (int i = 0; i < 30; i++) {
        NavigationBenchmark.request(app, "symbol.references", parameters);
      }
      recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
      recording.enable("jdk.ObjectAllocationSample").withStackTrace();
      recording.start();
      var times = new ArrayList<Double>();
      for (int i = 0; i < 2000; i++) {
        long started = System.nanoTime();
        var result = NavigationBenchmark.request(app, "symbol.references", parameters);
        times.add((System.nanoTime() - started) / 1e6);
        NavigationBenchmark.check(result.path("edges").size() == 16, result);
      }
      recording.stop();
      recording.dump(root.resolve("warm.jfr"));
      Files.writeString(root.resolve("warm.json"), Json.MAPPER.writeValueAsString(Map.of(
          "profiled", true, "times_ms", times,
          "status", NavigationBenchmark.request(app, "session.status", Map.of("session", session)))));
    }
  }
}
