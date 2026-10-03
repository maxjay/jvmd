package dev.jvmd.tests.boot;

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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Fixture jars produced inside the test (D.3): the sources are visible in the test, and no binary is checked in. */
final class BootFixtures {
    private BootFixtures() { }

    /** Compiles {@code sources} (path relative to the source root -> text) into class files and returns them by entry name. */
    static Map<String, byte[]> compile(Path work, Map<String, String> sources, String... options) throws IOException {
        Path src = Files.createDirectories(work.resolve("src")), out = Files.createDirectories(work.resolve("classes"));
        var files = new ArrayList<String>();
        for (var e : sources.entrySet()) {
            Path file = src.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file.toString());
        }
        var args = new ArrayList<String>(List.of("-proc:none", "-d", out.toString()));
        args.addAll(List.of(options));
        args.addAll(files);
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

    /** Packs entries into a jar whose entries all carry {@code timeMillis}: a different time is a different byte hash. */
    static Path pack(Path jar, long timeMillis, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream raw = Files.newOutputStream(jar); var out = new JarOutputStream(raw)) {
            for (var e : entries.entrySet()) {
                var entry = new JarEntry(e.getKey());
                entry.setTime(timeMillis);
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    static Path jar(Path dir, String name, long timeMillis, Map<String, String> sources, String... options) throws IOException {
        // Build outside the target directory: it may be a repository that is about to be enumerated.
        var work = Files.createTempDirectory("fixture-build-");
        try {
            return pack(dir.resolve(name), timeMillis, compile(work, sources, options));
        } finally {
            try (var walk = Files.walk(work)) { for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }

    static byte[] text(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
