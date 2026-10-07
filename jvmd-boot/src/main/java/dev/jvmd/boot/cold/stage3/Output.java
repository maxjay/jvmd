package dev.jvmd.boot.cold.stage3;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ResultRecord;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/** The exact class output map and Diff-driven materialisation (stage 3 B.5). */
public final class Output {
    private Output() { }
    public record Changes(int written, int deleted) { }
    private static final String WORK = ".jvmd-output";
    // Bounded JVM coordination (FileLock alone throws for overlapping locks in the same JVM).
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];
    static { Arrays.setAll(LOCKS, i -> new ReentrantLock()); }
    enum Step { INTENT, STAGE_WRITE, STAGE, DELETE, INSTALL, COMMIT }
    @FunctionalInterface interface Boundary { void after(Step step) throws IOException; }
    private record Staged(Path target, Path temporary, Identity content) { }

    /** Failed units contribute no entries, even if javac emitted a partial class before reporting an error. */
    public static Root build(ContentTree tree, NodeSink sink, Collection<ResultRecord> results) {
        return tree.build(entries(results).values(), sink);
    }

    /**
     * Apply results of changed/retired units only. Before-results must come from those units' admitted previous selections.
     * Unchanged class names are probed only when needed to reject an output collision; no unchanged CF payload is read.
     */
    public static Root apply(ContentTree tree, LocalStore store, Root previous,
                             Collection<ResultRecord> before, Collection<ResultRecord> after) {
        var old = entries(before); var next = entries(after);
        var removed = new ArrayList<byte[]>(); var added = new ArrayList<Entry>();
        for (var entry : old.values()) {
            var actual = tree.get(previous.hash(), h -> store.get(MachineStore.nodeKey(h)), entry.key());
            if (actual == null || !actual.h().equals(entry.h()))
                throw new IllegalArgumentException("Previous output differs from the selected unit");
            var replacement = next.get(entry.key());
            if (replacement == null || !replacement.h().equals(entry.h())) removed.add(entry.key());
        }
        for (var entry : next.values()) {
            var prior = old.get(entry.key());
            if (prior != null && prior.h().equals(entry.h())) continue;
            if (prior == null && tree.get(previous.hash(), h -> store.get(MachineStore.nodeKey(h)), entry.key()) != null)
                throw new IllegalArgumentException("Two clean units emitted " + name(entry));
            added.add(entry);
        }
        return tree.apply(previous, removed, added, h -> store.get(MachineStore.nodeKey(h)), store);
    }

    private static TreeMap<byte[],Entry> entries(Collection<ResultRecord> results) {
        var entries = new TreeMap<byte[], Entry>(Arrays::compareUnsigned);
        for (var result : results) if (result.attributed()) for (var file : result.classFiles()) {
            validateName(file.internalName());
            var key = Keys.ownerKey(file.internalName());
            if (entries.putIfAbsent(key, new Entry(key, file.contentHash().bytes(), file.contentHash())) != null)
                throw new IllegalArgumentException("Two clean units emitted " + file.internalName());
        }
        return entries;
    }

    /**
     * Materialises the selected OUT root. MAT is the last completed root; a nonempty MATP is a pending transition
     * which must finish before MAT can describe the current directory, or a different target can start.
     * Only Diff's paths are touched. Verified payloads are staged one at a time, never accumulated in memory.
     */
    public static Changes materialise(ContentTree tree, LocalStore store, Identity project, String module, int scope,
                                      Root output, Path directory) throws IOException {
        return materialise(tree, store, project, module, scope, output, directory, step -> { });
    }

    static Changes materialise(ContentTree tree, LocalStore store, Identity project, String module, int scope,
                               Root output, Path directory, Boundary boundary) throws IOException {
        Files.createDirectories(directory);
        var root = directory.toRealPath();
        var directoryId = tree.digest().hash(root.toString().getBytes(StandardCharsets.UTF_8));
        var marker = LocalStore.materialisedKey(project, module, scope, directoryId);
        var pending = LocalStore.materialisingKey(project, module, scope, directoryId);
        var lock = LOCKS[Math.floorMod(root.hashCode(), LOCKS.length)];
        lock.lock();
        try {
            var work = checked(root, root.resolve(WORK));
            Files.createDirectories(work);
            try (var channel = FileChannel.open(checked(root, work.resolve("lock")), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.lock()) {
                // One scope/store lineage owns a directory. Other writers cannot silently reuse independent MAT histories.
                var ownerKey = LocalStore.materialisationOwnerKey(directoryId);
                var token = store.get(ownerKey);
                if (token == null) {
                    token = java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
                    store.put(ownerKey, token); store.flush(); store.sync();
                }
                var expected = new Codec.Writer().raw(marker).raw(token).toBytes();
                var owner = ByteBuffer.wrap(expected);
                if (channel.size() == 0) {
                    while (owner.hasRemaining()) channel.write(owner);
                    channel.force(true);
                } else {
                    if (channel.size() != expected.length) throw new IOException("Output directory belongs to another materialisation: " + root);
                    var actual = ByteBuffer.allocate(expected.length);
                    while (actual.hasRemaining() && channel.read(actual) >= 0) { }
                    if (!Arrays.equals(actual.array(), expected)) throw new IOException("Output directory belongs to another materialisation: " + root);
                }
                return locked(tree, store, marker, pending, output, root, work, boundary);
            }
        } finally { lock.unlock(); }
    }

    private static Changes locked(ContentTree tree, LocalStore store, byte[] marker, byte[] pending, Root output,
                                  Path root, Path work, Boundary boundary) throws IOException {
        var recorded = store.get(marker);
        Root previous;
        if (recorded == null) { previous = tree.build(List.of(), store); store.flush(); }
        else previous = DefinerIndex.decodeRoot(recorded, tree.digest().width());
        int written = 0, deleted = 0;
        var interrupted = store.get(pending);
        if (interrupted != null && interrupted.length != 0) {
            var target = DefinerIndex.decodeRoot(interrupted, tree.digest().width());
            var repaired = transition(tree, store, marker, pending, previous, target, root, work, boundary);
            written += repaired.written(); deleted += repaired.deleted(); previous = target;
        }
        if (!previous.hash().equals(output.hash()) || recorded == null) {
            var changes = transition(tree, store, marker, pending, previous, output, root, work, boundary);
            written += changes.written(); deleted += changes.deleted();
        }
        return new Changes(written, deleted);
    }

    private static Changes transition(ContentTree tree, LocalStore store, byte[] marker, byte[] pending,
                                      Root previous, Root output, Path root, Path work, Boundary boundary) throws IOException {
        var diff = Diff.trees(tree.digest(), previous, output, hash -> store.get(MachineStore.nodeKey(hash)));
        var removed = new ArrayList<Path>();
        var added = new LinkedHashMap<Path, Staged>();
        // Paths and immutable references only. Validate every path before publishing intent or touching class files.
        for (var entry : diff.removed()) removed.add(target(root, name(entry)));
        for (var entry : diff.added()) {
            var path = target(root, name(entry));
            var content = Identity.of(entry.value());
            var stageId = tree.digest().hash(new Codec.Writer().id(output.hash()).raw(entry.key()).toBytes());
            var temporary = checked(root, work.resolve(java.util.HexFormat.of().formatHex(stageId.bytes()) + ".stage"));
            if (added.putIfAbsent(path, new Staged(path, temporary, content)) != null)
                throw new IOException("Class output paths collide: " + path);
        }
        store.put(pending, DefinerIndex.encodeRoot(output)); store.flush(); store.sync();
        boundary.after(Step.INTENT);
        for (var entry : added.values()) stage(tree, store, entry, boundary);
        int deleted = 0;
        for (var path : removed) if (!added.containsKey(path)) {
            Files.deleteIfExists(checked(root, path)); deleted++; boundary.after(Step.DELETE);
        }
        for (var entry : added.values()) {
            checked(root, entry.target());
            Files.createDirectories(entry.target().getParent());
            move(entry.temporary(), entry.target()); boundary.after(Step.INSTALL);
        }
        // One atomic store batch: no state can expose the new MAT together with the old pending transition.
        store.put(marker, DefinerIndex.encodeRoot(output)); store.put(pending, new byte[0]); store.flush(); store.sync();
        boundary.after(Step.COMMIT);
        return new Changes(added.size(), deleted);
    }

    private static void stage(ContentTree tree, LocalStore store, Staged entry, Boundary boundary) throws IOException {
        var bytes = store.get(LocalStore.classFileKey(entry.content()));
        if (bytes == null || !tree.digest().hash(bytes).equals(entry.content()))
            throw new IOException("Missing or corrupt class bytes for " + entry.target());
        try (var channel = FileChannel.open(entry.temporary(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            boundary.after(Step.STAGE_WRITE);
            channel.force(true);
        }
        boundary.after(Step.STAGE);
    }

    private static String name(Entry entry) {
        var in = new Codec.Reader(entry.key());
        String name = in.zstr();
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing output key bytes");
        return name;
    }

    private static void validateName(String name) {
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid internal class name: " + name);
        for (var segment : name.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
            throw new IllegalArgumentException("Invalid internal class name: " + name);
    }

    private static Path target(Path root, String name) throws IOException {
        validateName(name);
        if (name.startsWith(WORK + "/")) throw new IOException("Class output uses reserved materialisation path: " + name);
        var target = root.resolve(name + ".class").normalize();
        if (!target.startsWith(root)) throw new IOException("Class output escapes its directory: " + name);
        return checked(root, target);
    }

    private static Path checked(Path root, Path target) throws IOException {
        var current = root;
        for (var component : root.relativize(target)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current)))
                throw new IOException("Class output traverses a filesystem link: " + current);
        }
        return target;
    }

    private static void move(Path temporary, Path target) throws IOException {
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
