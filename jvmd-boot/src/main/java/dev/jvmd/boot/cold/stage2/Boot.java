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
import dev.jvmd.index.layer.machine.MachineLeaf;
import dev.jvmd.index.layer.machine.Stubs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
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
 * {@code FileMemo}, {@code IndexMemo}, the records to be written, the stub directories javac reads sibling modules from, and the
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
    final FileMemo fileMemo = new FileMemo();
    final IndexMemo indexMemo = new IndexMemo();
    /** The routes as step 1 resolved them: {@code module \0 scope -> entries}. */
    final Map<String, List<RouteEntry>> entries = new ConcurrentHashMap<>();
    /** The bound routes as the jobs wrote them. */
    final Map<String, Route> routes = new ConcurrentHashMap<>();
    final Map<String, FileRow> files = new ConcurrentHashMap<>();
    /** {@code DD|}, {@code DS|} and {@code DC|} records this boot used: they are part of the LOCAL tree of the project that used them. */
    final ConcurrentSkipListMap<byte[], byte[]> definers = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
    final ConcurrentLinkedQueue<String> faults = new ConcurrentLinkedQueue<>();
    final AtomicInteger sourceFiles = new AtomicInteger(), parsedFiles = new AtomicInteger(), sourceLeaves = new AtomicInteger(), compiledFiles = new AtomicInteger();
    /** Summed over jobs, so with several workers they exceed the wall time: header compilation (parse, enter, member completion), Φ_src, and definer indexes. */
    final AtomicLong headerNanos = new AtomicLong(), factsNanos = new AtomicLong(), definerNanos = new AtomicLong();
    private final ConcurrentHashMap<Identity, MachineLeaf> machineLeaves = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, Path> stubDirs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Optional<String>> systemVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identity, Optional<String[]>> moduleFacts = new ConcurrentHashMap<>();
    private volatile Path stubRoot;
    private FileSystem jrt;

    Boot(Digest digest, ContentTree tree, LocalStore store, ProjectModel model, Identity projectKey, Path repository) {
        this.digest = digest;
        this.tree = tree;
        this.store = store;
        this.model = model;
        this.projectKey = projectKey;
        this.repository = repository;
        this.sink = written.through(store);
    }

    static String routeKey(String module, int scope) { return module + "\0" + scope; }

    /**
     * The leaf of {@code k}: a leaf some job of this boot built comes from {@link Built}, which holds the object; only a MACHINE leaf
     * (a jar's, or a source leaf some earlier boot wrote) is read from the store ({@code L|k}).
     */
    MachineLeaf leaf(Identity k) {
        var sibling = built.byKey(k);
        if (sibling != null) return sibling;
        return machineLeaves.computeIfAbsent(k, key -> {
            var bytes = store.getLeaf(key);
            if (bytes == null) throw new IllegalStateException("No leaf for a bound key");
            return MachineLeaf.decode(bytes, digest.width());
        });
    }

    /** {@code N|hash}: a MACHINE read. */
    byte[] node(Identity hash) {
        var bytes = store.getNode(hash);
        if (bytes == null) throw new IllegalStateException("No node for a stored hash");
        return bytes;
    }

    /**
     * A directory of the stub class files of leaf {@code k}, made on first use (3.14). The stubs are {@code S|k}, shared across
     * projects and a pure function of {@code k}: a record some earlier boot wrote is read, and only a missing one is synthesised and written.
     */
    Path stubDir(Identity k) {
        return stubDirs.computeIfAbsent(k, key -> {
            try {
                var cached = store.getStub(key);
                List<Stubs.Stub> stubs;
                if (cached != null) stubs = Stubs.decode(cached);
                else {
                    stubs = Stubs.stubs(tree, leaf(key), this::node);
                    store.putStub(key, Stubs.encode(stubs));
                }
                if (stubRoot == null) synchronized (this) { if (stubRoot == null) stubRoot = Files.createTempDirectory("jvmd-stubs-"); }
                var dir = Files.createTempDirectory(stubRoot, "s");
                for (var stub : stubs) {
                    var file = dir.resolve(stub.internalName() + ".class");
                    Files.createDirectories(file.getParent());
                    Files.write(file, stub.bytes());
                }
                return dir;
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

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
            var entry = tree.get(key, this::node, new Codec.Writer(16).zstr("module-info").u8(0).toBytes());
            if (entry == null) return Optional.empty();
            var value = new Codec.Reader(entry.value());
            var res = new Codec.Reader(value.raw(value.count()));
            res.u8(); res.u16(); optStr(res); optStr(res);
            for (int i = res.count(); i > 0; i--) res.str();
            for (int i = res.count(); i > 0; i--) res.str();
            optStr(res); optStr(res);
            for (int i = res.count(); i > 0; i--) { res.str(); res.str(); optStr(res); }
            String name = res.str();
            res.u16();
            return Optional.of(new String[] {name, optStr(res)});
        });
    }

    private static String optStr(Codec.Reader in) { return in.u8() == 1 ? in.str() : null; }

    @Override public void close() {
        try { if (jrt != null && jrt != FileSystems.getFileSystem(java.net.URI.create("jrt:/"))) jrt.close(); } catch (IOException | RuntimeException ignored) { /* nothing to recover */ }
        var root = stubRoot;
        if (root == null) return;
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) { /* a temporary directory the OS will collect */ }
    }
}
