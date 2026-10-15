package dev.jvmd.index.layer.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The input of stage 2, as data (stage 2, 2.1 and appendix A): the project root, the JDK home, and per module its source roots,
 * javac options and the build tool's resolved, ordered classpath per scope. Stage 2 validates it and treats it as the truth about
 * the build; how it was obtained is a boundary (section 8).
 *
 * @param bytes the document as read: its digest is the model hash of the LOCAL root (B.7)
 */
public record ProjectModel(String root, String jdkHome, List<Module> modules, byte[] bytes) {
    /** A fault of the model itself: no LOCAL root is written (3.12). */
    public static final class Fault extends RuntimeException {
        public Fault(String message) { super(message); }
        public Fault(String message, Throwable cause) { super(message, cause); }
    }

    /** {@code coordinate} is the module's own, for siblings that depend on it. */
    public record Module(String name, String coordinate, int release, boolean moduleInfo, List<String> javacOptions, Scope main, Scope test) {
        public Scope scope(int scope) { return scope == LocalStore.MAIN ? main : test; }
    }

    public record Scope(List<String> sourceRoots, List<Dependency> dependencies, Processing processing) { }

    /** Exactly one of {@code location} (a jar) and {@code module} (a sibling) is set. */
    public record Dependency(String coordinate, String location, String module) { }

    /**
     * Annotation processing in a scope's header compilation (amendments 4.8): absent from the model means none.
     *
     * @param mode          {@code none}, {@code full} or {@code only}; header compilation treats {@code only} as {@code full}
     * @param processorPath jar locations, as dependency locations are written; empty means processors are found on the classpath
     * @param processors    explicit processor class names, empty for discovery
     */
    public record Processing(String mode, List<String> processorPath, List<String> processors) {
        public static final Processing NONE = new Processing("none", List.of(), List.of());

        public boolean enabled() { return !mode.equals("none"); }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ProjectModel parse(byte[] json) {
        try {
            var doc = MAPPER.readTree(json);
            if (doc == null || !doc.isObject()) throw new Fault("Project model is not a JSON object");
            var modules = new ArrayList<Module>();
            for (var m : doc.path("modules")) {
                var scopes = m.path("scopes");
                modules.add(new Module(text(m, "name"), text(m, "coordinate"), m.path("release").asInt(0), m.path("moduleInfo").asBoolean(false),
                        strings(m.path("javacOptions")), scope(scopes.path("main")), scope(scopes.path("test"))));
            }
            return new ProjectModel(text(doc, "root"), text(doc, "jdkHome"), List.copyOf(modules), json.clone());
        } catch (IOException e) {
            throw new Fault("Project model is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static Scope scope(JsonNode node) {
        var deps = new ArrayList<Dependency>();
        for (var d : node.path("dependencies")) {
            String location = d.hasNonNull("location") ? d.get("location").asText() : null;
            String module = d.hasNonNull("module") ? d.get("module").asText() : null;
            if ((location == null) == (module == null))
                throw new Fault("Dependency " + text(d, "coordinate") + " must have exactly one of location and module");
            deps.add(new Dependency(text(d, "coordinate"), location, module));
        }
        return new Scope(strings(node.path("sourceRoots")), List.copyOf(deps), processing(node.path("processing")));
    }

    private static Processing processing(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return Processing.NONE;
        String mode = node.path("mode").asText("full");
        if (!List.of("none", "full", "only").contains(mode)) throw new Fault("Unknown processing mode: " + mode);
        return new Processing(mode, strings(node.path("processorPath")), strings(node.path("processors")));
    }

    private static String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) throw new Fault("Project model is missing \"" + field + "\"");
        return node.get(field).asText();
    }

    private static List<String> strings(JsonNode node) {
        var out = new ArrayList<String>();
        for (var s : node) out.add(s.asText());
        return List.copyOf(out);
    }

    /** The validation faults of appendix A, each fatal to the boot; the module cycle is {@code Order}'s. */
    public void validate() {
        var names = new HashSet<String>();
        for (var m : modules) if (!names.add(m.name())) throw new Fault("Duplicate module name: " + m.name());
        for (var m : modules) {
            try { Coordinate.parse(m.coordinate()); } catch (IllegalArgumentException e) { throw new Fault("Module " + m.name() + ": " + e.getMessage(), e); }
            for (var scope : List.of(m.main(), m.test()))
                for (var d : scope.dependencies()) {
                    try { Coordinate.parse(d.coordinate()); } catch (IllegalArgumentException e) { throw new Fault("Module " + m.name() + ": " + e.getMessage(), e); }
                    if (d.module() != null && !names.contains(d.module()))
                        throw new Fault("Module " + m.name() + " depends on " + d.module() + ", which is not in the model");
                }
        }
        if (!Files.isRegularFile(Path.of(jdkHome).resolve("lib/modules"))) throw new Fault("jdkHome has no lib/modules: " + jdkHome);
        refuseSharedSources();
    }

    /**
     * Demo plan A2: a source file is compiled by one module in one scope. Its records ({@code F}, {@code X}) are keyed by its path, so
     * two modules or scopes compiling the same file would overwrite each other's. A source root that is, or lies under, a root of another
     * module or scope is therefore a fault, never collapsed into one.
     *
     * <p>Roots are compared as written (normalized) and, where that differs, as real paths, so a link from one module's root into
     * another's overlaps too. A root that does not exist is skipped, as the boot skips it. Roots nested within one scope are fine,
     * because each file is found once. Every overlap is named, in one fault. Work is one map entry per root and one lookup per ancestor.
     */
    private void refuseSharedSources() {
        var claims = new ArrayList<Claim>();
        for (var m : modules)
            for (var scope : List.of(LocalStore.MAIN, LocalStore.TEST))
                for (var r : m.scope(scope).sourceRoots()) {
                    Path declared;
                    try { declared = resolve(r).toAbsolutePath().normalize(); }
                    catch (InvalidPathException e) { throw new Fault("Module " + m.name() + ": invalid source root " + r, e); }
                    if (!Files.isDirectory(declared)) continue;
                    var owner = new Owner(m.name(), scope == LocalStore.MAIN ? "main" : "test");
                    claims.add(new Claim(declared, declared, owner));
                    try {
                        var real = declared.toRealPath();
                        if (!real.equals(declared)) claims.add(new Claim(real, declared, owner));
                    } catch (IOException unreadable) { /* compared as written */ }
                }
        var byPath = new HashMap<Path, Claim>();
        var overlaps = new LinkedHashSet<String>();
        for (var c : claims) {
            var other = byPath.putIfAbsent(c.path(), c);
            if (other != null && !other.owner().equals(c.owner())) overlaps.add(overlap(other, c));
        }
        for (var c : claims)
            for (var p = c.path().getParent(); p != null; p = p.getParent()) {
                var other = byPath.get(p);
                if (other != null && !other.owner().equals(c.owner())) overlaps.add(overlap(other, c));
            }
        if (!overlaps.isEmpty())
            throw new Fault("Source roots overlap, and a source file must belong to one module and scope: " + String.join("; ", overlaps));
    }

    /** The module and scope a source root belongs to. */
    private record Owner(String module, String scope) {
        @Override public String toString() { return module + " (" + scope + ")"; }
    }

    /** A source root under one of its paths: as written, or real. */
    private record Claim(Path path, Path declared, Owner owner) {
        String describe() { return declared + " of " + owner + (path.equals(declared) ? "" : " (really " + path + ")"); }
    }

    private static String overlap(Claim outer, Claim inner) { return inner.describe() + " is in " + outer.describe(); }

    /** A path of the model: absolute, or relative to the project root. */
    public Path resolve(String path) { return Path.of(root).resolve(path); }
}
