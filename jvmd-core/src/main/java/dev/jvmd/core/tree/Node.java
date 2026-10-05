package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.hash.Sum;
import java.util.ArrayList;
import java.util.List;

/**
 * One tree node (stage 1, 2.3 and B.1/B.2) and the only place its bytes are encoded or decoded.
 *
 * <pre>
 * entry  = u32 keyLen || key || id h || u32 valueLen || value
 * node0  = u8 0 || u8 0 || u32 count || entry[count]            (tree marker 0; a ContentList node has marker 1, B.2)
 * list0  = u8 0 || u8 1 || u32 count || (id element || id h)[count]
 * child  = id hash || id sum || u32 count || u32 firstKeyLen || firstKey
 * nodeL  = u8 level || u32 childCount || child[childCount]        (level &gt;= 1)
 * </pre>
 *
 * {@code hash} is the digest of the node bytes; {@code sum} is the sum of every {@code h} beneath it, so it is not stored in the
 * node's own bytes (it is recomputable and the hash covers everything that is stored).
 */
public record Node(int level, Identity hash, Identity sum, byte[] first, int count, byte[] bytes) {
    /** A summary of one child as stored in its parent. */
    public record Child(Identity hash, Identity sum, int count, byte[] first) { }

    public static Node leaf(Digest digest, Sum sums, List<Entry> entries) {
        var out = new Codec.Writer(64 + entries.size() * 96);
        out.u8(0).u8(0).u32(entries.size());
        var sum = sums.zero();
        for (var e : entries) {
            out.u32(e.key().length).raw(e.key()).id(e.h()).u32(e.value().length).raw(e.value());
            sum = sums.add(sum, e.h());
        }
        var bytes = out.toBytes();
        return new Node(0, digest.hash(bytes), sum, entries.isEmpty() ? new byte[0] : entries.get(0).key(), entries.size(), bytes);
    }

    public static Node interior(Digest digest, Sum sums, int level, List<Child> children) {
        var out = new Codec.Writer(64 + children.size() * 96);
        out.u8(level).u32(children.size());
        var sum = sums.zero();
        int count = 0;
        for (var c : children) {
            out.id(c.hash()).id(c.sum()).u32(c.count()).u32(c.first().length).raw(c.first());
            sum = sums.add(sum, c.sum());
            count += c.count();
        }
        var bytes = out.toBytes();
        return new Node(level, digest.hash(bytes), sum, children.get(0).first(), count, bytes);
    }

    public Child asChild() { return new Child(hash, sum, count, first); }

    /** The level is the first byte of every node, so a reader never needs the tree's height. */
    public static int level(byte[] bytes) { return bytes[0] & 0xFF; }

    /** A level-0 list node (B.2): elements in the caller's order, the marker byte 1 after the level. Entries carry the element id as key. */
    public static Node list(Digest digest, Sum sums, List<Entry> elements) {
        var out = new Codec.Writer(64 + elements.size() * 96);
        out.u8(0).u8(1).u32(elements.size());
        var sum = sums.zero();
        for (var e : elements) {
            out.raw(e.key()).id(e.h());
            sum = sums.add(sum, e.h());
        }
        var bytes = out.toBytes();
        return new Node(0, digest.hash(bytes), sum, elements.isEmpty() ? new byte[0] : elements.get(0).key(), elements.size(), bytes);
    }

    /** The marker of a level-0 node: 0 for a tree, 1 for a list. */
    public static int marker(byte[] bytes) { return bytes[1] & 0xFF; }

    /** The elements of a level-0 list node, as entries whose key is the element id and whose value is empty. */
    public static List<Entry> elements(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        if (in.u8() != 0 || in.u8() != 1) throw new IllegalArgumentException("Not a level-0 list node");
        int n = in.count();
        var out = new ArrayList<Entry>(n);
        for (int i = 0; i < n; i++) {
            var id = in.raw(width);
            out.add(new Entry(id, Entry.NONE, in.id(width)));
        }
        return out;
    }

    public static List<Entry> entries(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        if (in.u8() != 0 || in.u8() != 0) throw new IllegalArgumentException("Not a level-0 tree node");
        int n = in.count();
        var out = new ArrayList<Entry>(n);
        for (int i = 0; i < n; i++) {
            var key = in.raw(in.count());
            var h = in.id(width);
            out.add(new Entry(key, in.raw(in.count()), h));
        }
        return out;
    }

    public static List<Child> children(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        if (in.u8() == 0) throw new IllegalArgumentException("Not an interior node");
        int n = in.count();
        var out = new ArrayList<Child>(n);
        for (int i = 0; i < n; i++) {
            var hash = in.id(width);
            var sum = in.id(width);
            int count = in.count();
            out.add(new Child(hash, sum, count, in.raw(in.count())));
        }
        return out;
    }
}
