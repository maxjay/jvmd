package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.local.SourceFacts;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.core.tree.ContentTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class ProcessorHostTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    private static final String LOMBOK = "lombok.launch.AnnotationProcessorHider$AnnotationProcessor";

    @ParameterizedTest @MethodSource("digests")
    void malformedGeneratedSourceHasAFileFaultInsteadOfSilentPartialFacts(Digest digest) throws Exception {
        var processor = generator("isolating", false, "return 1");
        var file = dir.resolve("src/p/Input.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package p; public class Input {}");
        try (var host = new ProcessorHost(List.of(processor), List.of(), digest, dir.resolve("generated"));
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("src/p/Input.java", file)), List.of(),
                     Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
            assertThat(host.outputs()).hasSize(1);
            var generated = compiled.units.stream().filter(u -> u.path.endsWith("Generated.java")).findFirst().orElseThrow();
            assertThat(generated.parseError).contains("expected");
            assertThat(generated.declared).isEmpty();
            assertThat(compiled.units.getFirst().parseError).isNull();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void moduleDescriptorDoesNotPreventTheClasspathHeaderTaskFromRunningProcessors(Digest digest) throws Exception {
        var processor = generator("isolating", false);
        var file = dir.resolve("src/p/Input.java");
        var descriptor = dir.resolve("src/module-info.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package p; public class Input {}");
        Files.writeString(descriptor, "module fixture { exports p; }");
        try (var host = new ProcessorHost(List.of(processor), List.of(), digest, dir.resolve("generated"));
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("src/module-info.java", descriptor), new HeaderCompiler.Source("src/p/Input.java", file)),
                     List.of(), Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
            assertThat(host.outputs()).hasSize(1);
            assertThat(compiled.units).hasSize(3);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void autoValueRecordsItsGeneratedSourceAndOneOrigin(Digest digest) throws Exception {
        var processor = Path.of(com.google.auto.value.processor.AutoValueProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var annotations = Path.of(com.google.auto.value.AutoValue.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var file = dir.resolve("src/p/Value.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package p; @com.google.auto.value.AutoValue public abstract class Value { public abstract String name(); }");
        try (var host = new ProcessorHost(List.of(processor), List.of(), digest, dir.resolve("generated"));
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("src/p/Value.java", file)), List.of(annotations),
                     Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
            assertThat(host.outputs()).hasSize(1);
            assertThat(host.outputs().getFirst().name()).isEqualTo("p/AutoValue_Value.java");
            assertThat(host.outputs().getFirst().origins()).containsExactly(file.toUri());
            assertThat(host.capabilities().get("com.google.auto.value.processor.AutoValueProcessor").declared()).isEqualTo(1);
            assertThat(host.capabilities().get("com.google.auto.value.processor.AutoValueProcessor").observed()).isEqualTo(1);
            assertThat(host.faults()).isEmpty();
            assertThat(compiled.units).hasSize(2);
            var reads = host.modelReads().get("com.google.auto.value.processor.AutoValueProcessor");
            assertThat(reads).anyMatch(r -> r.operation().equals("Types.isAssignable") && r.types().contains("java/lang/Object"));
            assertThat(reads).anyMatch(r -> r.operation().equals("Elements.getTypeElement") && r.missingTypes().contains("p.String"));
            var report = Path.of("target", "autovalue-model-reads-" + digest.name() + ".txt");
            Files.createDirectories(report.getParent());
            Files.write(report, reads.stream().map(Object::toString).distinct().sorted().toList());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void processorCodeChangesMovePathIdentityEvenWhenItsApiIsEqual(Digest digest) throws Exception {
        var first = generator("isolating", false, "return 1;");
        var second = generator("isolating", false, "return 2;");
        try (var a = new ProcessorHost(List.of(first), List.of(), digest, dir.resolve("g1"));
             var b = new ProcessorHost(List.of(second), List.of(), digest, dir.resolve("g2"))) {
            assertThat(a.pathHash()).isNotEqualTo(b.pathHash());
        }
        var leaves = new ArrayList<dev.jvmd.core.hash.Identity>();
        for (var jar : List.of(first, second)) {
            try (var archive = new java.util.jar.JarFile(jar.toFile())) {
                var bytes = archive.getInputStream(archive.getJarEntry("fixture/Generator.class")).readAllBytes();
                var facts = ClassFacts.of(digest, bytes, "fixture/Generator");
                var builder = new LeafBuilder(new ContentTree(digest), new InMemoryLocalStore());
                facts.facts().stream().sorted((a, b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(builder::add);
                builder.edges(facts.edges());
                leaves.add(builder.seal());
            }
        }
        assertThat(leaves.getFirst()).isEqualTo(leaves.getLast());
    }

    @ParameterizedTest @MethodSource("digests")
    void lombokWrapperProducesTheSameFactsAsDirectProcessingAndClasses(Digest digest) throws Exception {
        var lombok = Path.of(lombok.Getter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var text = "package p; @lombok.Getter public class Bean { private String value; }";
        var classes = Stage2Support.compile(dir.resolve("oracle"), Map.of("p/Bean.java", text),
                List.of("-proc:full", "--processor-path", lombok.toString()), List.of(lombok));
        var file = dir.resolve("src/p/Bean.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        var source = List.of(new HeaderCompiler.Source("src/p/Bean.java", file));
        var expected = new LeafBuilder(new ContentTree(digest), new InMemoryLocalStore());
        var binary = ClassFacts.of(digest, classes.get("p/Bean.class"), "p/Bean");
        binary.facts().stream().sorted((a, b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(expected::add);
        expected.edges(binary.edges());
        var expectedK = expected.seal();
        var direct = directHeaderLeaf(file, lombok, digest);
        assertThat(direct.k()).isEqualTo(expectedK);
        assertThat(direct.a()).isEqualTo(expected.a());
        try (var host = new ProcessorHost(List.of(lombok), List.of(), digest, dir.resolve("generated"));
             var compiled = HeaderCompiler.compile(source, List.of(lombok), Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
            var extracted = new SourceFacts(digest, compiled.elements, compiled.types, compiled.trees, false).of(compiled.units.getFirst().declared);
            assertThat(extracted.faults()).isEmpty();
            var actual = new LeafBuilder(new ContentTree(digest), new InMemoryLocalStore());
            extracted.facts().stream().sorted((a, b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(actual::add);
            actual.edges(extracted.edges());
            assertThat(actual.seal()).isEqualTo(expectedK);
            assertThat(actual.a()).isEqualTo(expected.a());
            assertThat(host.capabilities().get(LOMBOK).declared()).isEqualTo(1);
            assertThat(host.capabilities().get(LOMBOK).observed()).isZero();
            assertThat(host.outputs()).isEmpty();
            assertThat(host.faults()).isEmpty();
        }
    }

    private record Projection(dev.jvmd.core.hash.Identity k, dev.jvmd.core.hash.Identity a) { }

    /** Independent header-only task with javac's own processor loading and native processing environment. */
    private Projection directHeaderLeaf(Path source, Path processor, Digest digest) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var output = Files.createDirectories(dir.resolve("direct-header"));
            var options = List.of("-source", Integer.toString(Stage2Support.FEATURE), "--system", Stage2Support.JDK.toString(), "-encoding", "UTF-8",
                    "-proc:full", "-implicit:none", "--class-path", processor.toString(), "--processor-path", processor.toString(), "-d", output.toString(), "-s", output.toString());
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, files, diagnostics, options, null, files.getJavaFileObjects(source));
            task.setLocale(java.util.Locale.ROOT);
            try {
                // enter() is deliberately absent from the public JavacTask API. Invoke only that phase, never analyze().
                var entered = (Iterable<?>) task.getClass().getMethod("enter").invoke(task);
                var types = new ArrayList<javax.lang.model.element.TypeElement>();
                for (var element : entered) if (element instanceof javax.lang.model.element.TypeElement type) types.add(type);
                var result = new SourceFacts(digest, task.getElements(), task.getTypes(), com.sun.source.util.Trees.instance(task), false).of(types);
                assertThat(diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)).isEmpty();
                assertThat(result.faults()).isEmpty();
                var builder = new LeafBuilder(new ContentTree(digest), new InMemoryLocalStore());
                result.facts().stream().sorted((a, b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(builder::add);
                builder.edges(result.edges());
                return new Projection(builder.seal(), builder.a());
            } finally {
                var context = task.getClass().getMethod("getContext").invoke(task);
                var javac = Class.forName("com.sun.tools.javac.main.JavaCompiler");
                var backend = javac.getMethod("instance", Class.forName("com.sun.tools.javac.util.Context")).invoke(null, context);
                javac.getMethod("close").invoke(backend);
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void isolatedGeneratorRecordsSourceAndOriginWithoutAttributingItsBody(Digest digest) throws Exception {
        var processor = generator("isolating", false);
        var file = dir.resolve("src/p/Input.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package p; public class Input { void body() { unknown(); } }");
        try (var host = new ProcessorHost(List.of(processor), List.of(), digest, dir.resolve("generated"));
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("src/p/Input.java", file)), List.of(),
                     Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
            assertThat(host.outputs()).hasSize(1);
            var output = host.outputs().getFirst();
            assertThat(output.processorClass()).isEqualTo("fixture.Generator");
            assertThat(output.origins()).containsExactly(file.toUri());
            assertThat(new String(output.bytes(), java.nio.charset.StandardCharsets.UTF_8)).contains("class Generated");
            assertThat(compiled.units).hasSize(2);
            assertThat(compiled.units.stream().flatMap(u -> u.declared.stream()).map(t -> t.getQualifiedName().toString())).contains("p.Generated");
            assertThat(host.capabilities().get("fixture.Generator").observed()).isEqualTo(1);
            assertThat(host.faults()).isEmpty();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void unsupportedProcessorsAreNamedAndCannotClaimReusableOutput(Digest digest) throws Exception {
        for (var declaration : List.of("", "isolating")) {
            var processor = generator(declaration, true);
            var first = dir.resolve("src/p/Input.java");
            var second = dir.resolve("src/p/Second.java");
            Files.createDirectories(first.getParent());
            Files.writeString(first, "package p; public class Input {}");
            Files.writeString(second, "package p; public class Second {}");
            try (var host = new ProcessorHost(List.of(processor), List.of(), digest, Files.createTempDirectory(dir, "generated"));
                 var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("src/p/Input.java", first), new HeaderCompiler.Source("src/p/Second.java", second)),
                         List.of(), Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
                assertThat(host.outputs()).hasSize(1);
                assertThat(host.capabilities().get("fixture.Generator").observed()).isEqualTo(2);
                assertThat(host.faults()).anyMatch(f -> f.contains("fixture.Generator"));
            }
        }
    }

    private Path generator(String declaration, boolean multipleOrigins) throws Exception {
        return generator(declaration, multipleOrigins, "return 1;");
    }

    private Path generator(String declaration, boolean multipleOrigins, String generatedBody) throws Exception {
        var work = Files.createTempDirectory(dir, "processor");
        var source = """
                package fixture;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                import java.util.*;
                @SupportedAnnotationTypes("*")
                public class Generator extends AbstractProcessor {
                    private boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        var roots = round.getRootElements().stream().filter(e -> e.getKind().isClass()).toList();
                        try (var out = processingEnv.getFiler().createSourceFile("p.Generated", ORIGINS).openWriter()) {
                            out.write("package p; public class Generated { public int value() { return 1; } }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """.replace("ORIGINS", multipleOrigins ? "roots.toArray(Element[]::new)" : "roots.getFirst()").replace("return 1;", generatedBody);
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Generator.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Generator\n"));
        if (!declaration.isEmpty()) entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Generator," + declaration + "\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }
}
