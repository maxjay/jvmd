import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.*;
import java.nio.file.*;
import java.util.*;

/** Declaration views include nested types, overloads, docs and unresolved signatures. */
public final class OverviewBenchmark {
    public static void main(String[] args) throws Exception {
        ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .setThreadAllocatedMemoryEnabled(true);
        run(Path.of(args[0]));
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),
                Map.of("samples", NavigationBenchmark.samples, "java_version", System.getProperty("java.runtime.version")));
    }

    static void run(Path root) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve("Overview.java");
        var source = new StringBuilder("class Overview<T extends Number> {\n");
        for (int i = 0; i < 100; i++) {
            source.append("/** Return input ").append(i).append(". */\nT value")
                    .append(i).append("(T input){return input;}\n");
        }
        source.append("class Nested { int field; int read(){return field;} }\n}");
        String original = source.toString();
        Files.writeString(file, original);
        var documents = new Documents();
        try (var analyzer = new Analyzer()) {
            analyzer.configure(new Analyzer.Context("bench:overview:1", "25", List.of(), List.of(root),
                    "overview", Map.of()), null, 256L * 1024 * 1024);
            analyzer.documents(documents);
            query(analyzer, file, original, "cold", 1);
            for (int i = 0; i < 5; i++) query(analyzer, file, original, "warm", 1);
            for (int i = 0; i < 3; i++) {
                String changed = "/** Revision " + i + " */\n" + original;
                Files.writeString(file, changed);
                query(analyzer, file, changed, "position_edit", 1);
            }
        }
    }

    private static void query(Analyzer analyzer, Path file, String text, String phase, int depth) throws Exception {
        var result = (Envelope) NavigationBenchmark.measure("overview_100", phase,
                () -> analyzer.overview(file, text, depth, 1000, 0));
        var symbols = Json.MAPPER.valueToTree(result.result()).path("symbols");
        NavigationBenchmark.check(result.tier() == 1 && result.warnings().isEmpty(), result);
        long methods = java.util.stream.StreamSupport.stream(symbols.spliterator(), false)
                .filter(row -> row.path("name").asText().startsWith("value")).count();
        NavigationBenchmark.check(methods == 100, symbols);
        for (var row : symbols) {
            NavigationBenchmark.check(!row.path("name").asText().equals("field"), row);
            if (row.path("name").asText().startsWith("value")) {
                NavigationBenchmark.check(text.substring(row.path("name_start").asInt(),
                        row.path("name_end").asInt()).equals(row.path("name").asText()), row);
                NavigationBenchmark.check(row.path("doc").asText().contains("Return input"), row);
            }
        }
    }
}
