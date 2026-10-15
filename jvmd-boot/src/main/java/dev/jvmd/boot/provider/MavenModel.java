package dev.jvmd.boot.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmd.core.Json;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.resolver.Resolution;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The stage 2 project model of a Maven reactor (stage 2, section 8 and appendix A; demo plan A4), translated from one jvmd-resolver
 * {@link Resolution}. Nothing is compiled and no lifecycle runs: each scope's dependencies are Maven's compile classpath, in Maven's
 * order, where a reactor module is a sibling wherever it occurs, built or not.
 *
 * <p>Annotation processor output directories are not source roots: the processors run inside stage 2 (amendments 4.8), so what an
 * earlier build left there would declare the generated types twice. A generated-sources directory that contains the processor output
 * is dropped with it; the resolver lists the generator subdirectories beside it separately.
 */
public final class MavenModel {
    private MavenModel() { }

    /**
     * @param json     the model document; its digest is the model hash of the LOCAL root
     * @param warnings what the model cannot express and therefore left out, one line each
     */
    public record Result(byte[] json, List<String> warnings) {
        public ProjectModel parse() { return ProjectModel.parse(json); }
    }

    /**
     * @param repository Maven's local repository: jar locations under it are written relative to it
     * @param jdkHome    the JDK the project compiles against
     * @throws ProjectModel.Fault if the resolution predates {@link Resolution#compileOrder()}
     */
    public static Result of(Resolution resolution, Path repository, Path jdkHome) {
        var root = Path.of(resolution.root()).toAbsolutePath().normalize();
        var repo = repository.toAbsolutePath().normalize();
        var names = new HashMap<String, String>();
        var modules = new ArrayList<Resolution.Module>();
        for (var m : resolution.modules()) {
            if (m.packaging().equals("pom")) continue;
            var dir = Path.of(m.directory()).toAbsolutePath().normalize();
            // "." for a jar module at the reactor root: no subdirectory module can have that name.
            names.put(m.gav(), dir.equals(root) ? "." : slashes(root.relativize(dir)));
            modules.add(m);
        }
        var coordinates = new HashMap<String, String>();
        for (var node : resolution.nodes()) if (node.path() != null) coordinates.putIfAbsent(node.path(), node.gav());

        // A sibling depended on at another version is the sibling (the overlay substitutes it by groupId:artifactId); say so.
        var warnings = new ArrayList<String>(resolution.warnings().stream().filter(w -> w.startsWith("overlay_version")).toList());
        var doc = Json.MAPPER.createObjectNode();
        doc.put("root", slashes(root));
        doc.put("jdkHome", slashes(jdkHome.toAbsolutePath().normalize()));
        var list = doc.putArray("modules");
        for (var m : modules) {
            var name = names.get(m.gav());
            var main = dependencies(resolution, m.gav() + ":main", names, coordinates, repo, name, warnings);
            var test = dependencies(resolution, m.gav() + ":test", names, coordinates, repo, name, warnings);
            test.removeAll(main);
            if (!m.testCompilerOptions().equals(m.compilerOptions()))
                warnings.add(name + ": test compiler options " + m.testCompilerOptions() + " differ from main; the model has one option list, main's");
            var mainRoots = roots(root, m.sources(), m.processing().generatedDirectory());
            boolean modular = moduleInfo(root, mainRoots);
            ObjectNode node = list.addObject();
            node.put("name", name).put("coordinate", m.gav()).put("release", release(m)).put("moduleInfo", modular);
            var options = node.putArray("javacOptions");
            m.compilerOptions().forEach(options::add);
            // As the compiler plugin passes it: a module descriptor records its project version.
            if (modular) options.add("--module-version").add(m.gav().substring(m.gav().lastIndexOf(':') + 1));
            var scopes = node.putObject("scopes");
            scope(scopes.putObject("main"), mainRoots, main, processing(m.processing(), release(m), name + " (main)", warnings), m.processing(), repo);
            scope(scopes.putObject("test"), roots(root, m.testSources(), m.testProcessing().generatedDirectory()), test,
                    processing(m.testProcessing(), release(m), name + " (test)", warnings), m.testProcessing(), repo);
        }
        try {
            return new Result(Json.MAPPER.writeValueAsBytes(doc), List.copyOf(warnings));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    /** One dependency of the model: exactly one of {@code location} (a jar) and {@code module} (a sibling) is set. */
    private record Dependency(String coordinate, String location, String module) { }

    private static List<Dependency> dependencies(Resolution resolution, String key, Map<String, String> names, Map<String, String> coordinates,
                                                 Path repo, String self, List<String> warnings) {
        var order = resolution.compileOrder().get(key);
        if (order == null) throw new ProjectModel.Fault("The resolution has no compile order for " + key + "; resolve the project again");
        var out = new LinkedHashSet<Dependency>();
        for (var entry : order) {
            if (entry.startsWith(Resolution.REACTOR)) {
                var gav = entry.substring(Resolution.REACTOR.length());
                var sibling = names.get(gav);
                if (sibling == null) {
                    // A classifier jar of a sibling (a test-jar): no module leaf stands for it.
                    warnings.add(self + " (" + key.substring(key.lastIndexOf(':') + 1) + "): sibling artifact " + gav + " is not a module leaf; left out");
                    continue;
                }
                if (!sibling.equals(self)) out.add(new Dependency(gav, null, sibling));
                continue;
            }
            var path = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) { warnings.add(self + ": classpath entry " + entry + " is not a jar file; left out"); continue; }
            if (path.startsWith(repo)) {
                var parts = repo.relativize(path);
                int n = parts.getNameCount();
                if (n >= 4) {
                    var group = new StringBuilder();
                    for (int i = 0; i < n - 3; i++) group.append(i == 0 ? "" : ".").append(parts.getName(i));
                    out.add(new Dependency(group + ":" + parts.getName(n - 3) + ":" + parts.getName(n - 2), slashes(parts), null));
                    continue;
                }
            }
            // A system-scope or otherwise external jar: its own absolute location, under the coordinate Maven resolved it as.
            var gav = coordinates.get(entry);
            if (gav == null) { warnings.add(self + ": classpath entry " + entry + " has no coordinate; left out"); continue; }
            out.add(new Dependency(gav, slashes(path), null));
        }
        return new ArrayList<>(out);
    }

    private static List<String> roots(Path root, List<String> sources, String processorOutput) {
        var generated = processorOutput == null || processorOutput.isBlank() ? null : Path.of(processorOutput).toAbsolutePath().normalize();
        var out = new ArrayList<String>();
        for (var s : sources) {
            var dir = Path.of(s).toAbsolutePath().normalize();
            // The processor output itself, a directory below it, or one that contains it.
            if (generated != null && (generated.startsWith(dir) || dir.startsWith(generated))) continue;
            out.add(dir.startsWith(root) ? slashes(root.relativize(dir)) : slashes(dir));
        }
        return out;
    }

    /** The language level: {@code --release}, else {@code -source}, else what the resolver read from the POM properties. */
    private static int release(Resolution.Module m) {
        var options = m.compilerOptions();
        for (var flag : List.of("--release", "-source", "--source")) {
            int i = options.indexOf(flag);
            if (i >= 0 && i + 1 < options.size()) return level(options.get(i + 1));
        }
        return level(m.release());
    }

    private static int level(String text) {
        if (text == null || text.isBlank()) return 0;
        var t = text.strip();
        if (t.startsWith("1.")) t = t.substring(2);
        try { return Integer.parseInt(t); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean moduleInfo(Path root, List<String> mainRoots) {
        for (var r : mainRoots) if (Files.isRegularFile(root.resolve(r).resolve("module-info.java"))) return true;
        return false;
    }

    /**
     * The scope's processing mode (demo plan A9). An unset proc is decided by the build JDK, which the model does not know: javac
     * before 23 processes implicitly, 23 and later does not. A module whose release is 23 or later can only be built by 23 or later, so
     * it gets none. Below that it gets full, which is what a project built for 17 or 21 relies on, and the choice is reported.
     */
    private static String processing(Resolution.Processing processing, int release, String scope, List<String> warnings) {
        if (!processing.mode().isBlank()) return processing.mode();
        if (release >= 23) return "none";
        warnings.add(scope + ": proc is not set; processors on the classpath run, as javac before 23 runs them");
        return "full";
    }

    private static void scope(ObjectNode node, List<String> roots, List<Dependency> dependencies, String mode, Resolution.Processing processing, Path repo) {
        var r = node.putArray("sourceRoots");
        roots.forEach(r::add);
        var d = node.putArray("dependencies");
        for (var dep : dependencies) {
            var n = d.addObject().put("coordinate", dep.coordinate());
            if (dep.location() != null) n.put("location", dep.location()); else n.put("module", dep.module());
        }
        // Absent from the model means none.
        if (mode.equals("none")) return;
        var p = node.putObject("processing").put("mode", mode);
        var path = p.putArray("processorPath");
        if (processing.explicitPath())
            for (var jar : processing.path()) {
                var file = Path.of(jar).toAbsolutePath().normalize();
                path.add(file.startsWith(repo) ? slashes(repo.relativize(file)) : slashes(file));
            }
        var names = p.putArray("processors");
        processing.names().forEach(names::add);
    }

    private static String slashes(Path path) { return path.toString().replace('\\', '/'); }
}
