package dev.jvmd.index.layer.local;

import dev.jvmd.core.tree.Codec;
import dev.jvmd.index.layer.machine.Keys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Stage 3 B.4: source occurrences of T/N ranges and D absences, stored outside the bodies tree. */
public record UsesRecord(List<Use> uses) {
    public static final int D = 2;

    public record Span(long start, long end) implements Comparable<Span> {
        public Span {
            if (start < 0 || end < start || end >= 0xffff_ffffL) throw new IllegalArgumentException("Invalid use span");
        }
        @Override public int compareTo(Span other) {
            int order = Long.compare(start, other.start);
            return order == 0 ? Long.compare(end, other.end) : order;
        }
    }

    public record Use(int tree, String type, int kind, String name, List<Span> spans) implements Comparable<Use> {
        public Use {
            if (tree == D) {
                if (kind != Keys.TYPE || !name.isEmpty()) throw new IllegalArgumentException("Invalid absence use");
            } else new Proof.Range(tree, type, kind, name);
            spans = spans.stream().distinct().sorted().toList();
        }
        public byte[] key() {
            return tree == D ? new Codec.Writer().u8(D).zstr(type).toBytes() : new Proof.Range(tree, type, kind, name).key();
        }
        @Override public int compareTo(Use other) { return Arrays.compareUnsigned(key(), other.key()); }
    }

    public UsesRecord {
        uses = uses.stream().sorted(Comparator.naturalOrder()).toList();
        Use previous = null;
        for (var use : uses) {
            if (previous != null && use.compareTo(previous) == 0) throw new IllegalArgumentException("Duplicate uses key");
            previous = use;
        }
    }

    public byte[] encode() {
        var out = new Codec.Writer().u32(uses.size());
        for (var use : uses) {
            out.raw(use.key()).u32(use.spans().size());
            for (var span : use.spans()) out.u32(span.start()).u32(span.end());
        }
        return out.toBytes();
    }

    public static UsesRecord decode(byte[] bytes) {
        var in = new Codec.Reader(bytes);
        var uses = new ArrayList<Use>();
        for (int i = 0, n = in.count(); i < n; i++) {
            int tree = in.u8();
            String type = in.zstr();
            int kind = tree == D ? Keys.TYPE : in.u8();
            String name = tree == D ? "" : in.zstr();
            var spans = new ArrayList<Span>();
            for (int j = 0, m = in.count(); j < m; j++) spans.add(new Span(in.u32(), in.u32()));
            uses.add(new Use(tree, type, kind, name, spans));
        }
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing uses bytes");
        return new UsesRecord(uses);
    }
}
