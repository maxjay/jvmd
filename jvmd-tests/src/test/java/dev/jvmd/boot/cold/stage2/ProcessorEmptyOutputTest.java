package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
class ProcessorEmptyOutputTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }
    private static final String SOURCE = "package p; @interface Entity {} @Entity class Input { static final boolean EMIT = false; int body() { return 1; } }";

    @ParameterizedTest @MethodSource("digests")
    void firstEmptyInvocationAndEmptyNonemptyEmptyTransitionsHaveExactManifests(Digest digest) throws Exception {
        for (var declaration : List.of("isolating", "aggregating")) {
            var project = Files.createDirectories(dir.resolve(declaration));
            var processor = processor(declaration);
            var file = project.resolve("m/src/main/java/p/Input.java");
            Stage2Support.write(project, Map.of("m/src/main/java/p/Input.java", SOURCE));
            var model = model(project, processor);
            var tree = new ContentTree(digest);
            var driver = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
            var empty = Stage2Support.jdkOnly(digest).copy();
            var first = driver.run(empty, model);
            assertThat(first.faults()).isEmpty();
            var firstManifest = manifests(empty, tree, first).getFirst();
            assertThat(firstManifest.root.count()).isZero();
            assertThat(publishedFiles(empty, tree, first, digest)).noneMatch(FileRow::generated);
            assertThat(empty.withPrefix("PROC").values()).allSatisfy(bytes -> {
                var capability = ProcessorRecords.Capability.decode(bytes);
                assertThat(capability.observed()).isEqualTo(ProcessorRecords.GENERATOR);
                assertThat(capability.reusable()).isTrue();
            });

            var repeat = empty.copy();
            assertThat(driver.run(repeat, model).faults()).isEmpty();
            assertThat(repeat.writes(LocalStore.generatedKey(firstManifest.id))).isZero();

            Files.writeString(file, SOURCE.replace("EMIT = false", "EMIT = true"));
            var emitting = repeat.copy();
            var emitted = driver.run(emitting, model);
            assertThat(emitted.faults()).isEmpty();
            var next = manifests(emitting, tree, emitted).getFirst();
            assertThat(next.id).isNotEqualTo(firstManifest.id);
            assertThat(next.root.count()).isEqualTo(1);
            var outputs = publishedFiles(emitting, tree, emitted, digest).stream().filter(FileRow::generated).toList();
            assertThat(outputs).hasSize(1);
            assertThat(outputs.getFirst().genId()).isEqualTo(next.id);
            var added = Diff.trees(digest, firstManifest.root, next.root, h -> emitting.get(MachineStore.nodeKey(h)));
            assertThat(added.removed()).isEmpty();
            assertThat(added.added()).hasSize(1);

            Files.writeString(file, SOURCE);
            var removed = emitting.copy();
            var last = driver.run(removed, model);
            assertThat(last.faults()).isEmpty();
            assertThat(manifests(removed, tree, last)).containsExactly(firstManifest);
            assertThat(removed.writes(LocalStore.generatedKey(firstManifest.id))).isZero();
            assertThat(publishedFiles(removed, tree, last, digest)).noneMatch(FileRow::generated);
            var deleted = Diff.trees(digest, next.root, firstManifest.root, h -> removed.get(MachineStore.nodeKey(h)));
            assertThat(deleted.removed()).hasSize(1);
            assertThat(deleted.added()).isEmpty();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void firstEmptyAggregateDomainDoesNotRequireAHistoricalCapabilityRecord(Digest digest) throws Exception {
        var processor = processor("aggregating");
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Input.java", SOURCE.replace("@Entity class Input", "class Input")));
        var model = model(dir, processor);
        var tree = new ContentTree(digest);
        var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of).run(store, model);
        assertThat(result.faults()).isEmpty();
        var domain = DefinerIndex.decodeRoot(store.get(LocalStore.processorDomainKey(Stage2.projectKey(digest, model), "m", 0, "fixture.Conditional")), digest.width());
        assertThat(domain.count()).isZero();
        assertThat(manifests(store, tree, result).getFirst().root.count()).isZero();
    }

    @ParameterizedTest @MethodSource("digests")
    void emptyAndEmittingOriginsRetainSeparateDerivations(Digest digest) throws Exception {
        var processor = processor("isolating");
        String firstPath = "m/src/main/java/p/First.java";
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Entity.java", "package p; @interface Entity {}",
                firstPath, "package p; @Entity class First { static final boolean EMIT = false; int body() { return 1; } }",
                "m/src/main/java/p/Second.java", "package p; @Entity class Second { static final boolean EMIT = true; }"));
        var model = model(dir, processor);
        var tree = new ContentTree(digest);
        var driver = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var before = Stage2Support.jdkOnly(digest).copy();
        var result = driver.run(before, model);
        assertThat(result.faults()).isEmpty();
        var original = allManifests(before, tree, result);
        assertThat(original).hasSize(2);
        assertThat(original).extracting(m -> m.root.count()).containsExactlyInAnyOrder(0, 1);
        var output = publishedFiles(before, tree, result, digest).stream().filter(FileRow::generated).findFirst().orElseThrow();
        var emitting = original.stream().filter(m -> m.root.count() == 1).findFirst().orElseThrow();
        assertThat(output.genId()).isEqualTo(emitting.id);

        Files.writeString(dir.resolve(firstPath), Files.readString(dir.resolve(firstPath)).replace("return 1;", "return 2;"));
        var after = before.copy();
        var changed = driver.run(after, model);
        assertThat(changed.faults()).isEmpty();
        var changedManifests = allManifests(after, tree, changed);
        assertThat(changedManifests).hasSize(2).contains(emitting);
        assertThat(changedManifests).doesNotContain(original.stream().filter(m -> m.root.count() == 0).findFirst().orElseThrow());
        assertThat(after.writes(LocalStore.generatedKey(emitting.id))).isZero();
    }

    @ParameterizedTest @MethodSource("digests")
    void noOutputDoesNotHideAnUnverifiedDeclarationOverlay(Digest digest) throws Exception {
        var work = dir.resolve("mutator");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Entity")
                public class Mutator extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        for (var annotation : annotations) for (var input : round.getElementsAnnotatedWith(annotation))
                            for (var member : input.getEnclosedElements()) if (member.getSimpleName().contentEquals("VALUE")) {
                                try {
                                    member.getClass().getMethod("setData", Object.class).invoke(member, Integer.valueOf(2));
                                    if (!Integer.valueOf(2).equals(((VariableElement)member).getConstantValue())) throw new AssertionError("mutation failed");
                                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE, "observed changed constant");
                                }
                                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                            }
                        return true;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Mutator.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Mutator\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Mutator,isolating\n"));
        var processor = Stage2Support.pack(work.resolve("processor.jar"), entries);
        var file = dir.resolve("src/p/Input.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package p; @interface Entity {} @Entity class Input { static final int VALUE = 1; }");
        try (var host = new ProcessorHost(List.of(processor), List.of(), digest, dir.resolve("generated"));
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("src/p/Input.java", file)), List.of(),
                     Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest, host)) {
            assertThat(host.outputs()).isEmpty();
            assertThat(host.faults()).anyMatch(f -> f.contains("fixture.Mutator") && f.contains("source declaration mutation"));
            assertThat(host.capabilities().get("fixture.Mutator").reusable()).isFalse();
            assertThat(host.diagnostics().messages()).anyMatch(m -> m.text().equals("observed changed constant"));
            assertThat(compiled.units.getFirst().declared).extracting(t -> t.getQualifiedName().toString()).contains("p.Input");
        }
    }

    private record Manifest(Identity id, Root root) { }
    private List<Manifest> manifests(InMemoryLocalStore store, ContentTree tree, Stage2.Result result) {
        var manifests = allManifests(store, tree, result);
        assertThat(manifests).hasSize(1);
        return manifests;
    }
    private List<Manifest> allManifests(InMemoryLocalStore store, ContentTree tree, Stage2.Result result) {
        var manifests = new ArrayList<Manifest>();
        var prefix = "GEN|".getBytes(StandardCharsets.US_ASCII);
        tree.forEach(result.root().hash(), h -> store.get(MachineStore.nodeKey(h)), e -> {
            if (e.key().length > prefix.length && Arrays.equals(prefix, Arrays.copyOf(e.key(), prefix.length)))
                manifests.add(new Manifest(Identity.of(Arrays.copyOfRange(e.key(), prefix.length, e.key().length)), DefinerIndex.decodeRoot(store.get(e.key()), e.key().length - prefix.length)));
        });
        return List.copyOf(manifests);
    }
    private List<FileRow> publishedFiles(InMemoryLocalStore store, ContentTree tree, Stage2.Result result, Digest digest) {
        var rows = new ArrayList<FileRow>();
        tree.forEach(result.root().hash(), h -> store.get(MachineStore.nodeKey(h)), e -> {
            if (e.key()[0] == 'F' && e.key()[1] == '|') rows.add(FileRow.decode("", store.get(e.key()), digest.width()));
        });
        return List.copyOf(rows);
    }
    private ProjectModel model(Path root, Path processor) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Stage2Support.model(root, new Stage2Support.Mod("m", "g:m:1", List.of())));
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.withArray("modules").get(0)).putObject("processing")
                .putArray("path").addObject().put("coordinate", "g:processor:1").put("location", processor.toString());
        return ProjectModel.parse(json.writeValueAsBytes(document));
    }
    private Path processor(String declaration) throws Exception {
        var work = dir.resolve("processor-" + declaration);
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Entity")
                public class Conditional extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || annotations.isEmpty()) return false;
                        done = true;
                        for (var input : round.getElementsAnnotatedWith(annotations.iterator().next())) {
                            boolean emit = input.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("EMIT"))
                                .map(e -> ((VariableElement)e).getConstantValue()).anyMatch(Boolean.TRUE::equals);
                            if (!emit) continue;
                            String generated = "Generated" + input.getSimpleName();
                            try (var out = processingEnv.getFiler().createSourceFile("p." + generated, ORIGINS).openWriter()) {
                                out.write("package p; public class " + generated + " {}");
                            } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        }
                        return true;
                    }
                }
                """.replace("ORIGINS", declaration.equals("isolating") ? "input" : "new Element[0]");
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Conditional.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Conditional\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Conditional," + declaration + "\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }
}
