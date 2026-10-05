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

    @ParameterizedTest @MethodSource("digests")
    void exactMemberInnerNameParticipatesInTheOuterStubCacheIdentity(Digest digest) throws Exception {
        var classes = Stage2Support.compile(dir.resolve("renamed-inner"), Map.of("p/K.java",
                "package p; public class K { public static class Foo {} }"), List.of(), List.of());
        var changed = new java.util.TreeMap<String, byte[]>();
        var cf = ClassFile.of();
        for (var entry : classes.entrySet()) changed.put(entry.getKey(), cf.transformClass(cf.parse(entry.getValue()), (builder, element) -> {
            if (element instanceof java.lang.classfile.attribute.InnerClassesAttribute inner) {
                builder.with(java.lang.classfile.attribute.InnerClassesAttribute.of(inner.classes().stream().map(info ->
                        info.innerClass().asInternalName().equals("p/K$Foo")
                                ? java.lang.classfile.attribute.InnerClassInfo.of(info.innerClass().asSymbol(), info.outerClass().map(c -> c.asSymbol()),
                                    java.util.Optional.of("Renamed"), info.flagsMask()) : info).toList()));
            } else builder.with(element);
        }));
        var originalType = Res.Type.decode(ClassFacts.of(digest, classes.get("p/K$Foo.class"), "p/K$Foo").facts().getFirst().res());
        var changedType = Res.Type.decode(ClassFacts.of(digest, changed.get("p/K$Foo.class"), "p/K$Foo").facts().getFirst().res());
        assertThat(changedType.access()).isEqualTo(originalType.access());
        assertThat(changedType.innerName()).isEqualTo("Renamed");
        assertThat(ClassFacts.of(digest, changed.get("p/K.class"), "p/K").facts().stream().map(Fact::h).toList())
                .as("the outer type's own resolution facts did not change")
                .isEqualTo(ClassFacts.of(digest, classes.get("p/K.class"), "p/K").facts().stream().map(Fact::h).toList());
        var lists = new java.util.HashMap<dev.jvmd.core.hash.Identity, byte[]>();
        var types = new java.util.HashMap<dev.jvmd.core.hash.Identity, byte[]>();
        var refs = new ArrayList<Stubs.Ref>();
        var cache = new Stubs.Cache() {
            @Override public byte[] list(dev.jvmd.core.hash.Identity key) { return lists.get(key); }
            @Override public void putList(dev.jvmd.core.hash.Identity key, byte[] value) {
                lists.put(key, value); refs.clear(); refs.addAll(Stubs.decodeList(value, digest.width()));
            }
            @Override public byte[] type(dev.jvmd.core.hash.Identity key) { return types.get(key); }
            @Override public void putType(dev.jvmd.core.hash.Identity key, byte[] value) { types.put(key, value); }
        };
        var before = stubs(digest, classes, cache);
        var oldKey = refs.stream().filter(r -> r.internalName().equals("p/K")).findFirst().orElseThrow().stKey();
        var expected = stubs(digest, changed);
        assertThat(expected.get("p/K.class")).isNotEqualTo(before.get("p/K.class"));
        var after = stubs(digest, changed, cache);
        var newKey = refs.stream().filter(r -> r.internalName().equals("p/K")).findFirst().orElseThrow().stKey();
        assertThat(newKey).as("same binary name and flags, different exact inner name").isNotEqualTo(oldKey);
        assertThat(after.get("p/K.class")).isEqualTo(expected.get("p/K.class"));
    }

    @ParameterizedTest @MethodSource("digests")
    void stubMemberIdentityBindsTheNullableExactName(Digest digest) {
        var own = digest.hash(new byte[] {1});
        var named = Stubs.stKey(digest, "p/K", own, List.of(new Stubs.Member("p/K$Foo", "Foo", 9)));
        var renamed = Stubs.stKey(digest, "p/K", own, List.of(new Stubs.Member("p/K$Foo", "Renamed", 9)));
        var absent = Stubs.stKey(digest, "p/K", own, List.of(new Stubs.Member("p/K$Foo", null, 9)));
        assertThat(named).isNotEqualTo(renamed).isNotEqualTo(absent);
        assertThat(renamed).isNotEqualTo(absent);
    }

    private Map<String, byte[]> stubs(Digest digest, Map<String, byte[]> classes) throws Exception {
        return stubs(digest, classes, Stubs.Cache.NONE);
    }

    private Map<String, byte[]> stubs(Digest digest, Map<String, byte[]> classes, Stubs.Cache cache) throws Exception {
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
        for (var stub : Stubs.stubs(digest, tree, leaf, h -> store.get(MachineStore.nodeKey(h)), cache))
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
