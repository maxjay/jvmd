package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.SourceFacts;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.Fact;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/** The annotation projection is the class-file projection, independent of executable bodies. */
@Tag("phase-3")
class SourceAnnotationProjectionTest {
    @TempDir Path dir;
    static Stream<Object[]> cases() {
        return Stream.of(Sha256.INSTANCE, new Digests.Sha3()).flatMap(d -> Stream.of(true, false).map(parameters -> new Object[] {d, parameters}));
    }

    @ParameterizedTest @MethodSource("cases")
    void declarationTypePathsParametersAndRecordsMatchBinary(Digest digest, boolean parameters) throws Exception {
        compare(digest, parameters, false, """
                package p;
                import java.lang.annotation.*;
                import java.util.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface A { int value(); }
                @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE_USE) @interface B {}
                @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE_USE) @interface C {}
                @Retention(RetentionPolicy.CLASS) @Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT}) @interface P { String value(); }
                public class K<@A(1) T extends @A(2) Number & @A(3) Comparable<T>> extends @A(4) Object {
                    public @A(5) List<@A(6) ? extends @A(7) @B @C String @A(8) []> @A(9) [] @B [] field;
                    public <@A(10) R extends @A(11) Object> @A(12) R method(
                        @A(13) K<T> this, @P("parameter") @A(14) R input) throws @A(15) Exception { return input; }
                    public static record Rec(@P("component") @A(16) String name) {}
                    public class Inner {
                        public @A(17) Inner(@A(18) K<T> K.this, @P("inner") @B String input) throws @B Exception {}
                        public class Deep { public Deep(@A(19) String input) {} }
                    }
                    private class PrivateInner { public PrivateInner(String input) {} }
                    public enum E { FIRST, SECOND }
                    public void body() { @A(20) String local = ""; class Local {} }
                }
                """);
    }

    @ParameterizedTest @MethodSource("cases")
    void exactDollarNamesAndWarningStatesMatchBinary(Digest digest, boolean parameters) throws Exception {
        compare(digest, parameters, false, """
                package p;
                @Deprecated(since="1", forRemoval=false) public class K {
                    public static class Foo$Bar {}
                    public static class Other { public static class Bar {} }
                    @Deprecated(since="2", forRemoval=true) public int field;
                    @Deprecated(since="3") @SafeVarargs public static <T> void call(T... values) {}
                }
                class Top$Level {}
                """);
    }

    @ParameterizedTest @MethodSource("cases")
    void sourceExtractionDoesNotAttributeBodies(Digest digest, boolean parameters) throws Exception {
        compare(digest, parameters, true, """
                package p;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @interface A {}
                public class K { public @A String method(@A String input) { return input; } }
                """);
    }

    @ParameterizedTest @MethodSource("cases")
    void javaStringsInConstantsDefaultsAndAnnotationPathsMatchBinary(Digest digest, boolean parameters) throws Exception {
        String text = "\\uD800|\\uDC00|\\uD83D\\uDE00|\\0|\\n|café";
        compare(digest, parameters, false, """
                package p;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE_USE,ElementType.RECORD_COMPONENT,ElementType.PARAMETER})
                @interface A { String value() default "TEXT"; String[] array() default {"TEXT"}; }
                @Retention(RetentionPolicy.CLASS) @interface B { A nested(); A[] array(); }
                @B(nested=@A("TEXT"), array={@A(value="TEXT",array={"TEXT"})}) public class K {
                    public static final String CONSTANT="TEXT";
                    public @A("TEXT") String method(@A("TEXT") String parameter) { return parameter; }
                    public record R(@A("TEXT") String component) {}
                }
                """.replace("TEXT", text));
    }

    @ParameterizedTest @MethodSource("cases")
    void packageInfoEmissionPoliciesAndAnnotationsMatchBinary(Digest digest, boolean parameters) throws Exception {
        int sequence=0;
        for(var policy:List.of("legacy","nonempty","always"))for(var retention:List.of("NONE","SOURCE","CLASS","RUNTIME","DEPRECATED")) {
            var project=dir.resolve("package-"+sequence++);
            String annotation=retention.equals("NONE")?"":"@p.Label(p.Values.VALUE) "+(retention.equals("DEPRECATED")?"@Deprecated(since=\"1\",forRemoval=true) ":"");
            var sources=Map.of("p/package-info.java","/** Package documentation. */ "+annotation+"package p;",
                    "p/Label.java","package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy."+
                            (retention.equals("NONE")||retention.equals("DEPRECATED")?"SOURCE":retention)+") @java.lang.annotation.Target(java.lang.annotation.ElementType.PACKAGE) public @interface Label {String value();} class Values {static final String VALUE=\"package\";}");
            var options=new ArrayList<String>();options.add("-Xpkginfo:"+policy);if(parameters)options.add("-parameters");
            var classes=Stage2Support.compile(project,sources,options,List.of());
            var binaryFacts=new ArrayList<Fact>();var binaryEdges=new ArrayList<Entry>();
            for(var c:classes.entrySet()) {
                var parsed=ClassFacts.of(digest,c.getValue(),c.getKey().substring(0,c.getKey().length()-6));
                binaryFacts.addAll(parsed.facts());binaryEdges.addAll(parsed.edges());
            }
            var expected=project(digest,binaryFacts,binaryEdges);
            var inputs=new java.util.TreeMap<String,String>();sources.forEach((name,text)->inputs.put("app/src/main/java/"+name,text));
            Stage2Support.write(project,inputs);
            var model=dev.jvmd.index.layer.local.ProjectModel.parse(Stage2Support.model(project,
                    new Stage2Support.Mod("app","g:app:1",List.of()).withOptions(options.toArray(String[]::new))));
            var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();
            var result=new Stage2(digest,tree,Stage2Support.FEATURE,1,project,ClassFacts::of).run(store,model);
            assertThat(result.faults()).isEmpty();
            var projectKey=Stage2.projectKey(digest,model);String path="app/src/main/java/p/package-info.java";
            var row=dev.jvmd.index.layer.local.FileRow.decode(path,store.get(dev.jvmd.index.layer.local.LocalStore.fileKey(projectKey,path)),digest.width());
            if(!retention.equals("NONE"))assertThat(row.headerProof()).anyMatch(read->read.typeKey().equals("p/Values")&&read.kind()==dev.jvmd.index.layer.machine.Keys.FIELD&&read.name().equals("VALUE"));
            var leaf=dev.jvmd.index.layer.machine.MachineLeaf.decode(store.get(MachineStore.leafKey(result.leaves().get("app/main"))),digest.width());
            var annotations=dev.jvmd.index.layer.machine.AnnotationLeaf.decode(store.get(MachineStore.annotationLeafKey(result.annotations().get("app/main"))),digest.width());
            var actual=new Projection(leaf.k(),result.annotations().get("app/main"),new Root(leaf.nHash(),leaf.r(),leaf.factCount(),leaf.nLevel()),annotations.annotations(),annotations.edges(),store);
            assertThat(actual.k).as("%s/%s source/binary package-info projection",policy,retention).isEqualTo(expected.k);
            sameTree(digest,"N",actual.names,expected.names,actual,expected);
            sameTree(digest,"A",actual.annotations,expected.annotations,actual,expected);
            sameTree(digest,"EA",actual.edges,expected.edges,actual,expected);
        }
    }

    private void compare(Digest digest, boolean parameters, boolean brokenBody, String source) throws Exception {
        var options = parameters ? List.of("-parameters") : List.<String>of();
        var classes = Stage2Support.compile(dir, Map.of("p/K.java", source), options, List.of());
        if (brokenBody) java.nio.file.Files.writeString(dir.resolve("src/p/K.java"), source.replace("return input;", "return unknown();"));
        var binaryFacts = new ArrayList<Fact>();
        var binaryEdges = new ArrayList<Entry>();
        for (var c : classes.entrySet()) {
            var parsed = ClassFacts.of(digest, c.getValue(), c.getKey().substring(0, c.getKey().length() - 6));
            binaryFacts.addAll(parsed.facts());
            binaryEdges.addAll(parsed.edges());
        }
        var expected = project(digest, binaryFacts, binaryEdges);
        try (var compiled = HeaderCompiler.compile(List.of(new HeaderCompiler.Source("p/K.java", dir.resolve("src/p/K.java"))),
                List.of(), Stage2Support.JDK, Stage2Support.FEATURE, options, digest)) {
            var facts = new SourceFacts(digest, compiled.elements, compiled.types, parameters).of(compiled.units.getFirst().declared);
            assertThat(facts.faults()).isEmpty();
            if (brokenBody) {
                var owner = compiled.units.getFirst().declared.stream().filter(t -> t.getSimpleName().contentEquals("K")).findFirst().orElseThrow();
                var method = owner.getEnclosedElements().stream().filter(e -> e.getSimpleName().contentEquals("method")).findFirst().orElseThrow();
                var path = compiled.trees.getPath(method);
                var declaration = (com.sun.source.tree.MethodTree) path.getLeaf();
                var statement = (com.sun.source.tree.ReturnTree) declaration.getBody().getStatements().getFirst();
                var expression = com.sun.source.util.TreePath.getPath(path.getCompilationUnit(), statement.getExpression());
                assertThat(compiled.trees.getTypeMirror(expression)).as("body expression was never attributed").isNull();
            }
            var actual = project(digest, facts.facts(), facts.edges());
            assertThat(actual.k).as("source/binary resolution projection").isEqualTo(expected.k);
            sameTree(digest, "N", actual.names, expected.names, actual, expected);
            sameTree(digest, "A", actual.annotations, expected.annotations, actual, expected);
            sameTree(digest, "EA", actual.edges, expected.edges, actual, expected);
            assertThat(actual.a).isEqualTo(expected.a);
        }
    }

    record Projection(Identity k, Identity a, Root names, Root annotations, Root edges, InMemoryLocalStore store) {}

    private Projection project(Digest digest, List<Fact> facts, List<Entry> edges) {
        var store = new InMemoryLocalStore();
        var tree = new ContentTree(digest);
        var builder = new LeafBuilder(tree, store);
        facts.stream().sorted((a, b) -> Arrays.compareUnsigned(a.m(), b.m())).forEach(builder::add);
        builder.edges(edges);
        var k = builder.seal();
        var leaf = builder.build();
        store.flush();
        var names = new Root(leaf.nHash(), leaf.r(), leaf.factCount(), leaf.nLevel());
        tree.verify(names, hash -> store.get(MachineStore.nodeKey(hash)));
        return new Projection(k, builder.a(), names, builder.annotations(), builder.annotationEdges(), store);
    }

    private void sameTree(Digest digest, String name, Root actualRoot, Root expectedRoot, Projection actual, Projection expected) {
        var diff = Diff.trees(digest, actualRoot, expectedRoot, h -> {
            var bytes = actual.store.get(MachineStore.nodeKey(h));
            return bytes == null ? expected.store.get(MachineStore.nodeKey(h)) : bytes;
        });
        String difference = "source-only=" + describe(diff.removed()) + "; binary-only=" + describe(diff.added());
        assertThat(diff.isEmpty()).as("Diff(%s_src, %s_bin): %s", name, name, difference).isTrue();
        assertThat(actualRoot.hash()).isEqualTo(expectedRoot.hash());
        assertThat(actualRoot.sum()).isEqualTo(expectedRoot.sum());
    }

    private List<String> describe(List<Entry> entries) {
        return entries.stream().map(e -> new String(e.key(), java.nio.charset.StandardCharsets.UTF_8) + ":" + HexFormat.of().formatHex(e.value())).toList();
    }
}
