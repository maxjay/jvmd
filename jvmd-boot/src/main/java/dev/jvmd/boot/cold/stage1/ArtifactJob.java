package dev.jvmd.boot.cold.stage1;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.index.layer.machine.ClassFacts;
import dev.jvmd.index.layer.machine.LeafBuilder;
import dev.jvmd.index.layer.machine.MachineStore;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 2 (stage 1, 4 and 5.4): one location in, one leaf (or an attach) out. Facts are streamed in owner order into the chunker
 * of {@code T}, so a job's memory is one open chunk per level plus the {@code N}, {@code E} and {@code O} buffers of its jar,
 * independent of jar size. A jar is mapped, not read into an array, so its size is bounded by the file system and not by Java.
 *
 * <p>There are no runtime checks of the sums: {@code sum(T)}, {@code sum(N)} and {@code sum(O)} are the same additions in the
 * same ring, so a check would compare a value with itself computed twice. They are invariants of the test suite (7.3).
 */
public final class ArtifactJob {
    /** What indexing one location gave: its leaf key, or null if it is not a readable archive, and the entries skipped in it. */
    public record Indexed(Identity k, List<String> faults) { }

    /**
     * Indexes one location into the shared node space and returns its leaf key (stage 2, 3.15): the same {@code L|k} and nodes a
     * MACHINE boot would write for these bytes. No {@code P|} is written: that record describes MACHINE's own commit.
     */
    public static Indexed index(Digest digest, ContentTree tree, int jdkFeature, Written written, MachineStore store, Stage1.Parser parser, Enumerate.Location location) {
        var seen = new Seen();
        var leaves = new Leaves();
        new ArtifactJob(digest, tree, jdkFeature, seen, leaves, written, store, parser, new ClassMemo(digest, List.of(location))).run(location);
        var observation = seen.all().get(0);
        if (seen.unreadableReason(observation) != null) return new Indexed(null, List.of(seen.unreadableReason(observation)));
        return new Indexed(leaves.kFor(observation.bh()), seen.skipped(observation.bh()));
    }

    /** Copy-and-digest chunk when hashing a mapped jar. One per worker thread; it sizes a copy, not a limit on anything. */
    private static final int HASH_CHUNK = 1 << 20;

    private final Digest digest;
    private final ContentTree tree;
    private final int jdkFeature;
    private final Seen seen;
    private final Leaves leaves;
    private final NodeSink sink;
    private final MachineStore store;
    private final Stage1.Parser parser;
    private final ClassMemo memo;

    ArtifactJob(Digest digest, ContentTree tree, int jdkFeature, Seen seen, Leaves leaves, Written written, MachineStore store,
                Stage1.Parser parser, ClassMemo memo) {
        this.digest = digest;
        this.tree = tree;
        this.jdkFeature = jdkFeature;
        this.seen = seen;
        this.leaves = leaves;
        this.sink = written.through(store);
        this.store = store;
        this.parser = parser;
        this.memo = memo;
    }

    void run(Enumerate.Location location) {
        try { work(location); } finally { memo.release(location); }
    }

    private static MemorySegment map(Path file, Arena arena) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
    }

    private Identity hash(MemorySegment jar) {
        var hasher = digest.hasher();
        var chunk = new byte[HASH_CHUNK];
        for (long at = 0; at < jar.byteSize(); ) {
            int n = (int) Math.min(chunk.length, jar.byteSize() - at);
            MemorySegment.copy(jar, ValueLayout.JAVA_BYTE, at, chunk, 0, n);
            hasher.update(chunk, 0, n);
            at += n;
        }
        return hasher.finish();
    }

    /** Φ of one class entry, through the memo. A hit is re-checked against the owner the entry name promises. */
    private ClassFacts facts(ClassMemo.Scope scope, Entries.Item item, byte[] classBytes) throws ClassFacts.Fault {
        var known = scope.get(item.crc(), item.size(), classBytes, () -> parser.parse(digest, classBytes, item.owner()));
        if (known.ownerKey().equals(item.owner())) return known;
        // Same bytes, but this entry's name promises another owner: parse for real so the mismatch is the fault it must be.
        return parser.parse(digest, classBytes, item.owner());
    }

    private void work(Enumerate.Location location) {
        var builder = new LeafBuilder(tree, sink);
        var faults = new ArrayList<String>();
        Identity bh;
        // The arena owns the mapping; leaving this block releases the jar before N, E and O are built.
        try (var arena = location.isModule() ? null : Arena.ofConfined()) {
            MemorySegment jar = null;
            if (location.isModule()) {
                bh = location.bh();
            } else {
                try {
                    jar = map(location.file(), arena);
                    bh = hash(jar);
                } catch (IOException unreadable) {
                    // No bytes, so no byte hash: the path record gets the zero identity, no leaf and one fault (3.9, B.5).
                    seen.observeUnreadable(location, Identity.zero(digest.width()), unreadable.getMessage());
                    return;
                }
            }
            seen.observe(location, bh);
            if (!seen.claim(bh)) return; // another location holds these bytes; its job decides this location's leaf

            Entries entries;
            List<Entries.Item> classes;
            try {
                entries = location.isModule() ? Entries.module(location.jrt(), location.module()) : Entries.zip(jar, jdkFeature);
                classes = entries.classes();
            } catch (IOException notAnArchive) {
                seen.unreadable(bh, notAnArchive.getMessage());
                return;
            }

            var scope = memo.open(location);
            for (var item : classes) {
                ClassFacts facts;
                try {
                    facts = facts(scope, item, entries.read(item));
                } catch (ClassFacts.Fault | IOException fault) {
                    faults.add(item.path());
                    continue;
                }
                if (facts.facts().isEmpty()) continue; // a local or anonymous class (stage 2, C.4): no facts, no type entry
                for (var fact : facts.facts()) builder.add(fact);
                builder.edges(facts.edges());
            }
        }
        seen.faults(bh, faults); // per location, into P| at commit

        var k = builder.seal();
        if (!leaves.claim(k)) {
            leaves.attach(bh, k);
            sink.flush();
            return;
        }

        var leaf = builder.build();
        // L is a function of k, so the winner writes it in its own batch, together with its nodes.
        store.putLeaf(k, leaf.encode());
        sink.flush();
        leaves.register(leaf, bh);
    }
}
