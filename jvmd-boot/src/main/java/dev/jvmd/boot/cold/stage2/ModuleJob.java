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
import java.nio.charset.StandardCharsets;
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
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One module and scope (stage 2, 4 step 2 and 5.4): bind its route, header-compile its source files against the classpath of jars
 * and sibling stubs, take every declaration through {@code Φ_src} into facts, and build the module's leaf with the same shape as a
 * jar's. Bodies are never looked at. Faults are per file and per declaration and never stop the job; a failure of anything else does.
 */
final class ModuleJob {
    private final Boot boot;

    ModuleJob(Boot boot) { this.boot = boot; }

    /** A source file as the row records it: no bytes (they are read once, hashed, handed to javac, and let go). */
    private record FileMeta(String path, long size, long mtimeNanos, Identity kappa) { }

    /** A file's row before its header proof, which needs the definer indexes of the route (3.18). */
    private record Pending(FileMeta file, Identity sum, List<String> types, List<FileRow.Fault> faults, List<Entry> edges, List<String> constants) { }

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
        var options = module.javacOptions();
        int release = HeaderCompiler.effectiveRelease(module.release(), options.contains("--enable-preview"), Runtime.version().feature());
        var toCompile = new ArrayList<HeaderCompiler.Source>();
        var found = new ArrayList<FileMeta>();
        for (var file : sources(module.scope(scope).sourceRoots())) {
            toCompile.add(new HeaderCompiler.Source(file.path(), file.bytes()));
            found.add(new FileMeta(file.path(), file.size(), file.mtimeNanos(), file.kappa()));
        }
        boot.sourceFiles.addAndGet(found.size());
        var facts = new ArrayList<Fact>();
        var edges = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        var pending = new ArrayList<Pending>();
        var seen = new HashSet<ByteBuffer>();
        long headerStarted = System.nanoTime();
        var compiled = HeaderCompiler.compile(toCompile, classpath, Path.of(boot.model.jdkHome()), release, options);
        boot.headerNanos.addAndGet(System.nanoTime() - headerStarted);
        boot.compiledFiles.addAndGet(toCompile.size());
        toCompile.clear(); // the source bytes are not kept past the compile
        try (compiled) {
            var extract = new SourceFacts(digest, compiled.elements, compiled.types, compiled.trees, options.contains("-parameters"));
            var unitsByPath = new HashMap<String, HeaderCompiler.Unit>();
            for (var u : compiled.units) unitsByPath.put(u.path, u);
            // 4. Each compilation unit, in path order.
            for (var file : found) {
                var unit = unitsByPath.get(file.path());
                if (!unit.parsed()) {
                    pending.add(new Pending(file, sums.zero(), List.of(), List.of(new FileRow.Fault(new byte[0], unit.parseError)), List.of(), List.of()));
                    continue;
                }
                // No boot-wide memo of a file's facts (2.6): the header proof in the file row is what lets a later layer keep them.
                long factsStarted = System.nanoTime();
                SourceFacts.Result result;
                if (unit.module != null) {
                    // The descriptor of the module's own main code; a test scope has none (its module is patched, not declared).
                    result = scope == LocalStore.MAIN ? extract.ofModule(unit.module, moduleVersion(options), name -> boot.moduleVersion(name, bound.sequence(), releaseOption(options)))
                            : new SourceFacts.Result(List.of(), List.of(), List.of(), List.of(), List.of());
                } else result = extract.of(unit.declared);
                boot.factsNanos.addAndGet(System.nanoTime() - factsStarted);
                boot.parsedFiles.incrementAndGet();
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
                pending.add(new Pending(file, sum, types, faults, result.edges(), result.constantTargets()));
            }
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

        // The leaf: a leaf this boot or MACHINE already holds is not built again. A leaf another job is writing at this moment is not
        // yet visible, and is built here too: every write is of the same bytes under the same keys, so the duplicate is harmless.
        if (boot.written.claimLeaf(k)) boot.sourceLeaves.incrementAndGet();
        MachineLeaf leaf = boot.built.byKey(k);
        if (leaf == null) {
            var stored = store.getLeaf(k);
            if (stored != null) leaf = MachineLeaf.decode(stored, digest.width());
        }
        if (leaf == null) {
            names.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
            var nRoot = boot.tree.build(names, sink);
            var eRoot = boot.tree.build(new ArrayList<>(edges.values()), sink);
            var oRoot = boot.tree.build(types, sink);
            leaf = new MachineLeaf(k, tRoot.sum(), nRoot.hash(), nRoot.level(), eRoot.hash(), eRoot.sum(), eRoot.level(), oRoot.hash(),
                    oRoot.level(), tRoot.count(), types.size(), eRoot.count());
            store.putLeaf(k, leaf.encode());
        }
        sink.flush();

        // 6. Register the leaf for the modules that depend on this one.
        boot.built.register(module.name(), module.coordinate(), scope, leaf);

        // 7. The definer indexes of this route, then the header proof of every file from what they resolve.
        long definerStarted = System.nanoTime();
        var resolver = definerIndex(bound);
        boot.definerNanos.addAndGet(System.nanoTime() - definerStarted);
        var own = new HashMap<String, Identity>();
        for (var type : types) own.put(new String(type.key(), StandardCharsets.ISO_8859_1), type.h());
        for (var p : pending) {
            // The types the headers mention (kind 7) and the types the constants resolved through (kind 8): one proof, two reverse entries.
            var header = headerTargets(p.edges());
            var constants = new TreeSet<>(p.constants());
            constants.removeAll(header);
            var all = new TreeSet<>(header);
            all.addAll(p.constants());
            var row = new FileRow(p.file().path(), p.file().kappa(), p.file().size(), p.file().mtimeNanos(), p.sum(), List.copyOf(p.types()), List.copyOf(p.faults()),
                    headerProof(all, own, resolver));
            boot.files.put(row.path(), row);
            var consumer = new dev.jvmd.index.layer.local.ReverseIndex.Consumer(row.kappa(), bound.leafSetExt());
            var named = row.headerProof().stream().map(FileRow.Proof::typeKey).collect(java.util.stream.Collectors.toSet());
            for (var type : header) if (named.contains(type)) boot.headerConsumers.computeIfAbsent(type, t -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(consumer);
            for (var type : constants) if (named.contains(type)) boot.constantConsumers.computeIfAbsent(type, t -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(consumer);
            for (var fault : row.faults()) boot.faults.add(row.path() + ": " + (fault.m().length == 0 ? "" : describe(fault.m()) + ": ") + fault.reason());
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

    /** The type keys (internal names) a file's {@code E} edges point at: every type its declaration headers mention. */
    private static java.util.Set<String> headerTargets(List<Entry> edges) {
        var targets = new TreeSet<String>();
        for (var edge : edges) {
            var key = edge.key();
            int end = 0;
            while (key[end] != 0) end++;
            targets.add(new String(key, 0, end, StandardCharsets.UTF_8));
        }
        return targets;
    }

    /**
     * 3.18: for every type the file's headers mention (the targets of its {@code E} edges), the {@code oSum} of its definer under this
     * binding. A type the module declares itself is its own definer; any other is resolved sibling, then external, then conflicts. A
     * name nothing declares has no definer and no entry. Sorted by type key, so the row is a function of the file and the binding. The
     * targets are the headers' types and the types a constant initialiser resolved through: both are names the facts depend on.
     */
    private static List<FileRow.Proof> headerProof(java.util.Set<String> targets, Map<String, Identity> own, DefinerIndex.Resolver resolver) {
        var proof = new ArrayList<FileRow.Proof>(targets.size());
        for (var target : targets) {
            var key = typeKeyOf(target);
            var oSum = own.get(new String(key, StandardCharsets.ISO_8859_1));
            if (oSum == null) oSum = resolver.oSum(key);
            if (oSum != null) proof.add(new FileRow.Proof(target, oSum));
        }
        return proof;
    }

    /**
     * The definer indexes of a route (3.6, 3.17, 5.5): the external and the sibling disjoint index, each folded from the nearest state of
     * its kind, and the conflict table across both. A disjoint index the store already holds (another project wrote the external one)
     * is not built again: it is a shared, derivable record, and reading it is allowed.
     */
    private DefinerIndex.Resolver definerIndex(Bound bound) {
        var external = state(IndexMemo.Kind.EXTERNAL, bound.leafSetExt(), bound.external());
        var sibling = state(IndexMemo.Kind.SIBLING, bound.leafSetSib(), bound.sibling());
        if (boot.indexMemo.claimConflicts(bound.routeHash())) {
            var root = DefinerIndex.encodeRoot(DefinerIndex.conflicts(boot.digest, boot.tree, external, sibling, bound.sequence(), boot.sink));
            boot.store.putConflicts(bound.routeHash(), root);
            boot.definers.put(LocalStore.conflictsKey(bound.routeHash()), root);
        }
        return new DefinerIndex.Resolver(external, sibling, bound.sequence());
    }

    /**
     * The state of one leaf set, and its disjoint record. Folded once per {@code (kind, key)} in the boot: the first job to ask claims it
     * and the others wait for that fold ({@link IndexMemo#once}), so jobs whose routes share an external leaf set do not each fold it.
     */
    private DefinerIndex.State state(IndexMemo.Kind kind, Identity key, List<Identity> leaves) {
        return boot.indexMemo.once(kind, key, () -> {
            var state = DefinerIndex.fold(boot.tree, leaves, boot.indexMemo.nearest(kind, leaves), boot::leaf, boot::node);
            boolean external = kind == IndexMemo.Kind.EXTERNAL;
            var recordKey = external ? LocalStore.disjointKey(key) : LocalStore.siblingKey(key);
            var value = external ? boot.store.getDisjoint(key) : boot.store.getSibling(key);
            if (value != null) state.disjoint(DefinerIndex.decodeRoot(value, boot.digest.width()));
            else {
                // Edited from the nearest state's tree when there is one. Its nodes are read from the store, so they are committed first.
                var root = DefinerIndex.disjoint(boot.digest, boot.tree, state, boot::node, boot.sink);
                value = DefinerIndex.encodeRoot(root);
                state.disjoint(root);
                if (external) boot.store.putDisjoint(key, value); else boot.store.putSibling(key, value);
                boot.sink.flush(); // another job may take this state as its base and read its tree
            }
            boot.definers.put(recordKey, value);
            return state;
        });
    }

    // ---- source files ------------------------------------------------------------------------------------------------------

    private record SourceFile(String path, long size, long mtimeNanos, byte[] bytes, Identity kappa) { }

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
            out.add(new SourceFile(e.getKey(), attributes.size(), instant.getEpochSecond() * 1_000_000_000L + instant.getNano(), bytes, boot.digest.hash(bytes)));
        }
        return out;
    }

    // ---- keys ---------------------------------------------------------------------------------------------------------------

    private static byte[] typeKey(String internalName) { return new Codec.Writer(internalName.length() + 2).zstr(internalName).u8(0).toBytes(); }

    /** The {@code O} key of a type: {@code zstr internalName}. */
    private static byte[] typeKeyOf(String internalName) { return new Codec.Writer(internalName.length() + 1).zstr(internalName).toBytes(); }

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
