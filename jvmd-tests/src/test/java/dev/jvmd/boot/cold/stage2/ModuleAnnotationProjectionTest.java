package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.local.SourceFacts;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Fact;
import dev.jvmd.index.layer.machine.Res;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Module declarations use the same annotation and warning projections as binary facts. */
@Tag("phase-3")
class ModuleAnnotationProjectionTest {
    @TempDir Path dir;
    private int sequence;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void nestedRepeatableAndPrimitiveAnnotationValuesMatchBinaryFacts(Digest digest) throws Exception {
        compare(digest, Map.of("module-info.java", """
                @p.Label(value=@p.Label.Nested("\\uD800|\\0|café"),
                    classes={p.Types.Inner.class, int[].class}, choice=p.Label.Choice.FIRST,
                    longs={-7L, 999999999999L}, doubles={-0.0, 1.25, Double.NaN}, flag=true, ch='é',
                    integer=-42, small=-7, tiny=7, floats={-0.0f, Float.NaN, Float.POSITIVE_INFINITY})
                @p.Tags.Tag("first") @p.Tags.Tag("second")
                module example { exports p; }
                """, "p/Label.java", """
                package p;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.MODULE)
                public @interface Label {
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @interface Nested { String value(); }
                    enum Choice { FIRST, SECOND }
                    Nested value(); Class<?>[] classes(); Choice choice();
                    long[] longs(); double[] doubles(); boolean flag(); char ch();
                    int integer(); short small(); byte tiny(); float[] floats();
                }
                """, "p/Tags.java", """
                package p;
                public class Tags {
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.MODULE)
                    @java.lang.annotation.Repeatable(Many.class)
                    public @interface Tag { String value(); }
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.MODULE)
                    public @interface Many { Tag[] value(); }
                }
                """, "p/Types.java", "package p; public class Types { public static class Inner {} }"));
    }

    @ParameterizedTest @MethodSource("digests")
    void annotationValuesAndSourceRetentionRespectTheirSeparateProjections(Digest digest) throws Exception {
        var sources = new TreeMap<>(Map.of("module-info.java", "@p.Label(\"one\") @Deprecated(since=\"first\") module example { exports p; }",
                "p/Label.java", """
                package p;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.MODULE)
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Label { String value(); }
                """));
        var first = compare(digest, sources);
        sources.put("module-info.java", sources.get("module-info.java").replace("one", "two").replace("first", "second"));
        var annotation = compare(digest, sources);
        assertThat(annotation.h()).isEqualTo(first.h());
        assertThat(annotation.aEntry(digest).h()).isNotEqualTo(first.aEntry(digest).h());
        sources.put("module-info.java", sources.get("module-info.java").replace("since=\"second\"", "since=\"second\", forRemoval=true"));
        assertThat(compare(digest, sources).h()).isNotEqualTo(first.h());
        sources.put("module-info.java", "@p.Label(\"one\") module example { exports p; }");
        sources.put("p/Label.java", sources.get("p/Label.java").replace("RetentionPolicy.RUNTIME", "RetentionPolicy.SOURCE"));
        var source = compare(digest, sources);
        sources.put("module-info.java", sources.get("module-info.java").replace("one", "two"));
        var sourceEdit = compare(digest, sources);
        assertThat(sourceEdit.h()).isEqualTo(source.h());
        assertThat(sourceEdit.tail()).isEmpty();
        assertThat(source.tail()).isEmpty();
    }

    private Fact compare(Digest digest, Map<String, String> sources) throws Exception {
        var root = dir.resolve("case-" + sequence++);
        var binary = Stage2Support.compile(root, sources, List.of(), List.of());
        var expected = ClassFacts.of(digest, binary.get("module-info.class"), "module-info");
        var expectedFact = expected.facts().getFirst();
        var module = Res.Type.decode(expectedFact.res()).module();
        var inputs = new ArrayList<HeaderCompiler.Source>();
        for (var name : new java.util.TreeSet<>(sources.keySet())) inputs.add(new HeaderCompiler.Source(name, root.resolve("src/" + name)));
        try (var compiled = HeaderCompiler.compile(inputs, List.of(), Stage2Support.JDK, Stage2Support.FEATURE, List.of(), digest)) {
            var unit = compiled.units.stream().filter(u -> u.module != null).findFirst().orElseThrow();
            var actual = new SourceFacts(digest, compiled.elements, compiled.types, false).ofModule(unit.module, unit.moduleElement,
                    null, name -> module.requires().stream().filter(r -> r.module().equals(name)).findFirst().orElseThrow().version(), unit.moduleTypes::get);
            assertThat(actual.faults()).isEmpty();
            var fact = actual.facts().getFirst();
            assertThat(Res.Type.decode(fact.res())).as("resolved native module metadata").isEqualTo(Res.Type.decode(expectedFact.res()));
            assertThat(fact.res()).isEqualTo(expectedFact.res());
            assertThat(fact.tail()).isEqualTo(expectedFact.tail());
            assertThat(fact.h()).isEqualTo(expectedFact.h());
            assertThat(actual.edges()).usingRecursiveComparison().isEqualTo(expected.edges());
            assertThat(dev.jvmd.boot.cold.stage3.ModuleDescriptor.emit(Res.Type.decode(fact.res()), fact.tail(),
                    new dev.jvmd.boot.cold.stage3.ModuleDescriptor.Options(Stage2Support.FEATURE + 44, true)))
                    .as("native annotated descriptor bytes").isEqualTo(binary.get("module-info.class"));
            return fact;
        }
    }
}
