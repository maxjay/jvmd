package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineLeaf;
import java.util.function.Function;

/** LAYOUT 4 header absences; positive entries retain the Stage 2 oSum encoding. */
public final class HeaderProof {
    private HeaderProof() { }

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
        return leaf != null && tree.rangeSum(leaf.k(), nodes, entry.key()).equals(tree.sums().zero());
    }

    public static boolean valid(FileRow row, ContentTree tree, MachineLeaf own, Route route, Function<byte[], byte[]> records) {
        var read = new DefinerIndex.Reader(tree, own, route, records);
        for (var entry : row.headerProof()) {
            var leaf = read.definer(entry.typeKey());
            if (leaf == null) return false;
            var current = tree.get(leaf.oHash(), read.nodes(), Keys.ownerKey(entry.typeKey()));
            if (current == null || !current.h().equals(entry.oSum())) return false;
        }
        for (var entry : row.absences()) {
            if (entry.form() == 0) {
                if (!read.absent(entry.type())) return false;
            } else if (!absent(entry, tree, read::definer, read.nodes())) return false;
        }
        return true;
    }
}
