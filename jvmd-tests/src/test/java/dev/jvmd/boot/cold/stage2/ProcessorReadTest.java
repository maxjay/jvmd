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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class ProcessorReadTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    @ParameterizedTest @MethodSource("digests")
    void queriesRecordResolvedTypesInheritedDeclarationsAndMissingTypes(Digest digest) throws Exception {
        var processor = processor();
        var api = api(1);
        var file = dir.resolve("src/p/Input.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package p; public class Input {}");
        try (var host = new ProcessorHost(List.of(processor), List.of(), digest, dir.resolve("generated"));
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("src/p/Input.java", file)), List.of(api),
                     Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
            assertThat(host.faults()).isEmpty();
            var reads = host.modelReads().get("fixture.Lookup");
            assertThat(reads).anyMatch(r -> r.operation().equals("Elements.getTypeElement") && r.types().contains("ext/Api"));
            assertThat(reads).anyMatch(r -> r.operation().equals("Elements.getAllMembers") && r.types().containsAll(List.of("ext/Api", "ext/Base", "java/lang/Object")));
            assertThat(reads).anyMatch(r -> r.operation().equals("Elements.getTypeElement") && r.missingTypes().equals(List.of("p.Optional")));
            assertThat(ProcessorReads.absentCandidates("p.Outer.Inner")).containsExactly("p$Outer$Inner", "p/Outer$Inner", "p/Outer/Inner");
            assertThat(new String(host.outputs().getFirst().bytes(), java.nio.charset.StandardCharsets.UTF_8)).contains("VALUE = 1;");
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void generationProofIncludesModelQueriesThatDoNotAppearInSourceHeaders(Digest digest) throws Exception {
        var processor = processor();
        var api = dir.resolve("api.jar");
        Files.copy(api(1), api);
        String input = "m/src/main/java/p/Input.java";
        Stage2Support.write(dir, Map.of(input, "package p; public class Input {}"));
        var model = model(processor, api);
        var project = Stage2.projectKey(digest, model);
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        assertThat(driver.run(first, model).faults()).isEmpty();
        var proof = FileRow.decode(input, first.get(LocalStore.fileKey(project, input)), digest.width());
        assertThat(proof.headerProof()).extracting(FileRow.Proof::typeKey).contains("ext/Api", "ext/Base");
        assertThat(proof.absences()).extracting(dev.jvmd.index.layer.local.HeaderProof.Absence::type).contains("p/Optional");
        var firstGenerated = generated(first, digest);

        Files.copy(api(2), api, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var second = first.copy();
        assertThat(driver.run(second, model).faults()).isEmpty();
        var secondGenerated = generated(second, digest);
        assertThat(secondGenerated.genId()).isNotEqualTo(firstGenerated.genId());
        assertThat(new String(second.get(LocalStore.generatedSourceKey(secondGenerated.kappa())), java.nio.charset.StandardCharsets.UTF_8)).contains("VALUE = 2;");

        Stage2Support.write(dir, Map.of("m/src/main/java/p/Optional.java", "package p; public class Optional {}"));
        var third = second.copy();
        assertThat(driver.run(third, model).faults()).isEmpty();
        var thirdGenerated = generated(third, digest);
        assertThat(thirdGenerated.genId()).isNotEqualTo(secondGenerated.genId());
        assertThat(new String(third.get(LocalStore.generatedSourceKey(thirdGenerated.kappa())), java.nio.charset.StandardCharsets.UTF_8)).contains("VALUE = 12;");
    }

    private FileRow generated(InMemoryLocalStore store, Digest digest) {
        return store.withPrefix("F").values().stream().map(bytes -> FileRow.decode("", bytes, digest.width())).filter(FileRow::generated).findFirst().orElseThrow();
    }

    private ProjectModel model(Path processor, Path api) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var doc = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Stage2Support.model(dir,
                new Stage2Support.Mod("m", "g:m:1", List.of(Stage2Support.Dep.jar("g:api:1", api.toString())))));
        var processing = ((com.fasterxml.jackson.databind.node.ObjectNode) doc.withArray("modules").get(0)).putObject("processing");
        processing.putArray("path").addObject().put("coordinate", "g:processor:1").put("location", processor.toString());
        return ProjectModel.parse(json.writeValueAsBytes(doc));
    }

    private Path api(int value) throws Exception {
        var work = dir.resolve("api-" + value);
        return Stage2Support.pack(work.resolve("api.jar"), Stage2Support.compile(work, Map.of(
                "ext/Api.java", "package ext; public class Api extends Base {}",
                "ext/Base.java", "package ext; public class Base { public static final int VALUE = " + value + "; }"), List.of(), List.of()));
    }

    private Path processor() throws Exception {
        var work = dir.resolve("processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*")
                public class Lookup extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        var input = round.getRootElements().stream().filter(e -> e.getSimpleName().contentEquals("Input")).findFirst().orElseThrow();
                        var elements = processingEnv.getElementUtils();
                        var api = elements.getTypeElement("ext.Api");
                        var field = (VariableElement) elements.getAllMembers(api).stream().filter(e -> e.getSimpleName().contentEquals("VALUE")).findFirst().orElseThrow();
                        int value = ((Number)field.getConstantValue()).intValue();
                        if (elements.getTypeElement("p.Optional") != null) value += 10;
                        try (var out = processingEnv.getFiler().createSourceFile("p.Result", input).openWriter()) {
                            out.write("package p; public class Result { public static final int VALUE = " + value + "; }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Lookup.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Lookup\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Lookup,isolating\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }
}
