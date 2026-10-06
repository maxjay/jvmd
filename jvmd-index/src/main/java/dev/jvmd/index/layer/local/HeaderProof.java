package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.function.Function;

/** Header attribution uses the same exact T/N ranges and type absences as body attribution. */
public final class HeaderProof {
    private HeaderProof() { }

    public record Range(String type, int kind, String name) implements Comparable<Range> {
        public Range {
            if (kind < Keys.TYPE || kind > Keys.METHOD || (kind == Keys.TYPE && !name.isEmpty()))
                throw new IllegalArgumentException("Invalid header range");
        }
        public byte[] key() { return Keys.groupKey(type, kind, name); }
        @Override public int compareTo(Range other) {
            int order = type.compareTo(other.type);
            if (order == 0) order = Integer.compare(kind, other.kind);
            return order == 0 ? name.compareTo(other.name) : order;
        }
    }

    /** form 0: absent type; form 1: absent inherited member-type range. */
    public record Absence(int form, String type, String name) {
        public Absence {
            if (form != 0 && form != 1) throw new IllegalArgumentException("Unknown header absence form " + form);
        }
        public byte[] key() { return form == 0 ? Keys.ownerKey(type) : Keys.memberTypesKey(type, name); }
        public void encode(Codec.Writer out) { out.u8(form).zstr(type).zstr(name); }
        public static Absence decode(Codec.Reader in) { return new Absence(in.u8(), in.zstr(), in.zstr()); }
    }

    /** Filter collected candidates against the sealed own leaf and the bound route. */
    public static boolean absent(Absence entry, ContentTree tree, Function<String, MachineLeaf> definer, Function<Identity, byte[]> nodes) {
        var leaf = definer.apply(entry.type());
        if (entry.form() == 0) return leaf == null;
        return leaf != null && tree.rangeSum(leaf.nHash(), nodes, entry.key()).equals(tree.sums().zero());
    }

    public static boolean valid(FileRow row, ContentTree tree, MachineLeaf own, Route route, Function<byte[], byte[]> records) {
        var read = new DefinerIndex.Reader(tree, own, route, records);
        for (var entry : row.headerProof()) {
            var leaf = read.definer(entry.typeKey());
            if (leaf == null) return false;
            var current = tree.rangeSum(leaf.k(), read.nodes(), entry.range().key());
            if (!current.equals(entry.sum())) return false;
        }
        for (var entry : row.absences()) {
            if (entry.form() == 0) {
                if (!read.absent(entry.type())) return false;
            } else if (!absent(entry, tree, read::definer, read.nodes())) return false;
        }
        return true;
    }
}
