package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.local.SourceFacts;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.index.layer.machine.Stubs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class BodyProcessorTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    record Fixture(Path input, Identity processorPath, Map<String, ProcessorRecords.Capability> capabilities,
                   List<ProcessorHost.Output> generated, Pool.Configuration configuration, Map<String, byte[]> classes) { }

    private Fixture fixture(Digest digest, List<Path> processors, List<Path> classpath, String name, String text) throws Exception {
        var input = dir.resolve("src/" + name); Files.createDirectories(input.getParent()); Files.writeString(input, text);
        var options = List.of("-encoding", "UTF-8", "-proc:full", "-implicit:none");
        var nativeOptions = new ArrayList<>(options);
        nativeOptions.addAll(List.of("--processor-path", String.join(java.io.File.pathSeparator, processors.stream().map(Path::toString).toList())));
        var classes = Stage2Support.compile(dir.resolve("oracle"), Map.of(name, text), nativeOptions, classpath);
        var tree = new ContentTree(digest); var store = new InMemoryLocalStore(); var builder = new LeafBuilder(tree, store);
        Identity pathHash; Map<String, ProcessorRecords.Capability> capabilities; List<ProcessorHost.Output> generated;
        try (var host = new ProcessorHost(processors, List.of(), digest, dir.resolve("header-generated"));
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source(name, input)), classpath, Stage2Support.JDK,
                     Stage2Support.FEATURE, options, digest, host)) {
            var facts = new SourceFacts(digest, compiled.elements, compiled.types, false)
                    .of(compiled.units.stream().flatMap(u -> u.declared.stream()).toList());
            assertThat(facts.faults()).isEmpty();
            facts.facts().stream().sorted((a, b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(builder::add);
            builder.edges(facts.edges()); builder.seal(); store.flush();
            pathHash = host.pathHash(); capabilities = host.capabilities(); generated = host.outputs();
        }
        var leaf = builder.build(); store.flush();
        var names = new ArrayList<String>(); var own = Files.createDirectories(dir.resolve("stubs"));
        for (var stub : Stubs.stubs(digest, tree, leaf, id -> store.get(dev.jvmd.index.layer.machine.MachineStore.nodeKey(id)), Stubs.Cache.NONE)) {
            var file = own.resolve(stub.internalName() + ".class"); Files.createDirectories(file.getParent()); Files.write(file, stub.bytes()); names.add(stub.internalName());
        }
        var configuration = new Pool.Configuration(new Pool.Key(digest.hash(new byte[]{1}), leaf.k()), own, classpath,
                StandardCharsets.UTF_8, options, names);
        return new Fixture(input, pathHash, capabilities, generated, configuration, classes);
    }

    record Run(Map<String, byte[]> classes, List<ProcessorHost.Output> generated, Map<String, ProcessorRecords.Capability> capabilities,
               List<String> faults, List<String> diagnostics, ProcessorRecords.Body observations) { }

    private Run run(Digest digest, Fixture fixture, List<Path> processors, Pool pool, Path source) throws Exception {
        var diagnostics = new ArrayList<String>();
        var optionsHash = digest.hash(new byte[]{11});
        var scope = new ProcessorRecords.Scope(fixture.processorPath(), optionsHash, fixture.capabilities().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(e -> new ProcessorRecords.Invocation(e.getKey(), e.getValue())).toList());
        try (var host = ProcessorHost.bodies(processors, digest, dir.resolve("body-capture"), StandardCharsets.UTF_8, scope, optionsHash)) {
            assertThatThrownBy(host::bodyObservations).hasMessage("Body processor observations are not finalized");
            var result = pool.withTask(source.toUri(), Files.readAllBytes(source), d -> diagnostics.add(d.getKind() + ":" + d.getMessage(java.util.Locale.ROOT)), task -> {
                host.attach(task);
                try {
                    var units = new ArrayList<com.sun.source.tree.CompilationUnitTree>(); task.parse().forEach(units::add);
                    assertThat(units).hasSize(1); task.analyze(); task.generate(); return 0;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            host.close(); // snapshot capability faults for unclosed outputs before asserting admission
            assertThat(dir.resolve("body-capture")).doesNotExist();
            return new Run(result.classes(), host.outputs(), host.capabilities(), host.faults(), diagnostics, host.bodyObservations());
        }
    }

    private void sameOutputs(List<ProcessorHost.Output> actual, List<ProcessorHost.Output> expected) {
        var a = new TreeMap<String, byte[]>(); actual.forEach(o -> a.put(o.name(), o.bytes()));
        var b = new TreeMap<String, byte[]>(); expected.forEach(o -> b.put(o.name(), o.bytes()));
        assertThat(a.keySet()).isEqualTo(b.keySet()); a.forEach((name, bytes) -> assertThat(bytes).as(name).isEqualTo(b.get(name)));
    }

    @ParameterizedTest @MethodSource("digests")
    void autoValueCapturesNativeSourceAndCompilesOnlyTheExplicitUnitAcrossReusedTasks(Digest digest) throws Exception {
        var processor = Path.of(com.google.auto.value.processor.AutoValueProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var annotations = Path.of(com.google.auto.value.AutoValue.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var processors = List.of(processor);
        var fixture = fixture(digest, processors, List.of(annotations), "p/Value.java", """
                package p;
                @com.google.auto.value.AutoValue public abstract class Value {
                    public abstract String name();
                    public static Value of(String name) { return new AutoValue_Value(name); }
                }
                """);
        assertThat(fixture.generated()).hasSize(1);
        try (var pool = new Pool(fixture.configuration(), 1)) {
            ProcessorRecords.Body previous = null;
            for (int repeat = 0; repeat < 2; repeat++) {
                var result = run(digest, fixture, processors, pool, fixture.input());
                assertThat(result.observations().reusable()).isTrue();
                if (previous != null) assertThat(result.observations()).isEqualTo(previous);
                previous = result.observations();
                assertThat(result.diagnostics()).isEmpty(); assertThat(result.faults()).isEmpty();
                sameOutputs(result.generated(), fixture.generated());
                assertThat(result.classes()).containsOnlyKeys("p/Value");
                assertThat(result.classes().get("p/Value")).isEqualTo(fixture.classes().get("p/Value.class"));
                assertThat(result.generated().getFirst().origins()).containsExactly(fixture.input().toUri());
            }
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void lombokOverlayGeneratesNativeOriginalAndNestedClassBytesAcrossReusedTasks(Digest digest) throws Exception {
        var lombok = Path.of(lombok.Getter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var processors = List.of(lombok);
        var fixture = fixture(digest, processors, processors, "p/Bean.java", """
                package p;
                @lombok.Value @lombok.Builder public class Bean {
                    String name;
                    public int size() { return getName().length(); }
                }
                """);
        try (var pool = new Pool(fixture.configuration(), 1)) {
            ProcessorRecords.Body previous = null;
            for (int repeat = 0; repeat < 2; repeat++) {
                var result = run(digest, fixture, processors, pool, fixture.input());
                assertThat(result.observations().reusable()).isTrue();
                if (previous != null) assertThat(result.observations()).isEqualTo(previous);
                previous = result.observations();
                assertThat(result.diagnostics()).isEmpty(); assertThat(result.faults()).isEmpty();
                assertThat(result.generated()).isEmpty();
                assertThat(result.classes()).containsOnlyKeys("p/Bean", "p/Bean$BeanBuilder");
                result.classes().forEach((name, bytes) -> assertThat(bytes).as(name).isEqualTo(fixture.classes().get(name + ".class")));
            }
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void eachGeneratedOriginRunsSeparatelyAndProducesOnlyItsImmediateOutputs(Digest digest) throws Exception {
        var entries = new TreeMap<>(Stage2Support.compile(dir.resolve("chain-processor"), Map.of(
                "fixture/Step.java", "package fixture; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Step { int value(); }",
                "fixture/Chain.java", """
                package fixture;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("fixture.Step") public class Chain extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        for (var a : annotations) for (var e : round.getElementsAnnotatedWith(a)) {
                            int step = e.getAnnotation(Step.class).value();
                            String name = e.getSimpleName().toString();
                            for (String suffix : step == 0 ? java.util.List.of("One", "Two") : java.util.List.of("Leaf")) {
                                String child = name + suffix;
                                String annotation = suffix.equals("One") ? "@fixture.Step(1) " : "";
                                try (var out = processingEnv.getFiler().createSourceFile("p." + child, e).openWriter()) {
                                    out.write("package p; " + annotation + "public class " + child + " { public int n(){ return 7; } }");
                                } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                            }
                        }
                        return false;
                    }
                }
                """), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Chain\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Chain,isolating\n"));
        var path = List.of(Stage2Support.pack(dir.resolve("chain.jar"), entries));
        var fixture = fixture(digest, path, path, "p/Input.java",
                "package p; @fixture.Step(0) public class Input { public int n(){ return new InputOne().n()+new InputTwo().n(); } }");
        assertThat(fixture.generated()).hasSize(3);
        try (var pool = new Pool(fixture.configuration(), 1)) {
            var first = run(digest, fixture, path, pool, fixture.input());
            assertThat(first.diagnostics()).isEmpty(); assertThat(first.faults()).isEmpty();
            sameOutputs(first.generated(), fixture.generated().stream().filter(o -> o.origins().equals(List.of(fixture.input().toUri()))).toList());
            assertThat(first.classes()).containsOnlyKeys("p/Input");
            assertThat(first.classes().get("p/Input")).isEqualTo(fixture.classes().get("p/Input.class"));
            for (String name : List.of("p/InputOne.java", "p/InputTwo.java")) {
                var output = fixture.generated().stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow();
                var result = run(digest, fixture, path, pool, Path.of(output.uri()));
                assertThat(result.diagnostics()).isEmpty(); assertThat(result.faults()).isEmpty();
                var expected = fixture.generated().stream().filter(o -> o.origins().equals(List.of(output.uri()))).toList();
                sameOutputs(result.generated(), expected);
                String type = name.replace(".java", "");
                assertThat(result.classes()).containsOnlyKeys(type);
                assertThat(result.classes().get(type)).isEqualTo(fixture.classes().get(type + ".class"));
            }
            var again = run(digest, fixture, path, pool, fixture.input());
            sameOutputs(again.generated(), first.generated()); assertThat(again.faults()).isEmpty();
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void unsupportedSyntaxReadsStillRunAndProcessorErrorsDoNotPoisonTheNextBorrow(Digest digest) throws Exception {
        var entries = new TreeMap<>(Stage2Support.compile(dir.resolve("syntax-processor"), Map.of("fixture/Syntax.java", """
                package fixture;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*") public class Syntax extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (round.processingOver()) return false;
                        try {
                            var env = processingEnv;
                            if (!env.getClass().getName().equals("com.sun.tools.javac.processing.JavacProcessingEnvironment")) {
                                var field = env.getClass().getDeclaredField("delegate"); field.setAccessible(true);
                                env = (ProcessingEnvironment) field.get(env);
                            }
                            var trees = com.sun.source.util.Trees.instance(env);
                            for (var root : round.getRootElements()) {
                                var text = trees.getTree(root).toString();
                                if (text.contains("return -1")) processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, "negative source body", root);
                                // The explicit input may never be overwritten, including when its stub is also on the classpath.
                                try { processingEnv.getFiler().createSourceFile(((TypeElement) root).getQualifiedName(), root); throw new AssertionError("Input overwritten"); }
                                catch (FilerException expected) { }
                            }
                        } catch (Exception e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Syntax\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Syntax,isolating\n"));
        var path = List.of(Stage2Support.pack(dir.resolve("syntax.jar"), entries));
        String good = "package p; public class Input { public int n(){ return 1; } }";
        var fixture = fixture(digest, path, List.of(), "p/Input.java", good);
        assertThat(fixture.capabilities().get("fixture.Syntax").reusable()).isFalse();
        try (var pool = new Pool(fixture.configuration(), 1)) {
            Files.writeString(fixture.input(), good.replace("return 1", "return -1"));
            var failed = run(digest, fixture, path, pool, fixture.input());
            assertThat(failed.classes()).isEmpty();
            assertThat(failed.diagnostics()).containsExactly("ERROR:negative source body");
            assertThat(failed.capabilities().get("fixture.Syntax").reusable()).isFalse();
            Files.writeString(fixture.input(), good);
            var next = run(digest, fixture, path, pool, fixture.input());
            assertThat(next.diagnostics()).isEmpty(); assertThat(next.generated()).isEmpty();
            assertThat(next.classes()).containsOnlyKeys("p/Input");
            assertThat(next.classes().get("p/Input")).isEqualTo(fixture.classes().get("p/Input.class"));
            assertThat(next.faults()).anyMatch(f -> f.contains("fixture.Syntax") && f.contains("Trees.getTree"));
            assertThat(next.capabilities().get("fixture.Syntax").reusable()).isFalse();
            assertThat(next.observations().reusable()).isFalse();
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void annotationAnswersMoveEvenWhenResolutionAndGeneratedBytesDoNot(Digest digest) throws Exception {
        var entries = new TreeMap<>(Stage2Support.compile(dir.resolve("reader"), Map.of("fixture/Reader.java", """
                package fixture;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*") public class Reader extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (round.processingOver()) return false;
                        for (var root : round.getRootElements()) {
                            if (!root.getSimpleName().contentEquals("Input")) continue;
                            var target = processingEnv.getElementUtils().getTypeElement("q.Meta");
                            for (var annotation : target.getAnnotationMirrors())
                                for (var value : annotation.getElementValues().entrySet())
                                    if (value.getKey().getSimpleName().contentEquals("value"))
                                        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE, "read=" + value.getValue().getValue());
                            try (var out = processingEnv.getFiler().createSourceFile("p.Made", root).openWriter()) {
                                out.write("package p; public class Made {}");
                            } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        }
                        return true;
                    }
                }
                """), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Reader\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Reader,isolating\n"));
        var processors = List.of(Stage2Support.pack(dir.resolve("reader.jar"), entries));
        var jars = new ArrayList<Path>(); var leaves = new ArrayList<dev.jvmd.index.layer.machine.MachineLeaf>();
        for (int i = 0; i < 3; i++) {
            var classes = Stage2Support.compile(dir.resolve("metadata-" + i), Map.of(
                    "q/Mark.java", "package q; public @interface Mark { String value(); String unread(); }",
                    "q/Meta.java", "package q; @Mark(value=\"" + (i == 2 ? "changed" : "original") + "\", unread=\"" + i + "\") public class Meta {}"),
                    List.of(), List.of());
            jars.add(Stage2Support.pack(dir.resolve("metadata-" + i + ".jar"), classes));
            var store = new InMemoryLocalStore(); var builder = new LeafBuilder(new ContentTree(digest), store);
            var facts = new ArrayList<dev.jvmd.index.layer.machine.Fact>();
            for (var file : classes.entrySet()) {
                var extracted = dev.jvmd.index.layer.machine.ClassFacts.of(digest, file.getValue(), file.getKey().replace(".class", ""));
                facts.addAll(extracted.facts()); builder.edges(extracted.edges());
            }
            facts.stream().sorted((a,b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(builder::add);
            builder.seal(); leaves.add(builder.build());
        }
        assertThat(leaves.get(1).k()).isEqualTo(leaves.getFirst().k());
        assertThat(leaves.get(2).k()).isEqualTo(leaves.getFirst().k());
        var fixture = fixture(digest, processors, List.of(jars.getFirst()), "p/Input.java", "package p; public class Input { Made value; }");
        var runs = new ArrayList<Run>();
        for (int i = 0; i < jars.size(); i++) {
            var c = fixture.configuration();
            var configuration = new Pool.Configuration(c.key(), c.ownStubs(), List.of(jars.get(i)), c.charset(), c.options(), c.ownTypes());
            // A freshly opened environment observes the current physical inputs; this does not test LIVE pool rebinding.
            try (var pool = new Pool(configuration, 1)) {
                var result = run(digest, fixture, processors, pool, fixture.input()); runs.add(result);
                assertThat(result.faults()).isEmpty(); assertThat(result.observations().reusable()).isTrue();
                assertThat(result.diagnostics()).containsExactly("NOTE:read=" + (i == 2 ? "changed" : "original"));
                sameOutputs(result.generated(), fixture.generated());
                assertThat(result.classes().get("p/Input")).isEqualTo(fixture.classes().get("p/Input.class"));
                assertThat(run(digest, fixture, processors, pool, fixture.input()).observations()).isEqualTo(result.observations());
            }
        }
        assertThat(runs.get(1).observations()).isEqualTo(runs.getFirst().observations());
        assertThat(runs.get(2).observations()).isNotEqualTo(runs.getFirst().observations());
        var zero = Identity.zero(digest.width()); var context = new ProcessorRecords.Context(fixture.processorPath(), zero, List.of());
        var route = new dev.jvmd.index.layer.local.Route(List.of(), zero, zero, zero, zero);
        var proof = new dev.jvmd.index.layer.local.Proof(new dev.jvmd.index.layer.local.Proof.Header(zero, zero, zero, zero,
                leaves.getFirst().r(), context), List.of(), List.of(), runs.getFirst().observations());
        java.util.function.Function<byte[], byte[]> noReads = key -> { throw new AssertionError("Processor gate opened resolution storage"); };
        var capabilityKey = dev.jvmd.index.layer.local.LocalStore.processorKey(fixture.processorPath(), "fixture.Reader");
        var admissionReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<byte[], byte[]> admitted = key -> {
            assertThat(key).isEqualTo(capabilityKey); admissionReads.incrementAndGet();
            return fixture.capabilities().get("fixture.Reader").encode();
        };
        assertThat(proof.valid(new ContentTree(digest), leaves.getFirst(), route, context, runs.get(1).observations(), admitted)).isTrue();
        assertThat(admissionReads.get()).isEqualTo(1);
        assertThat(proof.valid(new ContentTree(digest), leaves.getFirst(), route, context, runs.get(2).observations(), noReads)).isFalse();
        assertThat(proof.valid(new ContentTree(digest), leaves.getFirst(), route, context, runs.get(1).observations(), key -> {
            assertThat(key).isEqualTo(capabilityKey);
            return new ProcessorRecords.Capability(ProcessorRecords.ISOLATING, ProcessorRecords.VIOLATED).encode();
        })).isFalse();
        var kappa = digest.hash(Files.readAllBytes(fixture.input()));
        assertThat(proof.aci(digest, "Input.java", kappa, zero)).isEqualTo(proof.withProcessorBody(runs.get(1).observations()).aci(digest, "Input.java", kappa, zero))
                .isNotEqualTo(proof.withProcessorBody(runs.get(2).observations()).aci(digest, "Input.java", kappa, zero));
    }

    @ParameterizedTest @MethodSource("digests")
    void aggregatesAreExcludedBeforeStaticInitializationAndMismatchedInputsCannotRun(Digest digest) throws Exception {
        var classes = new TreeMap<>(Stage2Support.compile(dir.resolve("processor"), Map.of("fixture/Aggregate.java", """
                package fixture;
                @javax.annotation.processing.SupportedAnnotationTypes("*")
                public class Aggregate extends javax.annotation.processing.AbstractProcessor {
                    static { if (System.nanoTime() != 0) throw new AssertionError("Aggregate was loaded by a body task"); }
                    public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> a, javax.annotation.processing.RoundEnvironment r) {
                        throw new AssertionError("Aggregate was run by a body task");
                    }
                }
                """), List.of(), List.of()));
        classes.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Aggregate\n"));
        // Dynamic processors must be selected using the result observed by Stage 2, before init can execute again.
        classes.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Aggregate,dynamic\n"));
        var path = List.of(Stage2Support.pack(dir.resolve("aggregate.jar"), classes));
        var pathHash = digest.hash(digest.hash(Files.readAllBytes(path.getFirst())).view());
        var optionsHash = digest.hash(new byte[]{12});
        var scope = new ProcessorRecords.Scope(pathHash, optionsHash, List.of(new ProcessorRecords.Invocation("fixture.Aggregate",
                new ProcessorRecords.Capability(ProcessorRecords.AGGREGATING, ProcessorRecords.GENERATOR))));
        try (var host = ProcessorHost.bodies(path, digest, dir.resolve("capture"), StandardCharsets.UTF_8, scope, optionsHash)) {
            assertThat(host.processors()).isEmpty();
        }
        assertThatThrownBy(() -> ProcessorHost.bodies(path, digest, dir.resolve("capture"), StandardCharsets.UTF_8, scope, pathHash))
                .hasMessage("Processor options differ from Stage 2");
        var wrongBytes = new ProcessorRecords.Scope(optionsHash, optionsHash, scope.processors());
        assertThatThrownBy(() -> ProcessorHost.bodies(path, digest, dir.resolve("capture"), StandardCharsets.UTF_8, wrongBytes, optionsHash))
                .hasRootCauseMessage("Processor bytes differ from Stage 2");
        assertThat(dir.resolve("capture")).doesNotExist();
    }
}
