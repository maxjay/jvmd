package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class ProcessorRoundsTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    @ParameterizedTest @MethodSource("digests")
    void generatedOriginsKeepTheirOwnDerivationsAcrossRounds(Digest digest) throws Exception {
        var processor = processor("Chain", """
                for (var annotation : annotations) for (var input : round.getElementsAnnotatedWith(annotation)) {
                    int phase = ((Number) input.getAnnotationMirrors().getFirst().getElementValues().values().iterator().next().getValue()).intValue();
                    String name = input.getSimpleName() + (phase == 0 ? "Step" : "Result");
                    try (var out = processingEnv.getFiler().createSourceFile("p." + name, input).openWriter()) {
                        out.write("package p; " + (phase == 0 ? "@Generate(1) " : "") + "public class " + name + " {}");
                    } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                }
                return true;
                """);
        String firstPath = "m/src/main/java/p/First.java";
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Generate.java", "package p; @interface Generate { int value(); }",
                firstPath, "package p; @Generate(0) class First { int body() { return 1; } }",
                "m/src/main/java/p/Second.java", "package p; @Generate(0) class Second {}"));
        var model = model(processor);
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        assertThat(driver.run(first, model).faults()).isEmpty();
        var original = generated(first, digest);
        assertThat(original).containsOnlyKeys("p/FirstStep", "p/FirstStepResult", "p/SecondStep", "p/SecondStepResult");
        assertThat(original.values()).allSatisfy(row -> assertThat(row.genId()).isNotNull());
        assertThat(original.get("p/FirstStep").originPath()).isEqualTo(firstPath);
        assertThat(original.get("p/FirstStepResult").originPath()).isEqualTo(".jvmd/generated/bQ/0/p/FirstStep.java");
        assertThat(original.values().stream().map(FileRow::genId).distinct()).hasSize(4);

        var repeat = first.copy();
        assertThat(driver.run(repeat, model).faults()).isEmpty();
        for (var row : original.values()) assertThat(repeat.writes(LocalStore.generatedKey(row.genId()))).isZero();

        Files.writeString(dir.resolve(firstPath), "package p; @Generate(0) class First { int body() { return 2; } }");
        var edited = repeat.copy();
        assertThat(driver.run(edited, model).faults()).isEmpty();
        var changed = generated(edited, digest);
        assertThat(changed.get("p/FirstStep").genId()).isNotEqualTo(original.get("p/FirstStep").genId());
        assertThat(changed.get("p/FirstStep").kappa()).isEqualTo(original.get("p/FirstStep").kappa());
        for (var type : List.of("p/FirstStepResult", "p/SecondStep", "p/SecondStepResult")) {
            assertThat(changed.get(type).genId()).as(type).isEqualTo(original.get(type).genId());
            assertThat(edited.writes(LocalStore.generatedKey(original.get(type).genId()))).isZero();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void replayOnlyAssertionRejectsReuseAndRetainsNativeGeneratedRows(Digest digest) throws Exception {
        var processor = processor("RequiresBatch", """
                if (round.processingOver()) return false;
                if (round.getRootElements().size() < 2) throw new AssertionError("requires other source roots");
                for (var annotation : annotations) for (var input : round.getElementsAnnotatedWith(annotation)) {
                    String name = input.getSimpleName() + "Result";
                    try (var out = processingEnv.getFiler().createSourceFile("p." + name, input).openWriter()) {
                        out.write("package p; public class " + name + " {}");
                    } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                }
                return true;
                """);
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Generate.java", "package p; @interface Generate { int value(); }",
                "m/src/main/java/p/First.java", "package p; @Generate(0) class First {}",
                "m/src/main/java/p/Second.java", "package p; @Generate(0) class Second {}"));
        var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of).run(store, model(processor));
        assertThat(result.faults()).anyMatch(f -> f.contains("fixture.RequiresBatch") && f.contains("unsupported for reuse")
                && f.contains("requires other source roots"));
        assertThat(generated(store, digest)).containsOnlyKeys("p/FirstResult", "p/SecondResult")
                .allSatisfy((name, row) -> assertThat(row.genId()).isNull());
        assertThat(store.withPrefix("GEN")).isEmpty();
    }

    private Map<String, FileRow> generated(InMemoryLocalStore store, Digest digest) {
        var rows = new TreeMap<String, FileRow>();
        store.withPrefix("F").values().stream().map(bytes -> FileRow.decode("", bytes, digest.width())).filter(FileRow::generated)
                .forEach(row -> rows.put(row.typeKeys().getFirst(), row));
        return rows;
    }

    private Path processor(String name, String body) throws Exception {
        var work = dir.resolve(name);
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Generate")
                public class %s extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        %s
                    }
                }
                """.formatted(name, body);
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/" + name + ".java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture." + name + "\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture." + name + ",isolating\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }

    private ProjectModel model(Path processor) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var doc = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Stage2Support.model(dir, new Stage2Support.Mod("m", "g:m:1", List.of())));
        var processing = ((com.fasterxml.jackson.databind.node.ObjectNode) doc.withArray("modules").get(0)).putObject("processing");
        processing.putArray("path").addObject().put("coordinate", "g:processor:1").put("location", processor.toString());
        return ProjectModel.parse(json.writeValueAsBytes(doc));
    }
}
