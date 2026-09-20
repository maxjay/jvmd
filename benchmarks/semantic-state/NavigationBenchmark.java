import com.fasterxml.jackson.databind.JsonNode;
import com.sun.management.ThreadMXBean;
import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Real compiler and dispatcher workloads. Assertions run outside the timed requests. */
public final class NavigationBenchmark {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final List<Map<String, Object>> samples = new ArrayList<>();

    @FunctionalInterface
    interface Request { Object run() throws Exception; }

    static Object measure(String workload, String phase, Request request) throws Exception {
        long allocated = THREADS.getTotalThreadAllocatedBytes();
        long started = System.nanoTime();
        Object result = request.run();
        long elapsed = System.nanoTime() - started;
        var row = new LinkedHashMap<String, Object>();
        row.put("workload", workload);
        row.put("phase", phase);
        row.put("ms", elapsed / 1e6);
        row.put("allocated_bytes", THREADS.getTotalThreadAllocatedBytes() - allocated);
        samples.add(row);
        return result;
    }

    static void check(boolean condition, Object message) {
        if (!condition) throw new AssertionError(message);
    }

    static JsonNode request(Application app, String method, Map<String, Object> parameters) {
        var input = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        input.set("params", Json.MAPPER.valueToTree(parameters));
        var response = app.dispatcher().dispatch(input);
        check(!response.has("error"), response);
        var envelope = response.path("result");
        check(method.startsWith("session.") || envelope.path("tier").asInt() == 2, response);
        check(!envelope.path("truncated").asBoolean(), response);
        return envelope.path("result");
    }

    static String rootSource(String type, int value) {
        return "class Root { static " + type + " value(){return " + value + ";} }\n";
    }

    static void navigation(Path root, int count, String label, int cycles) throws Exception {
        Path source = Files.createDirectories(root.resolve("src"));
        Files.writeString(source.resolve("Root.java"), rootSource("Number", 1));
        for (int i = 0; i < count - 1; i++) {
            Files.writeString(source.resolve("User" + i + ".java"),
                    "class User" + i + " { Object read(){return " + (i < 16 ? "Root.value()" : i) + ";} }\n");
        }
        var config = new Config(Path.of(System.getProperty("java.home")), null, root.resolve("repository"),
                3, Duration.ofHours(4), 512, false, root.resolve("state"), root.resolve("daemon.sock"));
        try (var app = new Application(config)) {
            String session = request(app, "session.open", Map.of("root", source.toString())).path("session").asText();
            references(app, session, source, label, "cold_workspace");
            for (int i = 0; i < 5; i++) references(app, session, source, label, "warm");
            for (int cycle = 0; cycle < cycles; cycle++) {
                Files.writeString(source.resolve("User0.java"),
                        "class User0 { Object read(){int local=" + cycle + ";return Root.value();} }\n");
                references(app, session, source, label, "body_edit");
                Files.writeString(source.resolve("Root.java"), rootSource(cycle % 2 == 0 ? "Integer" : "Number", cycle + 2));
                references(app, session, source, label, "api_edit");
                var renamed = (JsonNode) measure(label, "rename_preview", () -> request(app, "edit.rename",
                        Map.of("session", session, "ref", "Root/value()", "new_name", "renamedValue", "dry_run", true)));
                check(!renamed.path("applied").asBoolean(), renamed);
                check(renamed.path("changes").size() == 17, renamed);
                check(renamed.toString().contains("renamedValue"), renamed);
            }
            samples.getLast().put("status", request(app, "session.status", Map.of("session", session)).path("workspace_bindings"));
        }
    }

    static void references(Application app, String session, Path source, String label, String phase) throws Exception {
        var result = (JsonNode) measure(label, phase, () -> request(app, "symbol.references",
                Map.of("session", session, "ref", "Root/value()", "direction", "in", "limit", 1000)));
        var callers = new TreeSet<String>();
        for (var edge : result.path("edges")) {
            check(edge.path("dst").asText().endsWith("Root#value()."), edge);
            String caller = edge.path("src").asText();
            callers.add(caller.substring(caller.lastIndexOf(' ') + 1));
        }
        var expected = new TreeSet<String>();
        for (int i = 0; i < 16; i++) expected.add("User" + i + "#read().");
        check(callers.equals(expected), result);
        // Read each returned location against the current source, including after edits.
        var occurrences = request(app, "symbol.occurrences",
                Map.of("session", session, "ref", "Root/value()", "include_declaration", true, "limit", 1000));
        check(occurrences.path("occurrences").size() == 17, occurrences);
        for (var occurrence : occurrences.path("occurrences")) {
            String text = Files.readString(Path.of(occurrence.path("file").asText()));
            check(text.substring(occurrence.path("start").asInt(), occurrence.path("end").asInt()).equals("value"), occurrence);
        }
        samples.getLast().put("status", request(app, "session.status", Map.of("session", session)).path("workspace_bindings"));
    }

    /** A real source module, with production javac and WorkspaceBindings; excludes RPC/resolver cost. */
    static void realModule(Path root, Path fixture, List<Path> dependencies) throws Exception {
        Path source = Files.createDirectories(root.resolve("src"));
        try (var paths = Files.walk(fixture)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.java")).toList()) {
                Path target = source.resolve(fixture.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        var documents = new Documents();
        try (var analyzer = new Analyzer(); var cache = new WorkspaceBindings(documents.fileStates())) {
            analyzer.configure(new Analyzer.Context("dev.jvmd:jvmd-core:1", "25", dependencies, List.of(source),
                    "real-core", Map.of(source.toUri().toString(), "dev.jvmd:jvmd-core:1")), null, 256L * 1024 * 1024);
            analyzer.documents(documents);
            WorkspaceBindings.SourceFiles files = () -> FileInventory.matching(source, ".java");
            WorkspaceBindings.BatchLoader loader = sources -> {
                var results = new LinkedHashMap<Path, CompilerPool.Outcome<Bindings.Snapshot>>();
                var batch = new LinkedHashMap<Path, String>();
                for (var entry : sources.entrySet()) {
                    batch.put(entry.getKey(), entry.getValue());
                    if (batch.size() == 32) { results.putAll(analyzer.bindingsBatch(batch)); batch.clear(); }
                }
                if (!batch.isEmpty()) results.putAll(analyzer.bindingsBatch(batch));
                return results;
            };
            Request query = () -> cache.getBatch(files, dependencies, documents, "real-core", 128L * 1024 * 1024, loader);
            checkSnapshot((WorkspaceBindings.Snapshot) measure("real_core", "cold_workspace", query));
            for (int i = 0; i < 5; i++) checkSnapshot((WorkspaceBindings.Snapshot) measure("real_core", "warm", query));
            Path edited = source.resolve("dev/jvmd/core/Hashing.java");
            String original = Files.readString(edited);
            for (int i = 0; i < 3; i++) {
                Files.writeString(edited, "// shifted source " + i + "\n" + original);
                var snapshot = (WorkspaceBindings.Snapshot) measure("real_core", "position_edit", query);
                checkSnapshot(snapshot);
                samples.getLast().put("status", cache.status());
            }
            // Full output agreement with a clean cache, outside measured request latency.
            var incremental = Json.MAPPER.valueToTree(query.run());
            try (var clean = new WorkspaceBindings(documents.fileStates())) {
                var rebuilt = clean.getBatch(files, dependencies, documents, "real-core", 128L * 1024 * 1024, loader);
                check(incremental.equals(Json.MAPPER.valueToTree(rebuilt)), "Real module differs from full rebuild");
            }
            samples.getLast().put("file_identities", documents.fileStates().status());
        }
    }

    static void checkSnapshot(WorkspaceBindings.Snapshot snapshot) {
        check(snapshot.tier() == 2 && snapshot.warnings().isEmpty(), snapshot.warnings());
        check(snapshot.diagnostics().stream().noneMatch(p -> p.kind().equals("ERROR")), snapshot.diagnostics());
        check(!snapshot.lookup("Hashing").isEmpty(), "Real module lookup failed");
    }

    /** Isolates precise-epoch reuse from conservative disk validation. No watcher claim. */
    static void validation(Path root) throws Exception {
        Files.createDirectories(root);
        var paths = new ArrayList<Path>();
        for (int i = 0; i < 512; i++) {
            Path path = root.resolve("Type" + i + ".java");
            Files.writeString(path, "class Type" + i + " {}\n"); paths.add(path);
        }
        var documents = new Documents();
        var token = new WorkspaceBindings.ValidationToken("fixed", 0, Map.of("source", 0L), Map.of());
        try (var cache = new WorkspaceBindings(documents.fileStates())) {
            int[] enumerations = {0};
            WorkspaceBindings.SourceFiles sources = () -> { enumerations[0]++; return paths; };
            WorkspaceBindings.BatchLoader loader = inputs -> {
                var outcomes = new LinkedHashMap<Path, CompilerPool.Outcome<Bindings.Snapshot>>();
                inputs.forEach((file, text) -> outcomes.put(file, new CompilerPool.Outcome<>(2,
                        new Bindings.Snapshot(Map.of(), List.of(), List.of(), Set.of()), List.of(), List.of())));
                return outcomes;
            };
            cache.getBatch(sources, List.of(), documents, "fixed", 1024 * 1024, () -> token, loader);
            int prior = enumerations[0];
            for (int i = 0; i < 20; i++) measure("validation_512", "precise_epoch", () ->
                    cache.getBatch(sources, List.of(), documents, "fixed", 1024 * 1024, () -> token, loader));
            check(enumerations[0] == prior, "Precise validation enumerated sources");
            for (int i = 0; i < 20; i++) measure("validation_512", "coarse_disk", () ->
                    cache.getBatch(sources, List.of(), documents, "fixed", 1024 * 1024, loader));
            check(enumerations[0] == prior + 20, "Coarse fallback not exercised");
            samples.getLast().put("status", cache.status());
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        THREADS.setThreadAllocatedMemoryEnabled(true);
        // First request has a cold JVM; later target workspaces have the same explicit warmup.
        navigation(root.resolve("startup"), 32, "cold_jvm_32", 1);
        navigation(root.resolve("small"), 128, "navigation_128", 3);
        navigation(root.resolve("large"), 512, "navigation_512", 3);
        List<Path> dependencies = Arrays.stream(args[3].split(java.io.File.pathSeparator)).map(Path::of).toList();
        realModule(root.resolve("real"), Path.of(args[2]), dependencies);
        validation(root.resolve("validation"));
        var report = new LinkedHashMap<String, Object>();
        report.put("samples", samples);
        report.put("java_version", System.getProperty("java.runtime.version"));
        report.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmHWM:")) report.put("peak_rss_kib", Long.parseLong(line.trim().split("\\s+")[1]));
        }
        report.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(bean ->
                Map.of("name", bean.getName(), "collections", bean.getCollectionCount(), "ms", bean.getCollectionTime())).toList());
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), report);
    }
}
