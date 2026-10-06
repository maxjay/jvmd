package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Diff;
import dev.jvmd.index.layer.machine.Keys;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Header and body reverse lookup: one empty record per exact dependency, project and source path. */
public final class ReverseIndex {
    private ReverseIndex() { }
    public static final int T = 0, N = 1, D = 2;
    private static final byte[] HEADER = {'X', '|', 'H', '|'};

    /** Form selects T, N or the definer universe. Empty member name selects a complete kind. */
    public record Dependency(int form, String type, int kind, String name) implements Comparable<Dependency> {
        public Dependency {
            if (form < T || form > D || kind < Keys.TYPE || kind > Keys.METHOD
                    || form != T && kind != Keys.TYPE || form == D && !name.isEmpty())
                throw new IllegalArgumentException("Invalid header reverse dependency");
        }
        public byte[] prefix() { return new Codec.Writer().raw(HEADER).u8(form).zstr(type).u8(kind).zstr(name).toBytes(); }
        public byte[] key(Identity project, String path) { return new Codec.Writer().raw(prefix()).id(project).zstr(path).toBytes(); }
        @Override public int compareTo(Dependency other) {
            int c = Integer.compare(form, other.form);
            if (c == 0) c = type.compareTo(other.type);
            if (c == 0) c = Integer.compare(kind, other.kind);
            return c == 0 ? name.compareTo(other.name) : c;
        }
    }

    public record Consumer(Identity project, String path) implements Comparable<Consumer> {
        @Override public int compareTo(Consumer other) {
            int c = project.compareTo(other.project);
            return c == 0 ? path.compareTo(other.path) : c;
        }
    }

    public static Dependency range(HeaderProof.Range range) { return new Dependency(T, range.type(), range.kind(), range.name()); }
    public static Dependency absence(HeaderProof.Absence absence) {
        return new Dependency(absence.form() == 0 ? D : N, absence.type(), Keys.TYPE, absence.name());
    }

    public static Set<Dependency> dependencies(FileRow row) {
        var out = new TreeSet<Dependency>();
        for (var proof : row.headerProof()) out.add(range(proof.range()));
        for (var absence : row.absences()) out.add(absence(absence));
        return out;
    }

    /** All current consumers of one prefix. Old cold generations may leave unreachable raw keys; the committed LOCAL root decides membership. */
    public static Set<Consumer> consumers(Digest digest, LocalStore store, Dependency dependency) {
        return new Reader(digest, store, false).read(Set.of(dependency));
    }

    public static byte[] bodyPrefix(Dependency dependency) {
        var out = new Codec.Writer();
        return dependency.form() == D ? out.raw(new byte[] {'X', '|', 'D', '|'}).zstr(dependency.type()).toBytes()
                : out.raw(new byte[] {'X', '|', 'G', '|'}).u8(dependency.form()).zstr(dependency.type()).u8(dependency.kind()).zstr(dependency.name()).toBytes();
    }

    public static byte[] bodyKey(Dependency dependency, Identity project, String path) {
        return new Codec.Writer().raw(bodyPrefix(dependency)).id(project).zstr(path).toBytes();
    }

    public static Set<Dependency> dependencies(Proof proof) {
        var out = new TreeSet<Dependency>();
        for (var type : proof.types()) for (var entry : type.entries()) {
            var range = entry.range();
            out.add(new Dependency(range.form(), range.type(), range.kind(), range.name()));
        }
        for (var type : proof.absent()) out.add(new Dependency(D, type, Keys.TYPE, ""));
        return out;
    }

    public static Set<Consumer> bodyConsumers(Digest digest, LocalStore store, Dependency dependency) {
        return new Reader(digest, store, true).read(Set.of(dependency));
    }

    /**
     * A semantic delta from T, N and O/DD/DS/DC to candidate paths. Only changed keys and their reverse prefixes are read;
     * no F row is read here. The caller validates just these candidates against each file's current own leaf and route.
     */
    public static Set<Consumer> candidates(Digest digest, LocalStore store, Diff.Result t, Diff.Result n, Diff.Result d) {
        return new Reader(digest, store, false).read(changes(t, n, d));
    }

    /** Uses BROOT membership, including after an LROOT recommit: these are the old proofs the semantic delta must reach. */
    public static Set<Consumer> bodyCandidates(Digest digest, LocalStore store, Diff.Result t, Diff.Result n, Diff.Result d) {
        return new Reader(digest, store, true).read(changes(t, n, d));
    }

    private static Set<Dependency> changes(Diff.Result t, Diff.Result n, Diff.Result d) {
        var changed = new TreeSet<Dependency>();
        for (var entries : List.of(t.removed(), t.added())) for (var entry : entries) {
            var m = Keys.Member.decode(entry.key());
            changed.add(new Dependency(T, m.owner(), m.kind(), m.name()));
            if (m.kind() != Keys.TYPE) changed.add(new Dependency(T, m.owner(), m.kind(), ""));
        }
        for (var entries : List.of(n.removed(), n.added())) for (var entry : entries) {
            var in = new Codec.Reader(entry.key());
            String name = in.zstr();
            if (in.u8() == Keys.TYPE) {
                String outer = in.zstr();
                if (!outer.isEmpty()) changed.add(new Dependency(N, outer, Keys.TYPE, name));
            }
        }
        for (var entries : List.of(d.removed(), d.added())) for (var entry : entries)
            changed.add(new Dependency(D, Keys.ownerOf(entry.key()), Keys.TYPE, ""));
        return changed;
    }

    private static final class Reader {
        private final Digest digest;
        private final LocalStore store;
        private final ContentTree tree;
        private final boolean body;
        private final java.util.Map<Identity, Identity> roots = new HashMap<>();
        Reader(Digest digest, LocalStore store, boolean body) {
            this.digest = digest; this.store = store; this.body = body; this.tree = new ContentTree(digest);
        }

        private Identity root(Identity project) {
            if (!roots.containsKey(project)) {
                var value = store.get(body ? LocalStore.bodiesRootKey(project) : LocalStore.localRootKey(project));
                roots.put(project, value == null ? null : body ? BodiesRoot.decode(value, digest.width()).bodiesRoot()
                        : LocalRoot.decode(digest, value).local().hash());
            }
            return roots.get(project);
        }

        Set<Consumer> read(Set<Dependency> dependencies) {
            var consumers = new TreeSet<Consumer>();
            for (var dependency : dependencies) {
                byte[] prefix = body ? bodyPrefix(dependency) : dependency.prefix();
                store.forEachKey(prefix, key -> {
                    var in = new Codec.Reader(key);
                    in.raw(prefix.length);
                    var project = in.id(digest.width());
                    String path = in.zstr();
                    var root = root(project);
                    if (root != null && tree.get(root, h -> store.get(MachineStore.nodeKey(h)), key) != null)
                        consumers.add(new Consumer(project, path));
                });
            }
            return consumers;
        }
    }
}
