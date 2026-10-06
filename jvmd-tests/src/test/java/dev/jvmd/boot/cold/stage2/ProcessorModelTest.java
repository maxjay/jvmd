package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor14;
import javax.lang.model.util.SimpleElementVisitor14;
import javax.lang.model.util.SimpleTypeVisitor14;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class ProcessorModelTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    private static final String SOURCE = """
            package p;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.SOURCE) @interface Label { int value() default 7; }
            @interface Nested { String value(); }
            @interface Shape { Nested nested(); Nested[] many(); Class<?> type(); }
            @Shape(nested=@Nested("one"), many={@Nested("two"), @Nested("three")}, type=String.class)
            @Deprecated(since="first") class Api<T extends Comparable<T>> {
                @Label(1) String field;
                /** Method documentation. */
                public <N extends Number> java.util.List<? extends N> method(String... args) throws java.io.IOException { return null; }
            }
            class Unrelated { int field; }
            record Rec(String value) {}
            """;

    @ParameterizedTest @MethodSource("digests")
    void graphQueriesVisitorsAndNativeUtilityArgumentsAgreeWithTheUnwrappedModel(Digest digest) throws Exception {
        var before = snapshot(digest, SOURCE, true);
        assertThat(before.proof).isEqualTo(snapshot(digest, SOURCE, true).proof);
        assertThat(before.proof).isNotEqualTo(snapshot(digest, SOURCE.replace("@Label(1)", "@Label(2)"), true).proof);
        assertThat(before.proof).isNotEqualTo(snapshot(digest, SOURCE.replace("Method documentation.", "Changed documentation."), true).proof);
        assertThat(before.proof).isNotEqualTo(snapshot(digest, SOURCE.replace("since=\"first\"", "since=\"second\""), true).proof);
        assertThat(before.proof).isEqualTo(snapshot(digest, SOURCE.replace("return null;", "int local = unknown(); return null;"), true).proof);
    }

    @ParameterizedTest @MethodSource("digests")
    void returningAnElementDoesNotReadItsUnqueriedAnnotationsOrOtherDeclarations(Digest digest) throws Exception {
        var before = snapshot(digest, SOURCE, false);
        assertThat(before.proof).isEqualTo(snapshot(digest, SOURCE.replace("@Label(1)", "@Label(2)"), false).proof);
        assertThat(before.proof).isEqualTo(snapshot(digest, SOURCE.replace("class Unrelated { int field; }", "class Unrelated { long added; void method() {} }"), false).proof);
    }

    @ParameterizedTest @MethodSource("digests")
    void identityComparisonsUseHandlesButOverriddenEqualityRemainsAQuery(Digest digest) throws Exception {
        var file = dir.resolve("Api.java"); Files.writeString(file, SOURCE);
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Api.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var reads = new ProcessorReads(compiled.elements, compiled.types, ignored -> {}, reason -> { throw new AssertionError(reason); });
            var api = reads.elements.getTypeElement("p.Api"); var other = reads.elements.getTypeElement("p.Unrelated");
            var before = reads.proof();
            for (int i = 0; i < 10; i++) {
                assertThat(api.equals(api)).isTrue(); assertThat(api.equals(other)).isFalse(); assertThat(api.equals(null)).isFalse();
            }
            assertThat(reads.proof()).isEqualTo(before);
            // Factory calls return distinct native handles; use separate phases as the captured replay requires.
            reads.phase(0); var a = reads.types.getArrayType(api.asType());
            reads.phase(1); var b = reads.types.getArrayType(api.asType());
            assertThat(a).isNotSameAs(b); before = reads.proof();
            assertThat(a.equals(b)).isTrue();
            assertThat(reads.proof()).isNotEqualTo(before);
            var replay = reads.replay(ignored -> {}, reason -> { throw new AssertionError(reason); });
            var replayApi = replay.elements.getTypeElement("p.Api"); replay.elements.getTypeElement("p.Unrelated");
            replay.phase(0); var replayA = replay.types.getArrayType(replayApi.asType());
            replay.phase(1); var replayB = replay.types.getArrayType(replayApi.asType());
            assertThat(replayA.equals(replayB)).isTrue(); assertThat(replay.proof()).isEqualTo(reads.proof());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void runtimeAnnotationClassValuesKeepTheirMirrorsWrapped(Digest digest) throws Exception {
        var work = dir.resolve("annotation");
        var classes = Stage2Support.compile(work, java.util.Map.of("fixture/WithTypes.java", """
                package fixture;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface WithTypes { Class<?> value(); Class<?>[] many(); }
                """), List.of(), List.of());
        var jar = Stage2Support.pack(work.resolve("annotation.jar"), classes);
        var file = dir.resolve("Api.java");
        Files.writeString(file, "package p; @fixture.WithTypes(value=String.class, many={Integer.class, Number.class}) class Api {}");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var replayLoader = new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Api.java", file)), List.of(jar),
                     Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var annotation = loader.loadClass("fixture.WithTypes").asSubclass(java.lang.annotation.Annotation.class);
            var unsupported = new ArrayList<String>();
            var reads = new ProcessorReads(compiled.elements, compiled.types, ignored -> {}, unsupported::add);
            assertThat(classValues(reads.elements, annotation, true)).isEqualTo(classValues(compiled.elements, annotation, false));
            var replay = reads.replay(ignored -> {}, unsupported::add);
            assertThat(classValues(replay.elements, annotation, true)).isEqualTo(classValues(compiled.elements, annotation, false));
            assertThat(replay.proof()).isEqualTo(reads.proof());
            var isolated = reads.replay(ignored -> {}, unsupported::add);
            var isolatedAnnotation = replayLoader.loadClass("fixture.WithTypes").asSubclass(java.lang.annotation.Annotation.class);
            assertThat(isolatedAnnotation).isNotSameAs(annotation);
            assertThat(classValues(isolated.elements, isolatedAnnotation, true)).isEqualTo(classValues(compiled.elements, annotation, false));
            assertThat(isolated.proof()).isEqualTo(reads.proof());
            assertThat(unsupported).isEmpty();
        }
    }

    private static List<String> classValues(Elements elements, Class<? extends java.lang.annotation.Annotation> annotation, boolean wrapped) throws Exception {
        var instance = elements.getTypeElement("p.Api").getAnnotation(annotation);
        assertThat(instance.annotationType()).isSameAs(annotation);
        var repeated = elements.getTypeElement("p.Api").getAnnotationsByType(annotation);
        assertThat(repeated.getClass().getComponentType()).isSameAs(annotation);
        assertThat(repeated).hasSize(1);
        assertThat(repeated[0].annotationType()).isSameAs(annotation);
        var names = new ArrayList<String>();
        for (var method : List.of("value", "many")) {
            try { annotation.getMethod(method).invoke(instance); throw new AssertionError("javac must return mirrors through the model exception"); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                assertThat(failure.getCause()).isInstanceOf(javax.lang.model.type.MirroredTypesException.class);
                var mirrors = (javax.lang.model.type.MirroredTypesException) failure.getCause();
                if (method.equals("value")) assertThat(mirrors).isInstanceOf(javax.lang.model.type.MirroredTypeException.class);
                for (var mirror : mirrors.getTypeMirrors()) {
                    assertThat(Proxy.isProxyClass(mirror.getClass())).isEqualTo(wrapped);
                    names.add(mirror.toString());
                }
            }
        }
        assertThat(names).containsExactly("java.lang.String", "java.lang.Integer", "java.lang.Number");
        return names;
    }

    private record Snapshot(byte[] proof) { }

    @ParameterizedTest @MethodSource("digests")
    void replayUsesObservedAnswersWithoutReenteringTheCompletedCompiler(Digest digest) throws Exception {
        var file = dir.resolve("Api.java");
        Files.writeString(file, SOURCE);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        var unsupported = new ArrayList<String>();
        ProcessorReads reads;
        List<String> expected;
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Api.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            reads = new ProcessorReads(guard(Elements.class, compiled.elements, blocked), guard(Types.class, compiled.types, blocked),
                    ignored -> {}, unsupported::add);
            expected = query(reads.elements, reads.types, true, true);
        }
        blocked.set(true);
        var replay = reads.replay(ignored -> {}, unsupported::add);
        assertThat(query(replay.elements, replay.types, true, true)).isEqualTo(expected);
        assertThat(replay.proof()).isEqualTo(reads.proof());
        assertThat(unsupported).isEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void replayRetainsTheQueryPhaseInsteadOfUsingTheLastModelAnswer(Digest digest) throws Exception {
        var file = dir.resolve("Api.java");
        Files.writeString(file, SOURCE);
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Api.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var current = new java.util.concurrent.atomic.AtomicReference<>("first round");
            var elements = (Elements) Proxy.newProxyInstance(Elements.class.getClassLoader(), new Class<?>[] {Elements.class}, (p, m, a) -> {
                if (m.getName().equals("getDocComment")) return current.get();
                try { return m.invoke(compiled.elements, a); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
            var reads = new ProcessorReads(elements, compiled.types, ignored -> {}, reason -> { throw new AssertionError(reason); });
            reads.phase(0);
            var type = reads.elements.getTypeElement("p.Api");
            assertThat(reads.elements.getDocComment(type)).isEqualTo("first round");
            reads.phase(1);
            current.set("second round");
            assertThat(reads.elements.getDocComment(type)).isEqualTo("second round");
            current.set("completed model must not be queried");
            var replay = reads.replay(ignored -> {}, reason -> { throw new AssertionError(reason); });
            replay.phase(0);
            var replayType = replay.elements.getTypeElement("p.Api");
            assertThat(replay.elements.getDocComment(replayType)).isEqualTo("first round");
            replay.phase(1);
            assertThat(replay.elements.getDocComment(replayType)).isEqualTo("second round");
            assertThat(replay.proof()).isEqualTo(reads.proof());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> replay.elements.getTypeElement("p.Unobserved"))
                    .isInstanceOf(ProcessorReads.ReplayUnavailable.class).hasMessageContaining("unobserved").hasMessageContaining("phase 1");

            // The same query changing within a phase cannot be silently assigned the first or last answer.
            reads.phase(2);
            current.set("before completion");
            reads.elements.getDocComment(type);
            current.set("after completion");
            reads.elements.getDocComment(type);
            replay.phase(2);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> replay.elements.getDocComment(replayType))
                    .isInstanceOf(ProcessorReads.ReplayUnavailable.class).hasMessageContaining("ambiguous").hasMessageContaining("phase 2");
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void replayCanReuseAnAnswerForEachOriginWithoutIncludingOtherOriginsQueries(Digest digest) throws Exception {
        var file = dir.resolve("Api.java");
        Files.writeString(file, SOURCE);
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Api.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var unsupported = new ArrayList<String>();
            var baseline = new ProcessorReads(compiled.elements, compiled.types, ignored -> {}, unsupported::add);
            assertThat(query(baseline.elements, baseline.types, false, true)).containsExactly("field");
            var captured = new ProcessorReads(compiled.elements, compiled.types, ignored -> {}, unsupported::add);
            // A different origin is queried first, moving every handle ordinal in the full invocation transcript.
            captured.elements.getTypeElement("p.Unrelated").getEnclosedElements().forEach(Element::getSimpleName);
            assertThat(query(captured.elements, captured.types, false, true)).containsExactly("field");
            assertThat(captured.proof()).isNotEqualTo(baseline.proof());
            for (int origin = 0; origin < 2; origin++) {
                var replay = captured.replay(ignored -> {}, unsupported::add);
                // Both origins can consume the captured answer even if the batched processor cached it after one query.
                assertThat(query(replay.elements, replay.types, false, true)).containsExactly("field");
                assertThat(replay.proof()).isEqualTo(baseline.proof());
            }
            assertThat(unsupported).isEmpty();
        }
    }

    private static <T> T guard(Class<T> api, T delegate, java.util.concurrent.atomic.AtomicBoolean blocked) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, (p, m, a) -> {
            if (blocked.get()) throw new AssertionError("native query after capture: " + m);
            try { return m.invoke(delegate, a); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        }));
    }

    private Snapshot snapshot(Digest digest, String source, boolean annotations) throws Exception {
        var file = dir.resolve("p/Api.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("p/Api.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var expected = query(compiled.elements, compiled.types, annotations, false);
            var unsupported = new ArrayList<String>();
            var reads = new ProcessorReads(compiled.elements, compiled.types, ignored -> {}, unsupported::add);
            assertThat(query(reads.elements, reads.types, annotations, true)).isEqualTo(expected);
            assertThat(unsupported).isEmpty();
            return new Snapshot(reads.proof());
        }
    }

    private static List<String> query(Elements elements, Types types, boolean annotations, boolean wrapped) {
        var values = new ArrayList<String>();
        var api = elements.getTypeElement("p.Api");
        assertThat(Proxy.isProxyClass(api.getClass())).isEqualTo(wrapped);
        Element field = api.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("field")).findFirst().orElseThrow();
        values.add(field.getSimpleName().toString());
        if (!annotations) return values;
        values.add(api.accept(new SimpleElementVisitor14<String, Void>() {
            @Override public String visitType(TypeElement element, Void unused) {
                assertThat(Proxy.isProxyClass(element.getClass())).isEqualTo(wrapped);
                return element.getQualifiedName().toString();
            }
        }, null));
        var annotation = field.getAnnotationMirrors().getFirst();
        for (var entry : elements.getElementValuesWithDefaults(annotation).entrySet()) {
            values.add(entry.getKey().getSimpleName().toString());
            values.add(entry.getValue().accept(new SimpleAnnotationValueVisitor14<String, Void>() {
                @Override public String visitInt(int value, Void unused) { return "int:" + value; }
            }, null));
        }
        values.add(api.getAnnotation(Deprecated.class).since());
        values.add(api.getAnnotationsByType(Deprecated.class)[0].since());
        for (var mirror : api.getAnnotationMirrors()) for (var value : mirror.getElementValues().values())
            values.add(annotationValue(value, wrapped));
        var method = (ExecutableElement) api.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("method")).findFirst().orElseThrow();
        values.add(elements.getDocComment(method));
        var member = types.asMemberOf((DeclaredType) api.asType(), method);
        values.add(member.accept(new SimpleTypeVisitor14<String, Void>() {
            @Override public String visitExecutable(ExecutableType type, Void unused) {
                assertThat(Proxy.isProxyClass(type.getClass())).isEqualTo(wrapped);
                return type.getReturnType() + ":" + type.getParameterTypes() + ":" + type.getThrownTypes();
            }
        }, null));
        TypeMirror fieldType = field.asType();
        values.add(Boolean.toString(types.isSameType(types.erasure(fieldType), elements.getTypeElement("java.lang.String").asType())));
        values.add(elements.getPackageOf(api).getQualifiedName().toString());
        values.add(Boolean.toString(elements.getModuleOf(api).isUnnamed()));
        var component = elements.getTypeElement("p.Rec").getRecordComponents().getFirst();
        values.add(component.getSimpleName() + ":" + component.getAccessor().getReturnType());
        return values;
    }

    private static String annotationValue(AnnotationValue value, boolean wrapped) {
        assertThat(Proxy.isProxyClass(value.getClass())).isEqualTo(wrapped);
        // A compound value must still expose both public model views through getValue and visitor dispatch.
        var raw = value.getValue();
        if (raw instanceof AnnotationMirror mirror) assertThat(Proxy.isProxyClass(mirror.getClass())).isEqualTo(wrapped);
        return value.accept(new SimpleAnnotationValueVisitor14<String, Void>() {
            @Override protected String defaultAction(Object item, Void unused) { return item.toString(); }
            @Override public String visitAnnotation(AnnotationMirror annotation, Void unused) {
                assertThat(Proxy.isProxyClass(annotation.getClass())).isEqualTo(wrapped);
                var parts = new ArrayList<String>();
                for (var nested : annotation.getElementValues().values()) parts.add(annotationValue(nested, wrapped));
                return annotation.getAnnotationType() + parts.toString();
            }
            @Override public String visitArray(List<? extends AnnotationValue> array, Void unused) {
                return array.stream().map(item -> annotationValue(item, wrapped)).toList().toString();
            }
        }, null);
    }
}
