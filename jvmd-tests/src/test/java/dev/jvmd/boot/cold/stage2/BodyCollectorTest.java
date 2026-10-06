package dev.jvmd.boot.cold.stage2;

import com.sun.source.util.Trees;
import dev.jvmd.boot.cold.stage3.Arrange;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class BodyCollectorTest {
    @TempDir Path dir;
    private int generations;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE,new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }

    record State(ContentTree tree,InMemoryLocalStore store,MachineLeaf own,Route route,Pool.Configuration configuration) {
        boolean valid(Proof proof) { return proof.valid(tree,own,route,null,store::get); }
    }
    record Compiled(Proof proof,ProofCollector.Body reads,UsesRecord uses,List<String> errors,Map<String,byte[]> classes) { }

    private void fixture(String source,Map<String,String> dependency) throws IOException {
        var files=new java.util.HashMap<String,String>();
        files.put("app/src/main/java/p/App.java",source);
        dependency.forEach((name,text) -> files.put("dep/src/main/java/"+name+".java",text));
        Stage2Support.write(dir,files);
    }

    private State boot(Digest digest) throws IOException {
        var model=ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("dep","g:dep:1",List.of()),
                new Stage2Support.Mod("app","g:app:1",List.of(Stage2Support.Dep.module("g:dep:1","dep")))));
        var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();
        var boot=new Stage2(digest,tree,Stage2Support.FEATURE,2,dir,ClassFacts::of).run(store,model);
        assertThat(boot.faults()).isEmpty();
        var own=MachineLeaf.decode(store.get(MachineStore.leafKey(boot.leaves().get("app/main"))),digest.width());
        var dependency=MachineLeaf.decode(store.get(MachineStore.leafKey(boot.leaves().get("dep/main"))),digest.width());
        var route=Route.decode(store.get(LocalStore.routeKey(Stage2.projectKey(digest,model),"app",0)),digest.width());
        var generation=dir.resolve("body-stubs-"+(generations++));
        var ownStubs=stubs(digest,tree,store,own,generation.resolve("own"));
        var depStubs=stubs(digest,tree,store,dependency,generation.resolve("dep"));
        var config=new Pool.Configuration(new Pool.Key(route.routeHash(),own.k()),ownStubs.path(),List.of(depStubs.path()),
                StandardCharsets.UTF_8,List.of("-proc:none","-implicit:none","-encoding","UTF-8","-g","-parameters"),ownStubs.types());
        return new State(tree,store,own,route,config);
    }

    record StubDir(Path path,List<String> types) { }
    private StubDir stubs(Digest digest,ContentTree tree,InMemoryLocalStore store,MachineLeaf leaf,Path directory) throws IOException {
        var types=new ArrayList<String>();Files.createDirectories(directory);
        for(var stub:Stubs.stubs(digest,tree,leaf,id -> store.get(MachineStore.nodeKey(id)),Stubs.Cache.NONE)) {
            var path=directory.resolve(stub.internalName()+".class");Files.createDirectories(path.getParent());Files.write(path,stub.bytes());types.add(stub.internalName());
        }
        return new StubDir(directory,types);
    }

    private Compiled compile(State state) throws Exception {
        try(var pool=new Pool(state.configuration(),1)) { return compile(state,pool); }
    }

    private Compiled compile(State state,Pool pool) throws Exception {
        var file=dir.resolve("app/src/main/java/p/App.java");var text=Files.readString(file);
        var source=new SimpleJavaFileObject(file.toUri(),JavaFileObject.Kind.SOURCE) { public CharSequence getCharContent(boolean ignore) { return text; } };
        var errors=new ArrayList<String>();
        var result=pool.withTask(source,d -> { if(d.getKind()==Diagnostic.Kind.ERROR) errors.add(d.getCode()); },task -> {
            try {
                var unit=task.parse().iterator().next();task.analyze();
                var reads=ProofCollector.bodies(unit,Trees.instance(task),task.getElements(),task.getTypes());
                task.generate();return reads;
            } catch(IOException ex) { throw new UncheckedIOException(ex); }
        });
        var reads=result.value().supplement(result.reads());
        var arranged=Arrange.body(state.tree(),state.own(),state.route(),null,reads,state.store()::get);
        return new Compiled(arranged.proof(),reads,arranged.uses(),List.copyOf(errors),result.classes());
    }

    private static Proof.Range t(String type,int kind,String name) { return new Proof.Range(Proof.T,type,kind,name); }
    private static Proof.Range n(String type,String name) { return new Proof.Range(Proof.N,type,Keys.TYPE,name); }

    @ParameterizedTest @MethodSource("digests")
    void distinctJavaStringConstantsInvalidateOnlyTheirActualConsumers(Digest digest) throws Exception {
        String lib = "package q; public class Lib { public static final String C=\"\\uD800\", UNUSED=\"?\"; }";
        String source = "package p; public class App { public String value(){return q.Lib.C;} }";
        fixture(source, Map.of("q/Lib", lib));
        var original = boot(digest); var before = compile(original);
        assertThat(before.errors()).isEmpty();
        assertThat(before.reads().ranges()).contains(t("q/Lib", Keys.FIELD, "C")).doesNotContain(t("q/Lib", Keys.FIELD, "UNUSED"));
        fixture(source, Map.of("q/Lib", lib.replace("C=\"\\uD800\"", "C=\"?\"")));
        var changed = boot(digest);
        assertThat(changed.valid(before.proof())).isFalse();
        assertThat(compile(changed).classes().get("p/App")).isNotEqualTo(before.classes().get("p/App"));
        fixture(source, Map.of("q/Lib", lib.replace("UNUSED=\"?\"", "UNUSED=\"\\uDC00\"")));
        assertThat(boot(digest).valid(before.proof())).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void exactOverloadAndConstantReadsIgnoreUnreadMethods(Digest digest) throws Exception {
        String lib="package q; public class Lib { public static final int C=1; public static String pick(Object x){ return null; } }";
        String source="package p; public class App { Object f(){ return q.Lib.pick(q.Lib.C); } }";
        fixture(source,Map.of("q/Lib",lib));
        var before=boot(digest);var compiled=compile(before);
        assertThat(compiled.errors()).isEmpty();assertThat(before.valid(compiled.proof())).isTrue();
        assertThat(compiled.reads().ranges()).contains(t("q/Lib",Keys.TYPE,""),t("q/Lib",Keys.FIELD,"C"),t("q/Lib",Keys.METHOD,"pick"))
                .doesNotContain(t("q/Lib",Keys.METHOD,""));
        assertThat(compiled.reads().ranges()).noneMatch(range -> range.type().equals("p/App"));
        Files.writeString(dir.resolve("dep/src/main/java/q/Lib.java"),lib.replace("public static String","public void unrelated(){} public static String"));
        assertThat(boot(digest).valid(compiled.proof())).isTrue();
        Files.writeString(dir.resolve("dep/src/main/java/q/Lib.java"),lib.replace("C=1","C=2"));
        assertThat(boot(digest).valid(compiled.proof())).isFalse();
        Files.writeString(dir.resolve("dep/src/main/java/q/Lib.java"),lib.replace("public static String","public static String pick(Integer x){ return null; } public static String"));
        var overloaded=boot(digest);assertThat(overloaded.valid(compiled.proof())).isFalse();
        assertThat(compile(overloaded).classes().get("p/App")).isNotEqualTo(compiled.classes().get("p/App"));
        var use=compiled.uses().uses().stream().filter(u -> u.tree()==Proof.T && u.type().equals("q/Lib") && u.kind()==Keys.FIELD && u.name().equals("C")).findFirst().orElseThrow();
        assertThat(use.spans()).anySatisfy(span -> assertThat(source.substring((int)span.start(),(int)span.end())).isEqualTo("q.Lib.C"));
    }

    @ParameterizedTest @MethodSource("digests")
    void inferredExpressionTypeCarriesItsSupertypeClosure(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ return q.Lib.pick(q.Lib.get()); } }",Map.of(
                "q/I","package q; public interface I {}",
                "q/A","package q; public class A implements I {}",
                "q/Lib","package q; public class Lib { public static A get(){ return null; } public static String pick(I x){ return null; } public static Number pick(Object x){ return null; } }"));
        var compiled=compile(boot(digest));assertThat(compiled.errors()).isEmpty();
        assertThat(compiled.reads().ranges()).contains(t("q/A",Keys.TYPE,""),t("q/I",Keys.TYPE,""));
        Files.writeString(dir.resolve("dep/src/main/java/q/A.java"),"package q; public class A {} ");
        var changed=boot(digest);assertThat(changed.valid(compiled.proof())).isFalse();
        assertThat(compile(changed).classes().get("p/App")).isNotEqualTo(compiled.classes().get("p/App"));
    }

    @ParameterizedTest @MethodSource("digests")
    void missingMethodIsAZeroMethodRangeAndArrivalRepairsTheError(Digest digest) throws Exception {
        fixture("package p; public class App { int f(){ return q.Lib.missing(); } }",Map.of("q/Lib","package q; public class Lib {}"));
        var compiled=compile(boot(digest));assertThat(compiled.errors()).isNotEmpty();
        assertThat(compiled.reads().ranges()).contains(t("q/Lib",Keys.METHOD,"missing")).doesNotContain(n("q/Lib","missing"),t("q/Lib",Keys.FIELD,"missing"));
        Files.writeString(dir.resolve("dep/src/main/java/q/Lib.java"),"package q; public class Lib { public static int missing(){ return 1; } }");
        var changed=boot(digest);assertThat(changed.valid(compiled.proof())).isFalse();assertThat(compile(changed).errors()).isEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void localClassReadsInheritedMethodContracts(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ class Local implements q.I { public void run(){} } return new Local(); } }",
                Map.of("q/I","package q; public interface I { void run(); }"));
        var compiled=compile(boot(digest));assertThat(compiled.errors()).isEmpty();
        assertThat(compiled.reads().ranges()).contains(t("q/I",Keys.METHOD,""));
        assertThat(compiled.reads().ranges()).noneMatch(range -> range.type().startsWith("p/App"));
        Files.writeString(dir.resolve("dep/src/main/java/q/I.java"),"package q; public interface I { void run(); void added(); }");
        var changed=boot(digest);assertThat(changed.valid(compiled.proof())).isFalse();assertThat(compile(changed).errors()).isNotEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void inheritedTypeAbsenceAndArrayIntrinsicAreDistinct(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(q.Sub s,int[] xs){ q.Sub.Inner x=null; return xs.length + String.valueOf(x); } }",Map.of(
                "q/Base","package q; public class Base { public static class Inner {} }",
                "q/Sub","package q; public class Sub extends Base {}"));
        var before=boot(digest);var compiled=compile(before);assertThat(compiled.errors()).isEmpty();
        assertThat(compiled.reads().ranges()).contains(n("q/Sub","Inner"),t("q/Base$Inner",Keys.TYPE,""));
        assertThat(compiled.reads().ranges()).noneMatch(range -> range.kind()==Keys.FIELD && range.name().equals("length"));
        Files.writeString(dir.resolve("dep/src/main/java/q/Sub.java"),"package q; public class Sub extends Base { public static class Inner {} }");
        var changed=boot(digest);assertThat(changed.valid(compiled.proof())).isFalse();
        assertThat(compile(changed).classes().get("p/App")).isNotEqualTo(compiled.classes().get("p/App"));
    }

    @ParameterizedTest @MethodSource("digests")
    void qualifiedExpressionHeadCanBeShadowedByAnInheritedField(Digest digest) throws Exception {
        fixture("package p; import q.Util; public class App extends q.Base { Object f(){ return Util.run(); } }",Map.of(
                "q/Base","package q; public class Base {}",
                "q/Util","package q; public class Util { public static int run(){ return 1; } }",
                "q/Other","package q; public class Other { public static String run(){ return null; } }"));
        var before=compile(boot(digest));assertThat(before.errors()).isEmpty();
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"),"package q; public class Base { public static Other Util; }");
        var changed=boot(digest);var after=compile(changed);assertThat(after.errors()).isEmpty();
        assertThat(after.classes().get("p/App")).isNotEqualTo(before.classes().get("p/App"));
        assertThat(changed.valid(before.proof())).isFalse();
        assertThat(before.reads().ranges()).contains(t("q/Base",Keys.FIELD,"Util"));
    }

    @ParameterizedTest @MethodSource("digests")
    void selectedThrowsTypeIsReadEvenWhenTheInvocationReturnsVoid(Digest digest) throws Exception {
        fixture("package p; public class App { void f(){ q.Lib.run(); } }",Map.of(
                "q/Failure","package q; public class Failure extends RuntimeException {}",
                "q/Lib","package q; public class Lib { public static void run() throws Failure {} }"));
        var compiled=compile(boot(digest));assertThat(compiled.errors()).isEmpty();
        assertThat(compiled.reads().ranges()).contains(t("q/Failure",Keys.TYPE,""));
        Files.writeString(dir.resolve("dep/src/main/java/q/Failure.java"),"package q; public class Failure extends Exception {}");
        var changed=boot(digest);assertThat(changed.valid(compiled.proof())).isFalse();assertThat(compile(changed).errors()).isNotEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void anonymousClassReadsInheritedMethodContracts(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ return new q.I(){ public void run(){} }; } }",
                Map.of("q/I","package q; public interface I { void run(); }"));
        var before=compile(boot(digest));assertThat(before.errors()).isEmpty();
        assertThat(before.reads().ranges()).contains(t("q/I",Keys.METHOD,""));
        Files.writeString(dir.resolve("dep/src/main/java/q/I.java"),"package q; public interface I { void run(); void added(); }");
        var changed=boot(digest);assertThat(changed.valid(before.proof())).isFalse();assertThat(compile(changed).errors()).isNotEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void selectedOwnMethodKeepsExternalOverloadCandidates(Digest digest) throws Exception {
        fixture("package p; public class App extends q.Base { String pick(Object x){ return null; } Object f(){ return pick(1); } }",
                Map.of("q/Base","package q; public class Base {}"));
        var before=compile(boot(digest));assertThat(before.errors()).isEmpty();
        assertThat(before.reads().ranges()).contains(t("q/Base",Keys.METHOD,"pick"));
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"),"package q; public class Base { public Number pick(int x){ return null; } }");
        var changed=boot(digest);var after=compile(changed);assertThat(after.errors()).isEmpty();
        assertThat(changed.valid(before.proof())).isFalse();assertThat(after.classes().get("p/App")).isNotEqualTo(before.classes().get("p/App"));
    }

    @ParameterizedTest @MethodSource("digests")
    void staticOnDemandLookupRetainsOtherImportCandidates(Digest digest) throws Exception {
        fixture("package p; import static q.First.*; import static q.Second.*; public class App { Object f(){ return pick(null); } }",Map.of(
                "q/First","package q; public class First { public static Object pick(Object x){ return null; } }",
                "q/Second","package q; public class Second {}"));
        var before=compile(boot(digest));assertThat(before.errors()).isEmpty();
        assertThat(before.reads().ranges()).contains(t("q/First",Keys.METHOD,"pick"),t("q/Second",Keys.METHOD,"pick"));
        Files.writeString(dir.resolve("dep/src/main/java/q/Second.java"),"package q; public class Second { public static String pick(String x){ return null; } }");
        var changed=boot(digest);var after=compile(changed);assertThat(after.errors()).isEmpty();
        assertThat(changed.valid(before.proof())).isFalse();assertThat(after.classes().get("p/App")).isNotEqualTo(before.classes().get("p/App"));
    }

    @ParameterizedTest @MethodSource("digests")
    void ambiguousTypeLookupBindsBothPresentHeaders(Digest digest) throws Exception {
        fixture("package p; import q.*; import r.*; public class App { Object f(){ Value x=null; return x; } }",Map.of(
                "q/Value","package q; public class Value {}","r/Value","package r; public class Value {}"));
        var before=compile(boot(digest));assertThat(before.errors()).contains("compiler.err.ref.ambiguous");
        assertThat(before.proof().types()).extracting(Proof.Type::key).contains("q/Value","r/Value");
        assertThat(before.proof().absent()).doesNotContain("q/Value","r/Value");
        Files.writeString(dir.resolve("dep/src/main/java/r/Value.java"),"package r; class Value {}");
        var changed=boot(digest);assertThat(compile(changed).errors()).isEmpty();assertThat(changed.valid(before.proof())).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void ambiguousInheritedMemberTypeTracksEveryDeclaration(Digest digest) throws Exception {
        fixture("package p; public class App implements q.First,q.Second { Object f(){ Value x=null; return x; } }",Map.of(
                "q/First","package q; public interface First { class Value {} }",
                "q/Second","package q; public interface Second { class Value {} }"));
        var before=compile(boot(digest));assertThat(before.errors()).contains("compiler.err.ref.ambiguous");
        Files.writeString(dir.resolve("dep/src/main/java/q/Second.java"),"package q; public interface Second {}");
        var changed=boot(digest);assertThat(compile(changed).errors()).isEmpty();assertThat(changed.valid(before.proof())).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void erroneousLambdaTargetStillReadsTheFunctionalContract(Digest digest) throws Exception {
        fixture("package p; public class App { q.Func f(){ return () -> {}; } }",Map.of(
                "q/Func","package q; public interface Func { void run(); void other(); }"));
        var before=compile(boot(digest));assertThat(before.errors()).isNotEmpty();
        Files.writeString(dir.resolve("dep/src/main/java/q/Func.java"),"package q; public interface Func { void run(); }");
        var changed=boot(digest);assertThat(compile(changed).errors()).isEmpty();assertThat(changed.valid(before.proof())).isFalse();
        assertThat(before.reads().ranges()).contains(t("q/Func",Keys.METHOD,""));
    }

    @ParameterizedTest @MethodSource("digests")
    void classLambdaTargetFailsOnItsHeaderWithoutReadingItsMethods(Digest digest) throws Exception {
        fixture("package p; public class App { q.Target f(){ return () -> {}; } }",Map.of(
                "q/Target","package q; public class Target {}"));
        var before=compile(boot(digest));assertThat(before.errors()).isNotEmpty();
        assertThat(before.reads().ranges()).doesNotContain(t("q/Target",Keys.METHOD,""));
        Files.writeString(dir.resolve("dep/src/main/java/q/Target.java"),"package q; public class Target { public void run(){} }");
        var changed=boot(digest);assertThat(compile(changed).errors()).containsExactlyElementsOf(before.errors());
        assertThat(changed.valid(before.proof())).isTrue();
    }

    @ParameterizedTest @MethodSource("digests")
    void ambiguousOverloadReadsBothCandidateParameterHierarchies(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ return q.Lib.pick(null); } }",Map.of(
                "q/First","package q; public interface First {}",
                "q/Second","package q; public interface Second {}",
                "q/Lib","package q; public class Lib { public static String pick(First x){ return null; } public static Number pick(Second x){ return null; } }"));
        var before=compile(boot(digest));assertThat(before.errors()).contains("compiler.err.ref.ambiguous");
        Files.writeString(dir.resolve("dep/src/main/java/q/Second.java"),"package q; public interface Second extends First {}");
        var secondWins=boot(digest);assertThat(compile(secondWins).errors()).isEmpty();
        assertThat(secondWins.valid(before.proof())).isFalse();
        Files.writeString(dir.resolve("dep/src/main/java/q/Second.java"),"package q; public interface Second {}");
        Files.writeString(dir.resolve("dep/src/main/java/q/First.java"),"package q; public interface First extends Second {}");
        var firstWins=boot(digest);assertThat(compile(firstWins).errors()).isEmpty();
        assertThat(firstWins.valid(before.proof())).isFalse();
        assertThat(before.reads().ranges()).contains(t("q/First",Keys.TYPE,""),t("q/Second",Keys.TYPE,""));
    }

    @ParameterizedTest @MethodSource("digests")
    void nativeHierarchyReadsRepeatOnWarmContextsAndDoNotLeakBetweenFiles(Digest digest) throws Exception {
        String source="package p; public class App { Object f(){ return q.Lib.pick(null); } }";
        fixture(source,Map.of("q/First","package q; public interface First {}",
                "q/Second","package q; public interface Second {}",
                "q/Lib","package q; public class Lib { public static String pick(First x){ return null; } public static Number pick(Second x){ return null; } }"));
        var state=boot(digest);
        try(var pool=new Pool(state.configuration(),1)) {
            var first=compile(state,pool);var again=compile(state,pool);
            assertThat(again.reads().ranges()).containsExactlyElementsOf(first.reads().ranges());
            assertThat(again.errors()).containsExactlyElementsOf(first.errors());
            Files.writeString(dir.resolve("app/src/main/java/p/App.java"),"package p; public class App { Object f(){ return null; } }");
            var other=compile(state,pool);assertThat(other.errors()).isEmpty();
            assertThat(other.reads().ranges()).noneMatch(r -> r.type().startsWith("q/"));
            Files.writeString(dir.resolve("app/src/main/java/p/App.java"),source);
            assertThat(compile(state,pool).reads().ranges()).containsExactlyElementsOf(first.reads().ranges());
            assertThat(pool.statistics().contexts()).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void wrongArityCandidateDoesNotBindItsParameterHierarchy(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ return q.Lib.pick(null); } }",Map.of(
                "q/Unused","package q; public interface Unused {}",
                "q/Parent","package q; public interface Parent {}",
                "q/Lib","package q; public class Lib { public static String pick(Object x){ return null; } public static Number pick(Unused x,int count){ return null; } }"));
        var before=compile(boot(digest));assertThat(before.errors()).isEmpty();
        assertThat(before.reads().ranges()).doesNotContain(t("q/Unused",Keys.TYPE,""));
        Files.writeString(dir.resolve("dep/src/main/java/q/Unused.java"),"package q; public interface Unused extends Parent {}");
        var changed=boot(digest);var after=compile(changed);assertThat(after.errors()).isEmpty();
        assertThat(changed.valid(before.proof())).isTrue();assertThat(after.classes().get("p/App")).isEqualTo(before.classes().get("p/App"));
    }

    @ParameterizedTest @MethodSource("digests")
    void expressionPackageHeadObservesFieldsButTypeUsesDoNot(Digest digest) throws Exception {
        String source="package p; public class App extends q.Base { Object f(){ return q.Util.run(); } }";
        fixture(source,Map.of("q/Base","package q; public class Base {}",
                "q/Util","package q; public class Util { public static int run(){ return 1; } }",
                "q/Holder","package q; public class Holder { public Other Util; }",
                "q/Other","package q; public class Other { public String run(){ return null; } }"));
        var before=compile(boot(digest));assertThat(before.errors()).isEmpty();
        assertThat(before.reads().ranges()).contains(t("q/Base",Keys.FIELD,"q"));
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"),"package q; public class Base { public Holder q; }");
        var changed=boot(digest);var after=compile(changed);assertThat(after.errors()).isEmpty();
        assertThat(after.classes().get("p/App")).isNotEqualTo(before.classes().get("p/App"));
        assertThat(changed.valid(before.proof())).isFalse();
        Files.writeString(dir.resolve("app/src/main/java/p/App.java"),
                "package p; import q.Util; public class App extends q.Base { Util value; Class<?> f(){ return Util.class; } }");
        var typeOnly=compile(boot(digest));assertThat(typeOnly.errors()).isEmpty();
        assertThat(typeOnly.reads().ranges()).doesNotContain(t("q/Base",Keys.FIELD,"Util"),t("q/Base",Keys.FIELD,"q"));
    }

    @ParameterizedTest @MethodSource("digests")
    void qualifiedMemberTypeInExpressionCanBecomeAField(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ return q.Outer.Inner.run(); } }",Map.of(
                "q/Outer","package q; public class Outer { public static class Inner { public static int run(){ return 1; } } }",
                "q/Other","package q; public class Other { public String run(){ return null; } }"));
        var before=compile(boot(digest));assertThat(before.errors()).isEmpty();
        assertThat(before.reads().ranges()).contains(t("q/Outer",Keys.FIELD,"Inner"));
        Files.writeString(dir.resolve("dep/src/main/java/q/Outer.java"),
                "package q; public class Outer { public static Other Inner; public static class Inner { public static int run(){ return 1; } } }");
        var changed=boot(digest);var after=compile(changed);assertThat(after.errors()).isEmpty();
        assertThat(changed.valid(before.proof())).isFalse();assertThat(after.classes().get("p/App")).isNotEqualTo(before.classes().get("p/App"));
    }

    @ParameterizedTest @MethodSource("digests")
    void bodyTypeAbsenceObservesArrivalInAnotherOwnSource(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ Missing local=null; return local; } }",Map.of("q/Lib","package q; public class Lib {}"));
        var before=compile(boot(digest));assertThat(before.errors()).isNotEmpty();assertThat(before.proof().absent()).contains("p/Missing");
        Files.writeString(dir.resolve("app/src/main/java/p/Missing.java"),"package p; public class Missing {}");
        var changed=boot(digest);assertThat(changed.valid(before.proof())).isFalse();assertThat(compile(changed).errors()).isEmpty();
    }

    @ParameterizedTest @MethodSource("digests")
    void missingExpressionQualifierObservesTypeArrival(Digest digest) throws Exception {
        fixture("package p; public class App { Object f(){ return Missing.run(); } }",Map.of("q/Lib","package q; public class Lib {}"));
        var before=compile(boot(digest));assertThat(before.errors()).isNotEmpty();
        Files.writeString(dir.resolve("app/src/main/java/p/Missing.java"),"package p; public class Missing { public static Object run(){ return null; } }");
        var changed=boot(digest);assertThat(compile(changed).errors()).isEmpty();assertThat(changed.valid(before.proof())).isFalse();
        assertThat(before.proof().absent()).contains("p/Missing");
    }

    @ParameterizedTest @MethodSource("digests")
    void inaccessibleTypeInBodyIsAHeaderReadNotAnAbsence(Digest digest) throws Exception {
        fixture("package p; import q.*; public class App { Object f(){ Hidden local=null; return local; } }",
                Map.of("q/Hidden","package q; class Hidden {}"));
        var before=compile(boot(digest));assertThat(before.errors()).isNotEmpty();
        Files.writeString(dir.resolve("dep/src/main/java/q/Hidden.java"),"package q; public class Hidden {}");
        var changed=boot(digest);assertThat(compile(changed).errors()).isEmpty();assertThat(changed.valid(before.proof())).isFalse();
        assertThat(before.proof().types()).anyMatch(t -> t.key().equals("q/Hidden"));
        assertThat(before.proof().absent()).doesNotContain("q/Hidden");
        assertThat(before.uses().uses()).anyMatch(u -> u.tree()==Proof.T && u.type().equals("q/Hidden") && !u.spans().isEmpty());
        assertThat(before.uses().uses()).noneMatch(u -> u.tree()==UsesRecord.D && u.type().equals("q/Hidden"));
    }

    @ParameterizedTest @MethodSource("digests")
    void boundsLambdasMethodReferencesAndEnumSwitchUseTheirSpecificRanges(Digest digest) throws Exception {
        fixture("package p; public class App { <T extends q.First & q.Second> Object f(T x){ return x.get(); } q.Func lambda(){ return s -> s; } q.Func reference(){ return q.Lib::apply; } int enumeration(q.E e){ return switch(e){ case A -> 1; case B -> 2; }; } }",Map.of(
                "q/First","package q; public interface First {}",
                "q/Second","package q; public interface Second { String get(); }",
                "q/Func","package q; public interface Func { String apply(String x); }",
                "q/Lib","package q; public class Lib { public static String apply(String x){ return x; } }",
                "q/E","package q; public enum E { A,B }"));
        var compiled=compile(boot(digest));assertThat(compiled.errors()).isEmpty();
        assertThat(compiled.reads().ranges()).contains(t("q/First",Keys.TYPE,""),t("q/Second",Keys.TYPE,""),
                t("q/First",Keys.METHOD,"get"),t("q/Second",Keys.METHOD,"get"),t("q/Func",Keys.METHOD,""),
                t("q/Lib",Keys.METHOD,"apply"),t("q/E",Keys.FIELD,""));
        Files.writeString(dir.resolve("dep/src/main/java/q/E.java"),"package q; public enum E { A,B,C }");
        var changed=boot(digest);assertThat(changed.valid(compiled.proof())).isFalse();assertThat(compile(changed).errors()).isNotEmpty();
    }

}
