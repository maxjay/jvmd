package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Attribute;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** From committed Stage 2 processing inputs through Attribute persistence, checked against fresh whole-module javac. */
@Tag("phase-3")
class ProcessedAttributeTest {
    @TempDir Path dir;
    private int sequence;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    record State(ContentTree tree, InMemoryLocalStore store, Identity project, MachineLeaf own, Route route, ModuleRecord module,
                 ProcessorPlan plan, Attribute.Options options, Pool.Configuration pool, List<Path> processors, List<Path> classpath,
                 Map<String,FileRow> rows, List<Path> original, List<String> faults) {
        Map<String,byte[]> classes(Attribute.Computed computed) {
            var classes = new TreeMap<String,byte[]>();
            computed.result().classFiles().forEach(c -> classes.put(c.internalName(), store.get(LocalStore.classFileKey(c.contentHash()))));
            return classes;
        }
    }

    private State boot(Digest digest, Map<String,String> sources, List<Path> processors, List<Path> classpath) throws Exception {
        var original = new ArrayList<Path>();
        for (var source : new TreeMap<>(sources).entrySet()) {
            var path = dir.resolve("app/src/main/java/" + source.getKey()); Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue()); original.add(path);
        }
        var config = dir.resolve("lombok.config"); if (!Files.exists(config)) Files.writeString(config, "config.stopBubbling = true\n");
        var deps = new ArrayList<Stage2Support.Dep>(); int n = 0;
        for (var jar : classpath) deps.add(Stage2Support.Dep.jar("g:dep" + n++ + ":1", jar.toString()));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var modelJson = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Stage2Support.model(dir,
                new Stage2Support.Mod("app", "g:app:1", deps).withOptions("-g", "-parameters")));
        var processing = ((com.fasterxml.jackson.databind.node.ObjectNode) modelJson.withArray("modules").get(0)).putObject("processing");
        var path = processing.putArray("path"); n = 0;
        for (var jar : processors) path.addObject().put("coordinate", "g:processor" + n++ + ":1").put("location", jar.toString());
        var model = ProjectModel.parse(json.writeValueAsBytes(modelJson));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, tree, Stage2Support.FEATURE, 2, dir, ClassFacts::of).run(store, model);
        var project = Stage2.projectKey(digest, model);
        var own = MachineLeaf.decode(store.get(MachineStore.leafKey(result.leaves().get("app/main"))), digest.width());
        var route = Route.decode(store.get(LocalStore.routeKey(project, "app", 0)), digest.width());
        var module = ModuleRecord.decode(store.get(LocalStore.moduleKey(project, "app")));
        var local = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(project)));
        var plan = ProcessorPlan.load(tree, local, project, "app", 0, store::get);
        var options = Attribute.Options.processed(digest, module, Stage2Support.JDK, plan.invocation());
        var stubs = Files.createDirectories(dir.resolve("stubs-" + sequence++)); var names = new ArrayList<String>();
        for (var stub : Stubs.stubs(digest, tree, own, id -> store.get(MachineStore.nodeKey(id)), Stubs.Cache.NONE)) {
            var file = stubs.resolve(stub.internalName() + ".class"); Files.createDirectories(file.getParent()); Files.write(file, stub.bytes()); names.add(stub.internalName());
        }
        var pool = new Pool.Configuration(new Pool.Key(route.routeHash(), own.k()), stubs, classpath, options.charset(), options.javac(), names);
        var rows = new TreeMap<String,FileRow>();
        int prefix = LocalStore.fileKey(project, "").length;
        for (var entry : store.withPrefix("F").entrySet()) {
            var name = new String(entry.getKey(), prefix, entry.getKey().length - prefix, java.nio.charset.StandardCharsets.UTF_8);
            rows.put(name, FileRow.decode(name, entry.getValue(), digest.width()));
        }
        return new State(tree, store, project, own, route, module, plan, options, pool, processors, classpath, rows, original, result.faults());
    }

    private Attribute attribute(State state, Pool pool) {
        return Attribute.processed(state.tree(), state.store(), state.own(), state.route(), pool, state.options(), state.plan(), state.processors(), dir);
    }
    private Attribute.Computed run(State state, Attribute attribute, String path) throws Exception {
        var row = state.rows().get(path); return attribute.run(row, dir.resolve(path).toUri(), Files.readAllBytes(dir.resolve(path)));
    }

    record Oracle(Map<String,byte[]> classes, List<ResultRecord.Diagnostic> messages) { }
    private Oracle oracle(State state) throws Exception {
        var output = Files.createDirectories(dir.resolve("oracle-" + sequence++)); var messages = new ArrayList<ResultRecord.Diagnostic>();
        var options = new ArrayList<>(state.options().javac()); options.removeIf(o -> o.startsWith("-proc:"));
        options.addAll(List.of("-proc:full", "--processor-path", String.join(java.io.File.pathSeparator, state.processors().stream().map(Path::toString).toList())));
        var compiler = ToolProvider.getSystemJavaCompiler();
        javax.tools.DiagnosticListener<javax.tools.JavaFileObject> listener = d -> messages.add(new ResultRecord.Diagnostic(switch (d.getKind()) {
            case ERROR -> 0; case WARNING, MANDATORY_WARNING -> 1; default -> 2;
        }, d.getStartPosition(), d.getEndPosition(), d.getCode(), d.getMessage(Locale.ROOT)));
        try (var files = compiler.getStandardFileManager(listener, Locale.ROOT, state.options().charset())) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, state.classpath()); files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output)); files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(output));
            var task = compiler.getTask(null, files, listener, options, null, files.getJavaFileObjectsFromPaths(state.original()));
            task.setLocale(Locale.ROOT); task.call();
        }
        var classes = new TreeMap<String,byte[]>();
        try (var files = Files.walk(output)) { for (var file : files.filter(p -> p.toString().endsWith(".class")).toList())
            classes.put(output.relativize(file).toString().replace('\\','/').replaceFirst("\\.class$", ""), Files.readAllBytes(file)); }
        return new Oracle(classes, List.copyOf(messages));
    }
    private static void sameBytes(Map<String,byte[]> actual, Map<String,byte[]> expected) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet()); actual.forEach((name, bytes) -> assertThat(bytes).as(name).isEqualTo(expected.get(name)));
    }
    private void allFiles(State state, boolean reusable) throws Exception {
        var expected = oracle(state); var actual = new TreeMap<String,byte[]>(); var messages = new ArrayList<ResultRecord.Diagnostic>();
        var local = state.store().get(LocalStore.localRootKey(state.project()));
        var declarations = ProcessorSources.load(state.tree(), LocalRoot.decode(state.tree().digest(), local), state.project(), "app", 0, state.store()::get);
        for (var row : state.rows().values()) for (var type : row.typeKeys())
            assertThat(declarations.type(type).path()).isEqualTo(row.path());
        try (var pool = new Pool(state.pool(), 1)) {
            var attribute = attribute(state, pool);
            for (var path : state.rows().keySet()) {
                var computed = run(state, attribute, path); actual.putAll(state.classes(computed)); messages.addAll(computed.result().diagnostics());
                assertThat(computed.reusable()).as(path + ": " + computed.faults()).isEqualTo(reusable);
                assertThat(Proof.decode(computed.proof().encode(), state.tree().digest().width())).isEqualTo(computed.proof());
                if (reusable) {
                    assertThat(computed.faults()).isEmpty();
                    assertThat(ResultRecord.decode(state.store().get(LocalStore.resultKey(computed.aci())), state.tree().digest().width())).isEqualTo(computed.result());
                    assertThat(UsesRecord.decode(state.store().get(LocalStore.usesKey(computed.aci())))).isEqualTo(computed.uses());
                } else {
                    assertThat(computed.faults()).isNotEmpty(); assertThat(computed.aci()).isNull();
                    assertThat(computed.proof().valid(state.tree(), state.own(), state.route(), computed.proof().header().processor(),
                            computed.proof().processorBody(), key -> { throw new AssertionError("Rejected proof read storage"); })).isFalse();
                }
                long writes = state.store().recordWriteCount();
                var again = run(state, attribute, path); assertThat(again).isEqualTo(computed);
                assertThat(state.store().recordWriteCount()).isEqualTo(writes);
            }
            assertThat(pool.statistics().contexts()).isEqualTo(1);
            assertThat(pool.statistics().tasks()).isEqualTo(2 * state.rows().size());
        }
        sameBytes(actual, expected.classes()); assertThat(messages).isEqualTo(expected.messages());
        assertThat(state.store().get(LocalStore.localRootKey(state.project()))).isEqualTo(local);
        assertThat(state.store().get(LocalStore.bodiesRootKey(state.project()))).isNull();
        assertThat(dir.resolve(".jvmd/body-capture")).doesNotExist();
        if (!reusable) { assertThat(state.store().withPrefix("RS")).isEmpty(); assertThat(state.store().withPrefix("U")).isEmpty(); }
    }

    @ParameterizedTest @MethodSource("digests")
    void autoValueOriginalAndGeneratedRowsPublishNativeResults(Digest digest) throws Exception {
        var processor = Path.of(com.google.auto.value.processor.AutoValueProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var annotation = Path.of(com.google.auto.value.AutoValue.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var state = boot(digest, Map.of("p/Value.java", """
                package p;
                @com.google.auto.value.AutoValue public abstract class Value {
                    public abstract String name();
                    public static Value of(String name) { return new AutoValue_Value(name); }
                }
                """), List.of(processor), List.of(annotation));
        assertThat(state.faults()).isEmpty(); assertThat(state.rows()).hasSize(2); allFiles(state, true);
    }

    @ParameterizedTest @MethodSource("digests")
    void lombokRewrittenTreesPublishNativeOriginalAndNestedClasses(Digest digest) throws Exception {
        var lombok = Path.of(lombok.Getter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(dir.resolve("lombok.config"), "config.stopBubbling = true\nlombok.addLombokGeneratedAnnotation = false\n");
        var state = boot(digest, Map.of("p/Bean.java", """
                package p;
                @lombok.Value @lombok.Builder public class Bean {
                    String name;
                    public int length() { return getName().length(); }
                }
                """, "p/Use.java", "package p; public class Use { public String value(){ return Bean.builder().name(\"value\").build().getName(); } }"),
                List.of(lombok), List.of(lombok));
        assertThat(state.faults()).isEmpty(); allFiles(state, true);
    }

    private Path processor(String body, String declaration) throws Exception {
        return processor(body, declaration, Map.of());
    }
    private Path processor(String body, String declaration, Map<String,String> extra) throws Exception {
        var sources = new TreeMap<>(extra);
        sources.put("fixture/Generate.java", """
                package fixture;
                import javax.annotation.processing.*;
                import javax.lang.model.*;
                import javax.lang.model.element.*;
                @SupportedAnnotationTypes("*") public class Generate extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion(){return SourceVersion.latestSupported();}
                    public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if(round.processingOver()) return false;
                        for(var root:round.getRootElements()) if(root.getSimpleName().contentEquals("Input")) {
                """ + body + "\n} return true; } }");
        var entries = new TreeMap<>(Stage2Support.compile(dir.resolve("processor-" + sequence++), sources, List.of(), List.of()));
        entries.put("META-INF/services/javax.annotation.processing.Processor", Stage2Support.text("fixture.Generate\n"));
        if (declaration != null) entries.put("META-INF/gradle/incremental.annotation.processors", Stage2Support.text("fixture.Generate," + declaration + "\n"));
        return Stage2Support.pack(dir.resolve("processor-" + sequence++ + ".jar"), entries);
    }
    private static final String GENERATE = """
            for(var suffix:java.util.List.of("One","Two")) {
                try(var out=processingEnv.getFiler().createSourceFile("p."+suffix,root).openWriter()) {
                    out.write("package p; public class "+suffix+" { public static final int VALUE=7; }");
                } catch(java.io.IOException e){throw new RuntimeException(e);}
            }
            """;

    @ParameterizedTest @MethodSource("digests")
    void completeAndEmptyManifestsAndProcessorDiagnosticsReachResults(Digest digest) throws Exception {
        var processor = processor(GENERATE + "processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE, \"from processor \" + (char)0xd800 + \" \" + (char)0xdc00, root);", "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public int value(){ return One.VALUE+Two.VALUE; } }",
                "p/Empty.java", "package p; public class Empty {}"), List.of(processor), List.of());
        assertThat(state.faults()).isEmpty();
        assertThat(state.plan().generation("fixture.Generate", "app/src/main/java/p/Empty.java").outputs().count()).isZero();
        allFiles(state, true);
    }

    @ParameterizedTest @MethodSource("digests")
    void undeclaredProcessorsStillProduceFreshNativeResultsWithoutReusableRecords(Digest digest) throws Exception {
        var processor = processor(GENERATE, null);
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public int value(){ return One.VALUE; } }"), List.of(processor), List.of());
        assertThat(state.faults()).anyMatch(f -> f.contains("fixture.Generate") && f.contains("unsupported"));
        allFiles(state, false);
    }

    @ParameterizedTest @MethodSource("digests")
    void multipleOriginsStillProduceFreshNativeResultsWithoutReusableRecords(Digest digest) throws Exception {
        var processor = processor(GENERATE.replace("createSourceFile(\"p.\"+suffix,root)",
                "createSourceFile(\"p.\"+suffix,root,processingEnv.getElementUtils().getTypeElement(\"p.Other\"))"), "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public int value(){ return One.VALUE; } } class Other {}"),
                List.of(processor), List.of());
        assertThat(state.faults()).anyMatch(f -> f.contains("fixture.Generate") && f.contains("2 originating elements"));
        allFiles(state, false);
    }

    @ParameterizedTest @MethodSource("digests")
    void currentSourceLocationConfigurationOptionsAndProcessorBytesAreRequiredBeforeExecution(Digest digest) throws Exception {
        var processor = processor(GENERATE, "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public int value(){ return One.VALUE; } }"), List.of(processor), List.of());
        String path = "app/src/main/java/p/Input.java"; var row = state.rows().get(path); var file = dir.resolve(path); var bytes = Files.readAllBytes(file);
        try (var pool = new Pool(state.pool(), 1)) {
            var attribute = attribute(state, pool); long writes = state.store().recordWriteCount();
            assertThatThrownBy(() -> attribute.run(row, dir.resolve("elsewhere/Input.java").toUri(), bytes)).hasMessageContaining("source location differs");
            var config = file.getParent().resolve("lombok.config"); Files.writeString(config, "lombok.getter.noIsPrefix = true\n");
            assertThatThrownBy(() -> attribute.run(row, file.toUri(), bytes)).hasMessageContaining("Processor context differs");
            Files.delete(config);
            var wrong = Stage2Support.pack(dir.resolve("wrong.jar"), Map.of("note.txt", Stage2Support.text("different processor bytes")));
            var bad = Attribute.processed(state.tree(), state.store(), state.own(), state.route(), pool, state.options(), state.plan(), List.of(wrong), dir);
            assertThatThrownBy(() -> bad.run(row, file.toUri(), bytes)).hasRootCauseMessage("Processor bytes differ from Stage 2");
            assertThat(pool.statistics().tasks()).isZero(); assertThat(state.store().recordWriteCount()).isEqualTo(writes);
            assertThatThrownBy(() -> Attribute.unprocessed(state.tree(), state.store(), state.own(), state.route(), pool, state.options()))
                    .hasMessage("Processor options require processor-aware attribution");
            var changed = new ModuleRecord(state.module().coordinate(), state.module().release(), false, List.of("-g:none"),
                    state.module().mainRoots(), state.module().testRoots(), state.module().processing());
            assertThatThrownBy(() -> Attribute.Options.processed(digest, changed, Stage2Support.JDK, state.plan().invocation()))
                    .hasMessage("Processor options differ from Stage 2");
            assertThat(run(state, attribute, path).reusable()).isTrue();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void sourceAnnotationsReachProcessorsWithoutReadingGeneratedBytesOrChangingTheManifest(Digest digest) throws Exception {
        var processor = processor("""
                boolean seen = !processingEnv.getElementUtils().getTypeElement("p.Metadata").getAnnotationMirrors().isEmpty();
                try(var out=processingEnv.getFiler().createSourceFile("p.Made",root).openWriter()) {
                    out.write("package p; public class Made { public static final boolean SEEN="+seen+"; }");
                } catch(java.io.IOException e){throw new RuntimeException(e);}
                """, "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public boolean value(){ return Made.SEEN; } }",
                "p/Metadata.java", "package p; @Label public class Metadata {}",
                "p/Label.java", "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Label {}"),
                List.of(processor), List.of());
        assertThat(state.faults()).isEmpty();
        var generation = state.plan().generation("fixture.Generate", "app/src/main/java/p/Input.java");
        var key = LocalStore.generatedKey(generation.derivation()); var before = state.store().get(key); var expected = oracle(state);
        var blobReads = state.rows().values().stream().filter(FileRow::generated).map(r -> state.store().watchReads(LocalStore.generatedSourceKey(r.kappa()))).toList();
        try (var pool = new Pool(state.pool(), 1)) {
            var computed = run(state, attribute(state, pool), "app/src/main/java/p/Input.java");
            assertThat(computed.result().attributed()).isTrue(); assertThat(computed.reusable()).as(computed.faults().toString()).isTrue();
            assertThat(computed.faults()).isEmpty();
            assertThat(state.classes(computed).get("p/Input")).isEqualTo(expected.classes().get("p/Input"));
            assertThat(computed.proof().processorBody().rejected()).isFalse();
        }
        assertThat(state.store().get(key)).isEqualTo(before); assertThat(blobReads).allSatisfy(n -> assertThat(n.get()).isZero());
        assertThat(state.store().withPrefix("RS")).hasSize(1); assertThat(state.store().withPrefix("U")).hasSize(1);
    }

    @ParameterizedTest @MethodSource("digests")
    void sourceTypeAnnotationValuesVisitorsAndDocCommentsMatchNativeDiagnostics(Digest digest) throws Exception {
        var processor = processor("""
                var elements = processingEnv.getElementUtils();
                var type = elements.getTypeElement("p.Metadata");
                var text = new StringBuilder(elements.getDocComment(type));
                for (var annotation : type.getAnnotationMirrors()) {
                    text.append("|").append(annotation).append("|").append(annotation.getAnnotationType());
                    text.append("|").append(annotation.getElementValues()).append("|").append(elements.getElementValuesWithDefaults(annotation));
                    for(var value : elements.getElementValuesWithDefaults(annotation).values()) {
                        text.append("|").append(value.accept(new javax.lang.model.util.SimpleAnnotationValueVisitor14<String,Void>() {
                            protected String defaultAction(Object value, Void ignored) {return value.getClass().getSimpleName()+":"+value;}
                            public String visitType(javax.lang.model.type.TypeMirror value, Void ignored) {return "type:"+value.getKind()+":"+value;}
                            public String visitEnumConstant(VariableElement value, Void ignored) {return "enum:"+value.getKind()+":"+value;}
                            public String visitArray(java.util.List<? extends AnnotationValue> value, Void ignored) {return "array:"+value;}
                            public String visitAnnotation(AnnotationMirror value, Void ignored) {return "nested:"+elements.getElementValuesWithDefaults(value);}
                        }, null));
                    }
                }
                var deprecated = type.getAnnotation(Deprecated.class);
                text.append("|").append(deprecated).append("|").append(deprecated.since()).append("|").append(deprecated.forRemoval());
                text.append("|").append(type.getAnnotationsByType(Deprecated.class).length);
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,text,root);
                """, "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input {}", "p/Metadata.java", """
                package p;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.SOURCE) @interface Label {
                    String value() default "default"; int number() default 9; Class<?> type() default void.class;
                    RetentionPolicy retention() default RetentionPolicy.SOURCE;
                    Holder.Nested nested() default @Holder.Nested; int[] array() default {1,2};
                    Holder.Nested nestedDefault() default @Holder.Nested;
                    Holder.Nested[] nestedArray() default {@Holder.Nested, @Holder.Nested("explicit")};
                }
                class Holder { @interface Nested {String value() default "nested default";} }
                /** Source documentation. */ @Label(number=7,value="source",type=int[].class,nested=@Holder.Nested)
                @Deprecated(since="version",forRemoval=true) public class Metadata {}
                """), List.of(processor), List.of());
        assertThat(state.faults()).isEmpty();
        allFiles(state, true);
    }

    @ParameterizedTest @MethodSource("digests")
    void inheritedAndRepeatedSourceAnnotationsKeepNativeProxyAndMirrorBehavior(Digest digest) throws Exception {
        var processor = processor("""
                var elements=processingEnv.getElementUtils(); var text=new StringBuilder();
                for(var name:java.util.List.of("p.Metadata", "p.Middle", "p.Input")) {
                    var type=elements.getTypeElement(name);
                    text.append("|").append(elements.getAllAnnotationMirrors(type));
                    text.append("|").append(type.getAnnotation(fixture.Tag.class));
                    for(var tag:type.getAnnotationsByType(fixture.Tag.class)) {
                        text.append("|").append(tag).append("|").append(tag.value());
                        try {tag.type(); throw new AssertionError("expected mirror");}
                        catch(javax.lang.model.type.MirroredTypeException e){text.append("|").append(e.getTypeMirror());}
                    }
                }
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,text,root);
                """, "isolating", Map.of("fixture/Tag.java", """
                        package fixture; import java.lang.annotation.*;
                        @Inherited @Repeatable(Tags.class) @Retention(RetentionPolicy.SOURCE)
                        public @interface Tag {String value(); Class<?> type() default String.class;}
                        """, "fixture/Tags.java", """
                        package fixture; import java.lang.annotation.*;
                        @Inherited @Retention(RetentionPolicy.SOURCE) public @interface Tags {Tag[] value();}
                        """));
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input extends Middle {}",
                "p/Middle.java", "package p; @fixture.Tag(\"override\") public class Middle extends Metadata {}",
                "p/Metadata.java", "package p; @fixture.Tag(\"first\") @fixture.Tag(value=\"second\",type=int[].class) public class Metadata {}"),
                List.of(processor), List.of(processor));
        assertThat(state.faults()).isEmpty(); allFiles(state, true);
    }

    @ParameterizedTest @MethodSource("digests")
    void onlyConsumedSourceMetadataChangesTheProcessorProof(Digest digest) throws Exception {
        var processor = processor("""
                String comment=processingEnv.getElementUtils().getDocComment(processingEnv.getElementUtils().getTypeElement("p.Metadata"));
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,comment,root);
                """, "isolating");
        var results = new ArrayList<Attribute.Computed>(); var states = new ArrayList<State>();
        for (var change : List.of(new String[]{"read", "unread", "1"}, new String[]{"read", "changed", "2"}, new String[]{"changed", "changed", "2"})) {
            var state = boot(digest, Map.of("p/Input.java", "package p; public class Input {}", "p/Metadata.java", """
                    package p;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) @interface Label {String value();}
                    /** %s */ @Label("%s") public class Metadata {int body(){return %s;}}
                    """.formatted(change[0],change[1],change[2])), List.of(processor), List.of());
            assertThat(state.faults()).isEmpty(); states.add(state);
        }
        Files.delete(dir.resolve("app/src/main/java/p/Metadata.java"));
        try (var pool = new Pool(states.getFirst().pool(), 1)) {
            for (var state : states) results.add(run(state, attribute(state, pool), "app/src/main/java/p/Input.java"));
            assertThat(pool.statistics().contexts()).isEqualTo(1); assertThat(pool.statistics().tasks()).isEqualTo(3);
        }
        assertThat(states).extracting(s -> s.own().k()).containsOnly(states.getFirst().own().k());
        assertThat(results).allSatisfy(r -> assertThat(r.reusable()).as(r.faults().toString()).isTrue());
        assertThat(results.get(1)).isEqualTo(results.get(0));
        assertThat(results.get(2).aci()).isNotEqualTo(results.get(0).aci());
        assertThat(results.get(2).proof().processorBody()).isNotEqualTo(results.get(0).proof().processorBody());
        assertThat(results.get(0).result().diagnostics().getFirst().message()).isEqualTo("read ");
        assertThat(results.get(2).result().diagnostics().getFirst().message()).isEqualTo("changed ");
    }

    @ParameterizedTest @MethodSource("digests")
    void outputMismatchStillRejectsReuseAndPreservesCommittedManifest(Digest digest) throws Exception {
        String property = "jvmd.test.output-drift";
        var processor = processor("""
                String value=System.getProperty("jvmd.test.output-drift", "before");
                try(var out=processingEnv.getFiler().createSourceFile("p.Made",root).openWriter()) {
                    out.write("package p; public class Made { public static final String VALUE=\\\""+value+"\\\"; }");
                } catch(java.io.IOException e){throw new RuntimeException(e);}
                """, "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public String value(){return Made.VALUE;} }"), List.of(processor), List.of());
        var generation = state.plan().generation("fixture.Generate", "app/src/main/java/p/Input.java");
        var key = LocalStore.generatedKey(generation.derivation()); var before = state.store().get(key); var expected = oracle(state);
        // Deliberate out-of-model fault injection checks conservation; this is not admission evidence for property-reading processors.
        String previous = System.getProperty(property);
        try (var pool = new Pool(state.pool(), 1)) {
            System.setProperty(property, "after");
            var computed = run(state, attribute(state, pool), "app/src/main/java/p/Input.java");
            assertThat(computed.reusable()).isFalse();
            assertThat(computed.faults()).anyMatch(f -> f.contains("generated output differs"));
            assertThat(computed.proof().processorBody().rejected()).isTrue();
            sameBytes(state.classes(computed), Map.of("p/Input", expected.classes().get("p/Input")));
        } finally { if (previous == null) System.clearProperty(property); else System.setProperty(property, previous); }
        assertThat(state.store().get(key)).isEqualTo(before);
        assertThat(state.store().withPrefix("RS")).isEmpty(); assertThat(state.store().withPrefix("U")).isEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void aggregateOnlyScopesCompileTheirCommittedGeneratedRowsWithoutRunningAggregates(Digest digest) throws Exception {
        var processor = processor(GENERATE, "aggregating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public int value(){ return One.VALUE; } }"), List.of(processor), List.of());
        assertThat(state.faults()).isEmpty(); assertThat(state.options().javac()).contains("-proc:none");
        assertThat(state.plan().generation("fixture.Generate", "").outputs().count()).isEqualTo(2);
        allFiles(state, true);
    }

    @ParameterizedTest @MethodSource("digests")
    void unsupportedAggregateRejectsScopeReuseEvenWithNoBodyInvocations(Digest digest) throws Exception {
        var processor = processor("""
                try(var ignored=processingEnv.getFiler().getResource(javax.tools.StandardLocation.CLASS_PATH,"","missing-resource").openInputStream()) {}
                catch(java.io.IOException expected) {}
                """ + GENERATE, "aggregating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public int value(){ return One.VALUE; } }"), List.of(processor), List.of());
        assertThat(state.faults()).anyMatch(f -> f.contains("fixture.Generate") && f.contains("resource read"));
        try (var pool = new Pool(state.pool(), 1)) {
            var result = run(state, attribute(state, pool), "app/src/main/java/p/Input.java");
            assertThat(result.proof().processorBody().processors()).isEmpty();
            assertThat(result.proof().processorBody().rejected()).isTrue(); assertThat(result.reusable()).isFalse();
        }
        allFiles(state, false);
    }

    @ParameterizedTest @MethodSource("digests")
    void concurrentProcessorTasksShareResultsAndNeverRewriteGeneratedSources(Digest digest) throws Exception {
        var processor = processor(GENERATE, "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input { public int value(){ return One.VALUE; } }"), List.of(processor), List.of());
        var blobReads = state.rows().values().stream().filter(FileRow::generated).map(r -> state.store().watchReads(LocalStore.generatedSourceKey(r.kappa()))).toList();
        var generations = state.store().withPrefix("GEN"); var sources = state.store().withPrefix("GS");
        long writes = state.store().recordWriteCount();
        try (var pool = new Pool(state.pool(), 2); var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1); var attribute = attribute(state, pool);
            java.util.concurrent.Callable<Attribute.Computed> job = () -> { start.await(); return run(state, attribute, "app/src/main/java/p/Input.java"); };
            var a = workers.submit(job); var b = workers.submit(job); start.countDown();
            var first = a.get(30, java.util.concurrent.TimeUnit.SECONDS); var second = b.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.reusable()).isTrue(); assertThat(second).isEqualTo(first);
            assertThat(state.store().recordWriteCount() - writes).isEqualTo(first.result().classFiles().size() + 2);
        }
        assertThat(blobReads).allSatisfy(n -> assertThat(n.get()).isZero());
        generations.forEach((key,value) -> assertThat(state.store().get(key)).isEqualTo(value));
        sources.forEach((key,value) -> assertThat(state.store().get(key)).isEqualTo(value));
    }

    @ParameterizedTest @MethodSource("digests")
    void processorErrorsKeepNativeDiagnosticsAndLeaveTheNextFileHealthy(Digest digest) throws Exception {
        var processor = processor("processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, \"rejected input\", root);", "isolating");
        var state = boot(digest, Map.of("p/Input.java", "package p; public class Input {}", "p/Empty.java", "package p; public class Empty {}"),
                List.of(processor), List.of());
        var expected = oracle(state);
        try (var pool = new Pool(state.pool(), 1)) {
            var attribute = attribute(state, pool); var failed = run(state, attribute, "app/src/main/java/p/Input.java");
            assertThat(failed.result().attributed()).isFalse(); assertThat(failed.result().classFiles()).isEmpty();
            assertThat(failed.result().diagnostics()).isEqualTo(expected.messages());
            var next = run(state, attribute, "app/src/main/java/p/Empty.java");
            assertThat(next.result().attributed()).isTrue(); assertThat(next.result().diagnostics()).isEmpty();
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
    }
}
