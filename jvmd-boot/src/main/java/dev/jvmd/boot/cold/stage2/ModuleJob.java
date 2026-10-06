package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.index.layer.local.Bind;
import dev.jvmd.index.layer.local.Bound;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.HeaderProof;
import dev.jvmd.index.layer.local.ProofCollector;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.local.SourceFacts;
import dev.jvmd.index.layer.machine.Fact;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One module and scope (stage 2, 4 step 2 and 5.4): bind its route, header-compile its source files against the classpath of jars
 * and sibling stubs, take every declaration through {@code Φ_src} into facts, and build the module's leaf with the same shape as a
 * jar's. Bodies are never looked at. Faults are per file and per declaration and never stop the job; a failure of anything else does.
 */
final class ModuleJob {
    private final Boot boot;

    ModuleJob(Boot boot) { this.boot = boot; }

    /** A source file found under a source root: javac reads it, once, when it asks for it. */
    private record Found(String path, Path file, long mtimeNanos) { }

    /** A file's row before its header proof, which needs the definer indexes of the route (3.18). Nothing of its bytes. */
    private record Pending(String path, long size, long mtimeNanos, Identity kappa, Identity sum, List<String> types, List<FileRow.Fault> faults,
                           Set<String> headerTargets, ProofCollector.Observations reads) { }

    /** What {@code Stage2} needs back: the leaf. Everything else is recorded in {@link Boot}. */
    MachineLeaf run(ProjectModel.Module module, int scope) throws IOException {
        var digest = boot.digest;
        var sums = boot.tree.sums();
        var store = boot.store;
        var sink = boot.sink;
        var entries = boot.entries.get(Boot.routeKey(module.name(), scope));

        // 1. Bind the route. No session providers exist in a cold boot.
        var bound = Bind.bind(digest, entries, Bind.NONE, boot.built.provider(), boot::leaf, sink);
        boot.routes.put(Boot.routeKey(module.name(), scope), new Route(entries, bound.routeHash(), bound.r(), bound.leafSetExt(), bound.leafSetSib()));

        // 2. Stubs for siblings; jars go on the classpath as jars.
        var classpath = new ArrayList<Path>();
        for (var binding : bound.bindings()) {
            if (binding.origin() == Bound.Origin.SIBLING) classpath.add(boot.stubDir(binding.k()));
            else if (binding.entry() instanceof RouteEntry.Jar jar) classpath.add(boot.repository.resolve(jar.location()));
        }

        // 3. Header-compile the scope's source files. A module-info.java is parsed with the rest and never entered.
        var options = new ArrayList<>(module.javacOptions());
        options.addAll(module.processing().options());
        int release = HeaderCompiler.effectiveRelease(module.release(), options.contains("--enable-preview"), Runtime.version().feature());
        var found = sources(module.scope(scope).sourceRoots());
        var toCompile = new ArrayList<HeaderCompiler.Source>(found.size());
        for (var file : found) toCompile.add(new HeaderCompiler.Source(file.path(), file.file()));
        boot.sourceFiles.addAndGet(found.size());
        var facts = new ArrayList<Fact>();
        var builder = new LeafBuilder(boot.tree, sink);
        var pending = new ArrayList<Pending>();
        var seen = new HashSet<ByteBuffer>();
        var processing = module.processing().path().isEmpty() || found.isEmpty() ? null : new ModuleProcessing(boot, module, scope, options, release,
                found.stream().map(Found::path).toList());
        var processorHost = processing == null ? null : processing.host;
        long headerStarted = System.nanoTime();
        HeaderCompiler.Compiled compiled;
        try { compiled = HeaderCompiler.compile(toCompile, classpath, Path.of(boot.model.jdkHome()), release, options, digest, processorHost); }
        catch (RuntimeException | Error failed) { if (processorHost != null) processorHost.close(); throw failed; }
        boot.headerNanos.addAndGet(System.nanoTime() - headerStarted);
        try (processorHost; compiled) {
            var extract = new SourceFacts(digest, compiled.elements, compiled.types, options.contains("-parameters"));
            var declarations = new SourceDeclarations(boot, compiled);
            var unitsByPath = new HashMap<String, HeaderCompiler.Unit>();
            for (var u : compiled.units) unitsByPath.put(u.path, u);
            var initialPaths = found.stream().map(Found::path).collect(Collectors.toSet());
            for (var unit : compiled.units) if (!initialPaths.contains(unit.path)) {
                found.add(new Found(unit.path, boot.model.resolve(unit.path), 0));
                boot.sourceFiles.incrementAndGet();
            }
            // 4. Each compilation unit, in path order.
            for (var file : found) {
                var unit = unitsByPath.get(file.path());
                var kappa = unit.kappa == null ? sums.zero() : unit.kappa; // javac could not read it: the file is a parse fault
                if (!unit.parsed()) {
                    pending.add(new Pending(file.path(), unit.size, file.mtimeNanos(), kappa, sums.zero(), List.of(),
                            List.of(new FileRow.Fault(new byte[0], unit.parseError)), Set.of(), ProofCollector.Observations.NONE));
                    continue;
                }
                // No boot-wide memo of a file's facts (2.6): the header proof in the file row is what lets a later layer keep them.
                long factsStarted = System.nanoTime();
                SourceFacts.Result result;
                if (unit.module != null) {
                    // The descriptor of the module's own main code; a test scope has none (its module is patched, not declared).
                    result = scope == LocalStore.MAIN ? extract.ofModule(unit.module, moduleVersion(options), name -> boot.moduleVersion(name, bound.sequence(), releaseOption(options)))
                            : SourceFacts.Result.NONE;
                } else result = extract.of(unit.declared, unit.packageDeclaration, unit.packageClass);
                var proofDeclarations = new ArrayList<javax.lang.model.element.Element>(unit.declared);
                if (unit.packageDeclaration != null) proofDeclarations.add(unit.packageDeclaration);
                var reads = ProofCollector.headers(proofDeclarations, compiled.trees, compiled.elements, compiled.types);
                boot.parsedFiles.incrementAndGet();
                var faults = new ArrayList<>(result.faults());
                faults.addAll(unit.faults);
                declarations.add(file.path(), unit.declared, unit.packageDeclaration, faults);
                boot.factsNanos.addAndGet(System.nanoTime() - factsStarted);
                var sum = sums.zero();
                var kept = new HashSet<ByteBuffer>();
                for (var fact : result.facts()) {
                    // The chunker needs strictly increasing keys: a second declaration with one key (C.5) is a fault on the later one.
                    if (!seen.add(ByteBuffer.wrap(fact.m()))) { faults.add(new FileRow.Fault(fact.m(), "duplicate declaration")); continue; }
                    kept.add(ByteBuffer.wrap(fact.m()));
                    facts.add(fact);
                    sum = sums.add(sum, fact.h());
                }
                builder.edges(result.edges());
                var types = new ArrayList<String>();
                for (var type : result.typeKeys()) if (kept.contains(ByteBuffer.wrap(Keys.typeKey(type)))) types.add(type);
                var absences = new ArrayList<>(reads.absences());
                var headerTargets = new TreeSet<>(result.headerTargets());
                if (processorHost != null) for (var read : processorHost.readsFor(file.file().toAbsolutePath().normalize().toUri())) {
                    headerTargets.addAll(read.types());
                    for (var name : read.missingTypes()) for (var candidate : ProcessorReads.absentCandidates(name))
                        absences.add(new HeaderProof.Absence(0, candidate, ""));
                }
                pending.add(new Pending(file.path(), unit.size, file.mtimeNanos(), kappa, sum, types, faults, headerTargets,
                        new ProofCollector.Observations(reads.ranges(), absences.stream().distinct().toList())));
            }
            declarations.finish(module.name(), scope);
        }

        // 5. Sort the facts by m and stream them into the leaf's shape.
        facts.sort((a, b) -> Arrays.compareUnsigned(a.m(), b.m()));
        for (var fact : facts) builder.add(fact);
        var k = builder.seal();
        store.putAnnotationLeaf(builder.a(), new dev.jvmd.index.layer.machine.AnnotationLeaf(builder.annotations(), builder.annotationEdges()).encode());

        // The leaf: the first job to reach this key finds or writes it, and the others take that one (Built.once). A leaf MACHINE or an
        // earlier project already holds is not built again.
        var leaf = boot.built.once(k, () -> {
            boot.sourceLeaves.incrementAndGet();
            var stored = store.get(MachineStore.leafKey(k));
            if (stored != null) return MachineLeaf.decode(stored, digest.width());
            var made = builder.build();
            store.putLeaf(k, made.encode());
            sink.flush();
            return made;
        });
        sink.flush();

        // 6. Register the leaf for the modules that depend on this one.
        boot.built.register(module.name(), module.coordinate(), scope, leaf, builder.a());

        // 7. The definer indexes of this route, then the header proof of every file from what they resolve.
        long definerStarted = System.nanoTime();
        var resolver = definerIndex(Boot.routeKey(module.name(), scope), bound);
        boot.definerNanos.addAndGet(System.nanoTime() - definerStarted);
        var own = new HashMap<String, Identity>();
        for (var type : builder.types()) own.put(Keys.ownerOf(type.key()), type.h());
        for (var p : pending) {
            var all = new TreeSet<>(p.reads().ranges());
            // Implicit declaration types (Object, Enum, generated record methods) also contribute facts without an explicit source tree.
            for (var target : p.headerTargets()) all.add(new HeaderProof.Range(target, Keys.TYPE, ""));
            java.util.function.Function<String, MachineLeaf> definer = type -> {
                if (own.containsKey(type)) return leaf;
                var external = resolver.definer(type);
                return external == null ? null : boot.leaf(external);
            };
            var absences = p.reads().absences().stream().filter(a -> HeaderProof.absent(a, boot.tree, definer, boot::node)).toList();
            var row = new FileRow(p.path(), p.kappa(), p.size(), p.mtimeNanos(), p.sum(), List.copyOf(p.types()), List.copyOf(p.faults()), headerProof(all, definer), leaf.r(), absences);
            boot.files.put(new dev.jvmd.index.layer.local.SourceUnit(module.name(), scope, row.path()), row);
            for (var fault : row.faults()) boot.faults.add(row.path() + ": " + (fault.m().length == 0 ? "" : Keys.ownerOf(fault.m()) + ": ") + fault.reason());
        }
        if (processing != null) {
            var rows = new TreeMap<String, FileRow>();
            for (var p : pending) rows.put(p.path(), boot.files.get(new dev.jvmd.index.layer.local.SourceUnit(module.name(), scope, p.path())));
            processing.finish(rows);
        }
        sink.flush();
        return leaf;
    }

    /**
     * The {@code --release N} the build passed, or 0. It is not the model's {@code release}: a build that passes {@code -source} and
     * {@code -target} reads the JDK's own modules, with their own version strings, while one that passes {@code --release} reads
     * {@code ct.sym}, where every module's version is {@code N}. Which of the two the build did is in its options, as passed.
     */
    private static int releaseOption(List<String> options) {
        for (int i = 0; i < options.size(); i++) {
            String value = options.get(i).equals("--release") && i + 1 < options.size() ? options.get(i + 1)
                    : options.get(i).startsWith("--release=") ? options.get(i).substring("--release=".length()) : null;
            if (value != null) { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException notANumber) { return 0; } }
        }
        return 0;
    }

    /** The {@code --module-version} the build passed, which javac writes into the module's own descriptor; null if none. */
    private static String moduleVersion(List<String> options) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals("--module-version") && i + 1 < options.size()) return options.get(i + 1);
            if (options.get(i).startsWith("--module-version=")) return options.get(i).substring("--module-version=".length());
        }
        return null;
    }

    /** Resolve each observed T prefix in the same own-first binding used for javac, retaining expected-zero member ranges. */
    private List<FileRow.Proof> headerProof(Set<HeaderProof.Range> ranges, java.util.function.Function<String, MachineLeaf> definer) {
        var proof = new ArrayList<FileRow.Proof>(ranges.size());
        for (var range : ranges) {
            var leaf = definer.apply(range.type());
            if (leaf != null) proof.add(new FileRow.Proof(range.type(), range.kind(), range.name(),
                    boot.tree.rangeSum(leaf.k(), boot::node, range.key())));
        }
        return proof;
    }

    /**
     * The definer indexes of a route (3.6, 3.17, 5.5): the external and the sibling disjoint index, each folded from the planned parent of
     * its kind, and the conflict table across both. A disjoint index the store already holds (another project wrote the external one)
     * is not built again: it is a shared, derivable record, and reading it is allowed.
     */
    private DefinerIndex.Resolver definerIndex(String routeKey, Bound bound) {
        var parentKey = boot.parents.get(routeKey);
        var parent = parentKey == null ? null : boot.indexMemo.route(parentKey);
        var externalBase = parent == null
                ? state(IndexMemo.Kind.EXTERNAL, boot.jdkLeafSet, boot.jdkLeaves, null) : parent.external();
        var external = state(IndexMemo.Kind.EXTERNAL, bound.leafSetExt(), bound.external(), externalBase);
        var sibling = state(IndexMemo.Kind.SIBLING, bound.leafSetSib(), bound.sibling(), parent == null ? null : parent.sibling());
        boot.indexMemo.route(routeKey, new IndexMemo.States(external, sibling));
        if (boot.indexMemo.claimConflicts(bound.routeHash())) {
            var root = DefinerIndex.encodeRoot(DefinerIndex.conflicts(boot.digest, boot.tree, external, sibling, bound.sequence(), boot.sink));
            boot.store.put(LocalStore.conflictsKey(bound.routeHash()), root);
            boot.definers.put(LocalStore.conflictsKey(bound.routeHash()), root);
        }
        return new DefinerIndex.Resolver(external, sibling, bound.sequence());
    }

    /**
     * The state of one leaf set, and its disjoint record. Folded once per {@code (kind, key)} in the boot: the first job to ask claims it
     * and the others wait for that fold ({@link IndexMemo#once}), so jobs whose routes share an external leaf set do not each fold it.
     */
    private DefinerIndex.State state(IndexMemo.Kind kind, Identity key, List<Identity> leaves, DefinerIndex.State base) {
        return boot.indexMemo.once(kind, key, () -> {
            var state = DefinerIndex.fold(boot.tree, leaves, base, boot::leaf, boot::node);
            var recordKey = kind == IndexMemo.Kind.EXTERNAL ? LocalStore.disjointKey(key) : LocalStore.siblingKey(key);
            var value = boot.store.get(recordKey);
            if (value != null) state.disjoint(DefinerIndex.decodeRoot(value, boot.digest.width()));
            else {
                // Edit the parent's tree. Its nodes were flushed before that route's states were published.
                var root = DefinerIndex.disjoint(boot.digest, boot.tree, state, boot::node, boot.sink);
                value = DefinerIndex.encodeRoot(root);
                state.disjoint(root);
                boot.store.put(recordKey, value);
                boot.sink.flush(); // another job may take this state as its base and read its tree
            }
            boot.definers.put(recordKey, value);
            return state;
        });
    }

    // ---- source files ------------------------------------------------------------------------------------------------------

    /** Step 1.4: regular {@code *.java} under the source roots, in path order, one stat each. A root that does not exist is skipped. */
    private List<Found> sources(List<String> roots) throws IOException {
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
        var out = new ArrayList<Found>(byPath.size());
        for (var e : byPath.entrySet()) {
            var instant = Files.readAttributes(e.getValue(), BasicFileAttributes.class).lastModifiedTime().toInstant();
            out.add(new Found(e.getKey(), e.getValue(), instant.getEpochSecond() * 1_000_000_000L + instant.getNano()));
        }
        return out;
    }
}
