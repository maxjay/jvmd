package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.ProcessorElementProjection;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Appendix F and Max's declaration-only processor projection clarification. */
@Tag("phase-3")
class ProcessorProjectionTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    private static final String SOURCE = """
            package p;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.SOURCE) @interface Entity { String value() default "a"; }
            @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE_USE) @interface Use { int value(); }
            @Entity("x") class E<T extends @Use(1) Number> {
                public static final int C = 1;
                String method(@Use(2) String input) throws Exception { return "old"; }
                class Nested { int value; }
            }
            class Other { int unrelated() { return 1; } }
            """;

    @ParameterizedTest @MethodSource("digests")
    void bodyExpressionsAndLocalDeclarationsAreExcluded(Digest digest) throws Exception {
        var before = project(digest, SOURCE, "p/E.java");
        var after = project(digest, SOURCE.replace("return \"old\";", "class Local { int x; } int local = unknown(); return \"new\";"), "p/E.java");
        assertThat(after.entries.getFirst().value()).isEqualTo(before.entries.getFirst().value());
        assertThat(after.root).isEqualTo(before.root);
        assertThat(project(digest, SOURCE.replace("return 1;", "return 2;"), "p/E.java").root).isEqualTo(before.root);
    }

    @ParameterizedTest @MethodSource("digests")
    void declarationDocCommentsAreObservableButOrdinaryAndBodyCommentsAreNot(Digest digest) throws Exception {
        var before = project(digest, SOURCE, "p/E.java");
        for (var changed : List.of(
                SOURCE.replace("@Entity(\"x\")", "/** Entity documentation. */ @Entity(\"x\")"),
                SOURCE.replace("public static final", "/** Constant documentation. */ public static final"),
                SOURCE.replace("String method", "/** Method documentation. */ String method"),
                SOURCE.replace("class Nested", "/** Nested documentation. */ class Nested"),
                SOURCE.replace("int value;", "/** Field documentation. */ int value;"))) {
            var after = project(digest, changed, "p/E.java");
            assertThat(after.root.hash()).as(changed).isNotEqualTo(before.root.hash());
            assertThat(after.root.sum()).as(changed).isNotEqualTo(before.root.sum());
        }
        assertThat(project(digest, SOURCE.replace("String method", "/* Ordinary comment. */ String method"), "p/E.java").root).isEqualTo(before.root);
        assertThat(project(digest, SOURCE.replace("return \"old\";", "/** Local documentation. */ class Local {} return \"old\";"), "p/E.java").root).isEqualTo(before.root);
        var documented = SOURCE.replace("String method", "/** First. */ String method");
        assertThat(project(digest, documented.replace("First.", "Second."), "p/E.java").root)
                .isNotEqualTo(project(digest, documented, "p/E.java").root);
    }

    @ParameterizedTest @MethodSource("digests")
    void everyObservableDeclarationChangeMovesTheProjection(Digest digest) throws Exception {
        var before = project(digest, SOURCE, "p/E.java");
        for (var changed : List.of(
                SOURCE.replace("@Entity(\"x\")", "@Entity(\"y\")"),
                SOURCE.replace("@Use(1)", "@Use(3)"),
                SOURCE.replace("@Use(2)", "@Use(4)"),
                SOURCE.replace("String input", "String renamed"),
                SOURCE.replace("throws Exception", "throws java.io.IOException"),
                SOURCE.replace("final int C = 1", "final int C = 2"),
                SOURCE.replace("String method", "Object method"),
                SOURCE.replace("int value;", "long value;"),
                SOURCE.replace("class Nested", "static class Nested"),
                SOURCE.replace("class Nested { int value; }", ""),
                SOURCE.replace("class Nested", "void added() {} class Nested"))) {
            var after = project(digest, changed, "p/E.java");
            assertThat(after.root.hash()).as(changed).isNotEqualTo(before.root.hash());
            assertThat(after.root.sum()).as(changed).isNotEqualTo(before.root.sum());
        }
        assertThat(project(digest, SOURCE, "moved/E.java").root).isNotEqualTo(before.root);
    }

    @ParameterizedTest @MethodSource("digests")
    void domainMembershipIsExactlyAnnotatedElements(Digest digest) throws Exception {
        var before = project(digest, SOURCE, "p/E.java");
        assertThat(before.entries).hasSize(1);
        var after = project(digest, SOURCE.replace("class Other", "@Entity class Other"), "p/E.java");
        assertThat(after.entries).hasSize(2);
        var difference = Diff.trees(digest, before.root, after.root, h -> {
            var value = before.store.get(MachineStore.nodeKey(h));
            return value == null ? after.store.get(MachineStore.nodeKey(h)) : value;
        });
        assertThat(difference.removed()).isEmpty();
        assertThat(difference.added()).hasSize(1);
        var sums = new ContentTree(digest).sums();
        assertThat(after.root.sum()).isEqualTo(sums.add(before.root.sum(), difference.added().getFirst().h()));
    }

    private record Domain(Root root, List<Entry> entries, InMemoryLocalStore store) {}

    @ParameterizedTest @MethodSource("digests")
    void recordComponentAndBackingFieldHaveDistinctKeys(Digest digest) throws Exception {
        var file = dir.resolve("R.java");
        Files.writeString(file, "record R(int value) {}");
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("R.java", file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var projection = new ProcessorElementProjection(compiled.elements, compiled.types);
            var type = compiled.units.getFirst().declared.getFirst();
            var component = type.getRecordComponents().getFirst();
            var field = type.getEnclosedElements().stream().filter(e -> e.getKind() == javax.lang.model.element.ElementKind.FIELD).findFirst().orElseThrow();
            assertThat(component.getSimpleName()).isEqualTo(field.getSimpleName());
            assertThat(projection.key(component)).isNotEqualTo(projection.key(field));
            var decoded = dev.jvmd.index.layer.local.ProcessorDeclaration.decode(projection.of(component, "R.java")).declaration();
            assertThat(decoded.kind()).isEqualTo(javax.lang.model.element.ElementKind.RECORD_COMPONENT);
            assertThat(decoded.key().bytes()).isEqualTo(projection.key(component));
            var mutable = decoded.key().bytes(); mutable[0] ^= 1;
            assertThat(decoded.key().bytes()).isEqualTo(projection.key(component));
            assertThat(decoded).isEqualTo(dev.jvmd.index.layer.local.ProcessorDeclaration.decode(projection.of(component, "R.java")).declaration());
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void packageElementKeysIncludeTheQualifiedName(Digest digest) throws Exception {
        var left = dir.resolve("a/same/One.java");
        var right = dir.resolve("b/same/Two.java");
        Files.createDirectories(left.getParent());
        Files.createDirectories(right.getParent());
        Files.writeString(left, "package a.same; class One {}");
        Files.writeString(right, "package b.same; class Two {}");
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("a/same/One.java", left), new HeaderCompiler.Source("b/same/Two.java", right)),
                List.of(), Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var projection = new ProcessorElementProjection(compiled.elements, compiled.types);
            assertThat(projection.key(compiled.elements.getPackageElement("a.same"))).isNotEqualTo(projection.key(compiled.elements.getPackageElement("b.same")));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void nestedAnnotationExplicitPresenceAndDefaultsRemainObservable(Digest digest) throws Exception {
        String source = "package p; @interface Nested { int value() default 1; } @interface Entity { Nested nested(); } @Entity(nested=@Nested) class E {}";
        var before = project(digest, source, "p/E.java");
        assertThat(project(digest, source.replace("nested=@Nested)", "nested=@Nested(1))"), "p/E.java").root).isNotEqualTo(before.root);
        assertThat(project(digest, source.replace("default 1", "default 2"), "p/E.java").root).isNotEqualTo(before.root);
    }

    @ParameterizedTest @MethodSource("digests")
    void annotationContainerOriginIsPartOfTheProcessorProjection(Digest digest) throws Exception {
        String source="""
                package p;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.SOURCE) @Repeatable(Entity.class) @interface Tag {}
                @Retention(RetentionPolicy.SOURCE) @interface Entity {Tag[] value();}
                @Tag @Tag class E {}
                """;
        var implicit=project(digest,source,"p/E.java");
        var explicit=project(digest,source.replace("@Tag @Tag class", "@Entity({@Tag,@Tag}) class"),"p/E.java");
        var before=dev.jvmd.index.layer.local.ProcessorDeclaration.decode(implicit.entries.getFirst().value()).declaration().annotations().getFirst();
        var after=dev.jvmd.index.layer.local.ProcessorDeclaration.decode(explicit.entries.getFirst().value()).declaration().annotations().getFirst();
        assertThat(after.explicit()).isEqualTo(before.explicit());assertThat(after.effective()).isEqualTo(before.effective());
        assertThat(before.origin()).isEqualTo(javax.lang.model.util.Elements.Origin.MANDATED);
        assertThat(after.origin()).isEqualTo(javax.lang.model.util.Elements.Origin.EXPLICIT);
        assertThat(explicit.root.hash()).isNotEqualTo(implicit.root.hash());
        assertThat(explicit.root.sum()).isNotEqualTo(implicit.root.sum());
    }

    @ParameterizedTest @MethodSource("digests")
    void compactConstructorBodiesAreExcludedButTheirPublicModelStateIsRetained(Digest digest) throws Exception {
        String source="package p; @interface Entity {} @Entity record E(int value) { E {if(value<0)throw new IllegalArgumentException();} }";
        var compact=project(digest,source,"p/E.java");
        assertThat(project(digest,source.replace("value<0","value<1"),"p/E.java").root).isEqualTo(compact.root);
        var explicit=project(digest,source.replace("E {if(value<0)throw new IllegalArgumentException();}","E(int value) {this.value=value;}"),"p/E.java");
        var declaration=dev.jvmd.index.layer.local.ProcessorDeclaration.decode(compact.entries.getFirst().value()).declaration();
        var members=((dev.jvmd.index.layer.local.ProcessorDeclaration.TypeDeclaration)declaration.detail()).enclosed();
        var constructor=(dev.jvmd.index.layer.local.ProcessorDeclaration.Executable)members.stream()
                .filter(member->member.kind()==javax.lang.model.element.ElementKind.CONSTRUCTOR).findFirst().orElseThrow().detail();
        assertThat(constructor.compactConstructor()).isTrue();assertThat(constructor.canonicalConstructor()).isTrue();
        assertThat(constructor.parameters().getFirst().origin()).isEqualTo(javax.lang.model.util.Elements.Origin.MANDATED);
        assertThat(explicit.root.hash()).isNotEqualTo(compact.root.hash());
        assertThat(explicit.root.sum()).isNotEqualTo(compact.root.sum());
    }

    @ParameterizedTest @MethodSource("digests")
    void commentKindIsObservableEvenWhenTheCommentTextIsEqual(Digest digest) throws Exception {
        var traditional=project(digest,SOURCE.replace("@Entity(\"x\")","/**text*/ @Entity(\"x\")"),"p/E.java");
        var markdown=project(digest,SOURCE.replace("@Entity(\"x\")","///text\n@Entity(\"x\")"),"p/E.java");
        var before=dev.jvmd.index.layer.local.ProcessorDeclaration.decode(traditional.entries.getFirst().value()).declaration();
        var after=dev.jvmd.index.layer.local.ProcessorDeclaration.decode(markdown.entries.getFirst().value()).declaration();
        assertThat(after.docComment()).isEqualTo(before.docComment());
        assertThat(after.docCommentKind()).isNotEqualTo(before.docCommentKind());
        assertThat(markdown.root.hash()).isNotEqualTo(traditional.root.hash());
        assertThat(markdown.root.sum()).isNotEqualTo(traditional.root.sum());
    }

    private Domain project(Digest digest, String source, String sourcePath) throws Exception {
        var file = dir.resolve(sourcePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        var entries = new ArrayList<Entry>();
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source(sourcePath, file)), List.of(),
                Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var projection = new ProcessorElementProjection(compiled.elements, compiled.types);
            for (var type : compiled.units.getFirst().declared) {
                if (type.getAnnotationMirrors().stream().anyMatch(a -> a.getAnnotationType().toString().equals("p.Entity"))) {
                    var key = projection.key(type);
                    var value = projection.of(type, sourcePath);
                    entries.add(new Entry(key, value, digest.hash(key, value)));
                }
            }
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        var store = new InMemoryLocalStore();
        var root = new ContentTree(digest).build(entries, store);
        store.flush();
        return new Domain(root, List.copyOf(entries), store);
    }
}
