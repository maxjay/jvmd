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
                    for(var entry:elements.getElementValuesWithDefaults(annotation).entrySet()) if(!annotation.getElementValues().containsKey(entry.getKey()))
                        text.append("|default-handle=").append(entry.getValue()==entry.getKey().getDefaultValue());
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
                    for(var annotation:elements.getAllAnnotationMirrors(type)) text.append(":origin=").append(elements.getOrigin(type,annotation));
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
    void memberDeclarationsRetainNativeSourceOrderNamesAnnotationsAndComments(Digest digest) throws Exception {
        var processor = processor("""
                var elements=processingEnv.getElementUtils(); var text=new StringBuilder();
                new javax.lang.model.util.ElementScanner14<Void,StringBuilder>() {
                    public Void scan(Element element, StringBuilder text) {
                        text.append("|").append(element.getKind()).append(":").append(element.getSimpleName());
                        text.append(":").append(element.getModifiers()).append(":").append(elements.getDocComment(element));
                        text.append(":deprecated=").append(elements.isDeprecated(element)).append(":origin=").append(elements.getOrigin(element));
                        text.append(":").append(element.getAnnotationMirrors()).append(":").append(elements.getAllAnnotationMirrors(element));
                        if(element instanceof TypeElement type) {
                            text.append(":all=").append(elements.getAllMembers(type));
                            for(var parameter:type.getTypeParameters()) scan(parameter,text);
                            for(var component:type.getRecordComponents()) scan(component,text);
                        }
                        if(element instanceof ExecutableElement method) {
                            for(var parameter:method.getTypeParameters()) scan(parameter,text);
                            text.append(":default=").append(method.getDefaultValue());
                            text.append(":bridge=").append(elements.isBridge(method)).append(":compact=").append(elements.isCompactConstructor(method));
                            text.append(":canonical=").append(elements.isCanonicalConstructor(method)).append(":component=").append(elements.recordComponentFor(method));
                            text.append(":params=").append(method.getParameters()).append(":types=").append(method.getTypeParameters());
                            text.append(":signature=").append(method.asType()).append(":receiver=").append(method.getReceiverType());
                        }
                        if(element instanceof VariableElement variable) text.append(":").append(variable.getConstantValue()).append(":").append(variable).append(":").append(variable.asType());
                        if(element.getEnclosingElement() instanceof TypeElement owner && (element.getKind()==ElementKind.FIELD || element.getKind()==ElementKind.METHOD))
                            text.append(":member=").append(processingEnv.getTypeUtils().asMemberOf((javax.lang.model.type.DeclaredType)owner.asType(),element));
                        return super.scan(element,text);
                    }
                }.scan(elements.getTypeElement("p.Metadata"),text);
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,text,root);
                """, "isolating");
        var state=boot(digest,Map.of("p/Input.java","package p; public class Input {}", "p/Metadata.java", """
                package p;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.SOURCE) @Target({ElementType.TYPE,ElementType.METHOD,ElementType.FIELD,
                    ElementType.CONSTRUCTOR,ElementType.PARAMETER,ElementType.TYPE_PARAMETER,ElementType.RECORD_COMPONENT})
                @interface Label {String value();}
                class Base<V> { @Label("inherited") protected V inherited(V argument){return argument;} public int hiding; class Inherited {} }
                public class Metadata<@Label("type parameter") T extends Number & Comparable<T>> extends Base<T> {
                    /** Last by name, first in source. */ @Label("field") private static final String z="constant";
                    @Label("no-arg constructor") Metadata() {}
                    /** Generic overload. */ @Label("generic") <@Label("method type") V extends T> V call(@Label("generic arg") final V sourceName){return sourceName;}
                    /** String overload. */ @Label("string") String call(@Label("string arg") String otherName){return otherName;}
                    @Label("constructor") Metadata(@Label("constructor arg") T argument) {}
                    /** Private generic method. */ @Label("private method") private <@Label("private variable") V extends T>
                        java.util.List<? super V[]> secret(@Label("private parameter") final V[] sourcePrivateName) throws java.io.IOException {return null;}
                    private java.util.Map<String, ? extends T[]> hidden;
                    /** @deprecated documentation only. */ @SuppressWarnings("dep-ann") private int documentedDeprecated;
                    @Deprecated(since="phase") private int annotationDeprecated;
                    class Inner$Named { @Label("inner constructor") Inner$Named(@Label("inner arg") String argument) {} }
                    record Data(@Label("component") String sourceComponent) {}
                    record ExplicitData(String value) {ExplicitData(String value){this.value=value;}}
                    record CompactData(String value) {CompactData {java.util.Objects.requireNonNull(value);}}
                    class Implicit {} class Explicit {Explicit(){}}
                    enum ImplicitChoice {ONE}
                    enum Choice { @Label("constant") ONE; @Label("enum constructor") Choice() {} }
                    @interface Defaults {String z() default "z"; String a() default "a";}
                    interface DefaultType { @Label("default method") default int size(@Label("size arg") int count){return count;} }
                    @Label("last field") int a;
                    private int hiding;
                    @Override public T inherited(T namedOverride){return namedOverride;}
                }
                """),List.of(processor),List.of());
        assertThat(state.faults()).isEmpty(); allFiles(state,true);
    }

    @ParameterizedTest @MethodSource("digests")
    void sourceTypeUsesRetainNativeAnnotationsAndStructure(Digest digest) throws Exception {
        var processor=processor("""
                var elements=processingEnv.getElementUtils(); var types=processingEnv.getTypeUtils(); var text=new StringBuilder();
                var visitor=new javax.lang.model.util.SimpleTypeVisitor14<Void,Integer>() {
                    void scan(javax.lang.model.type.TypeMirror type,int depth) {
                        text.append("[").append(type.getKind()).append(":").append(type).append(":").append(type.getAnnotationMirrors());
                        for(var annotation:type.getAnnotationMirrors()) text.append(elements.getElementValuesWithDefaults(annotation)).append(":origin=").append(elements.getOrigin(type,annotation));
                        for(var spot:type.getAnnotationsByType(fixture.Spot.class)) {
                            text.append(":repeat=").append(spot.value());
                            try {spot.type();throw new AssertionError("expected mirror");}
                            catch(javax.lang.model.type.MirroredTypeException e){text.append(":type=").append(e.getTypeMirror());}
                        }
                        if(depth>0) type.accept(this,depth-1); text.append("]");
                    }
                    public Void visitArray(javax.lang.model.type.ArrayType t,Integer d){scan(t.getComponentType(),d);return null;}
                    public Void visitDeclared(javax.lang.model.type.DeclaredType t,Integer d){scan(t.getEnclosingType(),d);for(var a:t.getTypeArguments())scan(a,d);return null;}
                    public Void visitTypeVariable(javax.lang.model.type.TypeVariable t,Integer d){scan(t.getUpperBound(),d);scan(t.getLowerBound(),d);return null;}
                    public Void visitWildcard(javax.lang.model.type.WildcardType t,Integer d){if(t.getExtendsBound()!=null)scan(t.getExtendsBound(),d);if(t.getSuperBound()!=null)scan(t.getSuperBound(),d);return null;}
                    public Void visitIntersection(javax.lang.model.type.IntersectionType t,Integer d){for(var b:t.getBounds())scan(b,d);return null;}
                    public Void visitExecutable(javax.lang.model.type.ExecutableType t,Integer d){
                        scan(t.getReturnType(),d);scan(t.getReceiverType(),d);
                        for(var a:t.getParameterTypes())scan(a,d);for(var v:t.getTypeVariables())scan(v,d);for(var e:t.getThrownTypes())scan(e,d);return null;
                    }
                };
                new javax.lang.model.util.ElementScanner14<Void,Void>() {
                    public Void scan(Element element,Void ignored) {
                        text.append("|").append(element.getKind()).append(":").append(element.getSimpleName()).append(":element=").append(element);visitor.scan(element.asType(),3);
                        if(element instanceof TypeElement type){
                            text.append(":members=").append(elements.getAllMembers(type));
                            visitor.scan(type.getSuperclass(),3);for(var i:type.getInterfaces())visitor.scan(i,3);
                            for(var p:type.getTypeParameters())scan(p,null);
                            for(var s:types.directSupertypes(type.asType()))visitor.scan(s,2);
                        }
                        if(element instanceof javax.lang.model.element.TypeParameterElement p)for(var b:p.getBounds())visitor.scan(b,3);
                        if(element instanceof ExecutableElement method){
                            visitor.scan(method.getReturnType(),3);visitor.scan(method.getReceiverType(),3);
                            for(var e:method.getThrownTypes())visitor.scan(e,3);for(var p:method.getTypeParameters())scan(p,null);
                        }
                        if(element.getEnclosingElement() instanceof TypeElement owner && (element.getKind()==ElementKind.FIELD||element.getKind()==ElementKind.METHOD))
                            visitor.scan(types.asMemberOf((javax.lang.model.type.DeclaredType)owner.asType(),element),3);
                        return super.scan(element,ignored);
                    }
                }.scan(elements.getTypeElement("p.Metadata"),null);
                var metadata=elements.getTypeElement("p.Metadata");
                for(var site:java.util.List.of(types.getDeclaredType(metadata),types.getDeclaredType(metadata,elements.getTypeElement("java.lang.Integer").asType()))) {
                    text.append("|factory");visitor.scan(site,3);
                    for(var parent:types.directSupertypes(site))visitor.scan(parent,3);
                    for(var member:elements.getAllMembers(metadata))if(member.getKind()==ElementKind.FIELD||member.getKind()==ElementKind.METHOD)
                        visitor.scan(types.asMemberOf(site,member),2);
                    visitor.scan(types.erasure(site),2);visitor.scan(types.capture(site),2);visitor.scan(types.getArrayType(site),2);
                }
                for(var field:metadata.getEnclosedElements())if(field.getSimpleName().contentEquals("repeated")) {
                    var type=((javax.lang.model.type.DeclaredType)field.asType()).getTypeArguments().get(0);
                    text.append(":container=").append(type.getAnnotation(fixture.Spots.class));
                    for(var spot:type.getAnnotationsByType(fixture.Spot.class)) {
                        text.append(":spot=").append(spot.value());
                        try {spot.type();throw new AssertionError("expected mirror");}
                        catch(javax.lang.model.type.MirroredTypeException e){text.append(":mirror=").append(e.getTypeMirror());}
                    }
                }
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,text,root);
                ""","isolating",Map.of("fixture/Spot.java","""
                package fixture;import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.SOURCE) @Repeatable(Spots.class)
                public @interface Spot {String value();Class<?> type() default String.class;}
                ""","fixture/Spots.java","""
                package fixture;import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.SOURCE)
                public @interface Spots {Spot[] value();}
                """));
        var state=boot(digest,Map.of("p/Input.java","package p; public class Input {}","p/Metadata.java","""
                package p;
                import java.lang.annotation.*; import java.util.*; import java.io.*;
                @Retention(RetentionPolicy.SOURCE) @Target({ElementType.TYPE_USE,ElementType.TYPE_PARAMETER})
                @interface Use {String value(); int number() default 7;}
                class Base<V> { @Use("inherited-return") V inherited(@Use("inherited-arg") V argument){return argument;} } interface Face<V> {}
                public class Metadata<@Use("parameter") T extends @Use("bound") Number & @Use("interface-bound") Comparable<@Use("self") T>>
                    extends @Use("super") Base<@Use("base-arg") T> implements @Use("face") Face<@Use("face-arg") T> {
                    @Use("field") String @Use("array") [] @Use("inner-array") [] field;
                    List<@Use("wildcard") ? extends @Use("wild-bound") T @Use("bound-array") []> list;
                    private List<@Use("private-wildcard") ? super @Use("private-bound") T> hidden;
                    List<@fixture.Spot("first") @fixture.Spot(value="second",type=int[].class) String> repeated;
                    <@Use("method-var") V extends @Use("method-bound") T> @Use("return") V @Use("return-array") [] call(
                        @Use("receiver") Metadata<T> this, @Use("arg") V @Use("arg-array") [] named) throws @Use("throws") IOException {return named;}
                    private @Use("private-return") T secret(@Use("private-receiver") Metadata<T> this,@Use("private-arg") T named){return named;}
                    class Inner { Inner(@Use("outer-receiver") Metadata<T> Metadata.this) {} }
                    record Data(@Use("component") String @Use("component-array") [] component) {}
                }
                """),List.of(processor),List.of(processor));
        assertThat(state.faults()).isEmpty();allFiles(state,true);
    }

    @ParameterizedTest @MethodSource("digests")
    void onlyConsumedTypeUseMetadataChangesTheProcessorProof(Digest digest) throws Exception {
        var processor=processor("""
                var type=processingEnv.getElementUtils().getTypeElement("p.Metadata");
                for(var field:type.getEnclosedElements())if(field.getSimpleName().contentEquals("observed")) {
                    var argument=((javax.lang.model.type.DeclaredType)field.asType()).getTypeArguments().get(0);
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,argument.getAnnotationMirrors().toString(),root);
                }
                ""","isolating");
        var states=new ArrayList<State>();var results=new ArrayList<Attribute.Computed>();var expected=new ArrayList<Oracle>();
        for(var change:List.of(new String[]{"read","unread","1"},new String[]{"read","changed","2"},new String[]{"changed","changed","2"})) {
            var state=boot(digest,Map.of("p/Input.java","package p; public class Input {}","p/Metadata.java","""
                    package p;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Use {String value();}
                    public class Metadata {
                        java.util.List<@Use("%s") String> observed;
                        private java.util.List<@Use("%s") String> unread;
                        int body(){return %s;}
                    }
                    """.formatted(change[0],change[1],change[2])),List.of(processor),List.of());
            assertThat(state.faults()).isEmpty();states.add(state);expected.add(oracle(state));
        }
        assertThat(states).extracting(state->state.own().k()).containsOnly(states.getFirst().own().k());
        Files.delete(dir.resolve("app/src/main/java/p/Metadata.java"));
        try(var pool=new Pool(states.getFirst().pool(),1)) {
            for(var state:states)results.add(run(state,attribute(state,pool),"app/src/main/java/p/Input.java"));
            assertThat(run(states.getFirst(),attribute(states.getFirst(),pool),"app/src/main/java/p/Input.java")).isEqualTo(results.getFirst());
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
        for(int i=0;i<results.size();i++) {
            assertThat(results.get(i).reusable()).as(results.get(i).faults().toString()).isTrue();
            assertThat(results.get(i).result().diagnostics()).isEqualTo(expected.get(i).messages());
        }
        assertThat(results.get(1)).isEqualTo(results.get(0));
        assertThat(results.get(2).aci()).isNotEqualTo(results.get(0).aci());
        assertThat(results.get(2).proof().processorBody()).isNotEqualTo(results.get(0).proof().processorBody());
    }

    @ParameterizedTest @MethodSource("digests")
    void constructorOriginChangesTheProofWithoutChangingTheStubApi(Digest digest) throws Exception {
        var processor=processor("""
                var elements=processingEnv.getElementUtils();
                var type=elements.getTypeElement("p.Metadata");
                for(var member:type.getEnclosedElements())if(member.getKind()==javax.lang.model.element.ElementKind.CONSTRUCTOR)
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,elements.getOrigin(member).name(),root);
                ""","isolating");
        var states=new ArrayList<State>();var results=new ArrayList<Attribute.Computed>();var expected=new ArrayList<Oracle>();
        for(var change:List.of(new String[]{"","1"},new String[]{"","2"},new String[]{"public Metadata() {}","2"})) {
            var state=boot(digest,Map.of("p/Input.java","package p; public class Input {}","p/Metadata.java",
                    "package p; public class Metadata {%s int body(){return %s;}}".formatted(change[0],change[1])),List.of(processor),List.of());
            assertThat(state.faults()).isEmpty();states.add(state);expected.add(oracle(state));
        }
        assertThat(states).extracting(state->state.own().k()).containsOnly(states.getFirst().own().k());
        Files.delete(dir.resolve("app/src/main/java/p/Metadata.java"));
        try(var pool=new Pool(states.getFirst().pool(),1)) {
            for(var state:states)results.add(run(state,attribute(state,pool),"app/src/main/java/p/Input.java"));
            assertThat(run(states.getFirst(),attribute(states.getFirst(),pool),"app/src/main/java/p/Input.java")).isEqualTo(results.getFirst());
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
        for(int i=0;i<results.size();i++) {
            assertThat(results.get(i).reusable()).as(results.get(i).faults().toString()).isTrue();
            assertThat(results.get(i).result().diagnostics()).isEqualTo(expected.get(i).messages());
        }
        assertThat(results.get(1)).isEqualTo(results.get(0));
        assertThat(results.get(2).aci()).isNotEqualTo(results.get(0).aci());
        assertThat(results.get(2).proof().processorBody()).isNotEqualTo(results.get(0).proof().processorBody());
        assertThat(results.get(0).result().diagnostics().getFirst().message()).isEqualTo("MANDATED");
        assertThat(results.get(2).result().diagnostics().getFirst().message()).isEqualTo("EXPLICIT");
    }

    @ParameterizedTest @MethodSource("digests")
    void privateMemberReadsRemainIndependentOfUnconsumedSignatureAndBodyChanges(Digest digest) throws Exception {
        var processor=processor("""
                var type=processingEnv.getElementUtils().getTypeElement("p.Metadata");
                for(var element:type.getEnclosedElements()) if(element.getSimpleName().contentEquals("secret")) {
                    var method=(ExecutableElement)element;
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,method.getParameters().get(0).getSimpleName(),root);
                }
                ""","isolating");
        var states=new ArrayList<State>(); var results=new ArrayList<Attribute.Computed>();
        for(var change:List.of(new String[]{"int","named","1","unread"},new String[]{"long","named","2","changed"},new String[]{"long","renamed","2","changed"})) {
            states.add(boot(digest,Map.of("p/Input.java","package p; public class Input {}","p/Metadata.java","""
                    package p;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) @interface Label {String value();}
                    public class Metadata {
                        @Label("%s") private String unread;
                        private int secret(final %s %s) {return %s;}
                    }
                    """.formatted(change[3],change[0],change[1],change[2])),List.of(processor),List.of()));
        }
        assertThat(states).allSatisfy(state -> assertThat(state.faults()).isEmpty());
        assertThat(states).extracting(state -> state.own().k()).containsOnly(states.getFirst().own().k());
        Files.delete(dir.resolve("app/src/main/java/p/Metadata.java"));
        try(var pool=new Pool(states.getFirst().pool(),1)) {
            for(var state:states) results.add(run(state,attribute(state,pool),"app/src/main/java/p/Input.java"));
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
        assertThat(results).allSatisfy(r -> assertThat(r.reusable()).as(r.faults().toString()).isTrue());
        assertThat(results.get(1)).isEqualTo(results.get(0));
        assertThat(results.get(2).aci()).isNotEqualTo(results.get(0).aci());
        assertThat(results.get(0).result().diagnostics().getFirst().message()).isEqualTo("named");
        assertThat(results.get(2).result().diagnostics().getFirst().message()).isEqualTo("renamed");
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
