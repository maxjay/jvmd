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
             var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("Api.java", file)), List.of(jar),
                     Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var annotation = loader.loadClass("fixture.WithTypes").asSubclass(java.lang.annotation.Annotation.class);
            var unsupported = new ArrayList<String>();
            var reads = new ProcessorReads(compiled.elements, compiled.types, ignored -> {}, unsupported::add);
            assertThat(classValues(reads.elements, annotation, true)).isEqualTo(classValues(compiled.elements, annotation, false));
            assertThat(unsupported).isEmpty();
        }
    }

    private static List<String> classValues(Elements elements, Class<? extends java.lang.annotation.Annotation> annotation, boolean wrapped) throws Exception {
        var instance = elements.getTypeElement("p.Api").getAnnotation(annotation);
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
