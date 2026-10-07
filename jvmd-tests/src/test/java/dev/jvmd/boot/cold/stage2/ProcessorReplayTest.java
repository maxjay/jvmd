package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("phase-3")
class ProcessorReplayTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void roundRoutingUsesCapturedAnnotationNamesAndSourceProvenance(Digest digest) throws Exception {
        var work = dir.resolve("processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Entity")
                public class RoundOnly extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (round.processingOver()) return false;
                        round.getRootElements();
                        for (var annotation : annotations) for (var input : round.getElementsAnnotatedWith(annotation)) {
                            try (var out = processingEnv.getFiler().createSourceFile("p.Result", input).openWriter()) {
                                out.write("package p; class Result {}");
                            } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                        }
                        return true;
                    }
                }
                """;
        var jar = Stage2Support.pack(work.resolve("processor.jar"), Stage2Support.compile(work,
                Map.of("fixture/RoundOnly.java", source), List.of(), List.of()));
        var file = dir.resolve("Input.java");
        Files.writeString(file, "package p; @interface Entity {} @Entity class Input {}");
        var blocked = new AtomicBoolean();
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Input.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var annotation = compiled.elements.getTypeElement("p.Entity");
            var guarded = (TypeElement) Proxy.newProxyInstance(TypeElement.class.getClassLoader(), new Class<?>[] {TypeElement.class}, (p, m, a) -> {
                if (blocked.get() && m.getDeclaringClass() != Object.class)
                    throw new AssertionError("Replay queried the native annotation: " + m.getName());
                try { return m.invoke(annotation, a); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
            var input = compiled.elements.getTypeElement("p.Input");
            var origins = new ProcessorReplay.Origins();
            var round = new RoundEnvironment() {
                @Override public boolean processingOver() { return false; }
                @Override public boolean errorRaised() { return false; }
                @Override public Set<? extends Element> getRootElements() { return Set.of(input); }
                @Override public Set<? extends Element> getElementsAnnotatedWith(TypeElement type) { return Set.of(input); }
                @Override public Set<? extends Element> getElementsAnnotatedWith(Class<? extends java.lang.annotation.Annotation> type) { return Set.of(input); }
            };
            var snapshot = new ProcessorReplay.Round(0, Set.of(guarded), round, element -> {
                if (blocked.get()) throw new AssertionError("Replay queried live source provenance");
                return origins.capture(element, file.toUri());
            });
            var reads = new ProcessorReads(compiled.elements, compiled.types, ignored -> {}, reason -> { throw new AssertionError(reason); });
            reads.phase(0);
            reads.answer("round.0.annotations", null, Set.of(guarded));
            reads.answer("round.0.getRootElements", null, Set.of(input));
            var expected = new ProcessorHost.Output("fixture.RoundOnly", JavaFileObject.Kind.SOURCE, "p/Result.java",
                    URI.create("memory:///p/Result.java"), List.of(file.toUri()), "package p; class Result {}".getBytes(StandardCharsets.UTF_8));
            blocked.set(true);
            var proof = ProcessorReplay.run(new java.net.URL[] {jar.toUri().toURL()}, "fixture.RoundOnly",
                    new ProcessorReplay.Environment(Map.of(), SourceVersion.latestSupported(), false), List.of("init", "annotations", "version"),
                    List.of(snapshot), reads, file.toUri(), origins::get, StandardCharsets.UTF_8, List.of(expected));
            assertThat(proof.proof()).isNotEmpty();
            assertThatThrownBy(() -> origins.get(annotation)).isInstanceOf(ProcessorReads.ReplayUnavailable.class)
                    .hasMessageContaining("unobserved source provenance");
            assertThatThrownBy(() -> snapshot.name(annotation)).isInstanceOf(ProcessorReads.ReplayUnavailable.class)
                    .hasMessageContaining("unobserved round annotation handle");
        }
    }
}
