package dev.jvmd.boot.cold.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Node;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The measurements of rule 9 on a real multi-module Maven project: header-compile wall time per thousand files, distinct leaf sets
 * against routes, definer-index build time, and the rate at which a module's source leaf has the same {@code r} as the class files
 * Maven's own javac wrote for it. Not part of the suite (tag {@code benchmark}); run with
 * {@code -DexcludedGroups=none -Dtest=Stage2Measurement -Djvmd.stage2.project=<root of a Maven project>} (default: this repository).
 */
@Tag("benchmark")
class Stage2Measurement {
    /** The module descriptor fact of a leaf, in words: name, flags, and every directive. */
    private static String describeModule(InMemoryLocalStore store, MachineLeaf leaf) {
        var tree = new ContentTree(Sha256.INSTANCE);
        var e = tree.get(leaf.k(), h -> store.get(MachineStore.nodeKey(h)), dev.jvmd.index.layer.machine.Keys.typeKey("module-info"));
        if (e == null) return "none";
        var v = new dev.jvmd.core.tree.Codec.Reader(e.value());
        var module = dev.jvmd.index.layer.machine.Res.Type.decode(v.raw(v.count())).module();
        if (module == null) return "none";
        var sb = new StringBuilder(module.name());
        sb.append(" flags=").append(Integer.toHexString(module.flags())).append(module.version() == null ? "" : " version=" + module.version());
        for (var r : module.requires()) sb.append("; requires ").append(r.module()).append(' ').append(Integer.toHexString(r.flags())).append(r.version() == null ? "" : " v" + r.version());
        for (var d : module.exports()) sb.append("; exports ").append(d.packageName()).append(d.to().isEmpty() ? "" : " to " + String.join(" to ", d.to()));
        for (var d : module.opens()) sb.append("; opens ").append(d.packageName()).append(d.to().isEmpty() ? "" : " to " + String.join(" to ", d.to()));
        for (var u : module.uses()) sb.append("; uses ").append(u);
        for (var p : module.provides()) sb.append("; provides ").append(p.service()).append(" with ").append(String.join(" with ", p.with()));
        return sb.toString();
    }

    private static String describe(byte[] m) {
        var text = new String(m, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\0', ' ');
        return text.length() > 90 ? text.substring(0, 90) : text;
    }

    @Test void measure() throws Exception {
        var root = Path.of(System.getProperty("jvmd.stage2.project", Path.of("..").toAbsolutePath().normalize().toString())).toAbsolutePath().normalize();
        var digest = Sha256.INSTANCE;
        var work = Files.createTempDirectory("stage2-measure");
        var report = new StringBuilder();
        long mavenStarted = System.nanoTime();
        var model = MavenModelHelper.build(root, MavenProjectTest.repository(), Stage2Support.FEATURE);
        report.append("project: ").append(root).append('\n');
        report.append("model obtained from Maven in ").append((System.nanoTime() - mavenStarted) / 1_000_000).append(" ms (not part of stage 2)\n");

        var machine = Stage2Support.jdkOnly(digest).copy(); // the JDK only: every jar is indexed on the spot
        var parsed = ProjectModel.parse(model.json());
        int workers = Integer.getInteger("jvmd.stage2.workers", Runtime.getRuntime().availableProcessors());
        // Peak used heap while the boot runs, sampled: with sources read lazily it should not follow the size of a module's source.
        var peak = new java.util.concurrent.atomic.AtomicLong();
        var sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
        var sampler = Thread.ofPlatform().daemon().start(() -> {
            var rt = Runtime.getRuntime();
            while (sampling.get()) { peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max); try { Thread.sleep(5); } catch (InterruptedException stop) { return; } }
        });
        System.gc();
        long baseline = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        var result = new Stage2(digest, new ContentTree(digest), Stage2Support.FEATURE, workers, MavenProjectTest.repository(), ClassFacts::of).run(machine, parsed);
        sampling.set(false);
        sampler.join();
        report.append(String.format("peak heap during the boot (sampled): %d MB above a %d MB baseline%n", Math.max(0, peak.get() - baseline) >> 20, baseline >> 20));

        var t = result.timings();
        report.append(String.format("modules=%d source_files=%d parsed_files=%d source_leaves=%d%n",
                result.modules(), result.sourceFiles(), result.parsedFiles(), result.sourceLeaves()));
        report.append(String.format("workers=%d wall=%d ms  (jars indexed on the spot: %d; nodes written: %d; faults: %d)%n", workers, result.wallMillis(), result.indexedOnTheSpot(), result.nodes(), result.faults().size()));
        report.append(String.format("header compile: %d ms summed over jobs = %.0f ms per thousand files%n", t.headerCompileMillis(), 1000.0 * t.headerCompileMillis() / Math.max(1, result.sourceFiles())));
        report.append(String.format("facts (Φ_src): %d ms; definer indexes: %d ms for %d distinct leaf sets over %d routes%n", t.factsMillis(), t.definerIndexMillis(), result.distinctLeafSets(), result.modules() * 2));

        var classStore = new InMemoryLocalStore();
        var maven = MavenProjectTest.outputLeaves(digest, model, work, classStore);
        Function<Identity, byte[]> reader = h -> { var a = machine.get(MachineStore.nodeKey(h)); return a != null ? a : classStore.get(MachineStore.nodeKey(h)); };
        int equal = 0, onlyModuleInfo = 0, total = 0;
        for (var e : maven.entrySet()) {
            var source = MachineLeaf.decode(machine.get(MachineStore.leafKey(result.leaves().get(e.getKey()))), digest.width());
            var expected = e.getValue();
            total++;
            if (source.r().equals(expected.r())) {
                equal++;
                report.append(String.format("  equal: %s (%d facts)%n", e.getKey(), source.factCount()));
                continue;
            }
            // Where they differ, T names the members: a module descriptor is a fact of the class output that classpath mode has no source for (section 8).
            var diff = Diff.trees(digest, new Root(source.k(), source.r(), source.factCount(), Node.level(reader.apply(source.k()))),
                    new Root(expected.k(), expected.r(), expected.factCount(), Node.level(reader.apply(expected.k()))), reader);
            var keys = new ArrayList<String>();
            for (var entry : diff.removed()) keys.add("source-only " + describe(entry.key()));
            for (var entry : diff.added()) keys.add("class-only " + describe(entry.key()));
            boolean moduleOnly = keys.stream().allMatch(k -> k.contains("module-info"));
            if (moduleOnly) onlyModuleInfo++;
            if (keys.stream().anyMatch(k -> k.contains("module-info"))) {
                report.append("    module (source): ").append(describeModule(machine, source)).append('\n');
                report.append("    module (class) : ").append(describeModule(classStore, expected)).append('\n');
            }
            report.append(String.format("  r differs: %s (source %d facts, class output %d facts): %s%n", e.getKey(), source.factCount(), expected.factCount(),
                    keys.stream().limit(4).toList()));
        }
        report.append(String.format("source-vs-class r equality: %d of %d module scopes; %d more differ only by the module-info descriptor%n", equal, total, onlyModuleInfo));
        for (var f : result.faults().stream().limit(12).toList()) report.append("  fault: ").append(f).append('\n');
        System.out.println(report);
        Files.writeString(Path.of("target/stage2-measurement.txt"), report.toString());
        assertThat(machine.hasLocalRoot(Stage2.projectKey(digest, parsed))).isTrue();
    }
}
