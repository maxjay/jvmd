package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Entry;
import dev.jvmd.core.tree.NodeSink;
import dev.jvmd.core.tree.Root;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** M (stage 1, 2.5): a ContentTree over {@code k -> L} with entry identity {@code r}, so {@code sum(M)} is the sum of every fact. */
public final class MachineTree {
    private MachineTree() { }

    /** @param leaves already sorted by k (unsigned bytes) */
    public static Root build(ContentTree tree, List<MachineLeaf> leaves, NodeSink sink) {
        var entries = new ArrayList<Entry>(leaves.size());
        for (var leaf : leaves) entries.add(new Entry(leaf.k().bytes(), leaf.encode(), leaf.r()));
        return tree.build(entries, sink);
    }

    /**
     * The ROOT record value (B.5): {@code str FORMAT || id hash || id sum || u32 count || u8 level || id Root}, where the last
     * field is the digest of every preceding byte. A reader verifies the record by recomputing it.
     */
    public static byte[] encodeRoot(Digest digest, Format format, Root m) {
        var body = new Codec.Writer(128).str(format.toString()).id(m.hash()).id(m.sum()).u32(m.count()).u8(m.level()).toBytes();
        return new Codec.Writer(body.length + digest.width()).raw(body).id(digest.hash(body)).toBytes();
    }

    /** A decoded ROOT value. */
    public record RootRecord(String format, Root root, Identity digest) { }

    /** Decodes a ROOT value and verifies its trailing digest; throws if it does not match. */
    public static RootRecord decodeRoot(Digest digest, byte[] value) {
        int width = digest.width();
        var in = new Codec.Reader(value);
        String format = in.str();
        var hash = in.id(width);
        var sum = in.id(width);
        int count = in.count();
        int level = in.u8();
        int bodyLength = in.position();
        var stored = in.id(width);
        if (!digest.hash(Arrays.copyOfRange(value, 0, bodyLength)).equals(stored)) throw new IllegalStateException("ROOT digest mismatch");
        return new RootRecord(format, new Root(hash, sum, count, level), stored);
    }

    /**
     * The {@code P|location} value (B.5): {@code id bh || opt<id> k || u64 size || i64 mtimeNanos || list<str> faults}. The faults
     * are the entry paths skipped in this location (A.7); an unreadable location has the zero {@code bh}, no {@code k} and one fault.
     */
    public static byte[] encodePath(Identity bh, Identity kOrNull, long size, long mtimeNanos, List<String> faults) {
        var out = new Codec.Writer(128).id(bh).optId(kOrNull).u64(size).i64(mtimeNanos).u32(faults.size());
        for (var fault : faults) out.str(fault);
        return out.toBytes();
    }

    /** A decoded {@code P|} value. */
    public record PathRecord(Identity bh, Identity k, long size, long mtimeNanos, List<String> faults) { }

    public static PathRecord decodePath(byte[] value, int width) {
        var in = new Codec.Reader(value);
        var bh = in.id(width);
        var k = in.u8() == 1 ? in.id(width) : null;
        long size = in.u64(), mtime = in.i64();
        int n = in.count();
        var faults = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) faults.add(in.str());
        return new PathRecord(bh, k, size, mtime, List.copyOf(faults));
    }
}
