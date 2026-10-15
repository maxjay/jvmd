package dev.jvmd.tests.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Stage1;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.AnnotationLeaf;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.MachineTree;
import dev.jvmd.index.layer.machine.Stubs;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stubs with the annotation tree carry exactly the annotations the class files retain, and nothing else of a tail moves their keys. */
@Tag("phase-3")
class StubAnnotationsTest {
    @TempDir Path temp;

    private static final Map<String, String> SOURCES = Map.of(
            "p/E.java", "package p; public enum E { A, B }",
            "p/Cl.java", "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface Cl { long l() default -1; }",
            "p/Rt.java", """
                    package p;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD}) @Documented @Inherited
                    public @interface Rt {
                        int i() default 1; String s() default ""; Class<?> c() default Object.class; E e() default E.A; Cl n() default @Cl;
                        String[] arr() default {}; double d() default 0; char ch() default 'x'; byte b() default 0;
                    }
                    """,
            "p/Subject.java", """
                    package p;
                    @Rt(i = -1, s = "s", c = int[].class, e = E.B, n = @Cl(l = 7), arr = {"x", "y"}, d = Double.NaN, ch = '\\uffff', b = -1)
                    @Cl @Deprecated(forRemoval = true, since = "9")
                    public class Subject {
                        @Rt(c = void.class) @Cl public int f;
                        @Deprecated public static final String K = "k";
                        @Rt @SafeVarargs public final void m(String... xs) { }
                        public void plain(int named) { }
                        @Cl private int hidden;
                    }
                    """,
            "p/Plain.java", "package p; public class Plain { public void f(int first, String second) { } }");

    private record Indexed(Map<String, byte[]> classes, List<Stubs.Stub> stubs, Map<String, Identity> keys) { }

    /** Compiles, boots MACHINE over the jar, and synthesises its stubs with the annotation tree, recording each type's key. */
    private Indexed index(String name, String... options) throws Exception {
        var classes = BootFixtures.compile(temp.resolve(name + "-work"), SOURCES, options);
        var jar = BootFixtures.pack(temp.resolve(name + ".jar"), MachineColdBootTest.T1, classes);
        var store = new InMemoryMachineStore();
        var digest = Sha256.INSTANCE;
        var tree = new ContentTree(digest);
        new Stage1(digest, tree, 25, 1, ClassFacts::of).run(store, List.of(Enumerate.jar(name + ".jar", jar)));
        var path = MachineTree.decodePath(store.get(MachineStore.pathKey(name + ".jar")), digest.width());
        var leaf = MachineLeaf.decode(store.get(MachineStore.leafKey(path.k())), digest.width());
        var annotations = AnnotationLeaf.decode(store.get(MachineStore.annotationLeafKey(path.a())), digest.width()).annotations();
        var keys = new HashMap<String, Identity>();
        var stubs = Stubs.stubs(digest, tree, leaf, path.a(), annotations, h -> store.get(MachineStore.nodeKey(h)), new Stubs.Cache() {
            @Override public byte[] list(Identity k) { return null; }
            @Override public void putList(Identity k, byte[] value) { for (var ref : Stubs.decodeList(value, digest.width())) keys.put(ref.internalName(), ref.stKey()); }
            @Override public byte[] type(Identity stKey) { return null; }
            @Override public void putType(Identity stKey, byte[] value) { }
        });
        return new Indexed(classes, stubs, keys);
    }

    @Test void aStubCarriesExactlyTheAnnotationsItsClassFileRetains() throws Exception {
        var indexed = index("annotated", "-parameters");
        int compared = 0;
        for (var stub : indexed.stubs()) {
            var real = ClassFile.of().parse(indexed.classes().get(stub.internalName() + ".class"));
            var synthesised = ClassFile.of().parse(stub.bytes());
            compared += compare(stub.internalName(), real, synthesised);
        }
        assertThat(compared).as("annotated elements compared").isGreaterThanOrEqualTo(6);
    }

    @Test void onlyEmittedAnnotationsMoveAStubKey() throws Exception {
        var withNames = index("with-names", "-parameters");
        var without = index("without-names");
        assertThat(withNames.keys().get("p/Plain")).as("parameter names are in the tail, not in the stub").isEqualTo(without.keys().get("p/Plain"));
        assertThat(withNames.keys().get("p/Subject")).isEqualTo(without.keys().get("p/Subject"));
        assertThat(withNames.keys().get("p/Subject")).as("emitted annotations are in the key").isNotEqualTo(withNames.keys().get("p/Plain"));
    }

    /** The visible and invisible annotations of the class and of each member the stub has, as comparable text; how many had any. */
    private static int compare(String owner, ClassModel real, ClassModel stub) {
        int compared = 0;
        assertThat(annotations(stub.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())))
                .as("%s visible", owner).isEqualTo(annotations(real.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())));
        assertThat(annotations(stub.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())))
                .as("%s invisible", owner).isEqualTo(annotations(real.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())));
        if (real.findAttribute(Attributes.runtimeVisibleAnnotations()).isPresent() || real.findAttribute(Attributes.runtimeInvisibleAnnotations()).isPresent()) compared++;
        for (var field : stub.fields()) {
            var original = real.fields().stream().filter(f -> f.fieldName().equalsString(field.fieldName().stringValue())).findFirst().orElseThrow();
            assertThat(annotations(field.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())))
                    .as("%s.%s visible", owner, field.fieldName()).isEqualTo(annotations(original.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())));
            assertThat(annotations(field.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())))
                    .as("%s.%s invisible", owner, field.fieldName()).isEqualTo(annotations(original.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())));
            if (original.findAttribute(Attributes.runtimeVisibleAnnotations()).isPresent() || original.findAttribute(Attributes.runtimeInvisibleAnnotations()).isPresent()) compared++;
        }
        for (var method : stub.methods()) {
            var original = real.methods().stream().filter(m -> m.methodName().equalsString(method.methodName().stringValue())
                    && m.methodType().equalsString(method.methodType().stringValue())).findFirst().orElseThrow();
            assertThat(annotations(method.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())))
                    .as("%s.%s visible", owner, method.methodName()).isEqualTo(annotations(original.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())));
            assertThat(annotations(method.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())))
                    .as("%s.%s invisible", owner, method.methodName()).isEqualTo(annotations(original.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of())));
            if (original.findAttribute(Attributes.runtimeVisibleAnnotations()).isPresent() || original.findAttribute(Attributes.runtimeInvisibleAnnotations()).isPresent()) compared++;
        }
        return compared;
    }

    private static List<String> annotations(List<Annotation> list) {
        var out = new ArrayList<String>();
        for (var a : list) out.add(text(a));
        return out;
    }

    private static String text(Annotation a) {
        var elements = new ArrayList<String>();
        for (var e : a.elements()) elements.add(e.name().stringValue() + "=" + text(e.value()));
        return a.className().stringValue() + elements;
    }

    private static String text(AnnotationValue v) {
        return switch (v) {
            case AnnotationValue.OfConstant c -> v.tag() + ":" + c.resolvedValue();
            case AnnotationValue.OfEnum e -> "e:" + e.className().stringValue() + "." + e.constantName().stringValue();
            case AnnotationValue.OfClass c -> "c:" + c.className().stringValue();
            case AnnotationValue.OfAnnotation n -> "@" + text(n.annotation());
            case AnnotationValue.OfArray arr -> arr.values().stream().map(StubAnnotationsTest::text).toList().toString();
        };
    }
}
