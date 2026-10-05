package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Fact;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.Res;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class TailStrippingTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    record Projection(MachineLeaf leaf, Identity a) { }

    @ParameterizedTest @MethodSource("digests")
    void rewritingAwayEveryTailAttributePreservesTheApi(Digest digest) throws Exception {
        var fixtures = new ArrayList<Map<String, String>>();
        fixtures.add(Fixtures.rich());
        fixtures.add(Map.of("p/K.java", """
                package p;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE_USE, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
                @interface A { int value() default 1; }
                @Deprecated(forRemoval=true) public class K<@A T> {
                    @SafeVarargs public static <T> void f(@A T... input) {}
                    public record R(@A String text) {}
                }
                """));
        int index = 0;
        for (var fixture : fixtures) {
            var classes = Stage2Support.compile(dir.resolve("fixture" + index++), fixture, List.of("-parameters"), List.of());
            var stripped = new LinkedHashMap<String, byte[]>();
            classes.forEach((name, bytes) -> stripped.put(name, strip(bytes)));
            var before = project(digest, classes);
            var after = project(digest, stripped);
            assertThat(after.leaf.encode()).isEqualTo(before.leaf.encode());
            assertThat(after.a).isNotEqualTo(before.a);
        }
    }

    private Projection project(Digest digest, Map<String, byte[]> classes) throws Exception {
        var store = new InMemoryLocalStore();
        var builder = new LeafBuilder(new ContentTree(digest), store);
        var facts = new ArrayList<Fact>();
        for (var c : classes.entrySet()) {
            var result = ClassFacts.of(digest, c.getValue(), c.getKey().substring(0, c.getKey().length() - 6));
            facts.addAll(result.facts());
            builder.edges(result.edges());
        }
        facts.sort((a, b) -> Arrays.compareUnsigned(a.m(), b.m()));
        facts.forEach(builder::add);
        builder.seal();
        return new Projection(builder.build(), builder.a());
    }

    /** Independent class-file rewrite, including record-component and parameter metadata. */
    private static byte[] strip(byte[] bytes) {
        var cf = ClassFile.of();
        var transform = ClassTransform.transformingMethods((builder, element) -> {
            if (element instanceof Attribute<?> a) { var kept = strip(a); if (kept != null) builder.with((MethodElement) kept); }
            else builder.with(element);
        }).andThen(ClassTransform.transformingFields((builder, element) -> {
            if (element instanceof Attribute<?> a) { var kept = strip(a); if (kept != null) builder.with((FieldElement) kept); }
            else builder.with(element);
        })).andThen((builder, element) -> {
            if (element instanceof Attribute<?> a) { var kept = strip(a); if (kept != null) builder.with((ClassElement) kept); }
            else builder.with(element);
        });
        return cf.transformClass(cf.parse(bytes), transform);
    }

    private static Attribute<?> strip(Attribute<?> attribute) {
        return switch (attribute) {
            case RuntimeVisibleTypeAnnotationsAttribute _, RuntimeInvisibleTypeAnnotationsAttribute _,
                 RuntimeVisibleParameterAnnotationsAttribute _, RuntimeInvisibleParameterAnnotationsAttribute _, MethodParametersAttribute _ -> null;
            case RuntimeVisibleAnnotationsAttribute a -> RuntimeVisibleAnnotationsAttribute.of(a.annotations().stream().filter(TailStrippingTest::resolutionAnnotation).toList());
            case RuntimeInvisibleAnnotationsAttribute a -> RuntimeInvisibleAnnotationsAttribute.of(a.annotations().stream().filter(TailStrippingTest::resolutionAnnotation).toList());
            case RecordAttribute r -> RecordAttribute.of(r.components().stream().map(c -> RecordComponentInfo.of(c.name(), c.descriptor(),
                    c.attributes().stream().map(TailStrippingTest::strip).filter(java.util.Objects::nonNull).toList())).toList());
            default -> attribute;
        };
    }

    private static boolean resolutionAnnotation(Annotation a) {
        String name = a.className().stringValue();
        return Res.Warnings.reads(name) || Res.META_ANNOTATIONS.stream().anyMatch(m -> name.equals("Ljava/lang/annotation/" + m + ";"));
    }
}
