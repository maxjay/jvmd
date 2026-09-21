package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmd.analyzer.SourceText;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.resolver.MavenResolver;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnostic-only large-workspace probe. It deliberately stops after a handful of unique
 * reference queries and records the existing session.status counters after each query.
 * Production code is not instrumented by this test.
 */
@Tag("perf")
class SemanticMemoryProbeTest {
    @TempDir Path temp;

    @Test
    void firstLargeWorkspaceReferencesExposeRetentionAndIndexBehaviour() throws Exception {
        assumeTrue(Boolean.getBoolean("jvmd.semantic.memory.probe"));
        Path root = TestSupport.repo();
        Path state = Files.createDirectories(temp.resolve("state"));
        var config =
            new Config(
                Path.of(System.getProperty("java.home")),
                null,
                Path.of(System.getProperty("user.home"), ".m2/repository"),
                3,
                Duration.ofHours(4),
                1024,
                false,
                state,
                state.resolve("daemon.sock"));

        var files = new LinkedHashSet<Path>();
        try (var resolver = new MavenResolver(config)) {
            for (var module : resolver.resolve(root).modules()) {
                for (String source :
                    java.util.stream.Stream.concat(module.sources().stream(), module.testSources().stream()).toList()) {
                    Path sourceRoot = Path.of(source);
                    if (!Files.isDirectory(sourceRoot)) continue;
                    try (var paths = Files.walk(sourceRoot)) {
                        paths
                            .filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .sorted()
                            .forEach(files::add);
                    }
                }
            }
        }

        int limit = Integer.getInteger("jvmd.semantic.memory.probe.count", 10);
        var seen = new LinkedHashSet<String>();
        int probes = 0;

        try (var app = new Application(config);
             var syntaxFiles =
                 javax.tools.ToolProvider.getSystemJavaCompiler()
                     .getStandardFileManager(null, null, null)) {
            String session = TestSupport.open(app, root);

            outer:
            for (Path file : files) {
                var text = new SourceText(Files.readString(file));
                var syntaxProblems =
                    new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
                var syntax =
                    (com.sun.source.util.JavacTask)
                        javax.tools.ToolProvider.getSystemJavaCompiler()
                            .getTask(
                                null,
                                syntaxFiles,
                                syntaxProblems,
                                List.of("-proc:none", "--release", "25"),
                                null,
                                syntaxFiles.getJavaFileObjects(file));
                var unit = syntax.parse().iterator().next();
                var identifiers =
                    text.identifiers(
                        unit,
                        com.sun.source.util.Trees.instance(syntax).getSourcePositions());

                for (var token : identifiers) {
                    var position = text.position(token.start());
                    var location =
                        Map.of(
                            "session", session,
                            "path", file.toString(),
                            "line", position.line(),
                            "character", position.character());

                    var at =
                        TestSupport.complete(app.dispatcher(), "symbol.atPosition", location)
                            .path("result").path("result");
                    TestSupport.complete(app.dispatcher(), "symbol.find", location);

                    var candidates = new ArrayList<JsonNode>();
                    if (at.path("ambiguous").asBoolean()) at.path("candidates").forEach(candidates::add);
                    else candidates.add(at);

                    for (var candidate : candidates) {
                        String scip = candidate.path("scip").asText();
                        if (scip.isBlank()) continue;
                        String key = scip + "\u0000" + token.text();
                        if (!seen.add(key)) continue;

                        TestSupport.complete(
                            app.dispatcher(),
                            "symbol.describe",
                            Map.of("session", session, "ref", scip));

                        var referenceArgs =
                            new LinkedHashMap<String, Object>(
                                Map.of(
                                    "session", session,
                                    "ref", scip,
                                    "direction", "out",
                                    "depth", 1,
                                    "limit", 1000));
                        long started = System.nanoTime();
                        int pages = 0;
                        JsonNode page;
                        do {
                            page =
                                TestSupport.complete(
                                    app.dispatcher(), "symbol.references", referenceArgs)
                                    .path("result");
                            pages++;
                            if (!page.path("truncated").asBoolean()) break;
                            referenceArgs.put("cursor", page.path("cursor").asText());
                        } while (pages < 100);
                        double latencyMs = (System.nanoTime() - started) / 1e6;

                        probes++;
                        emitStatus(app, session, probes, file, token.text(), scip, latencyMs, pages);
                        if (probes >= limit) break outer;
                    }
                }
            }
        }

        if (probes < limit)
            throw new AssertionError("Only found " + probes + " unique reference probes");
    }

    private static void emitStatus(
        Application app,
        String session,
        int step,
        Path file,
        String token,
        String scip,
        double latencyMs,
        int pages)
        throws Exception {
        JsonNode status =
            TestSupport.complete(
                    app.dispatcher(), "session.status", Map.of("session", session))
                .path("result").path("result");

        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long gcCount = 0, gcMillis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0) gcCount += collector.getCollectionCount();
            if (collector.getCollectionTime() >= 0) gcMillis += collector.getCollectionTime();
        }

        ObjectNode row = Json.MAPPER.createObjectNode();
        row.put("step", step);
        row.put("file", TestSupport.repo().relativize(file).toString());
        row.put("token", token);
        row.put("scip", scip);
        row.put("reference_latency_ms", latencyMs);
        row.put("pages", pages);
        row.put("heap_used_bytes", heap.getUsed());
        row.put("heap_committed_bytes", heap.getCommitted());
        row.put("gc_count", gcCount);
        row.put("gc_ms", gcMillis);
        row.set("workspace_bindings", status.path("workspace_bindings"));
        row.set("index_timings", status.path("index").path("timings"));
        row.set(
            "rocks_native_memory",
            status.path("index").path("generation_sink").path("native_memory"));
        row.set(
            "semantic_state",
            status.path("index").path("generation_sink").path("semantic_state"));
        row.set(
            "workspace_state",
            status.path("index").path("generation_sink").path("workspace_state"));
        row.set("source_publisher", status.path("index").path("source_publisher"));

        System.out.println("semantic-memory-probe " + Json.MAPPER.writeValueAsString(row));
    }
}
