package dev.jvmd.boot.cold.stage2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
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

/** End-to-end Appendix F records; the unit-level processor tests are not a substitute for this driver gate. */
@Tag("phase-3")
class ProcessorStage2Test {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    @ParameterizedTest @MethodSource("digests")
    void changingProcessorBodiesWithTheSameApiChangesHeaderProcessingInputs(Digest digest) throws Exception {
        var firstJar = versionedProcessor(1);
        var secondJar = versionedProcessor(2);
        var selected = dir.resolve("selected-processor.jar");
        Files.copy(firstJar, selected);
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Input.java", "package p; public class Input {}"));
        var model = model(selected, selected);
        var project = Stage2.projectKey(digest, model);
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        var a = driver.run(first, model);
        assertThat(a.faults()).isEmpty();
        var oldRow = publishedGenerated(first, digest, a).getFirst();
        var routeKey = LocalStore.routeKey(project, "m", 0);
        var before = dev.jvmd.index.layer.local.Route.decode(first.get(routeKey), digest.width());

        Files.copy(secondJar, selected, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var second = first.copy();
        var b = driver.run(second, model);
        assertThat(b.faults()).isEmpty();
        var after = dev.jvmd.index.layer.local.Route.decode(second.get(routeKey), digest.width());
        assertThat(after.routeHash()).as("processor API is unchanged").isEqualTo(before.routeHash());
        var newRow = publishedGenerated(second, digest, b).getFirst();
        assertThat(newRow.processor().processorPathHash()).isNotEqualTo(oldRow.processor().processorPathHash());
        assertThat(newRow.genId()).isNotEqualTo(oldRow.genId());
        assertThat(new String(second.get(LocalStore.generatedSourceKey(newRow.kappa())), java.nio.charset.StandardCharsets.UTF_8)).contains("return 2;");
        assertThat(new String(first.get(LocalStore.generatedSourceKey(oldRow.kappa())), java.nio.charset.StandardCharsets.UTF_8)).contains("return 1;");
        assertThat(b.leaves()).as("only executable bodies changed, including the generated body").isEqualTo(a.leaves());
    }

    private Path versionedProcessor(int value) throws Exception {
        return versionedProcessor(value, "isolating", false);
    }

    private Path versionedProcessor(int value, String declaration, boolean multipleOrigins) throws Exception {
        var work = dir.resolve("versioned-processor-" + value + "-" + declaration + "-" + multipleOrigins);
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*")
                public class Versioned extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        try (var out = processingEnv.getFiler().createSourceFile("p.RunValue", round.getRootElements().iterator().next()).openWriter()) {
                            out.write("package p; public class RunValue { public int value() { return NUMBER; } }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """.replace("NUMBER", Integer.toString(value));
        if (multipleOrigins) source = source.replace("round.getRootElements().iterator().next()", "round.getRootElements().toArray(Element[]::new)");
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Versioned.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Versioned\n"));
        if (!declaration.isEmpty()) entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Versioned," + declaration + "\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }

    @ParameterizedTest @MethodSource("digests")
    void undeclaredAndMultipleOriginProcessorsPublishFaultsAndNoReusableGen(Digest digest) throws Exception {
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Input.java", "package p; public class Input {}",
                "m/src/main/java/p/Second.java", "package p; public class Second {}"));
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        for (var declaration : List.of("", "isolating")) {
            var processor = versionedProcessor(1, declaration, true);
            var store = Stage2Support.jdkOnly(digest).copy();
            var result = driver.run(store, model(processor, processor));
            assertThat(result.faults()).anyMatch(f -> f.contains("fixture.Versioned") && f.contains("unsupported for reuse"));
            assertThat(publishedGenerated(store, digest, result)).hasSize(1).allSatisfy(row -> assertThat(row.genId()).isNull());
            assertThat(store.withPrefix("GEN")).isEmpty();
            assertThat(store.withPrefix("PROC").values()).allSatisfy(bytes ->
                    assertThat(dev.jvmd.index.layer.local.ProcessorRecords.Capability.decode(bytes).observed()).isEqualTo(2));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void lombokConfigurationMutationsChangeOnlyTheRowsBelowTheChangedDirectory(Digest digest) throws Exception {
        var lombok = jar(lombok.Getter.class);
        var paths = List.of("m/src/main/java/p/one/One.java", "m/src/main/java/p/one/deep/Deep.java", "m/src/main/java/p/two/Two.java");
        Stage2Support.write(dir, Map.of(
                paths.get(0), "package p.one; @lombok.Getter public class One { private String mValue; }",
                paths.get(1), "package p.one.deep; @lombok.Getter public class Deep { private String mValue; }",
                paths.get(2), "package p.two; @lombok.Getter public class Two { private String mValue; }",
                "lombok.config", "config.stopBubbling = true\n"));
        var model = model(lombok, lombok);
        var project = Stage2.projectKey(digest, model);
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var beforeStore = Stage2Support.jdkOnly(digest).copy();
        assertThat(driver.run(beforeStore, model).faults()).isEmpty();
        var original = beforeStore;
        for (var config : List.of("m/src/main/java/p/one/lombok.config", "m/src/main/java/p/one/deep/lombok.config")) {
            String parent = config.substring(0, config.lastIndexOf('/') + 1);
            for (int operation : List.of(0, 1, 2)) {
                if (operation == 2) Files.delete(dir.resolve(config));
                else Files.writeString(dir.resolve(config), "lombok.accessors.fluent = " + (operation == 0) + "\n");
                var afterStore = beforeStore.copy();
                assertThat(driver.run(afterStore, model).faults()).isEmpty();
                var beforeRoot = DefinerIndex.decodeRoot(beforeStore.get(LocalStore.resourcesKey(project)), digest.width());
                var afterRoot = DefinerIndex.decodeRoot(afterStore.get(LocalStore.resourcesKey(project)), digest.width());
                var delta = dev.jvmd.core.tree.Diff.trees(digest, beforeRoot, afterRoot, h -> afterStore.get(MachineStore.nodeKey(h)));
                var changed = Stream.concat(delta.added().stream(), delta.removed().stream()).map(e -> new dev.jvmd.core.tree.Codec.Reader(e.key()).zstr()).distinct().toList();
                assertThat(changed).containsExactly(config);
                for (var path : paths) {
                    var a = FileRow.decode(path, beforeStore.get(LocalStore.fileKey(project, path)), digest.width());
                    var b = FileRow.decode(path, afterStore.get(LocalStore.fileKey(project, path)), digest.width());
                    assertThat(b.processor().equals(a.processor())).as("configuration proof for %s, %s operation %s", path, config, operation).isEqualTo(!path.startsWith(parent));
                    if (operation != 2) assertThat(b.sum().equals(a.sum())).as("Lombok header for %s", path).isEqualTo(!path.startsWith(parent));
                    if (operation == 2) {
                        var baseline = FileRow.decode(path, original.get(LocalStore.fileKey(project, path)), digest.width());
                        assertThat(b.sum()).isEqualTo(baseline.sum());
                        assertThat(b.processor()).isEqualTo(baseline.processor());
                    }
                }
                beforeStore = afterStore;
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void nondeterministicOutputCannotOverwriteADerivationOrLeaveReusableRows(Digest digest) throws Exception {
        var work = dir.resolve("unstable-processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*")
                public class Unstable extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        try (var out = processingEnv.getFiler().createSourceFile("p.RandomValue", round.getRootElements().iterator().next()).openWriter()) {
                            out.write("package p; public class RandomValue { public static final long VALUE = " + System.nanoTime() + "L; }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Unstable.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Unstable\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Unstable,isolating\n"));
        var processor = Stage2Support.pack(work.resolve("processor.jar"), entries);
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Input.java", "package p; public class Input {}"));
        var model = model(processor, processor);
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        var firstResult = driver.run(first, model);
        assertThat(firstResult.faults()).isEmpty();
        var id = publishedGenerated(first, digest, firstResult).getFirst().genId();
        var rootBytes = first.get(LocalStore.generatedKey(id));
        var second = first.copy();
        var secondResult = driver.run(second, model);
        assertThat(secondResult.faults()).anyMatch(f -> f.contains("fixture.Unstable") && f.contains("different output sets"));
        assertThat(second.get(LocalStore.generatedKey(id))).isEqualTo(rootBytes);
        assertThat(second.writes(LocalStore.generatedKey(id))).isZero();
        assertThat(publishedGenerated(second, digest, secondResult)).allSatisfy(row -> assertThat(row.genId()).isNull());
        assertThat(second.withPrefix("PROC").values()).allSatisfy(bytes ->
                assertThat(dev.jvmd.index.layer.local.ProcessorRecords.Capability.decode(bytes).reusable()).isFalse());

        var third = second.copy();
        var thirdResult = driver.run(third, model);
        assertThat(thirdResult.faults()).anyMatch(f -> f.contains("fixture.Unstable") && f.contains("recorded capability violation"));
        assertThat(publishedGenerated(third, digest, thirdResult)).allSatisfy(row -> assertThat(row.genId()).isNull());
    }

    @ParameterizedTest @MethodSource("digests")
    void bodyReadingAggregateStillGeneratesButHasNoReusableDerivation(Digest digest) throws Exception {
        var processor = bodyReader();
        var file = dir.resolve("m/src/main/java/p/Input.java");
        Files.createDirectories(file.getParent());
        var model = model(processor, processor);
        var tree = new ContentTree(digest);
        var driver = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        for (int body : List.of(1, 2)) {
            Files.writeString(file, "package p; public class Input { int body() { return " + body + "; } }");
            var store = Stage2Support.jdkOnly(digest).copy();
            var result = driver.run(store, model);
            assertThat(result.faults()).anyMatch(f -> f.contains("fixture.BodyReader") && f.contains("unsupported for reuse") && f.contains("Trees"));
            var rows = publishedGenerated(store, digest, result);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().genId()).isNull();
            assertThat(store.withPrefix("GEN")).isEmpty();
            assertThat(store.withPrefix("PROC").values()).allSatisfy(bytes ->
                    assertThat(dev.jvmd.index.layer.local.ProcessorRecords.Capability.decode(bytes).reusable()).isFalse());
            var output = dir.resolve(".jvmd/generated/bQ/0/p/BodyResult.java");
            assertThat(Files.readString(output)).contains("VALUE = " + body);
        }
    }

    private Path bodyReader() throws Exception {
        var work = dir.resolve("body-processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                import com.sun.source.util.Trees;
                @SupportedAnnotationTypes("*")
                public class BodyReader extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        var input = (TypeElement) round.getRootElements().stream().filter(e -> e.getSimpleName().contentEquals("Input")).findFirst().orElseThrow();
                        try {
                            var field = processingEnv.getClass().getDeclaredField("delegate");
                            field.setAccessible(true);
                            var nativeEnvironment = (ProcessingEnvironment) field.get(processingEnv);
                            String body = Trees.instance(nativeEnvironment).getTree(input).toString();
                            try (var out = processingEnv.getFiler().createSourceFile("p.BodyResult").openWriter()) {
                                out.write("package p; public class BodyResult { public static final int VALUE = " + (body.contains("return 2;") ? 2 : 1) + "; }");
                            }
                        } catch (Exception e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/BodyReader.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.BodyReader\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.BodyReader,aggregating\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }

    @ParameterizedTest @MethodSource("digests")
    void multipleOutputsShareADerivationAndManifestDiffEqualsPublishedRowChanges(Digest digest) throws Exception {
        var processor = pairProcessor();
        var file = dir.resolve("m/src/main/java/p/Input.java");
        Files.createDirectories(file.getParent());
        String source = "package p; public class Input { public static final boolean ALT = false; }";
        Files.writeString(file, source);
        var model = model(processor, processor);
        var tree = new ContentTree(digest);
        var driver = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        var firstResult = driver.run(first, model);
        assertThat(firstResult.faults()).isEmpty();
        var beforeRows = publishedGenerated(first, digest, firstResult);
        assertThat(beforeRows).hasSize(3);
        assertThat(beforeRows.stream().map(FileRow::genId).distinct()).hasSize(1);
        var firstId = beforeRows.getFirst().genId();
        var before = DefinerIndex.decodeRoot(first.get(LocalStore.generatedKey(firstId)), digest.width());
        assertThat(before.count()).isEqualTo(3);

        var clean = Stage2Support.jdkOnly(digest).copy();
        var cleanResult = driver.run(clean, model);
        assertThat(cleanResult.faults()).isEmpty();
        var cleanRows = publishedGenerated(clean, digest, cleanResult);
        assertThat(cleanRows.stream().map(FileRow::genId).distinct()).containsExactly(firstId);
        assertThat(DefinerIndex.decodeRoot(clean.get(LocalStore.generatedKey(firstId)), digest.width()).hash()).isEqualTo(before.hash());
        for (var row : beforeRows)
            assertThat(clean.get(LocalStore.generatedSourceKey(row.kappa()))).isEqualTo(first.get(LocalStore.generatedSourceKey(row.kappa())));

        Files.writeString(file, source.replace("false", "true"));
        var second = first.copy();
        var secondResult = driver.run(second, model);
        assertThat(secondResult.faults()).isEmpty();
        var afterRows = publishedGenerated(second, digest, secondResult);
        assertThat(afterRows).hasSize(3);
        assertThat(afterRows.stream().map(FileRow::genId).distinct()).hasSize(1);
        var after = DefinerIndex.decodeRoot(second.get(LocalStore.generatedKey(afterRows.getFirst().genId())), digest.width());
        var diff = dev.jvmd.core.tree.Diff.trees(digest, before, after, h -> second.get(MachineStore.nodeKey(h)));
        var oldContents = beforeRows.stream().collect(java.util.stream.Collectors.toMap(r -> r.typeKeys().getFirst() + ".java", FileRow::kappa));
        var newContents = afterRows.stream().collect(java.util.stream.Collectors.toMap(r -> r.typeKeys().getFirst() + ".java", FileRow::kappa));
        var removed = new java.util.TreeSet<>(oldContents.keySet());
        removed.removeIf(path -> oldContents.get(path).equals(newContents.get(path)));
        var added = new java.util.TreeSet<>(newContents.keySet());
        added.removeIf(path -> newContents.get(path).equals(oldContents.get(path)));
        assertThat(diff.removed().stream().map(e -> Keys.generatedOutputPath(e.key()))).containsExactlyElementsOf(removed);
        assertThat(diff.added().stream().map(e -> Keys.generatedOutputPath(e.key()))).containsExactlyElementsOf(added);
        assertThat(removed).containsExactly("p/B.java", "p/C.java");
        assertThat(added).containsExactly("p/C.java", "p/D.java");
        var unchanged = beforeRows.stream().filter(r -> r.typeKeys().contains("p/A")).findFirst().orElseThrow();
        assertThat(second.writes(LocalStore.generatedSourceKey(unchanged.kappa()))).isZero();
    }

    private List<FileRow> publishedGenerated(InMemoryLocalStore store, Digest digest, Stage2.Result result) {
        var rows = new java.util.ArrayList<FileRow>();
        new ContentTree(digest).forEach(result.root().hash(), h -> store.get(MachineStore.nodeKey(h)), entry -> {
            if (entry.key()[0] == 'F' && entry.key()[1] == '|') {
                var row = FileRow.decode("", store.get(entry.key()), digest.width());
                if (row.generated()) rows.add(row);
            }
        });
        return List.copyOf(rows);
    }

    private Path pairProcessor() throws Exception {
        var work = dir.resolve("pair-processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*")
                public class Pair extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        var input = round.getRootElements().stream().filter(e -> e.getSimpleName().contentEquals("Input")).findFirst().orElseThrow();
                        boolean alternative = input.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("ALT"))
                            .map(e -> ((VariableElement)e).getConstantValue()).anyMatch(Boolean.TRUE::equals);
                        for (var name : List.of("A", alternative ? "D" : "B", "C")) {
                            try (var out = processingEnv.getFiler().createSourceFile("p." + name, input).openWriter()) {
                                out.write("package p; public class " + name + " { public static final boolean VALUE = " + (name.equals("C") && alternative) + "; }");
                            } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        }
                        return false;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Pair.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Pair\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Pair,isolating\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }

    @ParameterizedTest @MethodSource("digests")
    void aggregateDomainAndDerivationIgnoreBodiesButObserveAnnotationsAndMembership(Digest digest) throws Exception {
        var processor = aggregateProcessor();
        String source = "package p; import java.lang.annotation.*; @Retention(RetentionPolicy.SOURCE) @interface Entity { String value() default \"\"; } "
                + "@Entity(\"a\") class E { int body() { return 1; } } class Other {}";
        var file = dir.resolve("m/src/main/java/p/E.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        var model = model(processor, processor);
        var tree = new ContentTree(digest);
        var driver = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var original = Stage2Support.jdkOnly(digest).copy();
        assertThat(driver.run(original, model).faults()).isEmpty();
        var domainKey = LocalStore.processorDomainKey(Stage2.projectKey(digest, model), "m", 0, "fixture.Aggregate");
        var before = DefinerIndex.decodeRoot(original.get(domainKey), digest.width());
        assertThat(before.count()).isEqualTo(1);
        var beforeId = generated(original, digest).genId();

        Files.writeString(file, source.replace("return 1;", "return 2;"));
        var bodyEdit = original.copy();
        assertThat(driver.run(bodyEdit, model).faults()).isEmpty();
        assertThat(DefinerIndex.decodeRoot(bodyEdit.get(domainKey), digest.width())).isEqualTo(before);
        assertThat(generated(bodyEdit, digest).genId()).isEqualTo(beforeId);
        assertThat(bodyEdit.writes(LocalStore.generatedKey(beforeId))).isZero();

        Files.writeString(file, source.replace("@Entity(\"a\")", "@Entity(\"b\")"));
        var annotationEdit = original.copy();
        assertThat(driver.run(annotationEdit, model).faults()).isEmpty();
        assertThat(DefinerIndex.decodeRoot(annotationEdit.get(domainKey), digest.width()).sum()).isNotEqualTo(before.sum());
        assertThat(generated(annotationEdit, digest).genId()).isNotEqualTo(beforeId);

        Files.writeString(file, source.replace("int body()", "/** Visible documentation. */ int body()"));
        var commentEdit = original.copy();
        assertThat(driver.run(commentEdit, model).faults()).isEmpty();
        assertThat(DefinerIndex.decodeRoot(commentEdit.get(domainKey), digest.width()).sum()).isNotEqualTo(before.sum());
        var documented = generated(commentEdit, digest);
        assertThat(documented.genId()).isNotEqualTo(beforeId);
        assertThat(documented.kappa()).isNotEqualTo(generated(original, digest).kappa());

        Files.writeString(file, source.replace("int body()", "/** Visible documentation. */ int body()").replace("return 1;", "return 2;"));
        var documentedBodyEdit = commentEdit.copy();
        assertThat(driver.run(documentedBodyEdit, model).faults()).isEmpty();
        assertThat(DefinerIndex.decodeRoot(documentedBodyEdit.get(domainKey), digest.width()))
                .isEqualTo(DefinerIndex.decodeRoot(commentEdit.get(domainKey), digest.width()));
        assertThat(generated(documentedBodyEdit, digest).genId()).isEqualTo(documented.genId());
        assertThat(documentedBodyEdit.writes(LocalStore.generatedKey(documented.genId()))).isZero();

        Files.writeString(file, source.replace("class Other", "@Entity class Other"));
        var insertion = original.copy();
        assertThat(driver.run(insertion, model).faults()).isEmpty();
        var after = DefinerIndex.decodeRoot(insertion.get(domainKey), digest.width());
        var diff = dev.jvmd.core.tree.Diff.trees(digest, before, after, h -> insertion.get(MachineStore.nodeKey(h)));
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.added()).hasSize(1);
        assertThat(generated(insertion, digest).genId()).isNotEqualTo(beforeId);

        Files.writeString(file, source.replace("@Entity(\"a\")", ""));
        var deletion = original.copy();
        var deletedResult = driver.run(deletion, model);
        assertThat(deletedResult.faults()).isEmpty();
        assertThat(DefinerIndex.decodeRoot(deletion.get(domainKey), digest.width()).count()).isZero();
        assertThat(publishedGenerated(deletion, digest, deletedResult)).isEmpty();
        var manifests = new java.util.ArrayList<dev.jvmd.core.tree.Root>();
        tree.forEach(deletedResult.root().hash(), h -> deletion.get(MachineStore.nodeKey(h)), e -> {
            if (e.key().length > 4 && e.key()[0] == 'G' && e.key()[1] == 'E' && e.key()[2] == 'N' && e.key()[3] == '|')
                manifests.add(DefinerIndex.decodeRoot(deletion.get(e.key()), digest.width()));
        });
        assertThat(manifests).hasSize(1);
        assertThat(manifests.getFirst().count()).isZero();
        var oldManifest = DefinerIndex.decodeRoot(original.get(LocalStore.generatedKey(beforeId)), digest.width());
        var removal = dev.jvmd.core.tree.Diff.trees(digest, oldManifest, manifests.getFirst(), h -> deletion.get(MachineStore.nodeKey(h)));
        assertThat(removal.added()).isEmpty();
        assertThat(removal.removed()).hasSize(1);
    }

    private FileRow generated(InMemoryLocalStore store, Digest digest) {
        return store.withPrefix("F").values().stream().map(b -> FileRow.decode("", b, digest.width())).filter(FileRow::generated).findFirst().orElseThrow();
    }

    private Path aggregateProcessor() throws Exception {
        var work = dir.resolve("processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Entity")
                public class Aggregate extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || annotations.isEmpty()) return false;
                        done = true;
                        var names = new TreeSet<String>();
                        int docs = 0;
                        for (var annotation : annotations) for (var element : round.getElementsAnnotatedWith(annotation)) {
                            names.add(((TypeElement) element).getQualifiedName().toString());
                            for (var member : element.getEnclosedElements()) {
                                var comment = processingEnv.getElementUtils().getDocComment(member);
                                if (comment != null) docs += comment.hashCode();
                            }
                        }
                        try (var out = processingEnv.getFiler().createSourceFile("p.Registry").openWriter()) {
                            out.write("package p; public class Registry { public static final String NAMES = \\\"" + String.join(",", names) + "\\\"; public static final int DOCS = " + docs + "; }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return true;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Aggregate.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Aggregate\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Aggregate,aggregating\n"));
        return Stage2Support.pack(work.resolve("processor.jar"), entries);
    }

    @ParameterizedTest @MethodSource("digests")
    void autoValueGeneratedRowSharesItsDerivationManifestAndSourceBlob(Digest digest) throws Exception {
        var processor = jar(com.google.auto.value.processor.AutoValueProcessor.class);
        var annotation = jar(com.google.auto.value.AutoValue.class);
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Value.java", "package p; @com.google.auto.value.AutoValue public abstract class Value { public abstract String name(); }"));
        var model = model(processor, annotation);
        var store = Stage2Support.jdkOnly(digest).copy();
        var tree = new ContentTree(digest);
        var driver = new Stage2(digest, tree, Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var result = driver.run(store, model);
        assertThat(result.faults()).isEmpty();
        var rows = store.withPrefix("F").values().stream().map(bytes -> FileRow.decode("", bytes, digest.width())).toList();
        var generated = rows.stream().filter(FileRow::generated).toList();
        assertThat(generated).hasSize(1);
        var row = generated.getFirst();
        assertThat(row.originPath()).isEqualTo("m/src/main/java/p/Value.java");
        assertThat(row.genId()).isNotNull();
        var manifest = DefinerIndex.decodeRoot(store.get(LocalStore.generatedKey(row.genId())), digest.width());
        var output = tree.get(manifest.hash(), h -> store.get(MachineStore.nodeKey(h)), Keys.generatedOutputKey(0, "p/AutoValue_Value.java"));
        assertThat(output).isNotNull();
        assertThat(output.value()).isEqualTo(row.kappa().bytes());
        var bytes = store.get(LocalStore.generatedSourceKey(row.kappa()));
        assertThat(digest.hash(bytes)).isEqualTo(row.kappa());
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).contains("class AutoValue_Value");

        var next = store.copy();
        driver.run(next, model);
        assertThat(next.writes(LocalStore.generatedKey(row.genId()))).isZero();
        assertThat(next.writes(LocalStore.generatedSourceKey(row.kappa()))).isZero();
    }

    private static Path jar(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()); }

    private ProjectModel model(Path processor, Path annotation) throws Exception {
        var json = new ObjectMapper();
        var mod = new Stage2Support.Mod("m", "g:m:1", List.of(Stage2Support.Dep.jar("g:annotations:1", annotation.toString())));
        var doc = (ObjectNode) json.readTree(Stage2Support.model(dir, mod));
        var processing = ((ObjectNode) doc.withArray("modules").get(0)).putObject("processing");
        processing.putArray("path").addObject().put("coordinate", "g:processor:1").put("location", processor.toString());
        return ProjectModel.parse(json.writeValueAsBytes(doc));
    }
}
