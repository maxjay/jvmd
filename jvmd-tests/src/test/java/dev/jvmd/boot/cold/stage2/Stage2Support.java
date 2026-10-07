package dev.jvmd.boot.cold.stage2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/**
 * Fixtures for the stage 2 invariants (7.3): jars compiled here with {@code ToolProvider}, projects written to a temporary
 * directory, hand-written project models (appendix A) and one MACHINE boot of the JDK per digest, copied for each test.
 */
final class Stage2Support {
    private Stage2Support() { }

    /** Address for these fixtures' conventional module/src layout; shared-root fixtures supply explicit SourceUnits. */
    static dev.jvmd.index.layer.local.SourceUnit source(String path) {
        if (path.startsWith(".jvmd/generated/")) {
            var parts = path.split("/", 5);
            return new dev.jvmd.index.layer.local.SourceUnit(new String(java.util.Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8), Integer.parseInt(parts[3]), path);
        }
        int src = path.indexOf("/src/");
        String module = src < 0 ? "fixture" : path.substring(0, src);
        int scope = path.contains("/src/test/") || path.startsWith("test/") ? 1 : 0;
        return new dev.jvmd.index.layer.local.SourceUnit(module, scope, path);
    }

    static final long T1 = 1_700_000_000_000L;
    static final int FEATURE = Runtime.version().feature();
    static final Path JDK = Path.of(System.getProperty("java.home"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, InMemoryLocalStore> MACHINES = new ConcurrentHashMap<>();

    /** Compiles {@code sources} (path -> text) with javac into class files by entry name. */
    static Map<String, byte[]> compile(Path work, Map<String, String> sources, List<String> options, List<Path> classpath) throws IOException {
        Path src = Files.createDirectories(work.resolve("src")), out = Files.createDirectories(work.resolve("classes"));
        var args = new ArrayList<String>(List.of("-proc:none", "-d", out.toString()));
        if (!classpath.isEmpty()) args.addAll(List.of("--class-path", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList())));
        args.addAll(options);
        for (var e : sources.entrySet()) {
            Path file = src.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            args.add(file.toString());
        }
        var errors = new java.io.ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, errors, args.toArray(String[]::new));
        if (rc != 0) throw new IllegalStateException("Fixture does not compile:\n" + errors);
        var classes = new LinkedHashMap<String, byte[]>();
        try (var walk = Files.walk(out)) {
            for (var p : walk.filter(p -> p.toString().endsWith(".class")).sorted(Comparator.naturalOrder()).toList())
                classes.put(out.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        return classes;
    }

    static Path pack(Path jar, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream raw = Files.newOutputStream(jar); var out = new JarOutputStream(raw)) {
            for (var e : entries.entrySet()) {
                var entry = new JarEntry(e.getKey());
                entry.setTime(T1);
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Compiles sources into a jar at {@code repository/location}, against other jars of the repository. */
    static Path jar(Path repository, String location, Map<String, String> sources, List<String> options, String... classpath) throws IOException {
        var work = Files.createTempDirectory("stage2-jar-");
        try {
            var cp = new ArrayList<Path>();
            for (var c : classpath) cp.add(repository.resolve(c));
            return pack(repository.resolve(location), compile(work, sources, options, cp));
        } finally { delete(work); }
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) { for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }

    /** Writes {@code files} (path relative to {@code root} -> text). */
    static void write(Path root, Map<String, String> files) throws IOException {
        for (var e : files.entrySet()) {
            var file = root.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }
    }

    // ---- models --------------------------------------------------------------------------------------------------------------

    record Dep(String coordinate, String location, String module) {
        static Dep jar(String coordinate, String location) { return new Dep(coordinate, location, null); }
        static Dep module(String coordinate, String name) { return new Dep(coordinate, null, name); }
    }

    record Mod(String name, String coordinate, int release, List<String> options, List<String> mainRoots, List<Dep> mainDeps, List<String> testRoots, List<Dep> testDeps) {
        Mod(String name, String coordinate, List<Dep> mainDeps) { this(name, coordinate, FEATURE, List.of(), List.of(name + "/src/main/java"), mainDeps, List.of(name + "/src/test/java"), List.of()); }
        Mod withOptions(String... options) { return new Mod(name, coordinate, release, List.of(options), mainRoots, mainDeps, testRoots, testDeps); }
        Mod withTest(List<Dep> deps) { return new Mod(name, coordinate, release, options, mainRoots, mainDeps, testRoots, deps); }
    }

    /** The JSON of appendix A. */
    static byte[] model(Path root, Mod... modules) {
        var doc = JSON.createObjectNode();
        doc.put("root", root.toString().replace('\\', '/'));
        doc.put("jdkHome", JDK.toString().replace('\\', '/'));
        var list = doc.putArray("modules");
        for (var m : modules) {
            ObjectNode node = list.addObject();
            node.put("name", m.name()).put("coordinate", m.coordinate()).put("release", m.release()).put("moduleInfo", false);
            var options = node.putArray("javacOptions");
            m.options().forEach(options::add);
            var scopes = node.putObject("scopes");
            scope(scopes.putObject("main"), m.mainRoots(), m.mainDeps());
            scope(scopes.putObject("test"), m.testRoots(), m.testDeps());
        }
        try { return JSON.writeValueAsBytes(doc); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void scope(ObjectNode node, List<String> roots, List<Dep> deps) {
        var r = node.putArray("sourceRoots");
        roots.forEach(r::add);
        var d = node.putArray("dependencies");
        for (var dep : deps) {
            var n = d.addObject().put("coordinate", dep.coordinate());
            if (dep.location() != null) n.put("location", dep.location()); else n.put("module", dep.module());
        }
    }

    static ProjectModel parse(byte[] json) { return ProjectModel.parse(json); }

    // ---- MACHINE ---------------------------------------------------------------------------------------------------------------

    /** One MACHINE boot of {@code repository} and the running JDK per digest and repository, cached: copy it before use. */
    static InMemoryLocalStore machine(Digest digest, Path repository) {
        return MACHINES.computeIfAbsent(digest.name() + "|" + repository, key -> {
            var store = new InMemoryLocalStore();
            try {
                new Stage1(digest, new ContentTree(digest), FEATURE, 8, ClassFacts::of).run(store, repository, List.of(JDK));
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            return store;
        });
    }

    /**
     * MACHINE of the running JDK alone, one per digest: a test whose jars MACHINE must not have seen (they are indexed on the spot, 3.15)
     * starts from this, so that every such test shares one store instead of holding a copy of the JDK each.
     */
    static InMemoryLocalStore jdkOnly(Digest digest) {
        return MACHINES.computeIfAbsent(digest.name() + "|jdk-only", key -> {
            var store = new InMemoryLocalStore();
            try {
                var empty = Files.createTempDirectory("stage2-empty-repo");
                try { new Stage1(digest, new ContentTree(digest), FEATURE, 8, ClassFacts::of).run(store, empty, List.of(JDK)); } finally { delete(empty); }
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            return store;
        });
    }

    /** Drops the cached MACHINE stores: a class that is done with them does not keep the JDK in memory for the classes after it. */
    static void release() { MACHINES.clear(); }

    static byte[] text(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
