package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Arrange;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Body proof descent against real Stage 2 bindings; collection from attributed bodies is tested separately. */
@Tag("phase-3")
class BodyProofTest {
    @TempDir Path dir;
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    @AfterAll static void release() { Stage2Support.release(); }
    static final String BASE = "package q; public class Base { public static final int C = 1; public Number get() { return null; } }";
    static final Proof.Range HEADER = new Proof.Range(Proof.T, "q/Base", Keys.TYPE, "");
    static final Proof.Range CONSTANT = new Proof.Range(Proof.T, "q/Base", Keys.FIELD, "C");
    static final Proof.Range METHOD = new Proof.Range(Proof.T, "q/Base", Keys.METHOD, "get");
    static final Proof.Range MEMBER_TYPE = new Proof.Range(Proof.N, "q/Base", Keys.TYPE, "Foo");

    record State(ContentTree tree, InMemoryLocalStore store, MachineLeaf own, Route route) {
        Proof proof(List<Proof.Range> ranges, List<String> absent) { return Arrange.proof(tree, own, route, null, ranges, absent, store::get); }
        boolean valid(Proof proof) { return proof.valid(tree, own, route, null, store::get); }
    }

    private State boot(Digest digest, ProjectModel model) throws java.io.IOException {
        var tree = new ContentTree(digest);
        var store = Stage2Support.jdkOnly(digest).copy();
        var result = new Stage2(digest, tree, Stage2Support.FEATURE, 2, dir, ClassFacts::of).run(store, model);
        assertThat(result.faults()).isEmpty();
        var own = MachineLeaf.decode(store.get(MachineStore.leafKey(result.leaves().get("app/main"))), digest.width());
        var route = Route.decode(store.get(LocalStore.routeKey(Stage2.projectKey(digest, model), "app", 0)), digest.width());
        return new State(tree, store, own, route);
    }

    private ProjectModel fixture() throws Exception {
        Stage2Support.write(dir, Map.of("dep/src/main/java/q/Base.java", BASE,
                "dep/src/main/java/q/Other.java", "package q; public class Other {}",
                "app/src/main/java/p/App.java", "package p; public class App extends q.Base { int body() { return C; } }"));
        return ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("dep", "g:dep:1", List.of()),
                new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.module("g:dep:1", "dep")))));
    }

    @ParameterizedTest @MethodSource("digests")
    void rangesIgnoreUnreadMembersButObserveConstantsAndOverloadAdditions(Digest digest) throws Exception {
        var model = fixture();
        var before = boot(digest, model);
        var proof = before.proof(List.of(HEADER, CONSTANT, METHOD), List.of("p/Missing"));
        assertThat(proof.valid(before.tree, before.own, before.route, null, key -> { throw new AssertionError("Equal header read storage"); })).isTrue();
        assertThat(Proof.decode(proof.encode(), digest.width())).isEqualTo(proof);
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("public Number", "public void unrelated() {} public Number"));
        var unrelated = boot(digest, model);
        assertThat(unrelated.valid(proof)).isTrue();
        assertThat(unrelated.proof(List.of(METHOD, HEADER, CONSTANT, METHOD), List.of("p/Missing"))
                .aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})))
                .isEqualTo(proof.aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})));
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("C = 1", "C = 2"));
        assertThat(boot(digest, model).valid(proof)).isFalse();
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("public Number", "public String get(String x) { return x; } public Number"));
        assertThat(boot(digest, model).valid(proof)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void providerOnlyChangeInvalidatesBothDirectionsOfNAndReachesBodyConsumers(Digest digest) throws Exception {
        // F01/F02: real class files, identical Base T, unchanged leaf set, only route order changes.
        var with = Stage2Support.jar(dir, "with.jar", Map.of("q/Base.java",
                "package q; public class Base { public static class Foo {} }"), List.of());
        var without = Stage2Support.jar(dir, "without.jar", Map.of("q/Base.java",
                "package q; public class Base {}"), List.of());
        Stage2Support.write(dir, Map.of("app/src/main/java/p/App.java", "package p; public class App {}"));
        var a = Stage2Support.Dep.jar("g:with:1", with.toString());
        var b = Stage2Support.Dep.jar("g:without:1", without.toString());
        var states = new java.util.ArrayList<State>();
        for (var order : List.of(List.of(a, b), List.of(b, a))) states.add(boot(digest,
                ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("app", "g:app:1", order)))));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var client = new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///Use.java"), javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return "class Use { q.Base.Foo value; }"; }
        };
        for (int direction = 0; direction < 2; direction++) {
            var before = states.get(direction); var after = states.get(1 - direction);
            assertThat(before.route.leafSetExt()).isEqualTo(after.route.leafSetExt());
            var proof = before.proof(List.of(HEADER, MEMBER_TYPE), List.of());
            var current = after.proof(List.of(HEADER, MEMBER_TYPE), List.of());
            assertThat(proof.types().getFirst().oSum()).isEqualTo(current.types().getFirst().oSum());
            assertThat(proof.header().ddSum()).isEqualTo(current.header().ddSum());
            assertThat(proof.header().dsSum()).isEqualTo(current.header().dsSum());
            assertThat(proof.header().dcSum()).isEqualTo(current.header().dcSum());
            assertThat(proof.types().getFirst().entries()).isNotEqualTo(current.types().getFirst().entries());
            assertThat(after.valid(before.proof(List.of(HEADER), List.of()))).isTrue();
            assertThat(after.valid(proof)).as("N must not be discharged by equal T projections").isFalse();
            var indexed=ProofIndex.capture(before.tree,before.own,before.route,proof,
                    new ProofIndex.Inputs("Use.java",digest.hash(new byte[]{1}),digest.hash(new byte[]{2})),before.store::get,before.store);
            var afterBinding=ProofIndex.Binding.capture(after.tree,after.own,after.route,after.store::get);
            java.util.function.Function<byte[],byte[]> records=key->{
                var value=after.store.get(key);return value==null?before.store.get(key):value;
            };
            var transition=ProofIndex.Transition.between(before.tree,indexed.binding(),afterBinding,records,new ProofIndex.Work());
            assertThat(indexed.advance(transition,indexed.inputs(),null,null)).as("Indexed N provider delta").isNull();

            var project = digest.hash(new byte[]{42});
            var dependency = new ReverseIndex.Dependency(ReverseIndex.N, "q/Base", Keys.TYPE, "Foo");
            BodyReverseTest.publish(digest, before.tree, before.store, project,
                    List.of(ReverseIndex.bodyKey(dependency, project, Stage2Support.source("Use.java"))));
            var oldRoot = DefinerIndex.decodeRoot(before.store.get(LocalStore.conflictsKey(before.route.routeHash())), digest.width());
            var newRoot = DefinerIndex.decodeRoot(after.store.get(LocalStore.conflictsKey(after.route.routeHash())), digest.width());
            var delta = dev.jvmd.core.tree.Diff.content(digest, oldRoot, newRoot, h -> {
                var bytes = after.store.get(MachineStore.nodeKey(h));
                return bytes == null ? before.store.get(MachineStore.nodeKey(h)) : bytes;
            });
            assertThat(dev.jvmd.core.tree.Diff.trees(digest, oldRoot, newRoot, h -> {
                var bytes = after.store.get(MachineStore.nodeKey(h));
                return bytes == null ? before.store.get(MachineStore.nodeKey(h)) : bytes;
            }).isEmpty()).isTrue();
            var none = new dev.jvmd.core.tree.Diff.Result(List.of(), List.of());
            assertThat(ReverseIndex.bodyCandidates(digest, before.store, new ReverseIndex.Delta(none, none, none, delta)))
                    .containsExactly(new ReverseIndex.Consumer(project, Stage2Support.source("Use.java")));
            try (var manager = compiler.getStandardFileManager(null, null, null)) {
                var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
                String cp = String.join(java.io.File.pathSeparator, (direction == 0 ? List.of(with, without) : List.of(without, with))
                        .stream().map(Path::toString).toList());
                assertThat(compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-classpath", cp,
                        "-d", Files.createDirectories(dir.resolve("oracle-" + direction)).toString()), null, List.of(client)).call())
                        .isEqualTo(direction == 0);
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void memberTypeRangeMustBeCheckedWhenItsOwnerSumIsUnchanged(Digest digest) throws Exception {
        var model = fixture();
        var before = boot(digest, model);
        var proof = before.proof(List.of(HEADER, MEMBER_TYPE), List.of());
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("public Number", "public static class Foo {} public Number"));
        var after = boot(digest, model);
        var current = after.proof(List.of(HEADER, MEMBER_TYPE), List.of());
        assertThat(current.types().getFirst().oSum()).isEqualTo(proof.types().getFirst().oSum());
        assertThat(current.types().getFirst().entries()).isNotEqualTo(proof.types().getFirst().entries());
        var definer = new DefinerIndex.Reader(after.tree, after.own, after.route, after.store::get).definer("q/Base");
        var tReads = after.store.watchReads(MachineStore.nodeKey(definer.k()));
        var nReads = after.store.watchReads(MachineStore.nodeKey(definer.nHash()));
        assertThat(after.valid(proof)).isFalse();
        assertThat(tReads.get()).isZero();
        assertThat(nReads.get()).isPositive();
    }

    @ParameterizedTest @MethodSource("digests")
    void absencesCoverOwnAndRouteTypesAndOwnDefinitionsPrecedeTheRoute(Digest digest) throws Exception {
        var model = fixture();
        var before = boot(digest, model);
        var proof = before.proof(List.of(CONSTANT), List.of("p/Missing"));
        String missing = "package p; public class Missing {}";
        Stage2Support.write(dir, Map.of("app/src/main/java/p/Missing.java", missing));
        var ownAdded = boot(digest, model);
        assertThat(ownAdded.route.routeHash()).isEqualTo(before.route.routeHash());
        assertThat(ownAdded.valid(proof)).isFalse();
        Files.delete(dir.resolve("app/src/main/java/p/Missing.java"));
        Stage2Support.write(dir, Map.of("dep/src/main/java/p/Missing.java", missing));
        var routeAdded = boot(digest, model);
        assertThat(routeAdded.own.r()).isEqualTo(before.own.r());
        assertThat(routeAdded.valid(proof)).isFalse();
        Files.delete(dir.resolve("dep/src/main/java/p/Missing.java"));
        Stage2Support.write(dir, Map.of("app/src/main/java/q/Base.java", BASE.replace("C = 1", "C = 3")));
        assertThat(boot(digest, model).valid(proof)).isFalse();
    }

    @ParameterizedTest @MethodSource("digests")
    void repackagingEqualApisUsesIndexSumsWithoutOpeningAnyTree(Digest digest) throws Exception {
        var classes = Stage2Support.compile(dir.resolve("binary"), Map.of("q/Base.java", BASE,
                "q/Other.java", "package q; public class Other {}"), List.of(), List.of());
        var combined = Stage2Support.pack(dir.resolve("combined.jar"), classes);
        var base = Stage2Support.pack(dir.resolve("base.jar"), Map.of("q/Base.class", classes.get("q/Base.class")));
        var other = Stage2Support.pack(dir.resolve("other.jar"), Map.of("q/Other.class", classes.get("q/Other.class")));
        Stage2Support.write(dir, Map.of("app/src/main/java/p/App.java", "package p; public class App extends q.Base {}"));
        var first = boot(digest, ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("app", "g:app:1",
                List.of(Stage2Support.Dep.jar("g:combined:1", combined.toString()))))));
        var second = boot(digest, ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("app", "g:app:1",
                List.of(Stage2Support.Dep.jar("g:base:1", base.toString()), Stage2Support.Dep.jar("g:other:1", other.toString()))))));
        var proof = first.proof(List.of(CONSTANT, METHOD), List.of("q/Missing"));
        assertThat(second.route.routeHash()).isNotEqualTo(first.route.routeHash());
        var reads = new java.util.ArrayList<byte[]>();
        assertThat(proof.valid(second.tree, second.own, second.route, null, key -> {
            reads.add(key);
            if (key[0] == 'N') throw new AssertionError("Equal index sums opened a tree");
            return second.store.get(key);
        })).isTrue();
        assertThat(reads).hasSize(3);
        var current = second.proof(List.of(CONSTANT, METHOD), List.of("q/Missing"));
        assertThat(current.aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})))
                .isEqualTo(proof.aci(digest, "App.java", digest.hash(new byte[] {1}), digest.hash(new byte[] {2})));
    }

    @ParameterizedTest @MethodSource("digests")
    void reversingConflictingJarsRequiresANewPoolEvenWithTheSameLeafSets(Digest digest) throws Exception {
        String source="package p; public class App { public int value(){ return q.Base.C; } }";
        var firstJar=Stage2Support.jar(dir,"first.jar",Map.of("q/Base.java",BASE),List.of());
        var secondJar=Stage2Support.jar(dir,"second.jar",Map.of("q/Base.java",BASE.replace("C = 1","C = 2")),List.of());
        Stage2Support.write(dir,Map.of("app/src/main/java/p/App.java",source));
        var firstDep=Stage2Support.Dep.jar("g:first:1",firstJar.toString());
        var secondDep=Stage2Support.Dep.jar("g:second:1",secondJar.toString());
        var first=boot(digest,ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("app","g:app:1",List.of(firstDep,secondDep)))));
        var second=boot(digest,ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("app","g:app:1",List.of(secondDep,firstDep)))));
        assertThat(first.own().k()).isEqualTo(second.own().k());
        assertThat(first.route().leafSetExt()).isEqualTo(second.route().leafSetExt());
        assertThat(first.route().leafSetSib()).isEqualTo(second.route().leafSetSib());
        var firstKey=new Pool.Key(first.route().routeHash(),first.own().k());
        var secondKey=new Pool.Key(second.route().routeHash(),second.own().k());
        assertThat(firstKey).isNotEqualTo(secondKey);
        var options=List.of("-proc:none","-implicit:none","-encoding","UTF-8","-g","-parameters");
        var own=Files.createDirectories(dir.resolve("own"));
        for(var stub:Stubs.stubs(digest,first.tree(),first.own(),id -> first.store().get(MachineStore.nodeKey(id)),Stubs.Cache.NONE)) {
            var path=own.resolve(stub.internalName()+".class");Files.createDirectories(path.getParent());Files.write(path,stub.bytes());
        }
        var unit=new javax.tools.SimpleJavaFileObject(dir.resolve("app/src/main/java/p/App.java").toUri(),javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        byte[] previous=null;
        for(int i=0;i<2;i++) {
            var route=i==0 ? List.of(firstJar,secondJar) : List.of(secondJar,firstJar);
            var configuration=new Pool.Configuration(i==0 ? firstKey : secondKey,own,route,java.nio.charset.StandardCharsets.UTF_8,options,List.of("p/App"));
            var expected=Stage2Support.compile(dir.resolve("oracle-"+i),Map.of("p/App.java",source),options,route).get("p/App.class");
            try(var pool=new Pool(configuration,1)) {
                var result=pool.withTask(unit,null,task -> {
                    try { task.parse();task.analyze();task.generate();return null; }
                    catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                });
                var actual=result.classes().get("p/App");assertThat(actual).isEqualTo(expected);
                if(previous!=null) assertThat(actual).isNotEqualTo(previous);
                previous=actual;
            }
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void contextChangesPrecedeShortcutsAndAciBindsKeysNamesBytesAndOptions(Digest digest) throws Exception {
        var current = boot(digest, fixture());
        var zeroA = new Proof.Range(Proof.T, "q/Base", Keys.FIELD, "absentA");
        var zeroB = new Proof.Range(Proof.T, "q/Base", Keys.FIELD, "absentB");
        var a = current.proof(List.of(zeroA), List.of());
        var b = current.proof(List.of(zeroB), List.of());
        var one = digest.hash(new byte[] {1}); var two = digest.hash(new byte[] {2});
        var context = new ProcessorRecords.Context(one, two, List.of(new ProcessorRecords.ConfigEntry("p/lombok.config", one)));
        assertThat(a.valid(current.tree, current.own, current.route, context, key -> { throw new AssertionError("Invalid context read storage"); })).isFalse();
        var withProcessor = Arrange.proof(current.tree, current.own, current.route, context, List.of(zeroA), List.of(), current.store::get);
        assertThat(withProcessor.valid(current.tree, current.own, current.route, null, key -> { throw new AssertionError("Removed context read storage"); })).isFalse();
        assertThat(withProcessor.valid(current.tree, current.own, current.route, context, key -> { throw new AssertionError("Missing answers read storage"); })).isFalse();
        assertThatThrownBy(() -> withProcessor.aci(digest, "App.java", one, two)).hasMessage("Processor result has no reusable model observations");
        assertThat(Proof.decode(withProcessor.encode(), digest.width())).isEqualTo(withProcessor);
        var observed = withProcessor.withProcessorBody(new ProcessorRecords.Body(List.of()));
        assertThat(a.aci(digest, "App.java", one, two)).isNotEqualTo(b.aci(digest, "App.java", one, two))
                .isNotEqualTo(a.aci(digest, "Other.java", one, two)).isNotEqualTo(a.aci(digest, "App.java", two, two))
                .isNotEqualTo(a.aci(digest, "App.java", one, one)).isNotEqualTo(observed.aci(digest, "App.java", one, two));
        assertThatThrownBy(() -> new Proof.Range(7, "q/Base", Keys.TYPE, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Proof.Range(Proof.N, "q/Base", Keys.METHOD, "get")).isInstanceOf(IllegalArgumentException.class);
        var trailing = java.util.Arrays.copyOf(a.encode(), a.encode().length + 1);
        assertThatThrownBy(() -> Proof.decode(trailing, digest.width())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @MethodSource("digests")
    void processorAnswersAndAdmissionPrecedeEveryResolutionShortcut(Digest digest) throws Exception {
        var state = boot(digest, fixture()); var one = digest.hash(new byte[]{1}); var two = digest.hash(new byte[]{2});
        var context = new ProcessorRecords.Context(one, two, List.of());
        var supported = new ProcessorRecords.Capability(ProcessorRecords.ISOLATING, ProcessorRecords.GENERATOR);
        var unsupported = new ProcessorRecords.Capability(ProcessorRecords.ISOLATING, ProcessorRecords.VIOLATED);
        byte[] transcript = {1, 2, 3};
        var observations = new ProcessorRecords.Body(List.of(new ProcessorRecords.Observation("fixture.Reader", supported, transcript)));
        transcript[0] = 9; observations.processors().getFirst().answers()[0] = 8;
        assertThat(observations.processors().getFirst().answers()).containsExactly((byte)1, (byte)2, (byte)3);
        var proof = Arrange.proof(state.tree, state.own, state.route, context, List.of(METHOD), List.of(), state.store::get)
                .withProcessorBody(observations);
        java.util.function.Function<byte[], byte[]> noReads = key -> { throw new AssertionError("Processor gate read resolution storage"); };
        var decoded = Proof.decode(proof.encode(), digest.width());
        assertThat(decoded).isEqualTo(proof); assertThat(decoded.hashCode()).isEqualTo(proof.hashCode());
        assertThat(decoded.valid(state.tree, state.own, state.route, context, observations, key -> {
            assertThat(key).isEqualTo(LocalStore.processorKey(one, "fixture.Reader")); return supported.encode();
        })).isTrue();
        var rejected = decoded.withProcessorBody(observations.rejectReuse());
        assertThat(Proof.decode(rejected.encode(), digest.width())).isEqualTo(rejected);
        assertThat(rejected.valid(state.tree, state.own, state.route, context, observations, noReads)).isFalse();
        assertThatThrownBy(() -> rejected.aci(digest, "App.java", one, two)).isInstanceOf(IllegalStateException.class);
        assertThat(decoded.valid(state.tree, state.own, state.route, context, observations.rejectReuse(), noReads)).isFalse();
        assertThat(decoded.valid(state.tree, state.own, state.route, context, noReads)).isFalse();
        var changed = new ProcessorRecords.Body(List.of(new ProcessorRecords.Observation("fixture.Reader", supported, new byte[]{1, 2, 4})));
        assertThat(decoded.valid(state.tree, state.own, state.route, context, changed, noReads)).isFalse();
        assertThat(decoded.aci(digest, "App.java", one, two)).isNotEqualTo(decoded.withProcessorBody(changed).aci(digest, "App.java", one, two));
        var violation = new ProcessorRecords.Body(List.of(new ProcessorRecords.Observation("fixture.Reader", unsupported, new byte[]{1, 2, 3})));
        assertThat(decoded.valid(state.tree, state.own, state.route, context, violation, noReads)).isFalse();
        assertThat(decoded.withProcessorBody(violation).valid(state.tree, state.own, state.route, context, observations, noReads)).isFalse();
        assertThatThrownBy(() -> decoded.withProcessorBody(violation).aci(digest, "App.java", one, two)).isInstanceOf(IllegalStateException.class);
        assertThat(decoded.valid(state.tree, state.own, state.route, context, new ProcessorRecords.Body(List.of()), noReads)).isFalse();
        var aggregate = new ProcessorRecords.Body(List.of(new ProcessorRecords.Observation("fixture.Reader",
                new ProcessorRecords.Capability(ProcessorRecords.AGGREGATING, ProcessorRecords.GENERATOR), new byte[]{1,2,3})));
        assertThat(decoded.valid(state.tree, state.own, state.route, context, aggregate, noReads)).isFalse();
        var overlay = new ProcessorRecords.Observation("fixture.Overlay", new ProcessorRecords.Capability(ProcessorRecords.ISOLATING, ProcessorRecords.OVERLAY), null);
        var ordered = new ProcessorRecords.Body(List.of(observations.processors().getFirst(), overlay));
        var reversed = new ProcessorRecords.Body(List.of(overlay, observations.processors().getFirst()));
        assertThat(decoded.withProcessorBody(ordered).aci(digest, "App.java", one, two))
                .isNotEqualTo(decoded.withProcessorBody(reversed).aci(digest, "App.java", one, two));
        assertThatThrownBy(() -> new ProcessorRecords.Body(List.of(overlay, overlay))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.proof(List.of(), List.of()).withProcessorBody(observations)).isInstanceOf(IllegalArgumentException.class);
        // Configured aggregates never execute here, but a later violation still revokes a proof with no body invocation.
        var empty = new ProcessorRecords.Body(List.of());
        var aggregateOnly = empty.withConfiguredProcessors(List.of("fixture.Aggregate"));
        var aggregateProof = proof.withProcessorBody(aggregateOnly);
        assertThat(Proof.decode(aggregateProof.encode(), digest.width())).isEqualTo(aggregateProof);
        assertThat(aggregateProof.aci(digest, "App.java", one, two)).isEqualTo(proof.withProcessorBody(empty).aci(digest, "App.java", one, two));
        assertThat(aggregateProof.valid(state.tree, state.own, state.route, context, aggregateOnly, key -> {
            assertThat(key).isEqualTo(LocalStore.processorKey(one, "fixture.Aggregate"));
            return new ProcessorRecords.Capability(ProcessorRecords.AGGREGATING, ProcessorRecords.GENERATOR).encode();
        })).isTrue();
        assertThat(aggregateProof.valid(state.tree, state.own, state.route, context, aggregateOnly, key -> {
            assertThat(key).isEqualTo(LocalStore.processorKey(one, "fixture.Aggregate"));
            return new ProcessorRecords.Capability(ProcessorRecords.AGGREGATING, ProcessorRecords.VIOLATED).encode();
        })).isFalse();
        // With processor answers unchanged, normal exact range descent must still run when a compiler input moves.
        Files.writeString(dir.resolve("dep/src/main/java/q/Base.java"), BASE.replace("public Number", "public String get(String x) { return x; } public Number"));
        var after = boot(digest, ProjectModel.parse(Stage2Support.model(dir, new Stage2Support.Mod("dep", "g:dep:1", List.of()),
                new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.module("g:dep:1", "dep"))))));
        assertThat(decoded.valid(after.tree, after.own, after.route, context, observations, after.store::get)).isFalse();
    }
}
