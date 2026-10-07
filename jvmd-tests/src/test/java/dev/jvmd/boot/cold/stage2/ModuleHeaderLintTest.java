package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class ModuleHeaderLintTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    private record Case(String source, List<String> options) { }
    private record Native(boolean success, List<ResultRecord.Diagnostic> messages, byte[] descriptor) { }

    @ParameterizedTest @MethodSource("digests")
    void declarationLintAndSuppressionMatchNativeJavac(Digest digest) throws Exception {
        int sequence = 0;
        for (var example : List.of(new Case("module example1.part2 {}", List.of()),
                new Case("module example1 {}", List.of("-Xlint:-module")),
                new Case("@SuppressWarnings(\"module\") module example1 {}", List.of()),
                new Case("/** @deprecated old module */ module example {}", List.of("-Xlint:dep-ann")),
                new Case("/** @deprecated old module */ @Deprecated module example {}", List.of("-Xlint:dep-ann")),
                new Case("@Deprecated(unknown=1) module example1 {}", List.of()))) {
            var root = Files.createDirectories(dir.resolve("case-" + sequence++));
            var source = root.resolve("m/src/main/java");
            Stage2Support.write(source, Map.of("module-info.java", example.source()));
            var expected = nativeCompile(source.resolve("module-info.java"), root.resolve("native"), example.options());
            var model = ProjectModel.parse(Stage2Support.model(root,
                    new Stage2Support.Mod("m", "g:m:1", List.of()).withOptions(example.options().toArray(String[]::new))));
            var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
            new Stage2(digest, tree, Stage2Support.FEATURE, 1, root, ClassFacts::of).run(store, model);
            var project = Stage2.projectKey(digest, model);
            var diagnostics = HeaderDiagnostics.decode(store.get(LocalStore.headerDiagnosticsKey(project,
                    new SourceUnit("m", 0, "m/src/main/java/module-info.java"))), digest.width());
            assertThat(diagnostics.messages()).as(example.toString()).isEqualTo(expected.messages());
            var result = new Stage3(digest, tree, Stage2Support.FEATURE, 1, root).run(store, model).scopes().get("m/main");
            assertThat(result.diagnostics()).extracting(dev.jvmd.boot.cold.stage3.Diagnostics.Message::diagnostic)
                    .isEqualTo(expected.messages());
            var descriptor = result.files().getFirst().computed();
            assertThat(descriptor.result().attributed()).isEqualTo(expected.success());
            if (expected.success()) {
                assertThat(descriptor.proof()).isNull();
                assertThat(descriptor.result().diagnostics()).isEmpty();
                assertThat(store.get(LocalStore.classFileKey(descriptor.result().classFiles().getFirst().contentHash())))
                        .isEqualTo(expected.descriptor());
            } else assertThat(descriptor.result().classFiles()).isEmpty();
        }
    }

    private static Native nativeCompile(Path source, Path output, List<String> options) throws Exception {
        Files.createDirectories(output);
        var messages = new ArrayList<ResultRecord.Diagnostic>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        javax.tools.DiagnosticListener<JavaFileObject> listener = d -> messages.add(new ResultRecord.Diagnostic(
                d.getKind() == Diagnostic.Kind.ERROR ? 0 : d.getKind() == Diagnostic.Kind.NOTE ? 2 : 1,
                d.getStartPosition(), d.getEndPosition(), d.getCode(), d.getMessage(Locale.ROOT)));
        var args = new ArrayList<>(List.of("-proc:none", "-d", output.toString())); args.addAll(options);
        try (var files = compiler.getStandardFileManager(listener, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, listener, args, null, files.getJavaFileObjects(source));
            task.setLocale(Locale.ROOT); boolean success = task.call();
            return new Native(success, List.copyOf(messages), success ? Files.readAllBytes(output.resolve("module-info.class")) : null);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void sourceLintSuppressionChangesPresentationWithoutRegeneratingDescriptor(Digest digest) throws Exception {
        var source = dir.resolve("m/src/main/java");
        Stage2Support.write(source, Map.of("module-info.java", "module example1 {}"));
        var model = ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var headers = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var driver = new Stage3(digest, tree, Stage2Support.FEATURE, 1, dir);
        headers.run(store, model); var before = driver.run(store, model).scopes().get("m/main");
        assertThat(before.diagnostics()).hasSize(1);
        var descriptor = before.files().getFirst().computed();
        Stage2Support.write(source, Map.of("module-info.java", "@SuppressWarnings(\"module\") module example1 {}"));
        headers.run(store, model); var after = driver.run(store, model).scopes().get("m/main");
        var expected = nativeCompile(source.resolve("module-info.java"), dir.resolve("native-suppressed"), List.of());
        assertThat(expected.messages()).isEmpty();
        assertThat(after.diagnostics()).isEmpty();
        assertThat(after.descriptorEmissions()).isZero();
        assertThat(after.files().getFirst().computed()).isEqualTo(descriptor);
        assertThat(store.get(LocalStore.classFileKey(descriptor.result().classFiles().getFirst().contentHash())))
                .isEqualTo(expected.descriptor());
    }
}
