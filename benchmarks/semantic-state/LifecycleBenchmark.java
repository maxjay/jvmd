import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Lifecycle costs through the production compiler/cache, with two source modules. */
final class LifecycleBenchmark {
    private static void check(boolean condition, Object message) {
        NavigationBenchmark.check(condition, message);
    }

    static void run(Path root) throws Exception {
        Path library = Files.createDirectories(root.resolve("library/lib"));
        Path application = Files.createDirectories(root.resolve("application/app"));
        Path api = library.resolve("Api.java");
        String original = "package lib; public class Api { public static Number value(){return 1;} }\n";
        Files.writeString(api, original);
        for (int i = 0; i < 64; i++) {
            Files.writeString(application.resolve("Client" + i + ".java"),
                    "package app; class Client" + i + " { Object read(){return lib.Api.value();} }\n");
        }
        Path client = application.resolve("Client0.java");
        var roots = List.of(library.getParent(), application.getParent());
        var documents = new Documents();
        var coordinates = Map.of(roots.get(0).toUri().toString(), "bench:library:1",
                roots.get(1).toUri().toString(), "bench:application:1");
        try (var analyzer = new Analyzer(); var cache = new WorkspaceBindings(documents.fileStates())) {
            analyzer.configure(new Analyzer.Context("bench:application:1", "25", List.of(), roots,
                    "lifecycle", coordinates), null, 256L * 1024 * 1024);
            analyzer.documents(documents);
            WorkspaceBindings.SourceFiles files = () -> {
                var result = new ArrayList<Path>();
                for (Path source : roots) result.addAll(FileInventory.matching(source, ".java"));
                return result;
            };
            WorkspaceBindings.BatchLoader loader = analyzer::bindingsBatch;
            NavigationBenchmark.Request query = () -> cache.getBatch(files, List.of(), documents,
                    "lifecycle", 128L * 1024 * 1024, loader);
            var old = graph("cold_modules", query);
            var oldJson = Json.MAPPER.valueToTree(old);
            for (int i = 0; i < 5; i++) {
                graph("warm_modules", query);
                NavigationBenchmark.measure("lifecycle_65", "diagnostics_warm", () -> analyzer.diagnostics(client, documents));
            }
            for (int i = 0; i < 3; i++) {
                Files.writeString(api, original.replace("return 1;", "int local=" + i + ";return local;"));
                graph("body_edit", query);
                Files.writeString(api, original.replace("Number", i % 2 == 0 ? "Integer" : "Number"));
                graph("cross_module_api", query);
                var diagnostic = (Envelope) NavigationBenchmark.measure("lifecycle_65", "diagnostics_after_api",
                        () -> analyzer.diagnostics(client, documents));
                check(!Json.MAPPER.valueToTree(diagnostic).toString().contains("\"kind\":\"ERROR\""), diagnostic);
                Files.writeString(api, "/** docs " + i + " */\n" + original);
                graph("documentation_position", query);
            }
            documents.open(api, original.replace("return 1;", "return 7;"), 1);
            graph("buffer_open", query);
            documents.change(api, 2, List.of(new Documents.Change(null, original.replace("Number", "Integer"))));
            graph("buffer_api", query);
            documents.close(api);
            graph("buffer_close", query);
            Path added = application.resolve("Added.java");
            Files.writeString(added, "package app; class Added { Object read(){return lib.Api.value();} }\n");
            var addition = graph("file_add", query);
            check(!addition.lookup("Added").isEmpty(), "Added symbol missing");
            Files.delete(added);
            check(graph("file_delete", query).lookup("Added").isEmpty(), "Deleted symbol retained");
            graph("context_change", () -> cache.getBatch(files, List.of(), documents,
                    "lifecycle-next", 128L * 1024 * 1024, loader));
            check(oldJson.equals(Json.MAPPER.valueToTree(old)), "Old reader snapshot mutated");
            var incremental = graph("context_restore", query);
            try (var clean = new WorkspaceBindings(documents.fileStates())) {
                var rebuilt = clean.getBatch(files, List.of(), documents, "lifecycle", 128L * 1024 * 1024, loader);
                check(Json.MAPPER.valueToTree(incremental).equals(Json.MAPPER.valueToTree(rebuilt)),
                        "Incremental lifecycle output differs from clean analysis");
            }
            NavigationBenchmark.samples.getLast().put("status", cache.status());
        }
    }

    private static WorkspaceBindings.Snapshot graph(String phase, NavigationBenchmark.Request query) throws Exception {
        var result = (WorkspaceBindings.Snapshot) NavigationBenchmark.measure("lifecycle_65", phase, query);
        check(result.tier() == 2 && result.warnings().isEmpty(), result.warnings());
        check(result.diagnostics().stream().noneMatch(problem -> problem.kind().equals("ERROR")), result.diagnostics());
        check(!result.lookup("Api").isEmpty(), "API lookup missing");
        return result;
    }

    /** Separate from request timings: record live heap with and without old readers. */
    static Map<String, Object> retained(Path root) throws Exception {
        Files.createDirectories(root);
        var documents = new Documents();
        var files = new ArrayList<Path>();
        for (int i = 0; i < 256; i++) {
            Path file = root.resolve("Type" + i + ".java");
            Files.writeString(file, "class Type" + i + " { int value(){return 1;} }\n");
            files.add(file);
        }
        try (var analyzer = new Analyzer(); var cache = new WorkspaceBindings(documents.fileStates())) {
            analyzer.configure(new Analyzer.Context("bench:heap:1", "25", List.of(), List.of(root),
                    "heap", Map.of()), null, 256L * 1024 * 1024);
            analyzer.documents(documents);
            var readers = new ArrayList<WorkspaceBindings.Snapshot>();
            readers.add(cache.getBatch(() -> files, List.of(), documents, "heap", 128L * 1024 * 1024, analyzer::bindingsBatch));
            long initial = liveHeap();
            for (int i = 0; i < 12; i++) {
                Files.writeString(files.get(i), "class Type" + i + " { int value(){return 2;} }\n");
                readers.add(cache.getBatch(() -> files, List.of(), documents, "heap", 128L * 1024 * 1024, analyzer::bindingsBatch));
            }
            long withReaders = liveHeap();
            int snapshots = readers.size();
            check(readers.getFirst().lookup("Type0").size() == 1, "Old reader missing");
            readers.clear();
            long released = liveHeap();
            return Map.of("initial_live_heap_bytes", initial, "with_old_readers_bytes", withReaders,
                    "released_readers_bytes", released, "snapshots", snapshots, "status", cache.status());
        }
    }

    private static long liveHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
