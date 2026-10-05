package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("phase-3")
class StubProjectionTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }

    @ParameterizedTest @MethodSource("digests")
    void safeVarargsPreservesCallSiteUncheckedDiagnostics(Digest digest) throws Exception {
        for (boolean safe : new boolean[] {false, true}) {
            String fixture = "varargs-" + safe;
            var classes = Stage2Support.compile(dir.resolve(fixture), Map.of("p/K.java",
                    "package p; public class K { " + (safe ? "@SafeVarargs " : "") + "public static <T> void call(T... values) {} }"), List.of(), List.of());
            String client = "class Use { void f(java.util.List<String> xs) { p.K.call(xs); } }";
            var real = diagnostics(dir.resolve(fixture + "-real"), classes, client, "-Xlint:unchecked");
            var stub = diagnostics(dir.resolve(fixture + "-stub"), stubs(digest, classes), client, "-Xlint:unchecked");
            assertThat(stub).isEqualTo(real);
            if (safe) assertThat(real).isEmpty();
            else assertThat(real).anyMatch(d -> d.contains("compiler.warn.unchecked.generic.array.creation"));
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void dollarNamesSurviveTheInnerClassesProjection(Digest digest) throws Exception {
        for (boolean nested : new boolean[] {false, true}) {
            String fixture = "dollar-" + nested;
            String source = nested ? "public static class Foo { public static class Bar {} }" : "public static class Foo$Bar {}";
            var classes = Stage2Support.compile(dir.resolve(fixture), Map.of(
                    "p/K.java", "package p; public class K { " + source + " }",
                    "p/Top$Level.java", "package p; public class Top$Level {}"), List.of(), List.of());
            var projected = stubs(digest, classes);
            var original = ClassFacts.of(digest, classes.get("p/K$Foo$Bar.class"), "p/K$Foo$Bar");
            var stub = ClassFacts.of(digest, projected.get("p/K$Foo$Bar.class"), "p/K$Foo$Bar");
            assertThat(Res.Type.decode(original.facts().getFirst().res()).innerName()).isEqualTo(nested ? "Bar" : "Foo$Bar");
            assertThat(stub.facts().getFirst().res()).isEqualTo(original.facts().getFirst().res());
            String client = "class Use { p.Top$Level top; p.K." + (nested ? "Foo.Bar" : "Foo$Bar") + " member; }";
            assertThat(diagnostics(dir.resolve(fixture + "-real"), classes, client, "-Xlint:unchecked")).isEmpty();
            assertThat(diagnostics(dir.resolve(fixture + "-stub"), projected, client, "-Xlint:unchecked")).isEmpty();
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void deprecatedAttributeSurvivesWithoutAnAnnotation(Digest digest) throws Exception {
        var classes = Stage2Support.compile(dir.resolve("deprecated"), Map.of("p/K.java",
                "package p; @Deprecated(since=\"1\") public class K {}"), List.of(), List.of());
        var cf = ClassFile.of();
        byte[] stripped = cf.transformClass(cf.parse(classes.get("p/K.class")), (b, e) -> {
            if (!(e instanceof RuntimeVisibleAnnotationsAttribute)) b.with(e);
        });
        var attrOnly = Map.of("p/K.class", stripped);
        var projected = stubs(digest, attrOnly);
        var parsed = ClassFacts.of(digest, projected.get("p/K.class"), "p/K");
        var warning = Res.Type.decode(parsed.facts().getFirst().res()).warnings();
        assertThat(warning.deprecatedAttribute()).isTrue();
        assertThat(warning.deprecation()).isZero();
        var real = diagnostics(dir.resolve("deprecated-real"), attrOnly, "class Use { p.K field; }", "-Xlint:deprecation");
        assertThat(real).anyMatch(d -> d.contains("compiler.warn.has.been.deprecated"));
        assertThat(diagnostics(dir.resolve("deprecated-stub"), projected, "class Use { p.K field; }", "-Xlint:deprecation")).isEqualTo(real);
    }

    private Map<String, byte[]> stubs(Digest digest, Map<String, byte[]> classes) throws Exception {
        var tree = new ContentTree(digest);
        var store = new InMemoryLocalStore();
        var builder = new LeafBuilder(tree, store);
        var facts = new ArrayList<Fact>();
        for (var c : classes.entrySet()) {
            var parsed = ClassFacts.of(digest, c.getValue(), c.getKey().substring(0, c.getKey().length() - 6));
            facts.addAll(parsed.facts());
            builder.edges(parsed.edges());
        }
        facts.sort((a, b) -> Arrays.compareUnsigned(a.m(), b.m()));
        facts.forEach(builder::add);
        builder.seal();
        var leaf = builder.build();
        store.flush();
        assertThat(tree.rangeSum(leaf.nHash(), h -> store.get(MachineStore.nodeKey(h)), new byte[0])).isEqualTo(leaf.r());
        var result = new java.util.TreeMap<String, byte[]>();
        for (var stub : Stubs.stubs(digest, tree, leaf, h -> store.get(MachineStore.nodeKey(h)), Stubs.Cache.NONE))
            result.put(stub.internalName() + ".class", stub.bytes());
        return result;
    }

    private List<String> diagnostics(Path work, Map<String, byte[]> dependencies, String client, String lint) throws Exception {
        var cp = work.resolve("cp");
        for (var c : dependencies.entrySet()) {
            var path = cp.resolve(c.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, c.getValue());
        }
        var out = Files.createDirectories(work.resolve("out"));
        var file = work.resolve("Use.java");
        Files.writeString(file, client);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var fm = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            compiler.getTask(null, fm, diagnostics, List.of("-proc:none", lint, "-classpath", cp.toString(), "-d", out.toString()),
                    null, fm.getJavaFileObjects(file)).call();
        }
        return diagnostics.getDiagnostics().stream().map(d -> d.getKind() + " " + d.getCode() + " " + d.getPosition() + " " + d.getMessage(java.util.Locale.ROOT)).toList();
    }
}
