package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.local.Bind;
import dev.jvmd.index.layer.local.Bound;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.local.SourceFacts;
import dev.jvmd.index.layer.machine.Fact;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * One module and scope (stage 2, 4 step 2 and 5.4): bind its route, header-compile its source files against the classpath of jars
 * and sibling stubs, take every declaration through {@code Φ_src} into facts, and build the module's leaf with the same shape as a
 * jar's. Bodies are never looked at. Faults are per file and per declaration and never stop the job; a failure of anything else does.
 */
final class ModuleJob {
    private final Boot boot;

    ModuleJob(Boot boot) { this.boot = boot; }

    /** What {@code Stage2} needs back: the leaf key. Everything else is recorded in {@link Boot}. */
    Identity run(ProjectModel.Module module, int scope) throws IOException {
        var digest = boot.digest;
        var sums = boot.tree.sums();
        var store = boot.store;
        var sink = boot.sink;
        var entries = boot.entries.get(Boot.routeKey(module.name(), scope));

        // 1. Bind the route. No session providers exist in a cold boot.
        var bound = Bind.bind(digest, entries, Bind.NONE, boot.built.provider(), boot::leaf, sink);
        boot.routes.put(Boot.routeKey(module.name(), scope), new Route(entries, bound.routeHash(), bound.r(), bound.leafSet()));

        // 2. Stubs for siblings; jars go on the classpath as jars.
        var classpath = new ArrayList<Path>();
        for (var binding : bound.bindings()) {
            if (binding.origin() == Bound.Origin.BUILT) classpath.add(boot.stubDir(binding.k()));
            else if (binding.entry() instanceof RouteEntry.Jar jar) classpath.add(boot.repository.resolve(jar.location()));
        }

        // 3. Header-compile the scope's source files.
        var options = module.javacOptions();
        int release = options.contains("--enable-preview") || module.release() <= 0 ? Runtime.version().feature() : module.release();
        var roots = module.scope(scope).sourceRoots();
        var found = sources(roots);
        var toCompile = new ArrayList<HeaderCompiler.Source>();
        for (var file : found) if (!file.moduleInfo()) toCompile.add(new HeaderCompiler.Source(file.path(), file.bytes()));
        boot.sourceFiles.addAndGet(found.size());
        var facts = new ArrayList<Fact>();
        var edges = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        var rows = new ArrayList<FileRow>();
        var seen = new HashSet<ByteBuffer>();
        var optionsKey = digest.hash((String.join("\0", options) + "\0" + release).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long headerStarted = System.nanoTime();
        var compiled = HeaderCompiler.compile(toCompile, classpath, Path.of(boot.model.jdkHome()), release, options);
        boot.headerNanos.addAndGet(System.nanoTime() - headerStarted);
        boot.compiledFiles.addAndGet(toCompile.size());
        try (compiled) {
            var extract = new SourceFacts(digest, compiled.elements, compiled.types, options.contains("-parameters"));
            var unitsByPath = new java.util.HashMap<String, HeaderCompiler.Unit>();
            for (var u : compiled.units) unitsByPath.put(u.source.path(), u);
            // 4. Each compilation unit, in path order.
            for (var file : found) {
                if (file.moduleInfo()) { rows.add(row(file, sums.zero(), List.of(), List.of())); continue; }
                var unit = unitsByPath.get(file.path());
                if (!unit.parsed()) {
                    rows.add(row(file, sums.zero(), List.of(), List.of(new FileRow.Fault(new byte[0], unit.parseError))));
                    continue;
                }
                var result = boot.fileMemo.get(file.kappa(), bound.routeHash(), optionsKey);
                if (result == null) {
                    long factsStarted = System.nanoTime();
                    result = extract.of(unit.declared);
                    boot.factsNanos.addAndGet(System.nanoTime() - factsStarted);
                    boot.fileMemo.put(file.kappa(), bound.routeHash(), optionsKey, result);
                    boot.parsedFiles.incrementAndGet();
                }
                var faults = new ArrayList<>(result.faults());
                faults.addAll(unit.faults);
                var sum = sums.zero();
                var kept = new HashSet<ByteBuffer>();
                for (var fact : result.facts()) {
                    // The chunker needs strictly increasing keys: a second declaration with one key (C.5) is a fault on the later one.
                    if (!seen.add(ByteBuffer.wrap(fact.m()))) { faults.add(new FileRow.Fault(fact.m(), "duplicate declaration")); continue; }
                    kept.add(ByteBuffer.wrap(fact.m()));
                    facts.add(fact);
                    sum = sums.add(sum, fact.h());
                }
                for (var edge : result.edges()) edges.putIfAbsent(edge.key(), edge);
                var types = new ArrayList<String>();
                for (var type : result.typeKeys()) if (kept.contains(ByteBuffer.wrap(typeKey(type)))) types.add(type);
                rows.add(row(file, sum, types, faults));
            }
        }
        for (var row : rows) {
            boot.files.put(row.path(), row);
            for (var fault : row.faults()) boot.faults.add(row.path() + ": " + (fault.m().length == 0 ? "" : describe(fault.m()) + ": ") + fault.reason());
        }

        // 5. Sort the facts by m, stream them into T with the per-type running sum, and build N, E and O.
        facts.sort((a, b) -> Arrays.compareUnsigned(a.m(), b.m()));
        var chunker = boot.tree.chunker(sink);
        var names = new ArrayList<Entry>(facts.size());
        var types = new ArrayList<Entry>();
        byte[] owner = null;
        var ownerSum = sums.zero();
        for (var fact : facts) {
            chunker.add(fact.entry());
            names.add(fact.byName());
            var factOwner = ownerOf(fact.m());
            if (owner == null || !Arrays.equals(owner, factOwner)) {
                if (owner != null) types.add(new Entry(owner, Entry.NONE, ownerSum));
                owner = factOwner;
                ownerSum = sums.zero();
            }
            ownerSum = sums.add(ownerSum, fact.h());
        }
        if (owner != null) types.add(new Entry(owner, Entry.NONE, ownerSum));
        var tRoot = chunker.finish();
        var k = tRoot.hash();

        var done = boot.leafDone(k);
        if (boot.written.claimLeaf(k)) {
            try {
                // First time this boot sees this API; if MACHINE already has it (a jar with the same API) there is nothing to build.
                if (store.getLeaf(k) == null) {
                    names.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
                    var edgeList = new ArrayList<>(edges.values());
                    var nRoot = boot.tree.build(names, sink);
                    var eRoot = boot.tree.build(edgeList, sink);
                    var oRoot = boot.tree.build(types, sink);
                    var leaf = new MachineLeaf(k, tRoot.sum(), nRoot.hash(), nRoot.level(), eRoot.hash(), eRoot.sum(), eRoot.level(), oRoot.hash(),
                            oRoot.level(), tRoot.count(), types.size(), eRoot.count());
                    store.putLeaf(k, leaf.encode());
                }
                sink.flush();
                done.complete(null);
            } catch (RuntimeException | Error failed) {
                done.completeExceptionally(failed);
                throw failed;
            }
        } else done.join(); // another job is writing this leaf: its commit is what makes it readable
        sink.flush();
        boot.sourceLeaves.incrementAndGet();

        // 6. Register the leaf for the modules that depend on this one.
        boot.built.register(module.name(), module.coordinate(), scope, k);

        // 7. The definer index of this route, from the nearest one already built.
        long definerStarted = System.nanoTime();
        definerIndex(bound);
        boot.definerNanos.addAndGet(System.nanoTime() - definerStarted);
        sink.flush();
        return k;
    }

    private void definerIndex(Bound bound) {
        if (boot.indexMemo.written(bound.leafSet(), bound.routeHash())) return;
        var sorted = new ArrayList<>(bound.sequence());
        sorted.sort(Identity::compareTo);
        var base = boot.indexMemo.nearest(sorted);
        var result = DefinerIndex.build(boot.digest, boot.tree, bound.sequence(), base, boot::leaf, boot::node, boot.sink);
        var disjoint = DefinerIndex.encodeRoot(result.disjoint());
        var conflicts = DefinerIndex.encodeRoot(result.conflicts());
        boot.store.putDisjoint(bound.leafSet(), disjoint);
        boot.store.putConflicts(bound.routeHash(), conflicts);
        boot.definers.put(LocalStore.disjointKey(bound.leafSet()), disjoint);
        boot.definers.put(LocalStore.conflictsKey(bound.routeHash()), conflicts);
        boot.indexMemo.put(bound.leafSet(), result.state());
        boot.indexMemo.markWritten(bound.leafSet(), bound.routeHash());
    }

    // ---- source files ------------------------------------------------------------------------------------------------------

    private record SourceFile(String path, long size, long mtimeNanos, byte[] bytes, Identity kappa, boolean moduleInfo) { }

    /** Step 1.4: regular {@code *.java} under the source roots, in path order, one stat each, κ on read. A root that does not exist is skipped. */
    private List<SourceFile> sources(List<String> roots) throws IOException {
        var byPath = new TreeMap<String, Path>(Enumerate::compareNames);
        var base = Path.of(boot.model.root()).toAbsolutePath().normalize();
        for (var root : roots) {
            var dir = boot.model.resolve(root);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                for (var file : walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    var absolute = file.toAbsolutePath().normalize();
                    byPath.putIfAbsent((absolute.startsWith(base) ? base.relativize(absolute) : absolute).toString().replace('\\', '/'), file);
                }
            } catch (IOException unreadable) {
                throw new ProjectModel.Fault("Unreadable source root " + dir + ": " + unreadable.getMessage(), unreadable);
            }
        }
        var out = new ArrayList<SourceFile>(byPath.size());
        for (var e : byPath.entrySet()) {
            var attributes = Files.readAttributes(e.getValue(), BasicFileAttributes.class);
            var instant = attributes.lastModifiedTime().toInstant();
            var bytes = Files.readAllBytes(e.getValue());
            out.add(new SourceFile(e.getKey(), attributes.size(), instant.getEpochSecond() * 1_000_000_000L + instant.getNano(), bytes,
                    boot.digest.hash(bytes), e.getValue().getFileName().toString().equals("module-info.java")));
        }
        return out;
    }

    private static FileRow row(SourceFile file, Identity sum, List<String> types, List<FileRow.Fault> faults) {
        return new FileRow(file.path(), file.kappa(), file.size(), file.mtimeNanos(), sum, List.copyOf(types), List.copyOf(faults));
    }

    // ---- keys ---------------------------------------------------------------------------------------------------------------

    private static byte[] typeKey(String internalName) { return new Codec.Writer(internalName.length() + 2).zstr(internalName).u8(0).toBytes(); }

    /** The {@code O} key of the type a fact belongs to: {@code zstr internalName}, the prefix of {@code m}. */
    private static byte[] ownerOf(byte[] m) {
        int end = 0;
        while (m[end] != 0) end++;
        return Arrays.copyOf(m, end + 1);
    }

    private static String describe(byte[] m) {
        var in = new Codec.Reader(m);
        var out = new StringBuilder();
        for (int b; in.remaining() > 0 && (b = in.u8()) != 0; ) out.append((char) b);
        return out.toString();
    }
}
