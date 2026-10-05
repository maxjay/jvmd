package dev.jvmd.boot.cold.stage2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Test scaffolding, not the product's model provider (stage 2, section 8 and rule 10): runs Maven over a reactor and writes the
 * project model of appendix A. {@code mvn compile test-compile dependency:build-classpath} runs once per scope over the whole
 * reactor; the resolved classpath of each module is Maven's own answer, and this class only translates it: a jar under the local
 * repository becomes a location, another module's {@code target/classes} becomes a sibling. Stage 2 never sees a pom.
 */
final class MavenModelHelper {
    private MavenModelHelper() { }

    /** One module of the reactor, as its pom says it. */
    record Module(String name, Path directory, String coordinate, String packaging) {
        Path classes() { return directory.resolve("target/classes"); }
        Path testClasses() { return directory.resolve("target/test-classes"); }
    }

    /** The model JSON and what the caller needs to compare the result against Maven's own output. */
    record Model(byte[] json, List<Module> modules) { }

    /**
     * @param repository Maven's local repository: jar locations in the model are relative to it
     * @param release    the {@code --release} the modules compile with, recorded in the model
     */
    static Model build(Path root, Path repository, int release) throws IOException, InterruptedException {
        var modules = modules(root, root, new ArrayList<>());
        maven(root, "-Dmdep.includeScope=compile", "-Dmdep.outputFile=target/jvmd-classpath-main.txt", "help:effective-pom", "-Doutput=" + root.resolve("target/jvmd-effective-pom.xml").toAbsolutePath());
        maven(root, "-Dmdep.includeScope=test", "-Dmdep.outputFile=target/jvmd-classpath-test.txt");

        var effective = effectiveProjects(root.resolve("target/jvmd-effective-pom.xml"));
        var byClasses = new LinkedHashMap<Path, Module>();
        for (var m : modules) byClasses.put(m.classes().toAbsolutePath().normalize(), m);
        var json = new ObjectMapper();
        var doc = json.createObjectNode();
        doc.put("root", root.toAbsolutePath().normalize().toString().replace('\\', '/'));
        doc.put("jdkHome", System.getProperty("java.home").replace('\\', '/'));
        var list = doc.putArray("modules");
        for (var m : modules) {
            if (m.packaging().equals("pom")) continue;
            var main = classpath(m.directory().resolve("target/jvmd-classpath-main.txt"));
            var test = classpath(m.directory().resolve("target/jvmd-classpath-test.txt"));
            var mainEntries = entries(main, byClasses, repository, m);
            var testOnly = new ArrayList<>(entries(test, byClasses, repository, m));
            testOnly.removeAll(mainEntries);
            ObjectNode node = list.addObject();
            node.put("name", m.name()).put("coordinate", m.coordinate()).put("release", release).put("moduleInfo", false);
            // As the build passes them: the compiler plugin gives a module its project version as --module-version.
            var options = node.putArray("javacOptions");
            var project = effective.get(m.coordinate());
            if (project == null) throw new IOException("No effective Maven model for " + m.coordinate());
            for (var option : compilerOptions(project)) options.add(option);
            if (Files.isRegularFile(m.directory().resolve("src/main/java/module-info.java")))
                options.add("--module-version").add(m.coordinate().substring(m.coordinate().lastIndexOf(':') + 1));
            var scopes = node.putObject("scopes");
            var base = root.toAbsolutePath().normalize();
            scope(scopes.putObject("main"), sourceRoots(base, m, project, false), mainEntries);
            scope(scopes.putObject("test"), sourceRoots(base, m, project, true), testOnly);
        }
        return new Model(json.writeValueAsBytes(doc), modules);
    }

    /**
     * What the compiler plugin passes for the language level, as the poms say it: {@code --release N} when the module (or its parent)
     * sets {@code maven.compiler.release}, {@code -source N -target N} when that property is empty and source and target are set.
     */
    private static Map<String, Element> effectiveProjects(Path file) throws IOException {
        var result = new LinkedHashMap<String, Element>();
        var document = parse(file).getDocumentElement();
        var projects = document.getTagName().equals("project") ? List.of(document) : children(document, "project");
        for (var project : projects) result.put(text(project, "groupId") + ":" + text(project, "artifactId") + ":" + text(project, "version"), project);
        return result;
    }

    private static List<String> compilerOptions(Element project) {
        var out = new ArrayList<String>();
        var properties = child(project, "properties");
        String release = text(properties, "maven.compiler.release");
        if (release != null && !release.isBlank()) out.addAll(List.of("--release", release));
        else {
            String source = text(properties, "maven.compiler.source"), target = text(properties, "maven.compiler.target");
            if (source != null) out.addAll(List.of("-source", source));
            if (target != null) out.addAll(List.of("-target", target));
        }
        var build = child(project, "build");
        for (var plugin : children(child(build, "plugins"), "plugin")) {
            if (!"maven-compiler-plugin".equals(text(plugin, "artifactId"))) continue;
            var configuration = child(plugin, "configuration");
            for (var arg : children(child(configuration, "compilerArgs"), "arg")) out.add(arg.getTextContent().trim());
            if ("true".equals(text(configuration, "parameters"))) out.add("-parameters");
            String debug = text(configuration, "debug"), level = text(configuration, "debuglevel");
            if ("false".equals(debug)) out.add("-g:none");
            else if (level != null) out.add("-g:" + level);
            String encoding = text(configuration, "encoding");
            if (encoding == null) encoding = text(properties, "project.build.sourceEncoding");
            if (encoding != null) out.addAll(List.of("-encoding", encoding));
        }
        return out;
    }

    private static List<String> sourceRoots(Path base, Module module, Element project, boolean test) {
        var roots = new java.util.LinkedHashSet<Path>();
        var build = child(project, "build");
        String standard = text(build, test ? "testSourceDirectory" : "sourceDirectory");
        roots.add(standard == null ? module.directory().resolve(test ? "src/test/java" : "src/main/java") : Path.of(standard));
        roots.add(module.directory().resolve(test ? "target/generated-test-sources/test-annotations" : "target/generated-sources/annotations"));
        for (var plugin : children(child(build, "plugins"), "plugin")) {
            if (!"build-helper-maven-plugin".equals(text(plugin, "artifactId"))) continue;
            for (var execution : children(child(plugin, "executions"), "execution")) {
                boolean addsSources = children(child(execution, "goals"), "goal").stream()
                        .anyMatch(g -> g.getTextContent().trim().equals(test ? "add-test-source" : "add-source"));
                if (!addsSources) continue;
                for (var source : children(child(child(execution, "configuration"), "sources"), "source"))
                    roots.add(module.directory().resolve(source.getTextContent().trim()).normalize());
            }
        }
        return roots.stream().map(p -> relative(base, p)).toList();
    }

    private static List<Element> children(Element parent, String tag) {
        var result = new ArrayList<Element>();
        if (parent != null) for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(tag)) result.add(e);
        return result;
    }

    private static String relative(Path base, Path path) { return base.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'); }

    private static void scope(ObjectNode node, List<String> roots, List<String[]> entries) {
        var r = node.putArray("sourceRoots");
        roots.forEach(r::add);
        var d = node.putArray("dependencies");
        for (var e : entries) {
            var n = d.addObject().put("coordinate", e[0]);
            if (e[1] != null) n.put("location", e[1]); else n.put("module", e[2]);
        }
    }

    /** {coordinate, location, module} per classpath entry of a module, in Maven's order. A test-jar or any other directory is skipped. */
    private static List<String[]> entries(List<Path> classpath, Map<Path, Module> siblings, Path repository, Module self) {
        var out = new ArrayList<String[]>();
        var repo = repository.toAbsolutePath().normalize();
        for (var entry : classpath) {
            var path = entry.toAbsolutePath().normalize();
            var sibling = siblings.get(path);
            if (sibling != null) { if (sibling != self) out.add(new String[] {sibling.coordinate(), null, sibling.name()}); continue; }
            if (!path.startsWith(repo) || !path.toString().endsWith(".jar")) continue;
            var parts = repo.relativize(path);
            int n = parts.getNameCount();
            if (n < 4) continue;
            var group = new StringBuilder();
            for (int i = 0; i < n - 3; i++) group.append(i == 0 ? "" : ".").append(parts.getName(i));
            out.add(new String[] {group + ":" + parts.getName(n - 3) + ":" + parts.getName(n - 2), parts.toString().replace('\\', '/'), null});
        }
        return out;
    }

    private static List<Path> classpath(Path file) throws IOException {
        var out = new ArrayList<Path>();
        if (!Files.isRegularFile(file)) throw new IOException("Maven wrote no classpath at " + file);
        var text = Files.readString(file).trim();
        if (text.isEmpty()) return out;
        for (var part : text.split(java.io.File.pathSeparator)) out.add(Path.of(part));
        return out;
    }

    // ---- the reactor ------------------------------------------------------------------------------------------------------------

    private static List<Module> modules(Path root, Path directory, List<Module> out) throws IOException {
        var pom = parse(directory.resolve("pom.xml"));
        var project = pom.getDocumentElement();
        var parent = child(project, "parent");
        String group = text(project, "groupId") != null ? text(project, "groupId") : parent != null ? text(parent, "groupId") : null;
        String version = text(project, "version") != null ? text(project, "version") : parent != null ? text(parent, "version") : null;
        String packaging = text(project, "packaging") == null ? "jar" : text(project, "packaging");
        var name = directory.equals(root) ? "root" : root.relativize(directory).toString().replace('\\', '/');
        out.add(new Module(name, directory, group + ":" + text(project, "artifactId") + ":" + version, packaging));
        var modules = child(project, "modules");
        if (modules != null)
            for (var n = modules.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof Element e && e.getTagName().equals("module")) modules(root, directory.resolve(e.getTextContent().trim()), out);
        // The aggregator itself is not a module of the model: it has no sources.
        if (directory.equals(root)) out.removeIf(m -> m.directory().equals(root));
        return out;
    }

    private static org.w3c.dom.Document parse(Path pom) throws IOException {
        try { return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile()); }
        catch (Exception e) { throw new IOException("Cannot read " + pom, e); }
    }

    private static Element child(Element parent, String tag) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        return null;
    }

    private static String text(Element parent, String tag) { var e = child(parent, tag); return e == null ? null : e.getTextContent().trim(); }

    // ---- running Maven ----------------------------------------------------------------------------------------------------------

    /** One reactor run for one scope; offline first, since a developer machine has what the build needs, then online. */
    private static void maven(Path root, String... scope) throws IOException, InterruptedException {
        for (boolean offline : new boolean[] {true, false}) {
            var command = new ArrayList<String>(executable());
            if (offline) command.add("-o");
            command.addAll(List.of("-q", "compile", "test-compile", "dependency:build-classpath"));
            command.addAll(List.of(scope));
            var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(10, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IOException("Maven timed out"); }
            if (process.exitValue() == 0) return;
            if (!offline) throw new IOException("Maven failed:\n" + output);
        }
    }

    private static List<String> executable() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        for (var home : new String[] {System.getProperty("maven.home"), System.getenv("MAVEN_HOME"), System.getenv("M2_HOME")}) {
            if (home == null) continue;
            var bin = Path.of(home, "bin", windows ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(bin)) return windows ? List.of("cmd", "/c", bin.toString()) : List.of(bin.toString());
        }
        return windows ? List.of("cmd", "/c", "mvn") : List.of("mvn");
    }
}
