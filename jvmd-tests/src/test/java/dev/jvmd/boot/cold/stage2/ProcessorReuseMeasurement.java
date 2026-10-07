package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Attribute;
import dev.jvmd.boot.cold.stage3.BodyGeneration;
import dev.jvmd.boot.cold.stage3.Output;
import dev.jvmd.boot.cold.stage3.Pool;
import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** Invariant 27: the caller chooses a subset with Valid; the production cold driver still attributes every file. */
@Tag("benchmark")
class ProcessorReuseMeasurement {
    @TempDir Path dir;
    private static final String SOURCE = "app/src/main/java/";
    static Stream<Digest> digests() { return Stream.of(Sha256.INSTANCE, new Digests.Sha3()); }
    record Snapshot(Map<String, FileRow> rows, Identity options, Identity ownR, Identity route) { }
    record Reused(Snapshot snapshot, Set<String> attributed, Set<String> served, long validationNanos, long attributeNanos) { }

    @ParameterizedTest @MethodSource("digests")
    void twoThousandFilesReuseOnlyTheProvedResults(Digest digest) throws Exception {
        var sources = new TreeMap<String, String>();
        sources.put("p/Parent.java", "package p; public class Parent { public int getExtra(){return 11;} }");
        for (int i = 0; i < 1000; i++) {
            String part = i % 2 == 0 ? "left" : "right";
            sources.put("p/" + part + "/Bean" + i + ".java", "package p." + part + "; "
                    + "@lombok.Data @lombok.EqualsAndHashCode(callSuper=false) public class Bean" + i
                    + " extends p.Parent { private String name; public int own(){return 1;} }");
            if (i < 999) sources.put("p/" + part + "/Use" + i + ".java", "package p." + part + "; public class Use" + i
                    + " { public int value(Bean" + i + " bean){return bean.getExtra();} }");
        }
        assertThat(sources).hasSize(2000);
        Stage2Support.write(dir.resolve(SOURCE), sources);
        Files.writeString(dir.resolve("lombok.config"), "config.stopBubbling = true\nlombok.addLombokGeneratedAnnotation = false\n");
        var config = dir.resolve(SOURCE + "p/left/lombok.config");
        Files.writeString(config, "lombok.addLombokGeneratedAnnotation = false\n");
        var lombok = Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Stage2Support.model(dir,
                new Stage2Support.Mod("app", "g:app:1", List.of(Stage2Support.Dep.jar("g:lombok:1", lombok.toString()))).withOptions("-g", "-parameters")));
        var processing = ((com.fasterxml.jackson.databind.node.ObjectNode) document.withArray("modules").get(0)).putObject("processing");
        processing.putArray("path").addObject().put("coordinate", "g:lombok:1").put("location", lombok.toString());
        var model = ProjectModel.parse(json.writeValueAsBytes(document));
        var tree = new ContentTree(digest); var store = Stage2Support.jdkOnly(digest).copy();
        var stage2 = new Stage2(digest, tree, Stage2Support.FEATURE, 4, dir, ClassFacts::of);
        assertThat(stage2.run(store, model).faults()).isEmpty();
        var initial = new Stage3(digest, tree, Stage2Support.FEATURE, 4, dir).run(store, model);
        assertThat(initial.files()).isEqualTo(2000); assertThat(initial.faults()).isEmpty();
        var previous = snapshot(digest, tree, store, model);
        nativeBytes(digest, tree, store, model, lombok, "initial");
        var report = new StringBuilder("digest: " + digest.name() + "\nfiles: 2000\ninitial cold driver ms: " + initial.wallMillis() + "\n");
        String bean = SOURCE + "p/left/Bean0.java", caller = SOURCE + "p/left/Use0.java";
        Files.writeString(dir.resolve(bean), Files.readString(dir.resolve(bean)).replace("private String name;", "private String name; private int extra;"));
        assertThat(stage2.run(store, model).faults()).isEmpty();
        var api = reuse(digest, tree, store, model, lombok, previous);
        assertThat(api.attributed()).containsExactlyInAnyOrder(bean, caller);
        assertThat(api.served()).hasSize(1998);
        nativeBytes(digest, tree, store, model, lombok, "field");
        report(report, "field addition", api); previous = api.snapshot();
        Files.writeString(dir.resolve(bean), Files.readString(dir.resolve(bean)).replace("return 1;", "return 2;"));
        assertThat(stage2.run(store, model).faults()).isEmpty();
        var body = reuse(digest, tree, store, model, lombok, previous);
        assertThat(body.attributed()).containsExactly(bean); assertThat(body.served()).hasSize(1999);
        assertThat(body.snapshot().ownR()).isEqualTo(previous.ownR());
        nativeBytes(digest, tree, store, model, lombok, "body");
        report(report, "body edit", body); previous = body.snapshot();
        var subtree = new TreeSet<String>();
        previous.rows().keySet().stream().filter(p -> p.startsWith(SOURCE + "p/left/")).forEach(subtree::add);
        assertThat(subtree).hasSize(1000);
        for (boolean remove : List.of(false, true)) {
            if (remove) Files.delete(config); else Files.writeString(config, "lombok.addLombokGeneratedAnnotation = true\n");
            assertThat(stage2.run(store, model).faults()).isEmpty();
            var changed = reuse(digest, tree, store, model, lombok, previous);
            assertThat(changed.attributed()).isEqualTo(subtree); assertThat(changed.served()).hasSize(1000);
            assertThat(changed.snapshot().ownR()).isEqualTo(previous.ownR());
            assertThat(changed.snapshot().route()).isEqualTo(previous.route());
            nativeBytes(digest, tree, store, model, lombok, remove ? "config-delete" : "config-edit");
            report(report, remove ? "config deletion" : "config edit", changed); previous = changed.snapshot();
        }
        Files.writeString(Path.of("target/stage3-processor-reuse-" + digest.name() + ".txt"), report);
        System.out.println(report);
    }

    private static void report(StringBuilder report, String edit, Reused result) {
        String line = edit + ": attributed=" + result.attributed().size() + " retained rooted selections=" + result.served().size()
                + " validation ms=" + result.validationNanos() / 1_000_000 + " attribution ms=" + result.attributeNanos() / 1_000_000 + "\n";
        report.append(line); System.out.print(line);
    }

    private static Snapshot snapshot(Digest digest, ContentTree tree, InMemoryLocalStore store, ProjectModel model) {
        var project = Stage2.projectKey(digest, model);
        var local = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(project)));
        var rows = new TreeMap<String, FileRow>();
        var prefix = LocalStore.filePrefix(project);
        tree.forEach(local.local().hash(), id -> store.get(MachineStore.nodeKey(id)), prefix,e -> {
            var unit = SourceUnit.fromFileKey(e.key(), digest.width());
            if (unit.module().equals("app") && unit.scope() == 0) rows.put(unit.path(), FileRow.decode(unit.path(), store.get(e.key()), digest.width()));
        });
        var source = SourceLeaf.decode(store.get(LocalStore.sourceLeafKey(project, "app", 0)), digest.width());
        var own = MachineLeaf.decode(store.get(MachineStore.leafKey(source.k())), digest.width());
        var route = Route.decode(store.get(LocalStore.routeKey(project, "app", 0)), digest.width());
        var plan = ProcessorPlan.load(tree, local, project, "app", 0, store::get);
        return new Snapshot(Map.copyOf(rows), plan.invocation().optionsHash(), own.r(), route.routeHash());
    }

    private Reused reuse(Digest digest, ContentTree tree, InMemoryLocalStore store, ProjectModel model, Path lombok, Snapshot previous) throws Exception {
        var current = snapshot(digest, tree, store, model); var project = Stage2.projectKey(digest, model);
        var local = LocalRoot.decode(digest, store.get(LocalStore.localRootKey(project)));
        var generation = BodyGeneration.begin(tree, store, project, local);
        var source = SourceLeaf.decode(generation.local(LocalStore.sourceLeafKey(project, "app", 0)), digest.width());
        var own = MachineLeaf.decode(generation.get(MachineStore.leafKey(source.k())), digest.width());
        var route = Route.decode(generation.local(LocalStore.routeKey(project, "app", 0)), digest.width());
        var plan = ProcessorPlan.load(tree, local, project, "app", 0, generation::get);
        var observations = ProcessorHost.overlayObservations(plan.currentInvocation()).orElseThrow();
        var module = ModuleRecord.decode(generation.local(LocalStore.moduleKey(project, "app")));
        var options = Attribute.Options.processed(digest, module, Stage2Support.JDK, plan.invocation());
        var selected = new TreeMap<SourceUnit,List<Entry>>();
        var oldResults = new ArrayList<ResultRecord>();var results = new ArrayList<ResultRecord>();
        var attributed = new TreeSet<String>(); var served = new TreeSet<String>();
        long validationNanos = 0, attributeNanos = 0;
        try (var stubs = new StubDirectories(tree, generation, k -> MachineLeaf.decode(generation.get(MachineStore.leafKey(k)), digest.width()))) {
            var directory = stubs.get(source.k());
            var configuration = new Pool.Configuration(new Pool.Key(route.routeHash(), own.k()), directory.path(), List.of(lombok),
                    options.charset(), options.javac(), directory.types());
            try (var pool = new Pool(configuration, 1)) {
                var attribute = Attribute.processed(tree, generation, own, route, pool, options, plan, List.of(lombok), dir);
                for (var row : new TreeMap<>(current.rows()).values()) {
                    long started = System.nanoTime();
                    var proof = Proof.decode(generation.get(LocalStore.proofKey(project, "app", 0, row.path())), digest.width());
                    boolean valid = previous.options().equals(options.hash()) && previous.rows().get(row.path()).kappa().equals(row.kappa())
                            && proof.valid(tree, own, route, row.processor(), observations, generation::get);
                    validationNanos += System.nanoTime() - started;
                    Attribute.Computed computed;
                    if (valid) {
                        served.add(row.path());
                        continue; // already selected by its unchanged rooted unit manifest
                    } else {
                        var oldAci=proof.aci(digest,row.path().substring(row.path().lastIndexOf('/')+1),
                                previous.rows().get(row.path()).kappa(),previous.options());
                        oldResults.add(ResultRecord.decode(generation.get(LocalStore.resultKey(oldAci)),digest.width()));
                        started = System.nanoTime();
                        computed = attribute.run(row, dir.resolve(row.path()).toUri(), Files.readAllBytes(dir.resolve(row.path())));
                        attributeNanos += System.nanoTime() - started; attributed.add(row.path());
                    }
                    assertThat(computed.reusable()).as(row.path() + ": " + computed.faults()).isTrue();
                    assertThat(computed.result().attributed()).as(row.path() + ": " + computed.result().diagnostics()).isTrue();
                    var entries=new ArrayList<Entry>();
                    entries.add(generation.record(LocalStore.proofKey(project, "app", 0, row.path()), computed.proof().encode()));
                    entries.add(generation.record(LocalStore.resultKey(computed.aci()), computed.result().encode()));
                    entries.add(generation.record(LocalStore.usesKey(computed.aci()), computed.uses().encode()));
                    results.add(computed.result());
                    for (var dependency : ReverseIndex.dependencies(computed.proof()))
                        entries.add(generation.record(ReverseIndex.bodyKey(dependency, project, "app", 0, row.path()), Entry.NONE));
                    for (var c : computed.result().classFiles()) {
                        entries.add(java.util.Objects.requireNonNull(generation.reference(LocalStore.classFileKey(c.contentHash()))));
                    }
                    selected.put(new SourceUnit("app",0,row.path()),List.copyOf(entries));
                }
                assertThat(pool.statistics().tasks()).isEqualTo(attributed.size());
            }
        }
        var outputKey=LocalStore.outputKey(project,"app",0);
        var oldOutput=DefinerIndex.decodeRoot(generation.get(outputKey),digest.width());
        var nextOutput=Output.apply(tree,generation,oldOutput,oldResults,results);
        selected.put(new SourceUnit("app",0,""),List.of(generation.record(outputKey,DefinerIndex.encodeRoot(nextOutput))));
        generation.commitUnitsDelta(selected,Set.of());
        return new Reused(current, attributed, served, validationNanos, attributeNanos);
    }

    private void nativeBytes(Digest digest, ContentTree tree, InMemoryLocalStore store, ProjectModel model, Path lombok, String name) throws Exception {
        var output = Files.createDirectories(dir.resolve("native-" + name));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8);
             var sources = Files.walk(dir.resolve(SOURCE))) {
            files.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(output));
            var args = List.of("-proc:full", "-g", "-parameters", "--class-path", lombok.toString(), "--processor-path", lombok.toString());
            var messages = new java.io.StringWriter();
            assertThat(compiler.getTask(messages, files, null, args, null, files.getJavaFileObjectsFromPaths(
                    sources.filter(p -> p.toString().endsWith(".java")).sorted().toList())).call()).as(messages.toString()).isTrue();
        }
        var project = Stage2.projectKey(digest, model);
        var root = DefinerIndex.decodeRoot(store.get(LocalStore.outputKey(project, "app", 0)), digest.width());
        var actual = new TreeMap<String, byte[]>();
        tree.forEach(root.hash(), id -> store.get(MachineStore.nodeKey(id)), entry -> actual.put(new dev.jvmd.core.tree.Codec.Reader(entry.key()).zstr(),
                store.get(LocalStore.classFileKey(Identity.of(entry.value())))));
        var expected = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(output)) { for (var p : paths.filter(p -> p.toString().endsWith(".class")).toList())
            expected.put(output.relativize(p).toString().replace('\\', '/').replaceFirst("\\.class$", ""), Files.readAllBytes(p)); }
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        actual.forEach((type, bytes) -> assertThat(bytes).as(name + ":" + type).isEqualTo(expected.get(type)));
    }
}
