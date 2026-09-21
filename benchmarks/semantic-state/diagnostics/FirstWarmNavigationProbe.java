import dev.jvmd.core.Json;
import java.nio.file.Path;

/** First-five warm requests with the same cold request and untimed checks as the full suite. */
public final class FirstWarmNavigationProbe {
  public static void main(String[] args) throws Exception {
    NavigationBenchmark.navigation(Path.of(args[0]), 32, "workspace_32", 0);
    System.out.println(Json.MAPPER.writeValueAsString(NavigationBenchmark.samples));
  }
}
