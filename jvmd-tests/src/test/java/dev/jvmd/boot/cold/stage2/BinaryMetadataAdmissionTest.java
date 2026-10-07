package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Attribute;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import javax.tools.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** The F04 production counterexample, including warning-free -> warning and previously cached RS. */
@Tag("phase-3")
class BinaryMetadataAdmissionTest {
    @TempDir Path directory;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }
    record State(ContentTree tree, InMemoryLocalStore store, MachineLeaf own, Route route, Attribute.Options options,
                 FileRow row, Path source, Pool.Configuration configuration) {
        Attribute.Computed run(Pool pool) throws Exception {
            return Attribute.unprocessed(tree, store, own, route, pool, options).run(row, source.toUri(), Files.readAllBytes(source));
        }
    }
    private State boot(Digest digest, Path jar) throws Exception {
        var model = ProjectModel.parse(Stage2Support.model(directory, new Stage2Support.Mod("app", "g:app:1",
                List.of(Stage2Support.Dep.jar("g:lib:1", jar.toString())))));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, tree, Stage2Support.FEATURE, 2, directory, ClassFacts::of).run(store, model);
        assertThat(result.faults()).isEmpty(); var project = Stage2.projectKey(digest, model);
        var own = MachineLeaf.decode(store.get(MachineStore.leafKey(result.leaves().get("app/main"))), digest.width());
        var route = Route.decode(store.get(LocalStore.routeKey(project, "app", 0)), digest.width());
        var options = Attribute.Options.unprocessed(digest, ModuleRecord.decode(store.get(LocalStore.moduleKey(project, "app"))), Stage2Support.JDK);
        var stubs = Files.createTempDirectory(directory, "stubs-"); var names = new ArrayList<String>();
        for (var stub : Stubs.stubs(digest, tree, own, h -> store.get(MachineStore.nodeKey(h)), Stubs.Cache.NONE)) {
            var path = stubs.resolve(stub.internalName() + ".class"); Files.createDirectories(path.getParent()); Files.write(path, stub.bytes());
            names.add(stub.internalName());
        }
        String path = "app/src/main/java/p/App.java";
        var row = FileRow.decode(path, store.get(LocalStore.fileKey(project, Stage2Support.source(path))), digest.width());
        return new State(tree, store, own, route, options, row, directory.resolve(path),
                new Pool.Configuration(new Pool.Key(route.routeHash(), own.k()), stubs, List.of(jar), options.charset(), options.javac(), names));
    }
    private List<ResultRecord.Diagnostic> nativeCompile(Path output, Path dependencies, List<Path> sources, List<String> options) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler(); var messages = new ArrayList<ResultRecord.Diagnostic>();
        DiagnosticListener<JavaFileObject> listener = d -> messages.add(new ResultRecord.Diagnostic(switch (d.getKind()) {
            case ERROR -> 0; case WARNING, MANDATORY_WARNING -> 1; default -> 2;
        }, d.getStartPosition(), d.getEndPosition(), d.getCode(), d.getMessage(Locale.ROOT)));
        try (var files = compiler.getStandardFileManager(listener, Locale.ROOT, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(dependencies));
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            var task = compiler.getTask(null, files, listener, options, null, files.getJavaFileObjectsFromPaths(sources));
            task.setLocale(Locale.ROOT); assertThat(task.call()).as(messages.toString()).isTrue();
        }
        return messages;
    }
    private Path pack(Path classes) throws Exception {
        var entries = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(classes)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList())
                entries.put(classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        return Stage2Support.pack(directory.resolve("lib.jar"), entries);
    }
    private static Proof resolutionOnly(Proof proof) { return new Proof(proof.header(), proof.types(), proof.absent(), proof.processorBody()); }

    @ParameterizedTest @MethodSource("digests")
    void retainedMetadataNeverAdmitsAnInsufficientProofInEitherDirection(Digest digest) throws Exception {
        var classes = Files.createDirectories(directory.resolve("classes")); var oracle = Files.createDirectories(directory.resolve("oracle"));
        var mode = directory.resolve("Mode.java"); var ann = directory.resolve("Ann.java"); var lib = directory.resolve("Lib.java");
        Files.writeString(ann, "package q; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Ann { Mode value(); }");
        var compileOptions = List.of("-proc:none", "-implicit:none");
        Stage2Support.write(directory, Map.of("app/src/main/java/p/App.java", "package p; public class App { int value(){ return q.Lib.call(); } }"));
        int runs = 0;
        for (boolean hidden : List.of(false, true)) {
            String template = hidden ? "package q; public class Lib { ANNOTATION private int hidden; public static int call(){return 1;} }"
                    : "package q; ANNOTATION public class Lib { public static int call(){return 1;} }";
            Files.writeString(mode, "package q; public enum Mode { X,Y }");
            Files.writeString(lib, template.replace("ANNOTATION", "@Ann(Mode.X)"));
            assertThat(nativeCompile(classes, classes, List.of(mode, ann, lib), compileOptions)).isEmpty();
            byte[] broken = Files.readAllBytes(classes.resolve("q/Lib.class"));
            Files.writeString(mode, "package q; public enum Mode { Y }");
            assertThat(nativeCompile(classes, classes, List.of(mode), compileOptions)).isEmpty();
            var modeBytes = Files.readAllBytes(classes.resolve("q/Mode.class")); var annBytes = Files.readAllBytes(classes.resolve("q/Ann.class"));
            // Both a repaired enum value and complete annotation absence are unsafe to admit without metadata proofs.
            for (String repaired : List.of("@Ann(Mode.Y)", "")) {
                Files.writeString(lib, template.replace("ANNOTATION", repaired));
                assertThat(nativeCompile(classes, classes, List.of(lib), compileOptions)).isEmpty();
                byte[] good = Files.readAllBytes(classes.resolve("q/Lib.class"));
                var x = ClassFacts.of(digest, broken, "q/Lib"); var y = ClassFacts.of(digest, good, "q/Lib");
                assertThat(x.facts().stream().map(Fact::h).toList()).isEqualTo(y.facts().stream().map(Fact::h).toList());
                for (boolean reverse : List.of(false, true)) for (boolean cached : List.of(false, true)) {
                    State prior = null; Attribute.Computed priorResult = null;
                    for (int phase = 0; phase < 2; phase++) {
                        boolean warning = (phase == 0) != reverse;
                        Files.write(classes.resolve("q/Lib.class"), warning ? broken : good);
                        var state = boot(digest, pack(classes));
                        var expected = nativeCompile(oracle, classes, List.of(state.source()), state.options().javac());
                        assertThat(expected.stream().map(ResultRecord.Diagnostic::code).toList())
                                .containsExactlyElementsOf(warning ? List.of("compiler.warn.unknown.enum.constant") : List.of());
                        byte[] oldRecord = null; byte[] oldKey = null;
                        if (prior != null) {
                            assertThat(state.route().routeHash()).isEqualTo(prior.route().routeHash());
                            assertThat(resolutionOnly(priorResult.proof()).valid(state.tree(), state.own(), state.route(), null, state.store()::get)).isTrue();
                            oldKey = LocalStore.resultKey(resolutionOnly(priorResult.proof()).aci(digest, "App.java", prior.row().kappa(), prior.options().hash()));
                            oldRecord = priorResult.result().encode();
                            if (cached) { state.store().put(oldKey, oldRecord); state.store().flush(); }
                        }
                        try (var pool = new Pool(state.configuration(), 1)) {
                            for (int task = 0; task < 2; task++) {
                                var computed = state.run(pool); runs++;
                                assertThat(computed.reusable()).isFalse(); assertThat(computed.aci()).isNull();
                                assertThat(computed.faults()).anyMatch(f -> f.contains("retained binary metadata"));
                                assertThat(computed.proof().valid(state.tree(), state.own(), state.route(), null, state.store()::get)).isFalse();
                                assertThat(Proof.decode(computed.proof().encode(), digest.width())).isEqualTo(computed.proof());
                                assertThatThrownBy(() -> computed.proof().aci(digest, "App.java", state.row().kappa(), state.options().hash()))
                                        .hasMessageContaining("unsupported metadata");
                                assertThat(computed.result().diagnostics()).isEqualTo(expected);
                                assertThat(computed.result().classFiles()).hasSize(1);
                                assertThat(state.store().get(LocalStore.classFileKey(computed.result().classFiles().getFirst().contentHash())))
                                        .isEqualTo(Files.readAllBytes(oracle.resolve("p/App.class")));
                                if (oldKey != null) assertThat(state.store().get(oldKey)).isEqualTo(cached ? oldRecord : null);
                                priorResult = computed;
                            }
                            assertThat(pool.statistics().contexts()).isEqualTo(2); // fresh native deproxy diagnostics on each task
                        }
                        prior = state;
                    }
                }
            }
            assertThat(Files.readAllBytes(classes.resolve("q/Mode.class"))).isEqualTo(modeBytes);
            assertThat(Files.readAllBytes(classes.resolve("q/Ann.class"))).isEqualTo(annBytes);
        }
        assertThat(runs).isEqualTo(64);
    }
}
