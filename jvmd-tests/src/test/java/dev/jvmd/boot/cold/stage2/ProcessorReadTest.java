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
        Files.writeString(file, "package p; @interface Trigger {} @Trigger public class Input {}");
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
        Stage2Support.write(dir, Map.of(input, "package p; @interface Trigger {} @Trigger public class Input {}"));
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

    @ParameterizedTest @MethodSource("digests")
    void annotationReachedThroughTheElementGraphChangesGenerationWithoutChangingTheApi(Digest digest) throws Exception {
        var work = dir.resolve("graph-processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*")
                public class Graph extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        var input = round.getRootElements().stream().filter(e -> e.getSimpleName().contentEquals("Input")).findFirst().orElseThrow();
                        var api = processingEnv.getElementUtils().getTypeElement("ext.Api");
                        var field = api.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("field")).findFirst().orElseThrow();
                        var annotation = field.getAnnotationMirrors().getFirst();
                        var value = annotation.getElementValues().values().iterator().next().getValue();
                        try (var out = processingEnv.getFiler().createSourceFile("p.Result", input).openWriter()) {
                            out.write("package p; public class Result { public static final int VALUE = " + value + "; }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return false;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Graph.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Graph\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Graph,isolating\n"));
        var processor = Stage2Support.pack(work.resolve("processor.jar"), entries);
        var api = dir.resolve("graph-api.jar");
        Files.copy(annotatedApi(1), api);
        String input = "m/src/main/java/p/Input.java";
        Stage2Support.write(dir, Map.of(input, "package p; public class Input {}"));
        var model = model(processor, api);
        var project = Stage2.projectKey(digest, model);
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        assertThat(driver.run(first, model).faults()).isEmpty();
        var before = generated(first, digest);
        var originalProof = FileRow.decode(input, first.get(LocalStore.fileKey(project, input)), digest.width()).headerProof();

        Files.copy(annotatedApi(2), api, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var second = first.copy();
        assertThat(driver.run(second, model).faults()).isEmpty();
        assertThat(FileRow.decode(input, second.get(LocalStore.fileKey(project, input)), digest.width()).headerProof()).isEqualTo(originalProof);
        var after = generated(second, digest);
        assertThat(after.genId()).isNotEqualTo(before.genId());
        assertThat(new String(second.get(LocalStore.generatedSourceKey(after.kappa())), java.nio.charset.StandardCharsets.UTF_8)).contains("VALUE = 2;");

        var repeat = second.copy();
        assertThat(driver.run(repeat, model).faults()).isEmpty();
        assertThat(generated(repeat, digest).genId()).isEqualTo(after.genId());
        assertThat(repeat.writes(LocalStore.generatedKey(after.genId()))).isZero();
    }

    private Path annotatedApi(int value) throws Exception {
        var work = dir.resolve("graph-api-" + value);
        return Stage2Support.pack(work.resolve("api.jar"), Stage2Support.compile(work, Map.of(
                "ext/Label.java", "package ext; public @interface Label { int value(); }",
                "ext/Api.java", "package ext; public class Api { @Label(" + value + ") public String field; }"), List.of(), List.of()));
    }

    @ParameterizedTest @MethodSource("digests")
    void batchedOriginsEachProveACachedModelReadAndKeepSeparateDerivations(Digest digest) throws Exception {
        var work = dir.resolve("cached-origins");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Entity")
                public class Cached extends AbstractProcessor {
                    static Integer cached;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        for (var annotation : annotations) for (var input : round.getElementsAnnotatedWith(annotation)) {
                            if (cached == null) {
                                var api = processingEnv.getElementUtils().getTypeElement("ext.Api");
                                var type = api.asType();
                                var first = processingEnv.getTypeUtils().getArrayType(type);
                                var second = processingEnv.getTypeUtils().getArrayType(type);
                                if (first == second || !first.equals(second) || first.getComponentType() != second.getComponentType())
                                    throw new AssertionError("Factory allocation or component aliasing changed");
                                var field = api.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("field")).findFirst().orElseThrow();
                                cached = (Integer) field.getAnnotationMirrors().getFirst().getElementValues().values().iterator().next().getValue();
                            }
                            String name = input.getSimpleName() + "Result";
                            try (var out = processingEnv.getFiler().createSourceFile("p." + name, input).openWriter()) {
                                out.write("package p; public class " + name + " { public static final int VALUE = " + cached + "; }");
                            } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                        }
                        return true;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Cached.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Cached\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Cached,isolating\n"));
        var processor = Stage2Support.pack(work.resolve("processor.jar"), entries);
        var api = dir.resolve("cached-api.jar");
        Files.copy(annotatedApi(1), api);
        String firstPath = "m/src/main/java/p/First.java";
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Entity.java", "package p; @interface Entity {}",
                firstPath, "package p; @Entity class First { int body() { return 1; } }",
                "m/src/main/java/p/Second.java", "package p; @Entity class Second {}"));
        var model = model(processor, api);
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        assertThat(driver.run(first, model).faults()).isEmpty();
        var oldOutputs = generatedByType(first, digest);
        assertThat(oldOutputs).containsOnlyKeys("p/FirstResult", "p/SecondResult");
        Files.copy(annotatedApi(2), api, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var second = first.copy();
        assertThat(driver.run(second, model).faults()).isEmpty();
        var changed = generatedByType(second, digest);
        for (var name : oldOutputs.keySet()) {
            assertThat(changed.get(name).genId()).isNotEqualTo(oldOutputs.get(name).genId());
            assertThat(new String(second.get(LocalStore.generatedSourceKey(changed.get(name).kappa())), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("VALUE = 2;");
        }
        Files.writeString(dir.resolve(firstPath), "package p; @Entity class First { int body() { return 2; } }");
        var third = second.copy();
        assertThat(driver.run(third, model).faults()).isEmpty();
        var body = generatedByType(third, digest);
        assertThat(body.get("p/FirstResult").genId()).isNotEqualTo(changed.get("p/FirstResult").genId());
        assertThat(body.get("p/SecondResult").genId()).isEqualTo(changed.get("p/SecondResult").genId());
        assertThat(third.writes(LocalStore.generatedKey(body.get("p/SecondResult").genId()))).isZero();
    }

    private Map<String, FileRow> generatedByType(InMemoryLocalStore store, Digest digest) {
        var rows = new java.util.TreeMap<String, FileRow>();
        store.withPrefix("F").values().stream().map(bytes -> FileRow.decode("", bytes, digest.width())).filter(FileRow::generated)
                .forEach(row -> rows.put(row.typeKeys().getFirst(), row));
        return rows;
    }

    @ParameterizedTest @MethodSource("digests")
    void crossOriginStateThatChangesOutputsIsReportedWithoutWideningTheDerivation(Digest digest) throws Exception {
        var work = dir.resolve("counter-processor");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Entity")
                public class Counter extends AbstractProcessor {
                    static int count;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        for (var annotation : annotations) for (var input : round.getElementsAnnotatedWith(annotation)) {
                            String name = input.getSimpleName() + "Result";
                            try (var out = processingEnv.getFiler().createSourceFile("p." + name, input).openWriter()) {
                                out.write("package p; public class " + name + " { public static final int VALUE = " + ++count + "; }");
                            } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                        }
                        return true;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Counter.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Counter\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Counter,isolating\n"));
        var processor = Stage2Support.pack(work.resolve("processor.jar"), entries);
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Entity.java", "package p; @interface Entity {}",
                "m/src/main/java/p/First.java", "package p; @Entity class First {}",
                "m/src/main/java/p/Second.java", "package p; @Entity class Second {}"));
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var store = Stage2Support.jdkOnly(digest).copy();
        var result = driver.run(store, model(processor, null));
        assertThat(result.faults()).anyMatch(f -> f.contains("fixture.Counter") && f.contains("isolated generated bytes differ"));
        assertThat(generatedByType(store, digest)).hasSize(2).allSatisfy((name, row) -> assertThat(row.genId()).isNull());
        assertThat(store.withPrefix("GEN")).isEmpty();
        assertThat(store.withPrefix("PROC").values()).allSatisfy(bytes ->
                assertThat(dev.jvmd.index.layer.local.ProcessorRecords.Capability.decode(bytes).reusable()).isFalse());
        assertThat(Files.readString(dir.resolve(".jvmd/generated/bQ/0/p/FirstResult.java"))).contains("VALUE = 1;");
        assertThat(Files.readString(dir.resolve(".jvmd/generated/bQ/0/p/SecondResult.java"))).contains("VALUE = 2;");
    }

    @ParameterizedTest @MethodSource("digests")
    void isolatedReplayCannotInventAnAllocationToMatchBatchedAliasSensitiveOutput(Digest digest) throws Exception {
        var work = dir.resolve("allocation-exhaustion");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Entity")
                public class Allocating extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion(){return SourceVersion.latestSupported();}
                    public boolean process(Set<? extends TypeElement> annotations,RoundEnvironment round) {
                        for(var annotation:annotations) {
                            var inputs=round.getElementsAnnotatedWith(annotation);
                            var type=processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();
                            var first=processingEnv.getTypeUtils().getArrayType(type);
                            var second=inputs.size()==1?processingEnv.getTypeUtils().getArrayType(type):first;
                            for(var input:inputs) {
                                String name=input.getSimpleName()+"Result";
                                try(var out=processingEnv.getFiler().createSourceFile("p."+name,input).openWriter()) {
                                    out.write("package p; public class "+name+" { public static final boolean VALUE = "+(first==second)+"; }");
                                } catch(java.io.IOException e){throw new RuntimeException(e);}
                            }
                        }
                        return true;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/Allocating.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Allocating\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Allocating,isolating\n"));
        var processor = Stage2Support.pack(work.resolve("processor.jar"), entries);
        Stage2Support.write(dir, Map.of("m/src/main/java/p/Entity.java", "package p; @interface Entity {}",
                "m/src/main/java/p/First.java", "package p; @Entity class First {}",
                "m/src/main/java/p/Second.java", "package p; @Entity class Second {}"));
        var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of).run(store, model(processor, null));
        assertThat(result.faults()).anyMatch(f -> f.contains("fixture.Allocating") && f.contains("exhausted allocating processor query"));
        assertThat(generatedByType(store, digest)).hasSize(2).allSatisfy((name,row) -> assertThat(row.genId()).isNull());
        assertThat(store.withPrefix("GEN")).isEmpty();
        for(var name:List.of("FirstResult","SecondResult"))
            assertThat(Files.readString(dir.resolve(".jvmd/generated/bQ/0/p/"+name+".java"))).contains("VALUE = true;");
    }

    @ParameterizedTest @MethodSource("digests")
    void aggregateQueriesSourceAnnotationsOutsideItsDomainWithoutDependingOnBodies(Digest digest) throws Exception {
        var work = dir.resolve("aggregate-graph");
        var source = """
                package fixture;
                import java.util.*;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("p.Trigger")
                public class SourceGraph extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        var api = processingEnv.getElementUtils().getTypeElement("p.Other");
                        var field = api.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("field")).findFirst().orElseThrow();
                        var annotation = field.getAnnotationMirrors().getFirst();
                        var value = annotation.getElementValues().values().iterator().next().getValue();
                        try (var out = processingEnv.getFiler().createSourceFile("p.Result").openWriter()) {
                            out.write("package p; public class Result { public static final int VALUE = " + value + "; }");
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                        return true;
                    }
                }
                """;
        var entries = new java.util.LinkedHashMap<>(Stage2Support.compile(work, Map.of("fixture/SourceGraph.java", source), List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.SourceGraph\n"));
        entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.SourceGraph,aggregating\n"));
        var processor = Stage2Support.pack(work.resolve("processor.jar"), entries);
        String input = "m/src/main/java/p/Input.java", other = "m/src/main/java/p/Other.java";
        String otherSource = "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) "
                + "@interface Label { int value(); } class Other { @Label(1) String field; int body() { return 1; } }";
        Stage2Support.write(dir, Map.of(input, "package p; @interface Trigger {} @Trigger class Input {}", other, otherSource));
        var model = model(processor, null);
        var project = Stage2.projectKey(digest, model);
        var domainKey = LocalStore.processorDomainKey(project, "m", 0, "fixture.SourceGraph");
        var driver = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, 1, dir, ClassFacts::of);
        var first = Stage2Support.jdkOnly(digest).copy();
        assertThat(driver.run(first, model).faults()).isEmpty();
        var before = generated(first, digest);
        var beforeRow = FileRow.decode(other, first.get(LocalStore.fileKey(project, other)), digest.width());

        Files.writeString(dir.resolve(other), otherSource.replace("@Label(1)", "@Label(2)"));
        var second = first.copy();
        assertThat(driver.run(second, model).faults()).isEmpty();
        assertThat(second.get(domainKey)).isEqualTo(first.get(domainKey));
        assertThat(FileRow.decode(other, second.get(LocalStore.fileKey(project, other)), digest.width()).sum()).isEqualTo(beforeRow.sum());
        var after = generated(second, digest);
        assertThat(after.genId()).isNotEqualTo(before.genId());
        assertThat(new String(second.get(LocalStore.generatedSourceKey(after.kappa())), java.nio.charset.StandardCharsets.UTF_8)).contains("VALUE = 2;");

        Files.writeString(dir.resolve(other), otherSource.replace("@Label(1)", "@Label(2)").replace("return 1;", "return 2;"));
        var body = second.copy();
        assertThat(driver.run(body, model).faults()).isEmpty();
        assertThat(body.get(domainKey)).isEqualTo(second.get(domainKey));
        assertThat(generated(body, digest).genId()).isEqualTo(after.genId());
        assertThat(body.writes(LocalStore.generatedKey(after.genId()))).isZero();
    }

    private ProjectModel model(Path processor, Path api) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var doc = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Stage2Support.model(dir,
                new Stage2Support.Mod("m", "g:m:1", api == null ? List.of() : List.of(Stage2Support.Dep.jar("g:api:1", api.toString())))));
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
                @SupportedAnnotationTypes("p.Trigger")
                public class Lookup extends AbstractProcessor {
                    boolean done;
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (done || round.processingOver()) return false;
                        done = true;
                        var input = round.getElementsAnnotatedWith(annotations.iterator().next()).iterator().next();
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
