package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage1.Enumerate;
import dev.jvmd.boot.cold.stage1.Written;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.local.Route;
import dev.jvmd.index.layer.local.RouteEntry;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.MachineStore;
import dev.jvmd.index.layer.machine.Res;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the jobs of one boot share and nothing that outlives it (stage 2, 2.6 and 6): {@code Built}, {@code Written},
 * {@code IndexMemo}, the records to be written, the stub directories javac reads sibling modules from, and the
 * counters of the log line. None of it is written except through the store.
 */
final class Boot implements AutoCloseable {
    final Digest digest;
    final ContentTree tree;
    final LocalStore store;
    final ProjectModel model;
    final Identity projectKey;
    final Path repository;
    final Written written = new Written();
    final NodeSink sink;
    final Built built = new Built();
    final IndexMemo indexMemo = new IndexMemo();
    /** The routes as step 1 resolved them: {@code module \0 scope -> entries}. */
    final Map<String, List<RouteEntry>> entries = new ConcurrentHashMap<>();
    /** Planned ancestry: a test extends its main route; a main extends its first declared sibling's main route. */
    final Map<String, String> parents = new ConcurrentHashMap<>();
    /** Common external base for root modules, bound by step 1 before jobs start. */
    List<Identity> jdkLeaves;
    Identity jdkLeafSet;
    /** The bound routes as the jobs wrote them. */
    final Map<String, Route> routes = new ConcurrentHashMap<>();
    final Map<String, FileRow> files = new ConcurrentHashMap<>();
    /** {@code DD|}, {@code DS|} and {@code DC|} records this boot used: they are part of the LOCAL tree of the project that used them. */
    final ConcurrentSkipListMap<byte[], byte[]> definers = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
    final ConcurrentSkipListMap<byte[], byte[]> processingRecords = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
    private ProcessorConfiguration configuration;
    final ConcurrentLinkedQueue<String> faults = new ConcurrentLinkedQueue<>();
    final AtomicInteger sourceFiles = new AtomicInteger(), parsedFiles = new AtomicInteger(), sourceLeaves = new AtomicInteger();
    /** Summed over jobs, so with several workers they exceed the wall time: header compilation (parse, enter, member completion), Φ_src, and definer indexes. */
    final AtomicLong headerNanos = new AtomicLong(), factsNanos = new AtomicLong(), definerNanos = new AtomicLong();
    private final ConcurrentHashMap<Identity, MachineLeaf> machineLeaves = new ConcurrentHashMap<>();
    private final StubDirectories stubs;
    private final ConcurrentHashMap<String, Optional<String>> systemVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, Optional<String[]>> moduleFacts = new ConcurrentHashMap<>();
    private FileSystem jrt;

    Boot(Digest digest, ContentTree tree, LocalStore store, ProjectModel model, Identity projectKey, Path repository) {
        this.digest = digest;
        this.tree = tree;
        this.store = store;
        this.model = model;
        this.projectKey = projectKey;
        this.repository = repository;
        this.sink = written.through(store);
        this.stubs = new StubDirectories(tree, store, this::leaf);
    }

    static String routeKey(String module, int scope) { return module + "\0" + scope; }

    synchronized ProcessorConfiguration configuration() throws IOException {
        if (configuration != null) return configuration;
        var sources = new java.util.TreeSet<String>();
        var tracked = new java.util.TreeSet<String>();
        for (var module : model.modules()) {
            if (module.processing().path().isEmpty()) continue;
            tracked.addAll(module.processing().configurationFiles());
            for (var scope : List.of(module.main(), module.test())) for (var sourceRoot : scope.sourceRoots()) {
                var directory = model.resolve(sourceRoot);
                if (!Files.isDirectory(directory)) continue;
                try (var walk = Files.walk(directory)) {
                    for (var file : walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).toList()) sources.add(sourcePath(file.toUri()));
                }
            }
        }
        configuration = ProcessorConfiguration.scan(digest, tree, Path.of(model.root()), List.copyOf(sources), List.copyOf(tracked), sink, this::node);
        sink.flush();
        processingRecords.put(LocalStore.resourcesKey(projectKey), dev.jvmd.index.layer.local.DefinerIndex.encodeRoot(configuration.root()));
        return configuration;
    }

    String sourcePath(java.net.URI uri) {
        var base = Path.of(model.root()).toAbsolutePath().normalize();
        var file = Path.of(uri).toAbsolutePath().normalize();
        return (file.startsWith(base) ? base.relativize(file) : file).toString().replace('\\', '/');
    }

    Path generatedDirectory(String module, int scope) {
        var name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(module.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return model.resolve(".jvmd/generated/" + name + "/" + scope);
    }

    /**
     * The leaf of {@code k}: a leaf some job of this boot built comes from {@link Built}, which holds the object; only a MACHINE leaf
     * (a jar's, or a source leaf some earlier boot wrote) is read from the store ({@code L|k}).
     */
    MachineLeaf leaf(Identity k) {
        var sibling = built.byKey(k);
        if (sibling != null) return sibling;
        return machineLeaves.computeIfAbsent(k, key -> {
            var bytes = store.get(MachineStore.leafKey(key));
            if (bytes == null) throw new IllegalStateException("No leaf for a bound key");
            return MachineLeaf.decode(bytes, digest.width());
        });
    }

    /** {@code N|hash}: a MACHINE read. */
    byte[] node(Identity hash) {
        var bytes = store.get(MachineStore.nodeKey(hash));
        if (bytes == null) throw new IllegalStateException("No node for a stored hash");
        return bytes;
    }

    /**
     * A directory of the stub class files of leaf {@code k}, made on first use (3.14). A stub is {@code ST|Digest(typeKey || oSum)},
     * one type's, shared across leaves and projects, and {@code S|k} is the leaf's list of them: both are read first, and only a type
     * whose stub is missing is synthesised and written, so an edit costs the types it changed and not the module.
     */
    Path stubDir(Identity k) { return stubs.get(k).path(); }

    /**
     * The version javac records for {@code requires <module>} in a module descriptor (E.3): the version in the required module's own
     * descriptor. A system module's is read from the JDK the project compiles with; any other module is found among the leaves of the
     * route, which carry the descriptor fact of every jar and sibling that has a {@code module-info}. Null if nothing declares it.
     */
    String moduleVersion(String module, List<Identity> sequence, int release) {
        var system = systemVersions.computeIfAbsent(module, this::systemModuleVersion);
        // With --release N javac reads the JDK's modules from ct.sym, where every module's version is N; without it, the JDK's own.
        if (system.isPresent()) return release > 0 ? String.valueOf(release) : system.get();
        for (var k : sequence) {
            var fact = moduleFact(k);
            if (fact.isPresent() && fact.get()[0].equals(module)) return fact.get()[1];
        }
        return null;
    }

    private Optional<String> systemModuleVersion(String module) {
        try {
            synchronized (this) {
                if (jrt == null) {
                    var home = Path.of(model.jdkHome());
                    jrt = Enumerate.sameFile(home, Path.of(System.getProperty("java.home"))) ? FileSystems.getFileSystem(java.net.URI.create("jrt:/"))
                            : FileSystems.newFileSystem(java.net.URI.create("jrt:/"), Map.of("java.home", home.toString()));
                }
            }
            var descriptor = jrt.getPath("/modules/" + module + "/module-info.class");
            if (!Files.isRegularFile(descriptor)) return Optional.empty();
            var attribute = ClassFile.of().parse(Files.readAllBytes(descriptor)).findAttribute(Attributes.module());
            return attribute.isEmpty() ? Optional.empty() : Optional.of(attribute.get().moduleVersion().map(v -> v.stringValue()).orElse(null));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** {name, version} of the module descriptor fact in leaf {@code k}, if it has one: a point read of {@code T} at {@code module-info}. */
    private Optional<String[]> moduleFact(Identity k) {
        return moduleFacts.computeIfAbsent(k, key -> {
            var entry = tree.get(key, this::node, Keys.typeKey("module-info"));
            if (entry == null) return Optional.empty();
            var module = Res.Type.decode(entry.value()).module();
            return module == null ? Optional.empty() : Optional.of(new String[] {module.name(), module.version()});
        });
    }

    @Override public void close() {
        try { if (jrt != null && jrt != FileSystems.getFileSystem(java.net.URI.create("jrt:/"))) jrt.close(); } catch (IOException | RuntimeException ignored) { /* nothing to recover */ }
        stubs.close();
    }
}
