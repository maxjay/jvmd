package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.machine.Keys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

/** The tracked configuration map and each file's exact Lombok lookup chain, with absence observations. */
public final class ProcessorConfiguration {
    private final ContentTree tree;
    private final Path project;
    private final Root root;
    private final Function<Identity, byte[]> reader;

    private ProcessorConfiguration(ContentTree tree, Path project, Root root, Function<Identity, byte[]> reader) {
        this.tree = tree; this.project = project; this.root = root; this.reader = reader;
    }

    public Root root() { return root; }

    public static ProcessorConfiguration scan(Digest digest, ContentTree tree, Path project, List<String> sources,
                                              List<String> tracked, NodeSink sink, Function<Identity, byte[]> reader) throws IOException {
        project = project.toAbsolutePath().normalize();
        var files = new java.util.LinkedHashSet<Path>();
        for (var path : tracked) files.add(project.resolve(path).normalize());
        for (var source : sources) for (var parent = project.resolve(source).normalize().getParent(); parent != null; parent = parent.getParent())
            files.add(parent.resolve("lombok.config"));
        var entries = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        for (var file : files) {
            if (!Files.isRegularFile(file)) continue;
            var bytes = Files.readAllBytes(file);
            var key = Keys.resourceKey(path(project, file));
            entries.put(key, new Entry(key, bytes, digest.hash(key, digest.hash(bytes).view())));
        }
        return new ProcessorConfiguration(tree, project, tree.build(new ArrayList<>(entries.values()), sink), reader);
    }

    public List<ProcessorRecords.ConfigEntry> proof(String source) {
        return proof(tree.digest(), project, source, file -> tree.get(root.hash(), reader, Keys.resourceKey(path(project, file))));
    }

    /** Bind a body task's native configuration lookup to the committed source/configuration snapshot. */
    public record Snapshot(List<ProcessorRecords.ConfigEntry> proof, List<String> unmodelledImports) { }

    public static Snapshot current(Digest digest, Path project, String source) throws IOException {
        var checkedProject = project.toAbsolutePath().normalize();
        var imports = new ArrayList<String>();
        try {
            var proof = proof(digest, checkedProject, source, file -> {
                try {
                    if (!Files.isRegularFile(file)) return null;
                    var bytes = Files.readAllBytes(file); var key = Keys.resourceKey(path(checkedProject, file));
                    if (imports(bytes)) imports.add(path(checkedProject, file));
                    return new Entry(key, bytes, digest.hash(key, digest.hash(bytes).view()));
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            return new Snapshot(proof, List.copyOf(imports));
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }

    static List<ProcessorRecords.ConfigEntry> proof(Digest digest, Path project, String source, Function<Path, Entry> read) {
        var out = new ArrayList<ProcessorRecords.ConfigEntry>();
        // Lombok's default bubbling reaches the filesystem root, including ancestors outside the project unless stopped.
        for (var parent = project.resolve(source).normalize().getParent(); parent != null; parent = parent.getParent()) {
            var path = path(project, parent.resolve("lombok.config"));
            var entry = read.apply(parent.resolve("lombok.config"));
            out.add(new ProcessorRecords.ConfigEntry(path, entry == null ? Identity.zero(digest.width()) : entry.h()));
            if (entry != null && stop(entry.value())) break;
        }
        return List.copyOf(out);
    }

    /** Imported configuration is a separate read; callers must reject reuse until those paths are also proved. */
    public List<String> unmodelledImports(String source) {
        var out = new ArrayList<String>();
        for (var observation : proof(source)) {
            var entry = tree.get(root.hash(), reader, Keys.resourceKey(observation.path()));
            if (entry != null && imports(entry.value()))
                out.add(observation.path());
        }
        return List.copyOf(out);
    }

    static boolean imports(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).lines().anyMatch(l -> l.strip().startsWith("import "));
    }

    private static boolean stop(byte[] bytes) {
        boolean stop = false;
        for (var line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
            var assignment = line.strip().split("=", 2);
            if (assignment.length == 2 && assignment[0].strip().equals("config.stopBubbling")) stop = assignment[1].strip().equalsIgnoreCase("true");
        }
        return stop;
    }

    static String path(Path project, Path file) {
        return (file.startsWith(project) ? project.relativize(file) : file).toString().replace('\\', '/');
    }
}
