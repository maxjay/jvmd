package dev.jvmd.boot;

import dev.jvmd.boot.cold.stage2.Stage2;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalRoot;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.SourceLeaf;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Format;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.rocks.layer.Generation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Which classes a build changed after javac: weaving, bytecode enhancement, instrumentation. LOCAL holds javac's view of every type of a
 * module (its header facts, from source, with the module's annotation processors); the build's output directory holds the build's.
 * A type whose facts differ there, though its class file is newer than its source file, was rewritten after javac. Reading the class
 * files needs no knowledge of the plugin or task that did it, so this is the same for any build tool.
 *
 * <p>Header facts see what such a step adds to or changes in a type: members, supertypes, interfaces, annotations. A step that only
 * rewrites method bodies is not seen.
 */
public final class BuildOutputs {
    private BuildOutputs() { }

    /**
     * One module's build output against its LOCAL main scope.
     *
     * @param rewritten        the types whose facts differ from javac's, by internal name
     * @param rewrittenSources the source files (project-relative, as LOCAL names them) that declare them
     * @param compared         the types compared, equal or not
     * @param stale            the types not compared because their source is newer than their class file
     * @param unknown          class files for which LOCAL has no type: removed sources, or classes a build step created
     */
    public record Report(String module, Path classes, List<String> rewritten, List<String> rewrittenSources, int compared, int stale, int unknown) {
        public boolean rewrites() { return !rewritten.isEmpty(); }
    }

    /**
     * Compares each module's build output with the project's committed LOCAL root.
     *
     * @param outputs the build's class output directory per module name of {@code model}; a module without one is skipped
     */
    public static List<Report> compare(Path indexDir, ProjectModel model, Map<String, Path> outputs) throws IOException {
        var digest = Sha256.INSTANCE;
        var tree = new ContentTree(digest);
        var generation = Generation.of(indexDir, Format.of(digest, Runtime.version().feature()));
        var reports = new ArrayList<Report>();
        try (var store = generation.openLocal()) {
            Function<Identity, byte[]> nodes = h -> store.get(MachineStore.nodeKey(h));
            var projectKey = Stage2.projectKey(digest, model);
            var rootBytes = store.get(LocalStore.localRootKey(projectKey));
            if (rootBytes == null) return List.of();
            var root = LocalRoot.decode(digest, rootBytes);

            // The source file of every type, from the root's own file rows.
            var sourceOf = new HashMap<String, String>();
            var filePrefix = LocalStore.fileKey(projectKey, "");
            tree.forEachPrefix(root.local().hash(), nodes, filePrefix, entry -> {
                var row = store.get(entry.key());
                if (row == null || !digest.hash(row).equals(entry.h())) return;
                var path = dev.jvmd.core.tree.Mutf8.decode(entry.key(), filePrefix.length, entry.key().length - filePrefix.length);
                for (var type : FileRow.decode(path, row, digest.width()).typeKeys()) sourceOf.putIfAbsent(type, path);
            });

            for (var module : model.modules()) {
                var classes = outputs.get(module.name());
                if (classes == null || !Files.isDirectory(classes)) continue;
                var leafBytes = store.get(LocalStore.sourceLeafKey(projectKey, module.name(), LocalStore.MAIN));
                if (leafBytes == null) continue;
                var k = SourceLeaf.decode(leafBytes, digest.width()).k();
                var rewritten = new TreeSet<String>();
                var rewrittenSources = new TreeSet<String>();
                int compared = 0, stale = 0, unknown = 0;
                List<Path> files;
                try (var walk = Files.walk(classes)) {
                    files = walk.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().equals("module-info.class")).sorted().toList();
                }
                for (var file : files) {
                    var internalName = classes.relativize(file).toString().replace('\\', '/');
                    internalName = internalName.substring(0, internalName.length() - ".class".length());
                    var javac = new HashMap<ByteBuffer, Identity>();
                    tree.forEachPrefix(k, nodes, Keys.ownerKey(internalName), (Entry e) -> javac.put(ByteBuffer.wrap(e.key()), e.h()));
                    if (javac.isEmpty()) { unknown++; continue; }
                    var source = sourceOf.get(internalName);
                    if (source != null && !source.startsWith("@")) {
                        var sourceFile = model.resolve(source);
                        if (Files.isRegularFile(sourceFile) && Files.getLastModifiedTime(sourceFile).compareTo(Files.getLastModifiedTime(file)) > 0) { stale++; continue; }
                    }
                    var built = new HashMap<ByteBuffer, Identity>();
                    try {
                        for (var fact : ClassFacts.of(digest, Files.readAllBytes(file), internalName).facts()) built.put(ByteBuffer.wrap(fact.m()), fact.h());
                    } catch (ClassFacts.Fault unreadable) { unknown++; continue; }
                    compared++;
                    if (!built.equals(javac)) {
                        rewritten.add(internalName);
                        if (source != null) rewrittenSources.add(source);
                    }
                }
                reports.add(new Report(module.name(), classes, List.copyOf(rewritten), List.copyOf(rewrittenSources), compared, stale, unknown));
            }
        }
        return List.copyOf(reports);
    }
}
